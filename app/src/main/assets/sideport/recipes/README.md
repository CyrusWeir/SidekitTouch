# Sideport recipes

Each `.json` file here holds one recipe or an array of recipes. A recipe shapes how a ported
app's screens are drawn; without one the generic interpreter decides (see `Interpreter.kt`).

Write new ones from an inspector export: open the app in Sideport, press Options > Menu >
Inspect screen > Export, then `adb pull /sdcard/Android/data/hrln.interactive.sidekit/files/sideport/dumps/`.
Recipes pushed to `/sdcard/Android/data/hrln.interactive.sidekit/files/sideport/recipes/` load the
next time an app is opened, without rebuilding Sidekit.

Keys: `name`, `packages` (or `package`; `"*"` = every app), `screen` (activity name contains),
`whenPresent`, `title`, `hide`, `list`, `itemTitle`, `itemSecondary`, `actions`, `options`,
`priority`. Selectors: `id:send`, `id:com.app:id/send`, `text:Allow`, `text~:allow`,
`desc:More options`, `desc~:more`, `class:EditText`, combined with ` & `.
The first two actions found on screen go in the action bar beside Back; the rest go in Options.
