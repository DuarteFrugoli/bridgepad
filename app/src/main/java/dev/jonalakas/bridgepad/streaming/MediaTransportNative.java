package dev.jonalakas.bridgepad.streaming;

/** JNI boundary for the isolated native Media v1 transport diagnostics. */
public final class MediaTransportNative {
    static {
        System.loadLibrary("bridgepad_android_media");
    }

    private MediaTransportNative() {}

    public static native String runQuicProbe(
            long operationId, String host, String interfaceName, int port, int durationSeconds);

    public static native String runUdpAeadProbe(
            long operationId, String host, String interfaceName, int port, int durationSeconds);

    public static native void cancelProbe(long operationId);

    public static native String runQuicPlayable(
            long operationId, String host, int port, String certificateSha256,
            byte[] token, long sessionId, QuicFrameCallback callback);

    public static native void cancelPlayable(long operationId);
}
