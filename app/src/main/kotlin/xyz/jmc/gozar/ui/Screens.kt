package xyz.jmc.gozar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.jmc.gozar.ConfigStore
import xyz.jmc.gozar.R
import xyz.jmc.gozar.core.Support

private enum class Screen { HOME, SETTINGS, ABOUT }

@Composable
fun GozarRoot(onToggle: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScaffold(
            onToggle = onToggle,
            onSettings = { screen = Screen.SETTINGS },
            onAbout = { screen = Screen.ABOUT },
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
        Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.HOME })
    }
}

@Composable
private fun HomeScaffold(onToggle: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = GozarColors.Ink,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )

            // Support sits in the bar, not buried in a menu. When someone needs
            // it they are usually already having a bad time.
            BarIcon(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.CHANNEL_URL)))
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_telegram),
                    contentDescription = stringResource(R.string.support),
                    tint = GozarColors.Ink,
                    modifier = Modifier.size(21.dp),
                )
            }
            BarIcon(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = GozarColors.Ink,
                    modifier = Modifier.size(21.dp),
                )
            }
            BarIcon(onClick = onAbout) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.about),
                    tint = GozarColors.Ink,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        GozarScreen(onToggle = onToggle)
    }
}

@Composable
private fun BarIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = GozarColors.Ink,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(title, color = GozarColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(22.dp))
        body()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    var text by remember { mutableStateOf(store.link.orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }

    SubScreen(title = stringResource(R.string.settings), onBack = onBack) {
        Card {
            Text(
                stringResource(R.string.own_config),
                color = GozarColors.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.own_config_note),
                color = GozarColors.Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GozarColors.Paper)
                    .border(1.dp, GozarColors.Hairline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.own_config_hint),
                        color = GozarColors.Muted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; note = null },
                    textStyle = TextStyle(
                        color = GozarColors.Ink,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(GozarColors.Ember),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.save), filled = true) {
                    note = if (text.isBlank() || store.looksUsable(text)) {
                        store.link = text
                        context.getString(R.string.saved)
                    } else {
                        "That does not look like a config link"
                    }
                }
                PillButton(stringResource(R.string.clear), filled = false) {
                    text = ""
                    store.link = null
                    note = null
                }
            }

            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = GozarColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    SubScreen(title = stringResource(R.string.about), onBack = onBack) {
        Card {
            Text("Gozar", color = GozarColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("0.1.0", color = GozarColors.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(16.dp))
            Text(
                "Gozar tries several ways out at once and keeps whichever one actually carries traffic. " +
                    "It holds a second one warm behind it, so when the first is blocked the switch happens " +
                    "without you noticing. There is nothing to configure and nothing to choose.",
                color = GozarColors.Muted,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text(stringResource(R.string.support), color = GozarColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.channel_note), color = GozarColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            PillButton(handleLtr(), filled = false) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.CHANNEL_URL)))
            }
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text("Open source", color = GozarColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "MIT licensed. Built on Tor's pluggable transports and hev-socks5-tunnel, both BSD and MIT.",
                color = GozarColors.Muted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GozarColors.Card)
            .border(1.dp, GozarColors.Hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (filled) GozarColors.Ink else GozarColors.Card)
            .border(1.dp, if (filled) GozarColors.Ink else GozarColors.Hairline, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            color = if (filled) GozarColors.Paper else GozarColors.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A left to right mark in front of the handle.
 *
 * Without it "@parsv2r" renders as "parsv2r@" on a Persian phone, because the
 * paragraph direction wins over the latin run.
 */
fun handleLtr(): String = "\u200E" + Support.CHANNEL_HANDLE
