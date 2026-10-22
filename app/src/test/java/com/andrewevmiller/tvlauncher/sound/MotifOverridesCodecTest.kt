package com.andrewevmiller.tvlauncher.sound

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotifOverridesCodecTest {

    @Test
    fun roundTripsPackagesWithDotsAndUnderscores() {
        val map = mapOf(
            "com.google.android.youtube.tv" to MotifOverride(rerollCount = 2),
            "com.my_app.v2.tv_box" to MotifOverride(muted = true),
            "org.example.a_b_c" to MotifOverride(rerollCount = 41, muted = true),
            "x" to MotifOverride(rerollCount = 1),
        )
        val text = MotifOverridesCodec.encode(map)
        assertEquals(map, MotifOverridesCodec.decode(text))
        assertEquals(text, MotifOverridesCodec.encode(MotifOverridesCodec.decode(text)))
    }

    @Test
    fun encodingIsStableSortedAndMinimal() {
        val a = linkedMapOf("b.pkg" to MotifOverride(rerollCount = 1), "a.pkg" to MotifOverride(muted = true))
        val b = linkedMapOf("a.pkg" to MotifOverride(muted = true), "b.pkg" to MotifOverride(rerollCount = 1))
        assertEquals("a.pkg|0|1\nb.pkg|1|0", MotifOverridesCodec.encode(a))
        assertEquals(MotifOverridesCodec.encode(a), MotifOverridesCodec.encode(b))
        // defaults are never written
        assertEquals("", MotifOverridesCodec.encode(emptyMap()))
        assertEquals("only|3|0", MotifOverridesCodec.encode(mapOf("only" to MotifOverride(3), "plain" to MotifOverride())))
    }

    @Test
    fun largeCountsSurviveAndHugeOnesSaturate() {
        val big = mapOf("com.big" to MotifOverride(rerollCount = Int.MAX_VALUE))
        assertEquals(big, MotifOverridesCodec.decode(MotifOverridesCodec.encode(big)))
        assertEquals(1_000_000, MotifOverridesCodec.decode("p|1000000|0")["p"]!!.rerollCount)
        assertEquals(Int.MAX_VALUE, MotifOverridesCodec.decode("p|99999999999999|0")["p"]!!.rerollCount)
        // a re-roll at the ceiling stays there instead of wrapping negative
        assertEquals(Int.MAX_VALUE, MotifOverridesCodec.reroll(big, "com.big")["com.big"]!!.rerollCount)
    }

    @Test
    fun decodeIsForgivingAboutGarbage() {
        assertEquals(emptyMap<String, MotifOverride>(), MotifOverridesCodec.decode(null))
        assertEquals(emptyMap<String, MotifOverride>(), MotifOverridesCodec.decode(""))
        assertEquals(emptyMap<String, MotifOverride>(), MotifOverridesCodec.decode("  \n\n \t "))
        assertEquals(emptyMap<String, MotifOverride>(), MotifOverridesCodec.decode("\u0000\u0001garbage\nno separators\n|||\n||"))

        val text = listOf(
            "good.one|2|0",
            "",
            "not enough|fields",
            "too|many|fields|here",
            "|5|1", // empty package
            "  spaced.pkg  | 3 | 1 ",
            "bad.count|abc|1", // unreadable count reads as 0, the mute survives
            "neg.count|-4|0", // negative reads as 0, so this one is a default and is dropped
            "word.bool|1|TRUE",
            "also.garbage|2|maybe",
            "dup.pkg|1|0",
            "dup.pkg|9|1",
            "defaults.only|0|0",
        ).joinToString("\n")
        val decoded = MotifOverridesCodec.decode(text)
        assertEquals(
            mapOf(
                "good.one" to MotifOverride(2, false),
                "spaced.pkg" to MotifOverride(3, true),
                "bad.count" to MotifOverride(0, true),
                "word.bool" to MotifOverride(1, true),
                "also.garbage" to MotifOverride(2, false),
                "dup.pkg" to MotifOverride(9, true), // the last line wins
            ),
            decoded,
        )
    }

    @Test
    fun windowsLineEndingsAndATrailingNewlineDecode() {
        assertEquals(
            mapOf("a.b" to MotifOverride(1), "c.d" to MotifOverride(muted = true)),
            MotifOverridesCodec.decode("a.b|1|0\r\nc.d|0|1\r\n"),
        )
    }

    @Test
    fun unsafePackageNamesAreDroppedRatherThanBreakingTheFormat() {
        val map = mapOf(
            "ok.pkg" to MotifOverride(1),
            "bad|pkg" to MotifOverride(1),
            "bad\npkg" to MotifOverride(1),
            "  " to MotifOverride(1),
        )
        assertEquals(mapOf("ok.pkg" to MotifOverride(1)), MotifOverridesCodec.normalize(map))
        assertEquals("ok.pkg|1|0", MotifOverridesCodec.encode(map))
        assertEquals(emptyMap<String, MotifOverride>(), MotifOverridesCodec.reroll(emptyMap(), "evil|pkg"))
    }

    @Test
    fun editRulesMatchTheFakeImplementation() = runBlocking {
        // The DataStore class applies these same rules; the in-memory fake is the reference behaviour.
        val fake = FakeMotifOverrides()
        var real: Map<String, MotifOverride> = emptyMap()
        suspend fun check() = assertEquals(fake.overrides.first(), real)

        fake.reroll("p"); real = MotifOverridesCodec.reroll(real, "p"); check()
        fake.reroll("p"); real = MotifOverridesCodec.reroll(real, "p"); check()
        assertEquals(2, real["p"]!!.rerollCount)
        fake.setMuted("p", true); real = MotifOverridesCodec.setMuted(real, "p", true); check()
        fake.setMuted("q", true); real = MotifOverridesCodec.setMuted(real, "q", true); check()
        fake.setMuted("q", false); real = MotifOverridesCodec.setMuted(real, "q", false); check() // back to default: removed
        assertTrue("q" !in real)
        fake.reset("p"); real = MotifOverridesCodec.reset(real, "p"); check()
        assertTrue(real.isEmpty())
        // resetting an app that has no override is a no-op
        fake.reset("nobody"); real = MotifOverridesCodec.reset(real, "nobody"); check()
    }

    @Test
    fun aWholeSessionSurvivesStorage() {
        var stored: String? = null
        fun edit(f: (Map<String, MotifOverride>) -> Map<String, MotifOverride>) {
            val next = f(MotifOverridesCodec.decode(stored))
            stored = if (next.isEmpty()) null else MotifOverridesCodec.encode(next)
        }
        edit { MotifOverridesCodec.reroll(it, "com.plexapp.android") }
        edit { MotifOverridesCodec.reroll(it, "com.plexapp.android") }
        edit { MotifOverridesCodec.setMuted(it, "com.netflix.ninja", true) }
        assertEquals(
            mapOf("com.plexapp.android" to MotifOverride(2), "com.netflix.ninja" to MotifOverride(muted = true)),
            MotifOverridesCodec.decode(stored),
        )
        edit { MotifOverridesCodec.reset(it, "com.plexapp.android") }
        edit { MotifOverridesCodec.reset(it, "com.netflix.ninja") }
        assertEquals(null, stored) // the key is removed when nothing is overridden
    }
}
