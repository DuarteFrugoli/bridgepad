package dev.jonalakas.bridgepad.streaming

import android.content.Context
import dev.jonalakas.bridgepad.streaming.MediaPipelineMetrics
import dev.jonalakas.bridgepad.streaming.VideoFormat
import dev.jonalakas.bridgepad.transport.network.NetworkAuthenticationException
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaSignalingClient
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Hardware-decoded WebRTC video rendered directly into a native Surface. */
class AndroidWebRtcMediaClient(
    context: Context,
    private val eglContext: EglBase.Context,
    private val requestProvider: () -> NetworkMediaRequest?,
    private val videoSink: VideoSink,
    private val onStatus: (NetworkMediaStatus) -> Unit,
    private val onMetrics: (MediaPipelineMetrics) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val stopping = AtomicBoolean(false)
    private val ended = CountDownLatch(1)
    private val iceGathered = CountDownLatch(1)
    private val attachedTrack = AtomicReference<VideoTrack?>(null)
    @Volatile private var signaling: NetworkMediaSignalingClient? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var negotiatedFormat: VideoFormat? = null

    fun start() {
        onStatus(NetworkMediaStatus.Connecting)
        Thread(::runSession, "BridgePad-webrtc-media").apply { isDaemon = true }.start()
    }

    private fun runSession() {
        try {
            // Establish the actual Wi-Fi/USB route before ICE snapshots local
            // interfaces. This prevents a just-disabled route from leaking
            // stale candidates into the next session.
            val (localSignaling, preparation) = prepareSignalingOnCurrentRoute()
            negotiatedFormat = preparation.format
            onMetrics(
                MediaPipelineMetrics(
                    networkEstimateMicros = preparation.roundTripMicros / 2,
                ),
            )

            initializeFactory(applicationContext)
            val factoryOptions = PeerConnectionFactory.Options().apply {
                // Android's ConnectivityManager does not expose the downstream
                // interface created by USB tethering as an application Network.
                // Native interface enumeration keeps ordinary Wi-Fi candidates
                // while also making that directly routable USB interface usable.
                disableNetworkMonitor = true
            }
            val localFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoDecoderFactory(HardwareVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()
            factory = localFactory
            val localPeer = checkNotNull(
                localFactory.createPeerConnection(
                    PeerConnection.RTCConfiguration(emptyList()),
                    observer,
                ),
            ) { "Android could not create a WebRTC peer connection" }
            peer = localPeer
            localPeer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                ),
            )

            val offer = createOffer(localPeer)
            setDescription(localPeer, offer, local = true)
            check(iceGathered.await(10, TimeUnit.SECONDS)) {
                "Timed out while gathering local WebRTC candidates"
            }
            val completeOffer = checkNotNull(localPeer.localDescription) {
                "WebRTC did not keep its local offer"
            }
            val negotiation = localSignaling.exchangeOffer(completeOffer.description)
            setDescription(
                localPeer,
                SessionDescription(SessionDescription.Type.ANSWER, negotiation.answerSdp),
                local = false,
            )
            ended.await()
        } catch (error: Throwable) {
            if (!stopping.get()) {
                onStatus(NetworkMediaStatus.Failed(error.message ?: "WebRTC session failed"))
            }
        } finally {
            releaseNativeObjects()
            if (stopping.get()) onStatus(NetworkMediaStatus.Stopped)
        }
    }

    /**
     * Route transitions are asynchronous on both Android and Windows. In
     * particular, USB tethering can become routable before NSD publishes the
     * Desktop's new address. Re-read discovery state between short connection
     * attempts so switching Wi-Fi -> USB does not require restarting either
     * application.
     */
    private fun prepareSignalingOnCurrentRoute(): PreparedSignaling {
        val deadline = System.nanoTime() + ROUTE_SETTLE_TIMEOUT_NANOS
        var lastFailure: Throwable? = null
        while (!stopping.get()) {
            val request = requestProvider()
            if (request != null) {
                val candidate = NetworkMediaSignalingClient(request)
                signaling = candidate
                try {
                    return PreparedSignaling(candidate, candidate.prepare())
                } catch (error: Throwable) {
                    candidate.close()
                    if (signaling === candidate) signaling = null
                    if (!error.isTransientRouteFailure()) throw error
                    lastFailure = error
                }
            }
            if (System.nanoTime() >= deadline) {
                throw lastFailure ?: IllegalStateException("Desktop is unavailable on the current route")
            }
            Thread.sleep(ROUTE_RETRY_DELAY_MILLIS)
        }
        error("Media signalling stopped")
    }

    private fun Throwable.isTransientRouteFailure(): Boolean {
        val causes = generateSequence(this) { it.cause }.toList()
        if (causes.any { it is CertificateException || it is NetworkAuthenticationException }) {
            return false
        }
        return causes.any { it is IOException }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGathered.countDown()
        }

        override fun onIceCandidate(candidate: IceCandidate?) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) = Unit

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            attach(receiver?.track())
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            attach(transceiver?.receiver?.track())
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    negotiatedFormat?.let { onStatus(NetworkMediaStatus.Active(it)) }
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    onStatus(NetworkMediaStatus.Connecting)
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    onStatus(NetworkMediaStatus.Failed("WebRTC media connection failed"))
                    ended.countDown()
                }
                PeerConnection.PeerConnectionState.CLOSED -> ended.countDown()
                else -> Unit
            }
        }
    }

    private fun attach(track: MediaStreamTrack?) {
        val video = track as? VideoTrack ?: return
        attachedTrack.getAndSet(video)?.removeSink(videoSink)
        video.addSink(videoSink)
    }

    private fun createOffer(peer: PeerConnection): SessionDescription {
        val operation = SdpOperation()
        peer.createOffer(operation, MediaConstraints())
        return operation.awaitDescription("create WebRTC offer")
    }

    private fun setDescription(
        peer: PeerConnection,
        description: SessionDescription,
        local: Boolean,
    ) {
        val operation = SdpOperation()
        if (local) peer.setLocalDescription(operation, description)
        else peer.setRemoteDescription(operation, description)
        operation.awaitCompletion(if (local) "set local WebRTC description" else "set remote WebRTC description")
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        ended.countDown()
        signaling?.close()
        peer?.close()
    }

    private fun releaseNativeObjects() {
        attachedTrack.getAndSet(null)?.removeSink(videoSink)
        signaling?.close()
        signaling = null
        peer?.dispose()
        peer = null
        factory?.dispose()
        factory = null
    }

    private class SdpOperation : SdpObserver {
        private val completed = CountDownLatch(1)
        private val description = AtomicReference<SessionDescription?>(null)
        private val failure = AtomicReference<String?>(null)

        override fun onCreateSuccess(value: SessionDescription?) {
            description.set(value)
            completed.countDown()
        }

        override fun onSetSuccess() {
            completed.countDown()
        }

        override fun onCreateFailure(detail: String?) {
            failure.set(detail ?: "unknown SDP creation error")
            completed.countDown()
        }

        override fun onSetFailure(detail: String?) {
            failure.set(detail ?: "unknown SDP application error")
            completed.countDown()
        }

        fun awaitDescription(operation: String): SessionDescription {
            awaitCompletion(operation)
            return checkNotNull(description.get()) { "$operation returned no description" }
        }

        fun awaitCompletion(operation: String) {
            check(completed.await(10, TimeUnit.SECONDS)) { "$operation timed out" }
            failure.get()?.let { error("$operation failed: $it") }
        }
    }

    companion object {
        private const val ROUTE_RETRY_DELAY_MILLIS = 500L
        private val ROUTE_SETTLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20)
        private val initialized = AtomicBoolean(false)

        private fun initializeFactory(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions(),
                )
            }
        }
    }

    private data class PreparedSignaling(
        val client: NetworkMediaSignalingClient,
        val preparation: dev.jonalakas.bridgepad.transport.network.NetworkMediaPreparation,
    )
}
