package xyz.jmc.gozar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import xyz.jmc.gozar.core.Support

private val Ember = Color(0xFFFF7A18)
private val Ink = Color(0xFF0B0B0D)
private val Dim = Color(0xFF8A8A93)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ember, background = Ink)) {
                Surface(color = Ink) { GozarScreen() }
            }
        }
    }
}

@Composable
fun GozarScreen() {
    var state: ConnectionState by remember { mutableStateOf(ConnectionState.Idle) }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(if (state is ConnectionState.Connected) Ember else Color(0xFF17171B))
                .clickable {
                    state = when (state) {
                        is ConnectionState.Connected -> ConnectionState.Idle
                        else -> ConnectionState.Connecting("Looking for a way out")
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (state) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Connecting -> "Working"
                    else -> "Connect"
                },
                color = if (state is ConnectionState.Connected) Ink else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = when (val s = state) {
                is ConnectionState.Idle -> "Tap to start"
                is ConnectionState.Connecting -> s.note
                is ConnectionState.Connected -> "You're through"
                is ConnectionState.NoWayOut -> "Nothing is getting out right now"
            },
            color = Dim,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 26.dp),
        )

        // The channel only appears when everything has failed. During a real
        // shutdown that is the only thing left that helps, and the rest of the
        // time it is clutter.
        if (state is ConnectionState.NoWayOut) {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.CHANNEL_URL)))
                },
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(Support.CHANNEL_HANDLE, color = Ember, fontSize = 15.sp)
            }
        }
    }
}
