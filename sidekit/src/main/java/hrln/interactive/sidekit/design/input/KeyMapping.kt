package hrln.interactive.sidekit.design.input

import android.content.SharedPreferences
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One physical key: the input device that sent it and the code it reported. Keys are told apart
 * by key code; a key the system has no key code for ([KeyEvent.KEYCODE_UNKNOWN]) by its scan code.
 *
 * [device] is the input device's name, which stays the same across restarts and reconnects (its
 * id doesn't), so a binding only ever matches the keypad it was learned on: an external keyboard
 * or headset that sends the same code is left alone.
 */
data class PhysicalKey(val device: String, val keyCode: Int, val scanCode: Int = 0) {
    /** Readable name, e.g. "F1" or "Scan code 250". */
    val label: String
        get() = if (keyCode == KeyEvent.KEYCODE_UNKNOWN) "Scan code $scanCode" else keyName(keyCode)

    companion object {
        /** KeyEvent's own constant names, by code (keyCodeToString isn't available everywhere). */
        private val names: Map<Int, String> by lazy {
            KeyEvent::class.java.fields
                .filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .associate { it.getInt(null) to it.name.removePrefix("KEYCODE_") }
        }

        fun of(event: KeyEvent, device: String): PhysicalKey {
            val known = event.keyCode != KeyEvent.KEYCODE_UNKNOWN
            return PhysicalKey(device, event.keyCode, if (known) 0 else event.scanCode)
        }

        /** "KEYCODE_DPAD_UP" -> "D-pad up", "KEYCODE_F1" -> "F1". */
        fun keyName(code: Int): String {
            val raw = names[code] ?: return "Key $code"
            if (raw.length <= 3) return raw
            return raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }.replace("Dpad", "D-pad")
        }
    }
}

/**
 * Something a key does in SideKit: one printed key of a [KeypadModule] (or one of the system
 * keys). [keyCode] is the code the Sidephone's own module sends for it, which is what the rest of
 * SideKit (T9 typing, focus, Back, Options) acts on.
 */
class KeypadControl(val keyCode: Int, val label: String, val hint: String)

