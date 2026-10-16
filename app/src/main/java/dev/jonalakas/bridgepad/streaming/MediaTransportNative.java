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
            long operationId, String host, byte[] localAddress, int localScopeId,
            int port, String certificateSha256,
            byte[] token, long sessionId, MediaFrameCallback callback);

    public static native String runUdpPlayable(
            long operationId, String host, byte[] localAddress, int localScopeId,
            int port, byte[] token, byte[] key, long sessionId, MediaFrameCallback callback);

    public static native void cancelPlayable(long operationId);
}
