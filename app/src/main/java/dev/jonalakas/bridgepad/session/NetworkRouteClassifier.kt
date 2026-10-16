package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import java.net.InetAddress
import java.net.NetworkInterface

/** Classifies the interface actually used by a connected socket, not its remote IP. */
internal object NetworkRouteClassifier {
    fun matches(method: ConnectionMethod, localAddress: InetAddress): Boolean {
        val interfaceName = NetworkInterface.getByInetAddress(localAddress)?.name
        return matchesInterface(method, interfaceName)
    }

    fun matchesInterface(method: ConnectionMethod, interfaceName: String?): Boolean {
        val name = interfaceName?.lowercase() ?: return false
        return when (method) {
            ConnectionMethod.WIFI -> name.startsWith("wlan") || name.startsWith("wifi")
            ConnectionMethod.USB -> name.startsWith("rndis") ||
                name.startsWith("usb") || name.startsWith("ncm")
            else -> false
        }
    }
}
