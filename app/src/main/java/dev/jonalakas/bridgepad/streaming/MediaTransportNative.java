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
}
