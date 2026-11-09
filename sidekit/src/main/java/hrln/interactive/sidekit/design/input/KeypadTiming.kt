package hrln.interactive.sidekit.design.input

import android.view.ViewConfiguration

/**
 * The system long-press threshold, shared by T9 and the Sundial holds.
 *
 * [ViewConfiguration.getLongPressTimeout] follows `Settings.Secure.LONG_PRESS_TIMEOUT` (400 ms by
 * default), so a device or accessibility tool that changes the system value also changes these
 * gestures. The Back-to-dialer hold keeps its own, longer threshold in [SystemKeys].
 */
object KeypadTiming {
    val LONG_PRESS_MS: Long get() = ViewConfiguration.getLongPressTimeout().toLong()
}
