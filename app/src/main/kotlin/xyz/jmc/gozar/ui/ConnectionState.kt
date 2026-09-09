package xyz.jmc.gozar.ui

/**
 * Everything the screen is allowed to know.
 *
 * Note what is missing: no engine name, no protocol, no ping. The app knows
 * which of five transports is carrying the traffic and deliberately does not
 * say, because that is a decision the user was never asked to make and should
 * not have to think about.
 */
sealed interface ConnectionState {

    data object Idle : ConnectionState

    /** Trying. [note] is plain language, never a protocol name. */
    data class Connecting(val note: String) : ConnectionState

    data object Connected : ConnectionState

    /**
     * Nothing got through. This is the moment the channel matters: during a
     * real shutdown every engine fails at once, and an app that answers that
     * with "failed" has abandoned the user.
     */
    data object NoWayOut : ConnectionState
}
