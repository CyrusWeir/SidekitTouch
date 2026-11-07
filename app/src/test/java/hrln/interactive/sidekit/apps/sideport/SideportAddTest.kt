package hrln.interactive.sidekit.apps.sideport

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.apps.apps.LaunchableApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sideport's Add app picker and registry: Sidekit builds are never ported, and rows are unified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SideportAddTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("sideport-test", Context.MODE_PRIVATE)

    @Before fun cleanPrefs() {
        prefs.edit().clear().commit()
    }

    private fun app(pkg: String, label: String, cls: String = "Main") = LaunchableApp(ComponentName(pkg, cls), Process.myUserHandle(), label)

    @Test fun `candidates leave out every Sidekit build and apps already ported`() {
        val installed = listOf(
            app("hrln.interactive.sidekit", "Sidekit"),
            app("hrln.interactive.sidekit.dev", "Sidekit dev"),
            app("com.example.chat", "Example Chat"),
            app("com.example.mail", "Example Mail")
        )
        val ported = listOf(PortedApp("com.example.mail", "Example Mail"))
        assertEquals(listOf("com.example.chat"), sideportCandidates(installed, ported, "").map { it.component.packageName })
    }

    @Test fun `candidates keep one row per package and match the query`() {
        val installed = listOf(
            app("com.example.chat", "Example Chat", "Main"),
            app("com.example.chat", "Example Chat", "Share"),
            app("com.example.mail", "Example Mail")
        )
        val candidates = sideportCandidates(installed, emptyList(), "chat")
        assertEquals(listOf("com.example.chat/Main"), candidates.map { "${it.component.packageName}/${it.component.className}" })
    }

    @Test fun `the registry refuses to port a Sidekit build`() {
        val ported = PortedApps(prefs)
        ported.add("hrln.interactive.sidekit", "Sidekit")
        ported.add("hrln.interactive.sidekit.dev", "Sidekit dev")
        ported.add("com.example.chat", "Example Chat")
        assertEquals(listOf("com.example.chat"), ported.apps.value.map { it.packageName })
    }

    @Test fun `a Sidekit entry saved by an earlier version is dropped on read`() {
        prefs.edit().putString(
            "apps",
            """[{"package":"hrln.interactive.sidekit.dev","label":"Sidekit dev","mode":"Projected","added":1}]"""
        ).commit()
        assertEquals(emptyList<PortedApp>(), PortedApps(prefs).apps.value)
    }
}
