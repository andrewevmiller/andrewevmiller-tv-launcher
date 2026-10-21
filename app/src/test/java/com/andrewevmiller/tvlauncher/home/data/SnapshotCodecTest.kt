package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SnapshotCodecTest {
    private fun entry(pkg: String, label: String, category: AppCategory = AppCategory.OTHER, art: ArtKind = ArtKind.ICON) =
        SnapshotEntry(pkg, "$pkg.Main", label, category, pinned = pkg.length % 2 == 0, art = art, lastUpdateTime = 1_700_000_000_123L)

    @Test fun roundTripKeepsEverything() {
        val snapshot = Snapshot(
            "sig", 42L,
            listOf(
                entry("com.example.video", "Video", AppCategory.VIDEO, ArtKind.BANNER),
                entry("com.example.music", "Music", AppCategory.MUSIC, ArtKind.ICON),
                entry("com.example.none", "No art", AppCategory.TOOLS, ArtKind.NONE),
            ),
        )
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test fun labelsWithSeparatorsAndUnicodeSurvive() {
        val labels = listOf("A;B|C", "tab\there", "line\nbreak", "日本語", "🎬 Movies", "", "back\\slash")
        val snapshot = Snapshot("s", 1L, labels.mapIndexed { i, l -> entry("com.example.l$i", l) })
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(labels, decoded?.entries?.map { it.label })
    }

    @Test fun emptySnapshotRoundTrips() {
        val snapshot = Snapshot("s", 1L, emptyList())
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test fun emptyOrGarbageBytesDecodeToNull() {
        assertNull(SnapshotCodec.decode(ByteArray(0)))
        assertNull(SnapshotCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(SnapshotCodec.decode("not a snapshot at all".toByteArray()))
    }

    @Test fun everyTruncationDecodesToNull() {
        val bytes = SnapshotCodec.encode(Snapshot("sig", 7L, listOf(entry("com.example.a", "A"), entry("com.example.b", "B"))))
        for (len in 0 until bytes.size) {
            assertNull("prefix of $len bytes", SnapshotCodec.decode(bytes.copyOf(len)))
        }
        assertNotNull(SnapshotCodec.decode(bytes))
    }

    @Test fun otherVersionDecodesToNull() {
        val bytes = SnapshotCodec.encode(Snapshot("sig", 7L, listOf(entry("com.example.a", "A"))))
        bytes[3] = (SnapshotCodec.VERSION + 1).toByte() // low byte of the big-endian version int
        assertNull(SnapshotCodec.decode(bytes))
    }

    @Test fun absurdEntryCountIsRejectedWithoutAllocating() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(SnapshotCodec.VERSION); it.writeUTF("sig"); it.writeLong(1L); it.writeInt(Int.MAX_VALUE)
        }
        assertNull(SnapshotCodec.decode(out.toByteArray()))
        val negative = ByteArrayOutputStream()
        DataOutputStream(negative).use {
            it.writeInt(SnapshotCodec.VERSION); it.writeUTF("sig"); it.writeLong(1L); it.writeInt(-1)
        }
        assertNull(SnapshotCodec.decode(negative.toByteArray()))
    }

    @Test fun unknownCategoryAndArtNamesFallBack() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(SnapshotCodec.VERSION); it.writeUTF("sig"); it.writeLong(1L); it.writeInt(1)
            it.writeUTF("com.example.a"); it.writeUTF("com.example.a.Main"); it.writeUTF("A")
            it.writeUTF("FUTURE_CATEGORY"); it.writeBoolean(true); it.writeUTF("FUTURE_ART"); it.writeLong(5L)
        }
        val e = SnapshotCodec.decode(out.toByteArray())!!.entries.single()
        assertEquals(AppCategory.OTHER, e.category)
        assertEquals(ArtKind.NONE, e.art)
    }

    @Test fun artFileNamesAreSafeAndKeyedByBuild() {
        assertEquals("com.example.a-100.png", ArtFiles.name("com.example.a", 100L))
        assertEquals("com.example.a-101.png", ArtFiles.name("com.example.a", 101L))
        assertEquals("a_b_c-1.png", ArtFiles.name("a/b\\c", 1L))
    }

    @Test fun staleFilesAreOldBuildsGoneAppsAndTempFiles() {
        val keep = setOf("com.example.a-101.png", "com.example.b-5.png")
        val existing = listOf("com.example.a-100.png", "com.example.a-101.png", "com.example.b-5.png", "gone-1.png", "com.example.a-101.png.tmp")
        assertEquals(listOf("com.example.a-100.png", "gone-1.png", "com.example.a-101.png.tmp"), ArtFiles.stale(existing, keep))
    }
}
