//! H.264 and Opus WebRTC sender for `BridgePad`.
//!
//! Media uses ICE/DTLS/SRTP over UDP. Signalling and input intentionally stay
//! outside this crate so video congestion cannot block controller reports.

use bytes::Bytes;
use rtcp::receiver_report::ReceiverReport;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU32, Ordering};
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio::sync::mpsc::{Receiver, channel};
use webrtc::api::APIBuilder;
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::{MIME_TYPE_H264, MIME_TYPE_OPUS, MediaEngine};
use webrtc::ice_transport::ice_connection_state::RTCIceConnectionState;
use webrtc::ice_transport::ice_gathering_state::RTCIceGatheringState;
use webrtc::media::Sample;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::peer_connection_state::RTCPeerConnectionState;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::rtp_transceiver::RTCPFeedback;
use webrtc::rtp_transceiver::rtp_codec::{
    RTCRtpCodecCapability, RTCRtpCodecParameters, RTPCodecType,
};
use webrtc::track::track_local::TrackLocal;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;

const H264_CLOCK_RATE: u32 = 90_000;
const H264_PAYLOAD_TYPE: u8 = 102;
const OPUS_CLOCK_RATE: u32 = 48_000;
const OPUS_CHANNELS: u16 = 2;
const OPUS_PAYLOAD_TYPE: u8 = 111;
const MIN_BITRATE_BITS_PER_SECOND: u32 = 500_000;
const PEER_CLOSE_TIMEOUT: Duration = Duration::from_secs(2);
const RUNTIME_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(500);

pub type AnyError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug)]
pub enum MediaSendError {
    NotActive,
    Transport(AnyError),
}

impl std::fmt::Display for MediaSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotActive => formatter.write_str("WebRTC connection is not active"),
            Self::Transport(error) => write!(formatter, "{error}"),
        }
    }
}

impl std::error::Error for MediaSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::NotActive => None,
            Self::Transport(error) => Some(error.as_ref()),
        }
    }
}

pub struct MediaWebRtcSession {
    // Tokio's ordinary Runtime::drop waits forever for blocking work. WebRTC
    // dependencies may leave such work behind while a route disappears, so
    // session teardown must consume the runtime with a bounded shutdown.
    runtime: Option<Runtime>,
    peer: Arc<RTCPeerConnection>,
    video_track: Arc<TrackLocalStaticSample>,
    audio_track: Arc<TrackLocalStaticSample>,
    connected: Arc<AtomicBool>,
    closed: Arc<AtomicBool>,
    ice_state: Arc<AtomicU8>,
    target_bitrate: Arc<AtomicU32>,
    connected_rx: Receiver<()>,
}

