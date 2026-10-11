package dev.jonalakas.bridgepad.streaming;

/** Completed encoded access units delivered by the native QUIC receiver. */
public interface QuicFrameCallback {
    void onVideoFrame(byte[] annexB, long presentationMicros, boolean keyframe);
    void onAudioFrame(byte[] opus, long presentationMicros);
}
