package xyz.jmc.gozar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import xyz.jmc.gozar.Traffic
import xyz.jmc.gozar.Tunnel

data class UiState(
    val phase: Phase = Phase.IDLE,
    val note: String = "",
    val elapsedSeconds: Long = 0,
    val traffic: Traffic = Traffic(),
    /** Where the tunnel comes out, e.g. "🇩🇪 Germany". Empty until it is known. */
    val exit: String = "",
) {
    enum class Phase { IDLE, CONNECTING, CONNECTED, FAILED }
}

/**
 * Reads the real tunnel rather than keeping its own idea of what is happening.
 *
 * The timer and the counters only advance while the tunnel says it is up, so
 * the screen cannot show a session that is not there. That mattered more than
 * it sounds: a running clock over a dead tunnel is how people end up trusting
 * an app that is not working.
 */
@Composable
fun rememberTunnelState(notes: WaitingNotes): UiState {
    val phase by Tunnel.phase.collectAsState()
    val exit by Tunnel.exit.collectAsState()
    var elapsed by remember { mutableStateOf(0L) }
    var traffic by remember { mutableStateOf(Traffic()) }
    var note by remember { mutableStateOf(notes.idle) }

    LaunchedEffect(phase) {
        when (phase) {
            Tunnel.Phase.UP -> {
                note = notes.connected
                elapsed = 0
                var lastDown = 0L
                var lastUp = 0L
                while (true) {
                    delay(1_000)
                    elapsed++
                    val sample = Tunnel.traffic.sample()
                    traffic = sample.copy(
                        downRate = (sample.down - lastDown).coerceAtLeast(0),
                        upRate = (sample.up - lastUp).coerceAtLeast(0),
                    )
                    lastDown = sample.down
                    lastUp = sample.up
                }
            }

            Tunnel.Phase.WORKING -> {
                elapsed = 0
                traffic = Traffic()
                // Rotating lines so a long wait does not look like a freeze.
                // Deliberately vague: which transport is being tried is not
                // the user's problem and naming it invites fiddling.
                var i = 0
                while (true) {
                    note = notes.waiting[i % notes.waiting.size]
                    i++
                    delay(6_000)
                }
            }

            Tunnel.Phase.FAILED -> {
                note = notes.failed
                traffic = Traffic()
            }

            Tunnel.Phase.DOWN -> {
                note = notes.idle
                elapsed = 0
                traffic = Traffic()
            }
        }
    }

    return UiState(
        phase = when (phase) {
            Tunnel.Phase.UP -> UiState.Phase.CONNECTED
            Tunnel.Phase.WORKING -> UiState.Phase.CONNECTING
            Tunnel.Phase.FAILED -> UiState.Phase.FAILED
            Tunnel.Phase.DOWN -> UiState.Phase.IDLE
        },
        note = note,
        elapsedSeconds = elapsed,
        traffic = traffic,
        exit = exit,
    )
}

/** Screen copy, passed in so it can come from resources and be translated. */
data class WaitingNotes(
    val idle: String,
    val waiting: List<String>,
    val connected: String,
    val failed: String,
)
