package hrln.interactive.sidekit.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import hrln.interactive.sidekit.apps.banking.bankingGraph
import hrln.interactive.sidekit.apps.calculator.calculatorGraph
import hrln.interactive.sidekit.apps.calendar.calendarGraph
import hrln.interactive.sidekit.apps.camera.cameraGraph
import hrln.interactive.sidekit.apps.compass.compassGraph
import hrln.interactive.sidekit.apps.files.filesGraph
import hrln.interactive.sidekit.apps.health.healthGraph
import hrln.interactive.sidekit.apps.mail.mailGraph
import hrln.interactive.sidekit.apps.maps.mapsGraph
import hrln.interactive.sidekit.apps.music.musicGraph
import hrln.interactive.sidekit.apps.phonebook.phonebookGraph
import hrln.interactive.sidekit.apps.photos.photosGraph
import hrln.interactive.sidekit.apps.placeholder.PlaceholderAppScreen
import hrln.interactive.sidekit.apps.settings.settingsGraph
import hrln.interactive.sidekit.apps.sidemessage.IncomingLinks
import hrln.interactive.sidekit.apps.sidemessage.sidemessageGraph
import hrln.interactive.sidekit.apps.sidenotes.sidenotesGraph
import hrln.interactive.sidekit.apps.sideticks.sideticksGraph
import hrln.interactive.sidekit.apps.sideweather.sideweatherGraph
import hrln.interactive.sidekit.apps.translate.translateGraph
import hrln.interactive.sidekit.apps.tripplanning.tripPlanningGraph
import hrln.interactive.sidekit.apps.webbrowser.webBrowserGraph
import hrln.interactive.sidekit.launcher.LauncherRoute

const val LAUNCHER_ROUTE = "launcher"

/**
 * @param homeRequests incremented each time the Home button is pressed while SideKit is already
 * the foreground home app; every increment returns to the launcher.
 */
@Composable
fun SidekitNavHost(navController: NavHostController = rememberNavController(), homeRequests: Int = 0) {
    val back: () -> Unit = { navController.popBackStack() }
    LaunchedEffect(homeRequests) {
        if (homeRequests > 0) navController.popBackStack(LAUNCHER_ROUTE, inclusive = false)
    }
    // sms:/smsto: links and message notifications open a Sidemessage conversation.
    IncomingLinks.Follow(navController)
    // No screen transition. Every screen is the same frame (title, body, action bar) and the
    // title and bar are shared chrome that morph on their own, so navigating swaps only the body,
    // which plays its own entrance (rows rising in). Nothing moves as a whole screen, and only one
    // body is ever composed: no second screen held in memory or drawn during a transition.
    NavHost(
        navController = navController,
        startDestination = LAUNCHER_ROUTE,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(LAUNCHER_ROUTE) {
            LauncherRoute(onOpenApp = { app -> navController.navigate(app.route) { launchSingleTop = true } })
        }
        // Core SideKit apps
        sidemessageGraph(navController)
        sidenotesGraph(navController)
        sideticksGraph(navController)
        phonebookGraph(navController)
        photosGraph(navController)
        translateGraph(navController)
        calculatorGraph(navController)
        musicGraph(navController)
        cameraGraph(navController)
        sideweatherGraph(navController)
        // Sidephone everyday apps (96:419)
        calendarGraph(navController)
        mailGraph(navController)
        mapsGraph(navController)
        compassGraph(navController)
        filesGraph(navController)
        healthGraph(navController)
        webBrowserGraph(navController)
        settingsGraph(navController)
        bankingGraph(navController)
        tripPlanningGraph(navController)
        // Launcher tiles without an app mock
        SubApp.entries.filterNot { it.designed }.forEach { app ->
            composable(app.route) { PlaceholderAppScreen(app = app, onBack = back) }
        }
    }
}
