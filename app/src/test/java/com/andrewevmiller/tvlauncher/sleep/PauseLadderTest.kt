package com.andrewevmiller.tvlauncher.sleep

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseLadderTest {
    private fun rung(name: String) = PauseRung(name, "do $name")
    private fun names(list: List<PauseRung>) = list.map { it.name }

    /** A pretend player: [pausingRungs] stop it when performed, [throwingRungs] throw. */
    private class Device(
        playing: Boolean,
        private val pausingRungs: Set<String> = emptySet(),
        private val throwingRungs: Set<String> = emptySet(),
    ) {
        var isPlaying = playing
        val performed = ArrayList<String>()
        val settles = ArrayList<Long>()

        fun perform(rung: PauseRung) {
            performed.add(rung.name)
            if (rung.name in throwingRungs) throw IllegalStateException("boom ${rung.name}")
            if (rung.name in pausingRungs) isPlaying = false
        }

        fun settle(millis: Long) {
            settles.add(millis)
        }
    }

    private fun ladder(
        device: Device,
        order: List<PauseRung>,
        logs: MutableList<String> = ArrayList(),
        settleMillis: Long = 1_500L,
    ): PauseReport = runBlocking {
        runPauseLadder(
            order = order,
            isPlaying = { device.isPlaying },
            perform = { device.perform(it) },
            settle = { device.settle(it) },
            settleMillis = settleMillis,
            log = { logs.add(it) },
        )
    }

    private val media = rung("media")
    private val retry = rung("retry")
    private val focus = rung("focus")
    private val three = listOf(media, retry, focus)

    // --- ordering ---

    @Test
    fun defaultOrderIsMediaKeyThenRetryThenAudioFocus() {
        assertEquals(
            listOf(PauseRungs.MEDIA_KEY, PauseRungs.MEDIA_KEY_RETRY, PauseRungs.AUDIO_FOCUS),
            names(PauseRungs.DEFAULT_ORDER),
        )
    }

    @Test
    fun orderedWithANullBlankOrUnknownNameChangesNothing() {
        val default = names(PauseRungs.DEFAULT_ORDER)
        assertEquals(default, names(PauseRungs.ordered(null)))
        assertEquals(default, names(PauseRungs.ordered("")))
        assertEquals(default, names(PauseRungs.ordered("   ")))
        assertEquals(default, names(PauseRungs.ordered("telepathy")))
    }

    @Test
    fun orderedMovesTheProvenRungToTheFrontAndKeepsTheRest() {
        assertEquals(
            listOf(PauseRungs.AUDIO_FOCUS, PauseRungs.MEDIA_KEY, PauseRungs.MEDIA_KEY_RETRY),
            names(PauseRungs.ordered(PauseRungs.AUDIO_FOCUS)),
        )
        assertEquals(
            listOf(PauseRungs.MEDIA_KEY_RETRY, PauseRungs.MEDIA_KEY, PauseRungs.AUDIO_FOCUS),
            names(PauseRungs.ordered(PauseRungs.MEDIA_KEY_RETRY)),
        )
        assertEquals(names(PauseRungs.DEFAULT_ORDER), names(PauseRungs.ordered(PauseRungs.MEDIA_KEY)))
    }

    @Test
    fun orderedIsTrimmedAndCaseInsensitive() {
        assertEquals(PauseRungs.MEDIA_KEY_RETRY, PauseRungs.ordered("  MEDIA-KEY-RETRY ").first().name)
    }

    @Test
    fun orderedNeverDropsOrDuplicatesARung() {
        assertEquals(listOf("c", "a", "b"), names(PauseRungs.ordered("c", listOf(rung("a"), rung("b"), rung("c")))))
        assertEquals(listOf("b", "a", "c"), names(PauseRungs.ordered("b", listOf(rung("a"), rung("b"), rung("c")))))
        assertEquals(3, PauseRungs.ordered(PauseRungs.AUDIO_FOCUS).toSet().size)
    }

    @Test
    fun orderedFeedsTheLadder() {
        val device = Device(playing = true, pausingRungs = setOf(PauseRungs.AUDIO_FOCUS))
        val report = ladder(device, PauseRungs.ordered(PauseRungs.AUDIO_FOCUS))
        assertEquals(PauseReport(PauseResult.PAUSED, listOf(PauseRungs.AUDIO_FOCUS)), report)
    }

    // --- ladder ---

    @Test
    fun nothingPlayingAtTheStartTriesNoRung() {
        val device = Device(playing = false, pausingRungs = setOf("media"))
        val logs = ArrayList<String>()
        val report = ladder(device, three, logs)
        assertEquals(PauseReport(PauseResult.NOT_PLAYING, emptyList()), report)
        assertTrue(device.performed.isEmpty())
        assertTrue(device.settles.isEmpty())
        assertEquals(listOf("pause: nothing is playing, no rungs tried"), logs)
    }

    @Test
    fun stopsAtTheRungThatPausesAndNeverCallsLaterRungs() {
        val device = Device(playing = true, pausingRungs = setOf("retry"))
        val report = ladder(device, three)
        assertEquals(PauseReport(PauseResult.PAUSED, listOf("media", "retry")), report)
        assertEquals(listOf("media", "retry"), device.performed)
        assertEquals(listOf(1_500L, 1_500L), device.settles)
    }

    @Test
    fun aFirstRungThatPausesEndsTheLadderAtOneAttempt() {
        val device = Device(playing = true, pausingRungs = setOf("media"))
        assertEquals(PauseReport(PauseResult.PAUSED, listOf("media")), ladder(device, three))
        assertEquals(listOf("media"), device.performed)
        assertEquals(listOf(1_500L), device.settles)
    }

    @Test
    fun stillPlayingAfterEveryRungIsStillPlaying() {
        val device = Device(playing = true)
        val report = ladder(device, three)
        assertEquals(PauseReport(PauseResult.STILL_PLAYING, listOf("media", "retry", "focus")), report)
        assertEquals(listOf("media", "retry", "focus"), device.performed)
        assertEquals(listOf(1_500L, 1_500L, 1_500L), device.settles)
    }

    @Test
    fun anEmptyOrderWhilePlayingIsStillPlayingWithNoAttempts() {
        val device = Device(playing = true)
        assertEquals(PauseReport(PauseResult.STILL_PLAYING, emptyList()), ladder(device, emptyList()))
        assertTrue(device.performed.isEmpty())
    }

    @Test
    fun aThrowingRungIsLoggedAndTheLadderCarriesOn() {
        val device = Device(playing = true, pausingRungs = setOf("retry"), throwingRungs = setOf("media"))
        val logs = ArrayList<String>()
        val report = ladder(device, three, logs)
        assertEquals(PauseReport(PauseResult.PAUSED, listOf("media", "retry")), report)
        assertEquals(listOf("media", "retry"), device.performed)
        assertTrue(logs.toString(), logs.any { it == "pause rung 'media': FAILED: IllegalStateException: boom media" })
    }

    @Test
    fun aThrowingRungStillWaitsAndChecksBeforeTheNextOne() {
        val device = Device(playing = true, pausingRungs = setOf("retry"), throwingRungs = setOf("media"))
        ladder(device, three)
        assertEquals(listOf(1_500L, 1_500L), device.settles)
    }

    @Test
    fun aThrowingLastRungWhileStillPlayingIsStillPlaying() {
        val device = Device(playing = true, throwingRungs = setOf("focus"))
        val logs = ArrayList<String>()
        val report = ladder(device, three, logs)
        assertEquals(PauseReport(PauseResult.STILL_PLAYING, listOf("media", "retry", "focus")), report)
        assertTrue(logs.any { it.startsWith("pause rung 'focus': FAILED: IllegalStateException") })
    }

    @Test
    fun settleMillisIsPassedToEveryWait() {
        val device = Device(playing = true)
        ladder(device, three, settleMillis = 250L)
        assertEquals(listOf(250L, 250L, 250L), device.settles)
    }

    @Test
    fun everyStepIsLogged() {
        val device = Device(playing = true, pausingRungs = setOf("retry"))
        val logs = ArrayList<String>()
        ladder(device, three, logs)
        assertEquals(
            listOf(
                "pause rung 'media': do media",
                "pause rung 'media': sent",
                "pause rung 'media': still playing after 1500 ms",
                "pause rung 'retry': do retry",
                "pause rung 'retry': sent",
                "pause rung 'retry': not playing after 1500 ms",
                "pause: PAUSED on rung 2 of 3",
            ),
            logs,
        )
    }

    @Test
    fun exhaustedLadderIsLogged() {
        val device = Device(playing = true)
        val logs = ArrayList<String>()
        ladder(device, listOf(media), logs)
        assertEquals("pause: still playing after all 1 rung(s)", logs.last())
    }
}
