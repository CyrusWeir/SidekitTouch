# Sidekit setup

First launch opens a seven-step setup using Sidekit's existing canvas, theme, cards and action bar. It also appears once for existing installations that have not completed setup. Settings → Setup & keypad tour reopens the flow without resetting existing choices.

1. Choose Sidekit as Android's home app, or continue to do this later.
2. Match the accent to a Sidephone case. Changes apply immediately.
3. Choose the grid or list home layout.
4. Hide apps you do not want on home. The same controls are in Settings → Hide apps. Built-in and Sideport apps can be hidden or restored, including with Show all apps. Hidden apps are excluded from grid, list, search and dock; they are not uninstalled. Settings remains in Options.
5. Review feature-specific permissions and special access. Nothing is requested automatically. Declining access never blocks completion; the affected feature still needs its permission. Returning from Android settings refreshes status. A repeated runtime-permission request opens App settings so permanently denied access can be changed.
6. Choose letters or numbers as the default input mode.
7. Explore tappable, schematic keypad diagrams. The remembered/detected module comes first. Skip through all four without changing the active module, or explicitly select one with “Use”. Finish opens home.

The keypad hardware exposes no distinct module identifier. Existing detection recognises distinctive key events, persists the last module and initially defaults to T9. Setup does not claim to detect a module before such a key is pressed. Controller actions depend on the app/game; the tour does not promise universal game mappings.

Progress and preferences survive process restarts. Only Finish records completion. Account connections are configured inside their respective apps.

## Launcher search

Start typing on T9 or Mini QWERTY from home. The clock becomes the query field, and the dock becomes the normal typing/suggestions bar. Search matches visible app names without regard to case, across built-in and Sideport apps, in both layouts. A square X stays on the right, including while typing Options are open and when there are no results. X or Back clears the query and restores the dock. The header also accepts touch input.

## Verification

`./gradlew :app:testDebugUnitTest --tests '*OnboardingTest' --tests '*SettingsUiTest' --tests '*LauncherSearchTest' --tests '*LauncherUiTest' --tests '*KeypadKeysTest' :app:assembleDebug :app:lintDebug`

Rendered screenshots are in `app/screenshots/onboarding/`. They are Compose/Roborazzi captures at the Sidephone canvas size, not photographs of hardware.

Before merge, test on a physical Sidephone: fresh setup and restart mid-flow; accept/decline home and SMS roles; grant/deny permissions and return from special-access settings; verify grid/list and accent persistence; traverse using D-pad and OK; verify Back/Options and each actual module's typing, long presses and detection order. Also verify reopening setup from Settings and entering the app through an SMS link after setup.
