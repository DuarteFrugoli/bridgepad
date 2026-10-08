package dev.jonalakas.bridgepad.streaming;

/** JNI boundary for the isolated native QUIC DATAGRAM diagnostic. */
public final class QuicMediaNative {
    static {
        System.loadLibrary("bridgepad_android_quic");
    }

    private QuicMediaNative() {}

    public static native String runProbe(String host, int port, int durationSeconds);

    public static native void cancelProbe();
}
