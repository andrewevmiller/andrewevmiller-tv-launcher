// purecheck:skip (Robolectric: PowerManager, AudioManager)
package com.andrewevmiller.tvlauncher.sleep

import android.content.Context
import android.os.PowerManager
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.NoOpFieldSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSleepEndActionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** A sound whose [isReady] turns true after [readyAfterPolls] calls (never, by default) and records windDown. */
    private class FakeSound(var readyAfterPolls: Int = Int.MAX_VALUE) : FieldSound by NoOpFieldSound {
        var polls = 0
        val events = ArrayList<String>()

        override fun isReady(): Boolean {
            events.add("isReady")
            return polls++ >= readyAfterPolls
        }

        override fun windDown() {
            events.add("windDown")
        }
    }

    private fun actions(sound: FieldSound, slept: MutableList<Long> = ArrayList()) =
        AndroidSleepEndActions(context, { sound }, { NoOpScreensaverController }, sleeper = { slept.add(it) })

    // ---- the closing sound waits for the engine ---------------------------------------------------------

    @Test
    fun aReadyEnginePlaysAtOnceWithoutWaiting() {
        val sound = FakeSound(readyAfterPolls = 0)
        val slept = ArrayList<Long>()
        actions(sound, slept).playClosingSound()
        assertEquals(listOf("isReady", "windDown"), sound.events)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun anEngineThatBecomesReadyAfterAFewPollsIsWaitedFor() {
        val sound = FakeSound(readyAfterPolls = 4)
        val slept = ArrayList<Long>()
        actions(sound, slept).playClosingSound()

        assertEquals(List(4) { 50L }, slept)
        assertEquals(List(5) { "isReady" } + "windDown", sound.events)
    }

    @Test
    fun anEngineThatNeverGetsReadyIsWaitedForThreeSecondsThenPlayedAnyway() {
        val sound = FakeSound()
        val slept = ArrayList<Long>()
        actions(sound, slept).playClosingSound()

        assertEquals("polled every 50 ms", setOf(50L), slept.toSet())
        assertEquals("for 3 s", 3_000L, slept.sum())
        assertEquals("windDown is called once, after the last poll", "windDown", sound.events.last())
        assertEquals(1, sound.events.count { it == "windDown" })
    }

    @Test
    fun theWaitConstantsAreThreeSecondsEveryFiftyMillis() {
        assertEquals(3_000L, SleepConfig.SOUND_READY_WAIT_MILLIS)
        assertEquals(50L, SleepConfig.SOUND_READY_POLL_MILLIS)
    }

    @Test
    fun theDefaultFieldSoundIsReady() {
        assertTrue(NoOpFieldSound.isReady())
    }

    // ---- the screen -------------------------------------------------------------------------------------

    @Test
    fun isScreenOnFollowsThePowerManager() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val actions = actions(NoOpFieldSound)

        power.turnScreenOn(true)
        assertTrue(actions.isScreenOn())

        power.turnScreenOn(false)
        assertFalse(actions.isScreenOn())
    }
}
