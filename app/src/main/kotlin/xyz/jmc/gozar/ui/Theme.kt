package xyz.jmc.gozar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Warm paper rather than the near-black every other app in this category uses.
 * The point is that Gozar should not look like contraband on someone's phone.
 *
 * Ember is lifted straight off the app icon so the two read as one thing.
 */
object GozarColors {
    val Paper = Color(0xFFF5F2ED)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF16161A)
    val Ember = Color(0xFFFF7A18)
    val EmberDeep = Color(0xFFE85D04)
    val Muted = Color(0xFF74747E)
    val Hairline = Color(0xFFE2DED7)
    val Good = Color(0xFF1F7A5C)
    val Bad = Color(0xFFA8321F)
}

@Composable
fun GozarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = GozarColors.Ember,
            background = GozarColors.Paper,
            surface = GozarColors.Card,
            onBackground = GozarColors.Ink,
            onSurface = GozarColors.Ink,
        ),
        content = content,
    )
}
