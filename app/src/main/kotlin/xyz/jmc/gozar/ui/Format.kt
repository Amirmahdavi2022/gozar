package xyz.jmc.gozar.ui

import java.util.Locale

/** 1:04:09 while it matters, 04:09 before that. Nobody reads "3849 seconds". */
fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** Bytes, rounded to something a person can glance at. */
fun formatBytes(bytes: Long): Pair<String, String> {
    if (bytes < 1024) return bytes.toString() to "B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val text = if (value >= 100) String.format(Locale.US, "%.0f", value)
    else String.format(Locale.US, "%.1f", value)
    return text to units[index]
}

/** Per-second rate, same treatment. */
fun formatRate(bytesPerSecond: Long): String {
    val (value, unit) = formatBytes(bytesPerSecond)
    return "$value $unit/s"
}