impl MediaWebRtcSession {
    /// Creates an answerer for a complete, non-trickle ICE offer.
    ///
    /// # Errors
    ///
    /// Returns an error when the SDP is invalid or the WebRTC peer, track,
    /// interceptors, local answer or runtime cannot be created.
    pub fn answer_offer(
        offer_sdp: &str,
        initial_bitrate_bits_per_second: u32,
        maximum_bitrate_bits_per_second: u32,
    ) -> Result<(Self, String), AnyError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("bridgepad-webrtc")
            .build()?;
        let maximum = maximum_bitrate_bits_per_second.max(MIN_BITRATE_BITS_PER_SECOND);
        let initial = initial_bitrate_bits_per_second.clamp(MIN_BITRATE_BITS_PER_SECOND, maximum);
        let parts = runtime.block_on(Self::answer_offer_async(offer_sdp, initial, maximum));
        let parts = match parts {
            Ok(parts) => parts,
            Err(error) => {
                runtime.shutdown_timeout(RUNTIME_SHUTDOWN_TIMEOUT);
                return Err(error);
            }
        };
        Ok((
            Self {
                runtime: Some(runtime),
                peer: parts.peer,
                video_track: parts.video_track,
                audio_track: parts.audio_track,
                connected: parts.connected,
                closed: parts.closed,
                ice_state: parts.ice_state,
                target_bitrate: parts.target_bitrate,
                connected_rx: parts.connected_rx,
            },
            parts.answer_sdp,
        ))
    }

    async fn answer_offer_async(
        offer_sdp: &str,
        initial_bitrate: u32,
        maximum_bitrate: u32,
    ) -> Result<SessionParts, AnyError> {
        let mut media_engine = MediaEngine::default();
        media_engine.register_codec(
            RTCRtpCodecParameters {
                capability: h264_capability(),
                payload_type: H264_PAYLOAD_TYPE,
                ..Default::default()
            },
            RTPCodecType::Video,
        )?;
        media_engine.register_codec(
            RTCRtpCodecParameters {
                capability: opus_capability(),
                payload_type: OPUS_PAYLOAD_TYPE,
                ..Default::default()
            },
            RTPCodecType::Audio,
        )?;
        let registry = register_default_interceptors(
            webrtc::interceptor::registry::Registry::new(),
            &mut media_engine,
        )?;
        let api = APIBuilder::new()
            .with_media_engine(media_engine)
            .with_interceptor_registry(registry)
            .build();
        let peer = Arc::new(api.new_peer_connection(RTCConfiguration::default()).await?);

        let connected = Arc::new(AtomicBool::new(false));
        let closed = Arc::new(AtomicBool::new(false));
        let ice_state = Arc::new(AtomicU8::new(RTCIceConnectionState::New as u8));
        let (connected_tx, connected_rx) = channel(1);
        let state_connected = Arc::clone(&connected);
        let state_closed = Arc::clone(&closed);
        peer.on_peer_connection_state_change(Box::new(move |state| {
            let connected = Arc::clone(&state_connected);
            let closed = Arc::clone(&state_closed);
            let connected_tx = connected_tx.clone();
            Box::pin(async move {
                match state {
                    RTCPeerConnectionState::Connected => {
                        connected.store(true, Ordering::Relaxed);
                        let _ = connected_tx.try_send(());
                    }
                    RTCPeerConnectionState::Failed
                    | RTCPeerConnectionState::Disconnected
                    | RTCPeerConnectionState::Closed => {
                        connected.store(false, Ordering::Relaxed);
                        closed.store(true, Ordering::Relaxed);
                    }
                    _ => {}
                }
            })
        }));
        let observed_ice_state = Arc::clone(&ice_state);
        peer.on_ice_connection_state_change(Box::new(move |state| {
            observed_ice_state.store(state as u8, Ordering::Relaxed);
            println!("WebRTC ICE state: {state}");
            Box::pin(async {})
        }));

        let video_track = Arc::new(TrackLocalStaticSample::new(
            h264_capability(),
            "bridgepad-primary-monitor".to_owned(),
            "bridgepad-stream".to_owned(),
        ));
        let video_sender = peer
            .add_track(Arc::clone(&video_track) as Arc<dyn TrackLocal + Send + Sync>)
            .await?;
        video_sender
            .transport()
            .ice_transport()
            .on_selected_candidate_pair_change(Box::new(move |pair| {
                println!("WebRTC selected ICE candidate pair: {pair}");
                Box::pin(async {})
            }));
        let audio_track = Arc::new(TrackLocalStaticSample::new(
            opus_capability(),
            "bridgepad-system-audio".to_owned(),
            "bridgepad-stream".to_owned(),
        ));
        let audio_sender = peer
            .add_track(Arc::clone(&audio_track) as Arc<dyn TrackLocal + Send + Sync>)
            .await?;
        tokio::spawn(async move {
            let mut rtcp = vec![0_u8; 1_500];
            while audio_sender.read(&mut rtcp).await.is_ok() {}
        });
        let target_bitrate = Arc::new(AtomicU32::new(initial_bitrate));
        tokio::spawn(adapt_bitrate_from_receiver_reports(
            video_sender,
            Arc::clone(&target_bitrate),
            maximum_bitrate,
        ));

        print_ice_candidates("Android offer", offer_sdp);
        peer.set_remote_description(RTCSessionDescription::offer(offer_sdp.to_owned())?)
            .await?;
        let mut gather_complete = peer.gathering_complete_promise().await;
        let answer = peer.create_answer(None).await?;
        peer.set_local_description(answer).await?;
        if peer.ice_gathering_state() != RTCIceGatheringState::Complete {
            let _ = gather_complete.recv().await;
        }
        let answer_sdp = peer
            .local_description()
            .await
            .ok_or("WebRTC did not produce a local description")?
            .sdp;
        print_ice_candidates("Desktop answer", &answer_sdp);

        Ok(SessionParts {
            peer,
            video_track,
            audio_track,
            connected,
            closed,
            ice_state,
            target_bitrate,
            connected_rx,
            answer_sdp,
        })
    }

    /// Waits until ICE/DTLS reaches the connected state.
    ///
    /// # Errors
    ///
    /// Returns an error when the peer closes or the supplied timeout expires.
    pub fn wait_connected(&mut self, timeout: Duration) -> Result<(), AnyError> {
        if self.connected.load(Ordering::Relaxed) {
            return Ok(());
        }
        wait_for_connection(
            self.runtime.as_ref().expect("WebRTC runtime is active"),
            &mut self.connected_rx,
            timeout,
        )
        .map_err(|_| {
            let state = RTCIceConnectionState::from(self.ice_state.load(Ordering::Relaxed));
            format!("WebRTC connection timed out in ICE state '{state}'").into()
        })
    }

    /// Writes one Annex-B H.264 access unit to the WebRTC video track.
    ///
    /// # Errors
    ///
    /// Returns an error when the peer is not connected or RTP packetization
    /// cannot accept the sample.
    pub fn send_h264(&self, data: Vec<u8>, duration: Duration) -> Result<(), MediaSendError> {
        if !self.connected.load(Ordering::Relaxed) || self.closed.load(Ordering::Relaxed) {
            return Err(MediaSendError::NotActive);
        }
        self.runtime
            .as_ref()
            .expect("WebRTC runtime is active")
            .block_on(self.video_track.write_sample(&Sample {
                data: Bytes::from(data),
                duration,
                ..Default::default()
            }))
            .map_err(|error| MediaSendError::Transport(Box::new(error)))?;
        Ok(())
    }

    /// Writes one encoded 48 kHz stereo Opus packet to the WebRTC audio track.
    ///
    /// # Errors
    ///
    /// Returns an error when the peer is not connected or RTP packetization
    /// cannot accept the sample.
    pub fn send_opus(&self, data: Vec<u8>, duration: Duration) -> Result<(), MediaSendError> {
        if !self.connected.load(Ordering::Relaxed) || self.closed.load(Ordering::Relaxed) {
            return Err(MediaSendError::NotActive);
        }
        self.runtime
            .as_ref()
            .expect("WebRTC runtime is active")
            .block_on(self.audio_track.write_sample(&Sample {
                data: Bytes::from(data),
                duration,
                ..Default::default()
            }))
            .map_err(|error| MediaSendError::Transport(Box::new(error)))?;
        Ok(())
    }

    #[must_use]
    pub fn target_bitrate_bits_per_second(&self) -> u32 {
        self.target_bitrate.load(Ordering::Relaxed)
    }

    #[must_use]
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }
}

