package hrln.interactive.sidekit.system

/**
 * The package ids Sidekit ships under. The stable app is [ID]; every other build adds a suffix
 * to it (`hrln.interactive.sidekit.dev`, see app/build.gradle.kts), so a checkout's dev build can
 * be installed beside the daily driver.
 *
 * Sidekit can launch a sibling build as an ordinary app, but never itself, and no build can be
 * turned into a Sideport app: projecting Sidekit with Sidekit is recursive.
 */
object SidekitBuild {
    /** Application id of the stable build, shared by every variant. */
    const val ID = "hrln.interactive.sidekit"

    /** True for Sidekit itself and any build installed beside it. */
    fun isSidekit(packageName: String): Boolean = packageName == ID || packageName.startsWith("$ID.")
}
