package com.andrewevmiller.tvlauncher

import com.andrewevmiller.tvlauncher.home.data.FieldLayout
import com.andrewevmiller.tvlauncher.home.data.InMemoryLayoutStore
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.sound.MotifOverride
import com.andrewevmiller.tvlauncher.sound.Waveform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sanity checks for the in-memory fakes that previews and other agents' tests rely on. */
class ContractsTest {
    @Test
    fun settingsDefaultsMatchThePlan() {
        val s = LauncherSettings()
        assertEquals(true, s.darkMode)
        assertEquals(Waveform.SAW, s.waveform)
        assertEquals(Screensaver.OXYFIELD, s.screensaver)
        assertEquals(1f, s.dotGridAlpha, 0f)
    }

    @Test
    fun fakeSettingsApplyUpdates() = runBlocking {
        val repo = FakeSettingsRepository()
        repo.update { it.copy(waveform = Waveform.SUPERSAW, darkMode = false) }
        val s = repo.settings.first()
        assertEquals(Waveform.SUPERSAW, s.waveform)
        assertEquals(false, s.darkMode)
    }

    @Test
    fun motifOverridesRerollMuteAndReset() = runBlocking {
        val o = FakeMotifOverrides()
        o.reroll("com.plexapp.android")
        o.reroll("com.plexapp.android")
        o.setMuted("com.netflix.ninja", true)
        assertEquals(
            mapOf(
                "com.plexapp.android" to MotifOverride(rerollCount = 2),
                "com.netflix.ninja" to MotifOverride(muted = true),
            ),
            o.overrides.first(),
        )
        o.reset("com.plexapp.android")
        o.setMuted("com.netflix.ninja", false)
        assertEquals(emptyMap<String, MotifOverride>(), o.overrides.first())
    }

    @Test
    fun layoutStoreUpdates() = runBlocking {
        val store = InMemoryLayoutStore(FieldLayout(pinned = listOf("a/.A", "b/.B")))
        store.update { it.copy(pinned = it.pinned.reversed(), hidden = it.hidden + "c/.C") }
        assertEquals(FieldLayout(pinned = listOf("b/.B", "a/.A"), hidden = setOf("c/.C")), store.layout.first())
    }
}
