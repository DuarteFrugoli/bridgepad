package dev.jonalakas.bridgepad.core.session

enum class SessionStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class GamepadSessionState(
    val status: SessionStatus = SessionStatus.STOPPED,
    val errorMessage: String? = null,
) {
    init {
        require(status == SessionStatus.ERROR || errorMessage == null) {
            "An error message can only be attached to an error session."
        }
    }
}
