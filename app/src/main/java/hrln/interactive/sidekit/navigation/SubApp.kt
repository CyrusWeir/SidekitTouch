package hrln.interactive.sidekit.navigation

import hrln.interactive.sidekit.design.icons.SidekitIcon

/**
 * Every app the launcher can open, in launcher grid order (Launcher Grid view, 53:1129).
 *
 * [designed] is false for tiles that appear in the launcher mock but have no app mock in
 * Figma; those open [hrln.interactive.sidekit.apps.placeholder.PlaceholderAppScreen].
 * Apps after [WebBrowser] have mocks but no tile in the grid mock, so they're appended.
 */
enum class SubApp(val label: String, val icon: SidekitIcon, val designed: Boolean = true) {
    Sidemessage("Sidemessage", SidekitIcon.Chat),
    Sidenotes("Sidenotes", SidekitIcon.Docs),
    Sideticks("Sideticks", SidekitIcon.Checklist),
    Phonebook("Phonebook", SidekitIcon.CallLog),
    Photos("Photos", SidekitIcon.PhotoLibrary),
    Translate("Translate", SidekitIcon.Translate),
    Calculator("Calculator", SidekitIcon.Calculate),
    Mail("Mail", SidekitIcon.Mail),
    Files("Files", SidekitIcon.FolderCopy),
    Maps("Maps", SidekitIcon.Map),
    Music("Music", SidekitIcon.MusicNote2),
    Clock("Clock", SidekitIcon.Alarm),
    Video("Video", SidekitIcon.SmartDisplay),
    Vault("Vault", SidekitIcon.Encrypted),
    TripPlanning("Trip Planning", SidekitIcon.BusMapPin),
    Banking("Banking", SidekitIcon.AccountBalanceWallet),
    Settings("Settings", SidekitIcon.Settings),
    WebBrowser("Web browser", SidekitIcon.TravelExplore),
    Apps("Apps", SidekitIcon.DeployedCode),
    Camera("Camera", SidekitIcon.PhotoCamera),
    Sideweather("Sideweather", SidekitIcon.PartlyCloudyDay),
    Calendar("Calendar", SidekitIcon.CalendarMonth),
    Compass("Compass", SidekitIcon.Explore),
    Health("Health", SidekitIcon.EcgHeart),
    Sideport("Sideport", SidekitIcon.DeployedCode),
    ;

    /** Root navigation route of the app. Nested screens use "$route/...". */
    val route: String get() = "app/${name.lowercase()}"

    companion object {
        /** The blue dock pinned to the bottom of the launcher (53:1256). */
        val dock: List<SubApp> = listOf(Sidemessage, Sidenotes, Sideticks, Phonebook)
    }
}
