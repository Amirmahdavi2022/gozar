package xyz.jmc.gozar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.jmc.gozar.core.Support

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GozarTheme {
                Surface(color = GozarColors.Paper) { GozarRoot() }
            }
        }
    }
}

@Composable
fun GozarScreen(vm: GozarViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val connected = state.phase == UiState.Phase.CONNECTED
    val working = state.phase == UiState.Phase.CONNECTING

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusLine(connected = connected, working = working)

        Spacer(Modifier.weight(1f))

        ConnectButton(connected = connected, working = working, onClick = vm::toggle)

        Spacer(Modifier.height(26.dp))

        Text(
            text = state.note,
            color = if (state.phase == UiState.Phase.FAILED) GozarColors.Bad else GozarColors.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(30.dp))

        // The panel is always here, holding dashes when idle. A layout that
        // rearranges itself every time the state changes feels broken even
        // when nothing is wrong.
        SessionPanel(state = state, live = connected)

        Spacer(Modifier.weight(1f))

        FooterLink(
            highlighted = state.phase == UiState.Phase.FAILED,
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.CHANNEL_URL)))
            },
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun StatusLine(connected: Boolean, working: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot by animateColorAsState(
            targetValue = when {
                connected -> GozarColors.Good
                working -> GozarColors.Ember
                else -> GozarColors.Hairline
            },
            animationSpec = tween(400),
            label = "statusDot",
        )

        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                connected -> "on"
                working -> "working"
                else -> "off"
            },
            color = GozarColors.Muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ConnectButton(connected: Boolean, working: Boolean, onClick: () -> Unit) {
    // Dark on light when it is off, ember when it is on. The screen changing
    // temperature is the feedback, so there is no spinner anywhere.
    val fill by animateColorAsState(
        targetValue = if (connected) GozarColors.Ember else GozarColors.Ink,
        animationSpec = tween(500),
        label = "buttonFill",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (working) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(214.dp)
                .alpha(if (connected) 1f else 0f)
                .clip(CircleShape)
                .background(GozarColors.Ember.copy(alpha = 0.12f))
        )

        Box(
            modifier = Modifier
                .scale(scale)
                .size(178.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    connected -> "Disconnect"
                    working -> "Cancel"
                    else -> "Connect"
                },
                color = if (connected) GozarColors.Ink else GozarColors.Paper,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SessionPanel(state: UiState, live: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GozarColors.Card)
            .border(1.dp, GozarColors.Hairline, RoundedCornerShape(18.dp))
            .padding(vertical = 20.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (live) formatElapsed(state.elapsedSeconds) else "--:--",
            color = GozarColors.Ink,
            fontSize = 38.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        Text(text = "connected for", color = GozarColors.Muted, fontSize = 12.sp)

        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MeterCell(
                label = "down",
                total = state.traffic.down,
                rate = state.traffic.downRate,
                live = live,
                accent = GozarColors.Good,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).height(46.dp).background(GozarColors.Hairline))
            MeterCell(
                label = "up",
                total = state.traffic.up,
                rate = state.traffic.upRate,
                live = live,
                accent = GozarColors.EmberDeep,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MeterCell(
    label: String,
    total: Long,
    rate: Long,
    live: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val (value, unit) = formatBytes(total)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (live) value else "0",
                color = GozarColors.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(4.dp))
            Text(text = if (live) unit else "B", color = GozarColors.Muted, fontSize = 12.sp)
        }
        Text(
            text = if (live) formatRate(rate) else "idle",
            color = if (live) accent else GozarColors.Muted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = GozarColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun FooterLink(highlighted: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (highlighted) GozarColors.Ember else GozarColors.Muted,
        animationSpec = tween(400),
        label = "footerTint",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (highlighted) {
            Text(
                text = "When everything is blocked, fresh routes get posted here",
                color = GozarColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = handleLtr(),
            color = tint,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.clickable(onClick = onClick).padding(6.dp),
        )
    }
}
