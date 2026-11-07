package hrln.interactive.sidekit.apps.settings

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.input.HoldToAct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A tap is delivered on release; a hold fires while the key is still down and never also taps. */
@RunWith(AndroidJUnit4::class)
class HoldToActTest {
    private class FakeScheduler : HoldToAct.Scheduler {
        var scheduledDelay = -1L
        private var action: (() -> Unit)? = null
        override fun schedule(delayMs: Long, action: () -> Unit): HoldToAct.Cancellable {
            scheduledDelay = delayMs
            this.action = action
            return HoldToAct.Cancellable { this.action = null }
        }
        fun fire() {
            action?.invoke()
        }
    }

    private val holds = mutableListOf<Pair<Int, KeyEvent>>()
    private val scheduler = FakeScheduler()

    private fun hold() = HoldToAct(
        holdMs = 600,
        onHold = { action, source -> holds += action to source },
        scheduler = scheduler
    )

    private fun event(action: Int, code: Int, time: Long, repeat: Int = 0) = KeyEvent(0L, time, action, code, repeat, 0, 1, 0)

    private fun action(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_TAB -> KeyEvent.KEYCODE_BACK
        KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_ENDCALL
        else -> null
    }

    @Test fun shortTapIsDeliveredOnRelease() {
        val hold = hold()
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0), ::action))
        assertEquals(600L, scheduler.scheduledDelay)
        val result = hold.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 100), ::action)
        assertTrue(result is HoldToAct.Result.Deliver)
        result as HoldToAct.Result.Deliver
        assertEquals(listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_TAB), result.events.map { it.keyCode })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), result.events.map { it.action })
    }

    @Test fun holdFiresWhileHeldAndSwallowsTheRelease() {
        val hold = hold()
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0), ::action))
        scheduler.fire()
        assertEquals(listOf(KeyEvent.KEYCODE_BACK), holds.map { it.first })
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 500), ::action))
    }

    @Test fun holdFiresOnReleaseWhenTheTimerDidNotRun() {
        val hold = hold()
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0), ::action))
        assertEquals(
            HoldToAct.Result.Consume,
            hold.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 700), ::action)
        )
        assertEquals(listOf(KeyEvent.KEYCODE_ENDCALL), holds.map { it.first })
    }

    @Test fun otherKeysPassAndDropAPendingTap() {
        val hold = hold()
        assertEquals(HoldToAct.Result.Pass, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0), ::action))
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 100), ::action))
        assertEquals(HoldToAct.Result.Pass, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 150), ::action))
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 200), ::action))
        assertEquals(emptyList<Int>(), holds.map { it.first })
    }

    @Test fun resetCancelsAPendingHold() {
        val hold = hold()
        assertEquals(HoldToAct.Result.Consume, hold.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0), ::action))
        hold.reset()
        scheduler.fire()
        assertEquals(emptyList<Int>(), holds.map { it.first })
    }
}