/** The controls of each module, in the order they're printed on it. */
object KeypadControls {
    fun of(module: KeypadModule): List<KeypadControl> = when (module) {
        KeypadModule.T9 -> system + dpad + listOf(
            control(KeyEvent.KEYCODE_ENTER, "OK", "Select, or take a suggestion"),
        ) + (1..9).map { digit(it) } + listOf(
            control(KeyEvent.KEYCODE_STAR, "*", "Punctuation and symbols"),
            digit(0),
            control(KeyEvent.KEYCODE_POUND, "#", "Case; hold for numbers"),
            control(KeyEvent.KEYCODE_DEL, "Delete", "Delete the previous character"),
        )
        KeypadModule.Qwerty -> system + QwertyLayout.letters.map { (code, letters) ->
            control(code, letters.uppercase(), "Hold for ${QwertyLayout.printed[code]}")
        } + listOf(
            control(KeyEvent.KEYCODE_SPACE, "Space", "Hold for 0"),
            control(KeyEvent.KEYCODE_DEL, "Delete", "Delete the previous character"),
            control(KeyEvent.KEYCODE_ENTER, "Enter", "New line, or take a suggestion"),
            control(QwertyLayout.SUGGEST, "↕", "Next suggestion"),
            control(QwertyLayout.SYM, "SYM", "Symbols"),
            control(QwertyLayout.CASE, "aA", "Case; hold for numbers"),
        )
        KeypadModule.Sundial -> system + listOf(
            control(KeyEvent.KEYCODE_DPAD_UP, "Top", "Move up"),
            control(KeyEvent.KEYCODE_MEDIA_NEXT, "Right edge", "Next track"),
            control(KeyEvent.KEYCODE_DPAD_DOWN, "Bottom", "Move down"),
            control(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Left edge", "Previous track"),
            control(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Centre", "Play or pause"),
            control(KeyEvent.KEYCODE_DPAD_LEFT, "Corner ←", "Move left"),
            control(KeyEvent.KEYCODE_DPAD_RIGHT, "Corner →", "Move right"),
            control(KeyEvent.KEYCODE_TAB, "Tab", "Next control"),
            control(KeyEvent.KEYCODE_ENTER, "Enter", "Select"),
        )
        KeypadModule.GamePad -> system + dpad + listOf(
            control(KeyEvent.KEYCODE_BUTTON_A, "A", "Confirm"),
            control(KeyEvent.KEYCODE_BUTTON_B, "B", "Back in games"),
            control(KeyEvent.KEYCODE_BUTTON_X, "X", "Game action"),
            control(KeyEvent.KEYCODE_BUTTON_Y, "Y", "Game action"),
            control(KeyEvent.KEYCODE_BUTTON_L1, "Shoulder", "Game action"),
        )
    }

    fun find(module: KeypadModule, keyCode: Int): KeypadControl? = of(module).firstOrNull { it.keyCode == keyCode }

    private fun control(code: Int, label: String, hint: String) = KeypadControl(code, label, hint)
    private fun digit(n: Int): KeypadControl {
        val letters = listOf("", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")[n]
        return control(KeyEvent.KEYCODE_0 + n, if (letters.isEmpty()) "$n" else "$n $letters", if (n == 0) "Space; hold for 0" else if (n == 1) "Punctuation; hold for 1" else "Letters; hold for $n")
    }

    private val system = listOf(
        // Back is the plain Back key here: the Sidephone's top-left key (CALL) only becomes Back
        // through the keypad accessibility service, which keeps working whatever is mapped.
        control(KeyEvent.KEYCODE_BACK, "Back", "Go back a screen"),
        control(KeyEvent.KEYCODE_ENDCALL, "Options", "Show the screen's extra actions"),
    )
    private val dpad = listOf(
        control(KeyEvent.KEYCODE_DPAD_UP, "Up", "Move up"),
        control(KeyEvent.KEYCODE_DPAD_DOWN, "Down", "Move down"),
        control(KeyEvent.KEYCODE_DPAD_LEFT, "Left", "Move left; cursor"),
        control(KeyEvent.KEYCODE_DPAD_RIGHT, "Right", "Move right; cursor"),
    )
}

/**
 * Custom keypad mapping, for keypads SideKit doesn't recognise and for anyone who wants a
 * Sidephone key somewhere else.
 *
 * Off by default ([customModule] is null): keys arrive untouched and the module is detected from
 * them as before. Turned on, SideKit treats the keypad as [customModule] (detection pauses, so a
 * stray key can't switch it away) and every key bound in that module's profile is rewritten to the
 * control it was bound to ([translate]) before anything else sees it. Keys that aren't bound keep
 * doing what they did, so a half-finished profile never takes a working key away.
 *
 * Each module keeps its own profile, saved across restarts. Every SideKit window with a keypad
 * (the app and the Sideport overlay) shares the saved profiles.
 */
@Stable
class KeyMapper internal constructor(
    private val prefs: SharedPreferences?,
    private val deviceName: (KeyEvent) -> String = ::defaultDeviceName,
) {
    /** The module the custom mapping is for, or null for automatic detection. */
    var customModule: KeypadModule? by mutableStateOf(null)
        private set

    private var profiles: Map<KeypadModule, Map<Int, PhysicalKey>> by mutableStateOf(emptyMap())

    /** Keys held down, and the code each one started with: its repeats and release keep it. */
    private val held = HashMap<PhysicalKey, Int>()
    private var last: Translation? = null
    private var capture: ((PhysicalKey, KeyEvent) -> Boolean)? = null
    private var watcher: ((PhysicalKey, KeyEvent) -> Unit)? = null

    // Held strongly: SharedPreferences keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == PREF_CUSTOM || key.startsWith(PREF_PROFILE)) load()
    }

    init {
        load()
        prefs?.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Keys bound in [module]'s profile, by the control they were bound to. */
    fun bindings(module: KeypadModule): Map<Int, PhysicalKey> = profiles[module].orEmpty()

    internal fun setCustom(module: KeypadModule?) {
        if (customModule == module) return
        customModule = module
        prefs?.edit()?.apply { if (module == null) remove(PREF_CUSTOM) else putString(PREF_CUSTOM, module.name) }?.apply()
    }

    /**
     * Binds [key] to the control [controlCode] of [module]. A key does one thing, so if it was
     * bound to another control that control goes back to its default; that control is returned.
     */
    fun bind(module: KeypadModule, controlCode: Int, key: PhysicalKey): Int? {
        val current = bindings(module)
        val displaced = current.entries.firstOrNull { it.value == key && it.key != controlCode }?.key
        save(module, current.filterValues { it != key } + (controlCode to key))
        return displaced
    }

    /** The control [key] is bound to in [module], if any. */
    fun controlFor(module: KeypadModule, key: PhysicalKey): Int? =
        bindings(module).entries.firstOrNull { it.value == key }?.key

    fun reset(module: KeypadModule, controlCode: Int) = save(module, bindings(module) - controlCode)

    fun resetAll(module: KeypadModule) = save(module, emptyMap())

    /**
     * Rewrites a key event from the keypad to the control it is mapped to. Returns [event] itself
     * when nothing changes, and null when the press belongs to a key capture and must go no
     * further. Safe to call more than once for the same event as it travels through dispatch.
     */
    fun translate(event: KeyEvent): KeyEvent? {
        // The same event reaches several stages (before the IME, then the activity). The system
        // recycles event objects, so identity alone doesn't prove it's the same press.
        last?.let {
            if (it.matches(event)) return it.out
            // Already translated (handed on by an earlier stage): translating again would map it twice.
            if (event === it.out && event !== it.event) return event
        }
        val out = compute(event)
        last = Translation(event, event.action, event.keyCode, event.downTime, event.eventTime, event.repeatCount, out)
        return out
    }

    /**
     * Sends the next key presses to [onKey] instead of SideKit while it returns true: for learning
     * a key. A captured press is swallowed whole (its repeats and release too). Returns a function
     * that stops capturing.
     */
    fun capture(onKey: (PhysicalKey, KeyEvent) -> Boolean): () -> Unit {
        capture = onKey
        return { if (capture === onKey) capture = null }
    }

    /** Shows every key press (and what it became) to [onKey] without changing it: the key tester. */
    fun watch(onKey: (PhysicalKey, KeyEvent) -> Unit): () -> Unit {
        watcher = onKey
        return { if (watcher === onKey) watcher = null }
    }

    /** The physical key an event came from. */
    fun keyOf(event: KeyEvent): PhysicalKey = PhysicalKey.of(event, deviceName(event))

    private fun compute(event: KeyEvent): KeyEvent? {
        // Virtual keyboards mark their key events; they're never the keypad.
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return event
        if (event.action == KeyEvent.ACTION_MULTIPLE) return event
        val key = keyOf(event)
        val code = when (event.action) {
            KeyEvent.ACTION_DOWN -> held[key]?.takeIf { event.repeatCount > 0 } ?: run {
                // A new press: decided now, then kept until its release.
                val taken = capture?.invoke(key, event) == true
                (if (taken) SWALLOWED else mappedCode(key)).also { held[key] = it }
            }
            // A release finishes the press the way it started, even if the mapping changed meanwhile.
            else -> held.remove(key) ?: if (capture != null) SWALLOWED else mappedCode(key)
        }
        if (code == SWALLOWED) return null
        val out = if (code == event.keyCode) event else event.withCode(code)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) watcher?.invoke(key, out)
        return out
    }

    private fun mappedCode(key: PhysicalKey): Int {
        val module = customModule ?: return key.keyCode
        return controlFor(module, key) ?: key.keyCode
    }

    private fun load() {
        val p = prefs ?: return
        customModule = p.getString(PREF_CUSTOM, null)?.let { name -> KeypadModule.entries.firstOrNull { it.name == name } }
        profiles = KeypadModule.entries.associateWith { module ->
            p.getStringSet(PREF_PROFILE + module.name, emptySet()).orEmpty().mapNotNull(::decode).toMap()
        }
    }

    private fun save(module: KeypadModule, bindings: Map<Int, PhysicalKey>) {
        profiles = profiles + (module to bindings)
        prefs?.edit()?.putStringSet(PREF_PROFILE + module.name, bindings.map { (control, key) -> encode(control, key) }.toSet())?.apply()
    }

    companion object {
        private const val PREF_CUSTOM = "custom_module"
        private const val PREF_PROFILE = "profile."
        private const val SWALLOWED = Int.MIN_VALUE

        /** "control|keyCode|scanCode|device": the device name goes last because it may hold anything. */
        private fun encode(control: Int, key: PhysicalKey) = "$control|${key.keyCode}|${key.scanCode}|${key.device}"

        private fun decode(entry: String): Pair<Int, PhysicalKey>? {
            val parts = entry.split('|', limit = 4)
            if (parts.size != 4) return null
            val control = parts[0].toIntOrNull() ?: return null
            val code = parts[1].toIntOrNull() ?: return null
            val scan = parts[2].toIntOrNull() ?: return null
            return control to PhysicalKey(parts[3], code, scan)
        }

        private fun defaultDeviceName(event: KeyEvent): String =
            event.device?.name ?: if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) "Virtual" else ""

        /** A mapper that saves to [prefs] (the keypad's own preferences). */
        fun create(prefs: SharedPreferences?): KeyMapper = KeyMapper(prefs)

        /** A mapper for tests, with a fixed way of naming devices. */
        fun forTesting(prefs: SharedPreferences? = null, deviceName: (KeyEvent) -> String = { "" }) = KeyMapper(prefs, deviceName)
    }
}

private class Translation(
    val event: KeyEvent, val action: Int, val keyCode: Int, val downTime: Long, val eventTime: Long, val repeat: Int,
    val out: KeyEvent?,
) {
    fun matches(e: KeyEvent) = e === event && e.action == action && e.keyCode == keyCode && e.downTime == downTime &&
        e.eventTime == eventTime && e.repeatCount == repeat
}

