package xyz.jmc.gozar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.jmc.gozar.NoTraffic
import xyz.jmc.gozar.Traffic
import xyz.jmc.gozar.TrafficSource

data class UiState(
    val phase: Phase = Phase.IDLE,
    /** Plain language only. Never a protocol name. */
    val note: String = "Tap to start",
    val elapsedSeconds: Long = 0,
    val traffic: Traffic = Traffic(),
) {
    enum class Phase { IDLE, CONNECTING, CONNECTED, FAILED }
}

/**
 * Holds what the screen shows.
 *
 * The notes it cycles through while connecting are deliberately vague. They
 * exist so the wait feels like something is happening, not so the user can
 * follow along with which transport is being tried — that is not their problem
 * and naming it would only invite them to start fiddling.
 */
class GozarViewModel(
    private val traffic: TrafficSource = NoTraffic,
    private val connect: suspend () -> Boolean = { false },
    private val disconnect: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var attempt: Job? = null

    private val waitingNotes = listOf(
        "Looking for a way out",
        "Trying another route",
        "Still looking",
    )

    fun toggle() {
        when (_state.value.phase) {
            UiState.Phase.CONNECTED, UiState.Phase.CONNECTING -> stop()
            else -> start()
        }
    }

    private fun start() {
        _state.value = UiState(phase = UiState.Phase.CONNECTING, note = waitingNotes.first())

        attempt = viewModelScope.launch {
            val rotation = launch {
                var i = 0
                while (true) {
                    delay(6_000)
                    i = (i + 1) % waitingNotes.size
                    _state.update { it.copy(note = waitingNotes[i]) }
                }
            }

            val ok = runCatching { connect() }.getOrDefault(false)
            rotation.cancel()

            if (ok) {
                _state.update { it.copy(phase = UiState.Phase.CONNECTED, note = "You're through") }
                startTicking()
            } else {
                _state.value = UiState(
                    phase = UiState.Phase.FAILED,
                    note = "Nothing is getting out right now",
                )
            }
        }
    }

    private fun stop() {
        attempt?.cancel()
        ticker?.cancel()
        viewModelScope.launch { runCatching { disconnect() } }
        _state.value = UiState()
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var seconds = 0L
            var lastDown = 0L
            var lastUp = 0L

            while (true) {
                delay(1_000)
                seconds++
                val sample = traffic.sample()
                val current = sample.copy(
                    downRate = (sample.down - lastDown).coerceAtLeast(0),
                    upRate = (sample.up - lastUp).coerceAtLeast(0),
                )
                lastDown = sample.down
                lastUp = sample.up

                _state.update { it.copy(elapsedSeconds = seconds, traffic = current) }
            }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        attempt?.cancel()
        super.onCleared()
    }
}
