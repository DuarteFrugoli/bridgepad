package dev.jonalakas.bridgepad.streaming;

/** Completed encoded access units delivered by either native media receiver. */
public interface MediaFrameCallback {
    void onVideoFrame(byte[] annexB, long presentationMicros, boolean keyframe);
    void onAudioFrame(byte[] opus, long presentationMicros);
    /** UDP recovery must discard queued/decode-pending pictures before the next IDR. */
    void onVideoDiscontinuity();
    boolean needsVideoKeyframe();
}
