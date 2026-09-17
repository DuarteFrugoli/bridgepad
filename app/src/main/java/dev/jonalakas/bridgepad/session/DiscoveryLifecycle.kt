package dev.jonalakas.bridgepad.session

/**
 * Serializes Android NSD start/stop requests.
 *
 * NsdManager only allows a listener to be reused after its stop callback. An
 * Activity can stop and start while that callback is still pending (rotation,
 * system Bluetooth UI, and similar transitions), so starting immediately
 * would leave discovery stopped or fail with an already-active operation.
 */
internal class DiscoveryLifecycle {
    enum class Command { NONE, START, STOP }

    private enum class Phase { IDLE, STARTING, ACTIVE, STOPPING }

    private var desired = false
    private var phase = Phase.IDLE

    fun requestStart(): Command {
        desired = true
        return if (phase == Phase.IDLE) {
            phase = Phase.STARTING
            Command.START
        } else {
            Command.NONE
        }
    }

    fun started() {
        if (phase == Phase.STARTING) phase = Phase.ACTIVE
    }

    fun requestStop(): Command {
        desired = false
        return when (phase) {
            Phase.STARTING, Phase.ACTIVE -> {
                phase = Phase.STOPPING
                Command.STOP
            }
            Phase.IDLE, Phase.STOPPING -> Command.NONE
        }
    }

    /** Called only after Android confirms that the previous listener stopped. */
    fun stopped(): Command {
        phase = Phase.IDLE
        return if (desired) {
            phase = Phase.STARTING
            Command.START
        } else {
            Command.NONE
        }
    }

    /** A failed start is inactive and can be retried by the next start request. */
    fun startFailed() {
        phase = Phase.IDLE
    }

    /** If stop failed, the old discovery is still considered active. */
    fun stopFailed(): Command {
        phase = Phase.ACTIVE
        return if (desired) {
            Command.NONE
        } else {
            phase = Phase.STOPPING
            Command.STOP
        }
    }

    fun wantsDiscovery(): Boolean = desired
}
