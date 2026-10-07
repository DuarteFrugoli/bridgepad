package dev.jonalakas.bridgepad.session

/**
 * Process-wide ownership for the one output session allowed to emit input.
 *
 * Claiming a new transport stops the previous owner before the caller starts
 * emitting. A generation token prevents a late callback from an old session
 * from releasing the current one.
 */
class OutputSessionOwner {
    private val lock = Any()
    private var generation = 0L
    private var active: Entry? = null

    fun claim(kind: Kind, stop: () -> Unit): Lease {
        val previous: Entry?
        val token: Long
        synchronized(lock) {
            token = ++generation
            previous = active
            active = Entry(token, kind, stop)
        }
        previous?.stop?.invoke()
        return Lease(this, token, kind)
    }

    private fun release(token: Long) {
        synchronized(lock) {
            if (active?.token == token) active = null
        }
    }

    class Lease internal constructor(
        private val owner: OutputSessionOwner,
        private val token: Long,
        val kind: Kind,
    ) {
        fun release() = owner.release(token)
    }

    enum class Kind {
        BLUETOOTH_HID,
        BLUETOOTH_DESKTOP,
        NETWORK_DESKTOP,
    }

    private data class Entry(
        val token: Long,
        val kind: Kind,
        val stop: () -> Unit,
    )
}
