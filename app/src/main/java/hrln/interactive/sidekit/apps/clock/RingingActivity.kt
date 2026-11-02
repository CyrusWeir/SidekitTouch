package hrln.interactive.sidekit.apps.clock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme

class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val repo = Clock.repository(applicationContext)
        setContent {
            val data by repo.data.collectAsStateWithLifecycle()
            SidekitTheme {
                SidekitCanvas {
                    val ring = data?.ringing?.takeIf { System.currentTimeMillis() - it.at < 600000 }
                    SidekitScaffold(title = ring?.label ?: "Clock", actions = if (ring == null) listOf(SideAction("dismiss", "Close") { finish() }) else listOf(
                        SideAction("snooze", "Snooze") { repo.snooze(); finish() },
                        SideAction("dismiss", "Dismiss") { repo.dismiss(); finish() },
                    )) { padding ->
                        val list = rememberLazyListState()
                        SidekitList(padding, state = list) {
                            item { SidekitItem(if (ring == null) "No alarm ringing" else "Snooze for 5 minutes", Modifier.listFade(list, 0)) }
                        }
                    }
                }
            }
        }
    }
}