fn wait_for_connection(
    runtime: &Runtime,
    connected_rx: &mut Receiver<()>,
    timeout: Duration,
) -> Result<(), AnyError> {
    match runtime.block_on(async { tokio::time::timeout(timeout, connected_rx.recv()).await }) {
        Ok(Some(())) => Ok(()),
        _ => Err("WebRTC connection timed out".into()),
    }
}

impl Drop for MediaWebRtcSession {
    fn drop(&mut self) {
        let Some(runtime) = self.runtime.take() else {
            return;
        };
        // RTCPeerConnection::close may perform blocking work before its future
        // yields. Wrapping it in tokio::time::timeout therefore does not make
        // block_on bounded. Run close on the runtime and bound shutdown itself,
        // which guarantees that the daemon connection lease is released.
        let peer = Arc::clone(&self.peer);
        runtime.spawn(async move {
            let _ = peer.close().await;
        });
        runtime.shutdown_timeout(PEER_CLOSE_TIMEOUT + RUNTIME_SHUTDOWN_TIMEOUT);
    }
}

fn h264_capability() -> RTCRtpCodecCapability {
    RTCRtpCodecCapability {
        mime_type: MIME_TYPE_H264.to_owned(),
        clock_rate: H264_CLOCK_RATE,
        channels: 0,
        sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=4d0020"
            .to_owned(),
        rtcp_feedback: vec![
            RTCPFeedback {
                typ: "nack".to_owned(),
                parameter: String::new(),
            },
            RTCPFeedback {
                typ: "nack".to_owned(),
                parameter: "pli".to_owned(),
            },
        ],
    }
}

fn opus_capability() -> RTCRtpCodecCapability {
    RTCRtpCodecCapability {
        mime_type: MIME_TYPE_OPUS.to_owned(),
        clock_rate: OPUS_CLOCK_RATE,
        channels: OPUS_CHANNELS,
        sdp_fmtp_line: "minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1".to_owned(),
        rtcp_feedback: vec![],
    }
}

