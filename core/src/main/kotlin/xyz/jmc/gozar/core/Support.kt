package xyz.jmc.gozar.core

/**
 * Where a user ends up when nothing works.
 *
 * Every engine can fail at once — during a full shutdown they will — and an app
 * that answers that moment with "failed" is useless. This is the fallback the
 * UI points at, and where fresh configs and news get posted.
 */
object Support {
    const val CHANNEL_URL = "https://t.me/parsv2r"
    const val CHANNEL_HANDLE = "@parsv2r"
}
