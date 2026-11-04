package hrln.interactive.sidekit.apps.sideport

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import hrln.interactive.sidekit.MainActivity
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.service.SideportService
import hrln.interactive.sidekit.navigation.LAUNCHER_ROUTE
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.flow.MutableStateFlow

/** Why a ported app couldn't open. */
enum class OpenResult { Opened, NeedsService, NotInstalled }

object Sideport {
    /**
     * Opens a ported app as a Sidekit app: starts the projection, then the app beneath it. The
     * projection shows "Opening…" straight away, so the app's own UI is never seen.
     */
    fun open(context: Context, app: PortedApp): OpenResult {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return OpenResult.NotInstalled
        val service = SideportService.instance ?: return OpenResult.NeedsService
        service.startSession(app)
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return OpenResult.Opened
    }
}

object SideportRoutes {
    val ROOT = SubApp.Sideport.route
    val HOME = "$ROOT/home"
    val ADD = "$ROOT/add"
    val INSTALL = "$ROOT/install"
    val HUB = "$ROOT/hub/{pkg}"
    val CHAT = "$ROOT/chat/{key}"
    val PLAYER = "$ROOT/player/{pkg}"
    fun hub(pkg: String) = "$ROOT/hub/$pkg"
    fun chat(key: String) = "$ROOT/chat/${android.net.Uri.encode(key)}"
    fun player(pkg: String) = "$ROOT/player/$pkg"
}

/** Links from a projection back into Sidekit (the app's hub of messages and media). */
object SideportLinks {
    const val ACTION_HUB = "hrln.interactive.sidekit.action.SIDEPORT_HUB"
    private const val EXTRA_PACKAGE = "package"
    private val pending = MutableStateFlow<String?>(null)

    fun hubIntent(context: Context, packageName: String): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_HUB).putExtra(EXTRA_PACKAGE, packageName)

    fun handle(intent: Intent?): Boolean {
        if (intent?.action != ACTION_HUB) return false
        pending.value = intent.getStringExtra(EXTRA_PACKAGE)?.let(SideportRoutes::hub) ?: return false
        return true
    }

    @Composable
    fun Follow(nav: NavHostController) {
        val route by pending.collectAsState()
        LaunchedEffect(route) {
            val target = route ?: return@LaunchedEffect
            pending.value = null
            nav.navigate(SideportRoutes.ROOT) { popUpTo(LAUNCHER_ROUTE) }
            nav.navigate(target)
        }
    }
}
