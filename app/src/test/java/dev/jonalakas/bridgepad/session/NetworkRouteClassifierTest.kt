package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRouteClassifierTest {
    @Test
    fun wifiDoesNotBecomeOnlineThroughUsbTethering() {
        assertTrue(NetworkRouteClassifier.matchesInterface(ConnectionMethod.WIFI, "wlan0"))
        assertFalse(NetworkRouteClassifier.matchesInterface(ConnectionMethod.WIFI, "rndis0"))
        assertFalse(NetworkRouteClassifier.matchesInterface(ConnectionMethod.WIFI, "usb0"))
    }

    @Test
    fun usbDoesNotBecomeOnlineThroughWifi() {
        assertTrue(NetworkRouteClassifier.matchesInterface(ConnectionMethod.USB, "rndis0"))
        assertTrue(NetworkRouteClassifier.matchesInterface(ConnectionMethod.USB, "usb0"))
        assertTrue(NetworkRouteClassifier.matchesInterface(ConnectionMethod.USB, "ncm0"))
        assertFalse(NetworkRouteClassifier.matchesInterface(ConnectionMethod.USB, "wlan0"))
        assertFalse(NetworkRouteClassifier.matchesInterface(ConnectionMethod.USB, null))
    }
}
