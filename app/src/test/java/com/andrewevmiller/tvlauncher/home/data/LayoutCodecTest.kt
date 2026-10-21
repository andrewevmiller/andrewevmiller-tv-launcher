package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutCodecTest {

    /** Components as ComponentName.flattenToString() produces them, plus unicode. */
    private val special = listOf(
        "com.x/.Foo\$Bar",
        "com.y/com.y.ui.Main",
        "com.café.player/.Streamü",
        "漢字.app/.Main",
        "com.x/.Foo-Bar_2",
    )

    // ---- Round trips -------------------------------------------------------------------------

    @Test
    fun pinnedRoundTripEmpty() {
        assertEquals("", LayoutCodec.encodePinned(emptyList()))
        assertEquals(emptyList<String>(), LayoutCodec.decodePinned(LayoutCodec.encodePinned(emptyList())))
    }

    @Test
    fun pinnedRoundTripOne() {
        val pinned = listOf("com.plexapp.android/.activity.SplashActivity")
        assertEquals(pinned, LayoutCodec.decodePinned(LayoutCodec.encodePinned(pinned)))
    }

    @Test
    fun pinnedRoundTripManyKeepsWireOrder() {
        val pinned = listOf("com.z/.Last", "com.a/.First", "com.m/.Middle", "com.b/.Second")
        assertEquals(
            "com.z/.Last\ncom.a/.First\ncom.m/.Middle\ncom.b/.Second",
            LayoutCodec.encodePinned(pinned),
        )
        assertEquals(pinned, LayoutCodec.decodePinned(LayoutCodec.encodePinned(pinned)))
    }

    @Test
    fun hiddenRoundTripEmptyOneMany() {
        assertEquals("", LayoutCodec.encodeHidden(emptySet()))
        assertEquals(emptySet<String>(), LayoutCodec.decodeHidden(LayoutCodec.encodeHidden(emptySet())))

        val one = setOf("com.a/.A")
        assertEquals(one, LayoutCodec.decodeHidden(LayoutCodec.encodeHidden(one)))

        val many = setOf("com.c/.C", "com.a/.A", "com.b/.B")
        assertEquals(many, LayoutCodec.decodeHidden(LayoutCodec.encodeHidden(many)))
    }

    @Test
    fun specialCharactersRoundTripInBothLists() {
        assertEquals(special, LayoutCodec.decodePinned(LayoutCodec.encodePinned(special)))
        assertEquals(special.toSet(), LayoutCodec.decodeHidden(LayoutCodec.encodeHidden(special.toSet())))
    }

    @Test
    fun hiddenEncodingIsSortedSoOutputIsDeterministic() {
        val forward = linkedSetOf("com.a/.A", "com.b/.B", "com.c/.C")
        val reverse = linkedSetOf("com.c/.C", "com.b/.B", "com.a/.A")
        val expected = "com.a/.A\ncom.b/.B\ncom.c/.C"
        assertEquals(expected, LayoutCodec.encodeHidden(forward))
        assertEquals(expected, LayoutCodec.encodeHidden(reverse))
    }

    // ---- Forgiving decode ----------------------------------------------------------------------

    @Test
    fun nullAndBlankDecodeToEmpty() {
        for (raw in listOf(null, "", "   ", "\n\n", " \t \n\r\n ")) {
            assertEquals(emptyList<String>(), LayoutCodec.decodePinned(raw))
            assertEquals(emptySet<String>(), LayoutCodec.decodeHidden(raw))
        }
    }

    @Test
    fun blankLinesAreDropped() {
        assertEquals(
            listOf("com.a/.A", "com.b/.B"),
            LayoutCodec.decodePinned("\ncom.a/.A\n\n\ncom.b/.B\n\n"),
        )
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            listOf("com.a/.A", "com.b/.B"),
            LayoutCodec.decodePinned("  com.a/.A \t\n\tcom.b/.B  "),
        )
        assertEquals(
            setOf("com.a/.A", "com.b/.B"),
            LayoutCodec.decodeHidden("com.a/.A\r\ncom.b/.B\r\n"),
        )
    }

    @Test
    fun duplicatesAreRemovedKeepingFirstOccurrence() {
        assertEquals(
            listOf("com.b/.B", "com.a/.A"),
            LayoutCodec.decodePinned("com.b/.B\ncom.a/.A\ncom.b/.B\ncom.a/.A"),
        )
        assertEquals(listOf("com.a/.A"), LayoutCodec.decodePinned("com.a/.A\n  com.a/.A  "))
        assertEquals(setOf("x", "y"), LayoutCodec.decodeHidden("x\ny\nx"))
    }

    @Test
    fun garbageLinesAreKeptAsOpaqueEntries() {
        // The codec does not validate component syntax; AppsRepository drops entries that aren't installed.
        assertEquals(
            listOf("not a component", "@@@"),
            LayoutCodec.decodePinned("not a component\n@@@"),
        )
    }

    @Test
    fun encodeRejectsLineBreaksInEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutCodec.encodePinned(listOf("a/.A\nb/.B"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutCodec.encodeHidden(setOf("a/.A\r"))
        }
    }

    // ---- normalize -----------------------------------------------------------------------------

    @Test
    fun normalizeHiddenWinsOverPinned() {
        val out = LayoutCodec.normalize(
            FieldLayout(pinned = listOf("a/.A", "b/.B", "c/.C"), hidden = setOf("b/.B")),
        )
        assertEquals(FieldLayout(pinned = listOf("a/.A", "c/.C"), hidden = setOf("b/.B")), out)
    }

    @Test
    fun normalizeRemovesDuplicatePinnedKeepingFirst() {
        val out = LayoutCodec.normalize(
            FieldLayout(pinned = listOf("a/.A", "b/.B", "a/.A", "c/.C", "b/.B")),
        )
        assertEquals(FieldLayout(pinned = listOf("a/.A", "b/.B", "c/.C")), out)
    }

    @Test
    fun normalizePreservesPinnedOrderExactly() {
        val pinned = listOf("c/.C", "a/.A", "b/.B", "d/.D")
        assertEquals(pinned, LayoutCodec.normalize(FieldLayout(pinned = pinned)).pinned)
    }

    @Test
    fun normalizeTrimsAndDropsBlankEntries() {
        val out = LayoutCodec.normalize(
            FieldLayout(
                pinned = listOf(" a/.A ", "", "   ", "b/.B"),
                hidden = setOf("  c/.C\t", ""),
            ),
        )
        assertEquals(FieldLayout(pinned = listOf("a/.A", "b/.B"), hidden = setOf("c/.C")), out)
    }

    @Test
    fun normalizeKeepsSeededUnchanged() {
        assertEquals(true, LayoutCodec.normalize(FieldLayout(pinned = listOf("a/.A"), seeded = true)).seeded)
        assertEquals(false, LayoutCodec.normalize(FieldLayout(seeded = false)).seeded)

        val hiddenAndSeeded = LayoutCodec.normalize(
            FieldLayout(pinned = listOf("a/.A"), hidden = setOf("a/.A"), seeded = true),
        )
        assertEquals(FieldLayout(pinned = emptyList(), hidden = setOf("a/.A"), seeded = true), hiddenAndSeeded)
    }

    @Test
    fun normalizeLeavesACleanLayoutUnchangedAndIsIdempotent() {
        val clean = FieldLayout(pinned = listOf("z/.Z", "a/.A"), hidden = setOf("m/.M"), seeded = true)
        assertEquals(clean, LayoutCodec.normalize(clean))

        val messy = FieldLayout(pinned = listOf("a/.A", "a/.A", "b/.B"), hidden = setOf("b/.B", "  "))
        val once = LayoutCodec.normalize(messy)
        assertEquals(once, LayoutCodec.normalize(once))
    }

    @Test
    fun seededIsStoredSeparatelyAndSurvivesTheCodecRoundTrip() {
        // The store writes seeded under its own key. Rebuilding the layout from the two text
        // round trips plus that flag must give back the same layout.
        val layout = FieldLayout(pinned = listOf("a/.A", "b/.B"), hidden = setOf("c/.C"), seeded = true)
        val rebuilt = FieldLayout(
            pinned = LayoutCodec.decodePinned(LayoutCodec.encodePinned(layout.pinned)),
            hidden = LayoutCodec.decodeHidden(LayoutCodec.encodeHidden(layout.hidden)),
            seeded = layout.seeded,
        )
        assertEquals(layout, LayoutCodec.normalize(rebuilt))
        assertEquals(true, LayoutCodec.normalize(rebuilt).seeded)
    }
}
