package com.andrewevmiller.tvlauncher.marginnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginNoteRulesTest {

    private fun repeat(s: String, n: Int): String = s.repeat(n)

    @Test
    fun aNoteOfExactlyEightyCharactersIsKept() {
        val eighty = repeat("a", 80)
        assertEquals(eighty, MarginNoteRules.sanitize(eighty))
    }

    @Test
    fun aNoteOfEightyOneCharactersIsClampedToEighty() {
        val eightyOne = repeat("a", 80) + "b"
        assertEquals(repeat("a", 80), MarginNoteRules.sanitize(eightyOne))
    }

    @Test
    fun clampingLeavesNoTrailingSpace() {
        // The 80th character is a space once the text is cut, so it must be trimmed away again.
        val input = repeat("a", 79) + " " + "bcd"
        assertEquals(repeat("a", 79), MarginNoteRules.sanitize(input))
    }

    @Test
    fun newlinesAreStrippedNotReplaced() {
        assertEquals("finishS2", MarginNoteRules.sanitize("finish\nS2"))
        assertEquals("finishS2", MarginNoteRules.sanitize("finish\r\nS2"))
        assertEquals("ab", MarginNoteRules.sanitize("a\n\n\rb"))
    }

    @Test
    fun tabsAndOtherControlCharactersAreStripped() {
        assertEquals("ab", MarginNoteRules.sanitize("a\tb"))
        assertEquals("ab", MarginNoteRules.sanitize("a\u0000b"))
        assertEquals("ab", MarginNoteRules.sanitize("a\u001Bb"))
        assertEquals("ab", MarginNoteRules.sanitize("a\u007Fb"))
        assertEquals("ab", MarginNoteRules.sanitize("a\u0085b"))
    }

    @Test
    fun lineAndParagraphSeparatorsAreStripped() {
        assertEquals("ab", MarginNoteRules.sanitize("a b"))
        assertEquals("ab", MarginNoteRules.sanitize("a b"))
    }

    @Test
    fun onlySpacesBecomesEmpty() {
        assertEquals("", MarginNoteRules.sanitize("     "))
        assertEquals("", MarginNoteRules.sanitize(" \t \n "))
        assertEquals("", MarginNoteRules.sanitize(""))
    }

    @Test
    fun leadingAndTrailingSpacesAreTrimmed() {
        assertEquals("finish S2 of Severance", MarginNoteRules.sanitize("   finish S2 of Severance  "))
    }

    @Test
    fun nullBecomesEmpty() {
        assertEquals("", MarginNoteRules.sanitize(null))
    }

    @Test
    fun innerSpacesAreKept() {
        assertEquals("a  b", MarginNoteRules.sanitize("a  b"))
    }

    @Test
    fun unicodeIsKeptAndCountedByCharacter() {
        val accented = repeat("é", 80)
        assertEquals(accented, MarginNoteRules.sanitize(accented))
        assertEquals(repeat("é", 80), MarginNoteRules.sanitize(repeat("é", 81)))
    }

    @Test
    fun emojiAreNeverSplitInTheMiddleOfASurrogatePair() {
        val emoji = "😀" // one code point, two chars
        val cut = MarginNoteRules.sanitize(repeat(emoji, 81))
        assertEquals(repeat(emoji, 80), cut)
        assertEquals(80, cut.codePointCount(0, cut.length))
        assertEquals(160, cut.length)
    }

    @Test
    fun aCutThatWouldSplitAPairStopsBeforeIt() {
        // 79 ASCII characters then an emoji: the 80th code point is the whole emoji, so nothing is split.
        val emoji = "😀"
        val input = repeat("a", 79) + emoji + "b"
        assertEquals(repeat("a", 79) + emoji, MarginNoteRules.sanitize(input))
    }

    @Test
    fun loneSurrogatesAreDropped() {
        assertEquals("ab", MarginNoteRules.sanitize("a\uD83Db"))
        assertEquals("ab", MarginNoteRules.sanitize("a\uDE00b"))
    }

    @Test
    fun sanitizeIsIdempotent() {
        val inputs = listOf("  x\ny  ", repeat("z", 90), "😀 ok", "")
        for (raw in inputs) {
            val once = MarginNoteRules.sanitize(raw)
            assertEquals(once, MarginNoteRules.sanitize(once))
        }
    }

    @Test
    fun storedNoteWinsOverTheSecureSetting() {
        assertEquals("stored", MarginNoteRules.merge("stored", "from setting"))
    }

    @Test
    fun theSecureSettingIsTheFallbackWhenNothingIsStored() {
        assertEquals("from setting", MarginNoteRules.merge("", "from setting"))
        assertEquals("from setting", MarginNoteRules.merge(null, "from setting"))
        assertEquals("from setting", MarginNoteRules.merge("   ", "from setting"))
    }

    @Test
    fun noNoteWhenNeitherSourceHasText() {
        assertEquals("", MarginNoteRules.merge("", ""))
        assertEquals("", MarginNoteRules.merge(null, null))
        assertEquals("", MarginNoteRules.merge("  \n ", " \t "))
    }

    @Test
    fun theSecureSettingIsSanitisedToo() {
        assertEquals("long", MarginNoteRules.merge(null, "lo\nng"))
        assertEquals(repeat("a", 80), MarginNoteRules.merge(null, repeat("a", 90)))
    }

    @Test
    fun aStoredNoteIsSanitisedBeforeItWins() {
        assertEquals("stored", MarginNoteRules.merge(" stored\n", "from setting"))
    }

    @Test
    fun rowTextWithANote() {
        assertEquals("NOTE  finish S2 of Severance", MarginNoteRules.rowText("finish S2 of Severance"))
    }

    @Test
    fun rowTextWithoutANote() {
        assertEquals("NOTE  add a note", MarginNoteRules.rowText(""))
    }

    @Test
    fun aSanitisedNoteNeverExceedsTheLimit() {
        val sanitised = MarginNoteRules.sanitize(repeat("x y\t", 100))
        assertTrue(sanitised.codePointCount(0, sanitised.length) <= MarginNoteRules.MAX_LENGTH)
        assertFalse(sanitised.startsWith(" "))
        assertFalse(sanitised.endsWith(" "))
    }
}
