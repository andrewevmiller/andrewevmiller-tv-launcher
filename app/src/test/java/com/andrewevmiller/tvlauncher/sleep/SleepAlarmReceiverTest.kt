// purecheck:skip (Robolectric: BroadcastReceiver, Application)
package com.andrewevmiller.tvlauncher.sleep

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.andrewevmiller.tvlauncher.FieldApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepAlarmReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val container = (context as FieldApplication).container

    private fun fire() = Intent(context, SleepAlarmReceiver::class.java).setAction(SleepAlarmScheduler.ACTION_FIRE)

    private fun logged(fragment: String, timeoutMillis: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (ShadowLog.getLogsForTag(SleepConfig.TAG).any { it.msg?.contains(fragment) == true }) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun theContainerHandsOutTheSystemTimer() {
        assertNotNull(container.sleepTimer as? SystemSleepTimer)
        assertEquals(SleepTimerState.Idle, container.sleepTimer.state.value)
    }

    @Test
    fun anIntentThatIsNotOurAlarmIsIgnored() {
        SleepAlarmReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(100)
        assertTrue(ShadowLog.getLogsForTag(SleepConfig.TAG).none { it.msg?.contains("alarm received") == true })
    }

    @Test
    fun aStrayAlarmWithNothingDueDoesNothingAndLeavesTheTimerAlone() {
        container.sleepTimer.start(30)
        val before = container.sleepTimer.state.value

        SleepAlarmReceiver().onReceive(context, fire())

        assertTrue("the worker ran and found nothing due", logged("no due timer"))
        assertEquals(before, container.sleepTimer.state.value)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }
}