struct SessionParts {
    peer: Arc<RTCPeerConnection>,
    video_track: Arc<TrackLocalStaticSample>,
    audio_track: Arc<TrackLocalStaticSample>,
    connected: Arc<AtomicBool>,
    closed: Arc<AtomicBool>,
    ice_state: Arc<AtomicU8>,
    target_bitrate: Arc<AtomicU32>,
    connected_rx: Receiver<()>,
    answer_sdp: String,
}

fn print_ice_candidates(label: &str, sdp: &str) {
    let candidates = ice_candidate_summaries(sdp);
    if candidates.is_empty() {
        println!("{label} has no ICE candidates");
    } else {
        println!("{label} ICE candidates: {}", candidates.join(", "));
    }
}

fn ice_candidate_summaries(sdp: &str) -> Vec<String> {
    sdp.lines()
        .filter_map(|line| {
            let fields = line.split_whitespace().collect::<Vec<_>>();
            if !fields
                .first()
                .is_some_and(|field| field.starts_with("a=candidate:"))
                || fields.len() < 8
            {
                return None;
            }
            let candidate_type = fields
                .windows(2)
                .find(|pair| pair[0] == "typ")
                .map_or("unknown", |pair| pair[1]);
            Some(format!(
                "{} {} [{}]:{}",
                fields[2].to_ascii_uppercase(),
                candidate_type,
                fields[4],
                fields[5],
            ))
        })
        .collect()
}

async fn adapt_bitrate_from_receiver_reports(
    sender: Arc<webrtc::rtp_transceiver::rtp_sender::RTCRtpSender>,
    target: Arc<AtomicU32>,
    maximum: u32,
) {
    while let Ok((packets, _)) = sender.read_rtcp().await {
        for packet in packets {
            let Some(report) = packet.as_any().downcast_ref::<ReceiverReport>() else {
                continue;
            };
            let fraction_lost = report
                .reports
                .iter()
                .map(|entry| entry.fraction_lost)
                .max()
                .unwrap_or(0);
            update_bitrate(&target, maximum, fraction_lost);
        }
    }
}

fn update_bitrate(target: &AtomicU32, maximum: u32, fraction_lost: u8) {
    let current = target.load(Ordering::Relaxed);
    let next = if fraction_lost >= 26 {
        current.saturating_mul(3) / 4
    } else if fraction_lost <= 5 {
        current.saturating_add((current / 20).max(50_000))
    } else {
        current
    };
    target.store(
        next.clamp(MIN_BITRATE_BITS_PER_SECOND, maximum),
        Ordering::Relaxed,
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bitrate_drops_quickly_on_loss_and_recovers_slowly() {
        let bitrate = AtomicU32::new(4_000_000);
        update_bitrate(&bitrate, 8_000_000, 40);
        assert_eq!(bitrate.load(Ordering::Relaxed), 3_000_000);
        update_bitrate(&bitrate, 8_000_000, 0);
        assert_eq!(bitrate.load(Ordering::Relaxed), 3_150_000);
    }

    #[test]
    fn bitrate_stays_inside_profile_bounds() {
        let bitrate = AtomicU32::new(MIN_BITRATE_BITS_PER_SECOND);
        update_bitrate(&bitrate, 8_000_000, u8::MAX);
        assert_eq!(bitrate.load(Ordering::Relaxed), MIN_BITRATE_BITS_PER_SECOND);
        bitrate.store(8_000_000, Ordering::Relaxed);
        update_bitrate(&bitrate, 8_000_000, 0);
        assert_eq!(bitrate.load(Ordering::Relaxed), 8_000_000);
    }

    #[test]
    fn connection_timeout_is_created_inside_the_owned_runtime() {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .expect("runtime");
        let (_connected_tx, mut connected_rx) = channel(1);

        assert!(
            wait_for_connection(&runtime, &mut connected_rx, Duration::from_millis(1)).is_err()
        );
    }

    #[test]
    fn ice_candidate_summary_excludes_sdp_credentials() {
        let sdp = concat!(
            "a=ice-ufrag:private\r\n",
            "a=ice-pwd:also-private\r\n",
            "a=candidate:1 1 udp 2122260223 10.145.116.81 39061 typ host generation 0\r\n",
        );

        assert_eq!(
            ice_candidate_summaries(sdp),
            vec!["UDP host [10.145.116.81]:39061"]
        );
    }

    #[test]
    fn opus_profile_matches_android_webrtc() {
        let capability = opus_capability();
        assert_eq!(capability.mime_type, MIME_TYPE_OPUS);
        assert_eq!(capability.clock_rate, 48_000);
        assert_eq!(capability.channels, 2);
    }
}
