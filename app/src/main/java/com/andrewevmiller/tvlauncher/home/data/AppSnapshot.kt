package com.andrewevmiller.tvlauncher.home.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** What kind of picture an app has: a 16:9 banner, a square icon, or none. */
enum class ArtKind { NONE, ICON, BANNER }

/** One app as the launcher last saw it, minus the pixels (those live in files, see [ArtFiles]). */
data class SnapshotEntry(
    val packageName: String,
    val className: String,
    val label: String,
    val category: AppCategory,
    val pinned: Boolean,
    val art: ArtKind,
    /** The package's `lastUpdateTime` when this entry was built; it keys the artwork file too. */
    val lastUpdateTime: Long,
)

data class Snapshot(val signature: String, val savedAt: Long, val entries: List<SnapshotEntry>)

/**
 * Binary form of [Snapshot] (DataOutputStream), so labels with any characters round-trip without
 * escaping. Anything unreadable decodes to null and is treated as "no snapshot".
 */
object SnapshotCodec {
    const val VERSION = 1
    private const val MAX_ENTRIES = 10_000

    fun encode(snapshot: Snapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(snapshot.signature)
            out.writeLong(snapshot.savedAt)
            out.writeInt(snapshot.entries.size)
            for (e in snapshot.entries) {
                out.writeUTF(e.packageName)
                out.writeUTF(e.className)
                out.writeUTF(e.label)
                out.writeUTF(e.category.name)
                out.writeBoolean(e.pinned)
                out.writeUTF(e.art.name)
                out.writeLong(e.lastUpdateTime)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): Snapshot? = try {
        DataInputStream(data.inputStream()).use { input ->
            if (input.readInt() != VERSION) return null
            val signature = input.readUTF()
            val savedAt = input.readLong()
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) return null
            val entries = ArrayList<SnapshotEntry>(count)
            repeat(count) {
                // Read each field exactly once, in the order encode wrote them.
                val packageName = input.readUTF()
                val className = input.readUTF()
                val label = input.readUTF()
                val categoryName = input.readUTF()
                val pinned = input.readBoolean()
                val artName = input.readUTF()
                val lastUpdateTime = input.readLong()
                entries += SnapshotEntry(
                    packageName = packageName,
                    className = className,
                    label = label,
                    category = AppCategory.entries.firstOrNull { it.name == categoryName } ?: AppCategory.OTHER,
                    pinned = pinned,
                    art = ArtKind.entries.firstOrNull { it.name == artName } ?: ArtKind.NONE,
                    lastUpdateTime = lastUpdateTime,
                )
            }
            Snapshot(signature, savedAt, entries)
        }
    } catch (e: IOException) {
        null
    }
}

/** Naming and cleanup rules for the cached artwork files. Keyed by build, so an update gets a new file. */
object ArtFiles {
    fun name(packageName: String, lastUpdateTime: Long): String =
        packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + lastUpdateTime + ".png"

    /** The files in [existing] that are not in [keep] (older builds, uninstalled apps, half-written temp files). */
    fun stale(existing: Collection<String>, keep: Set<String>): List<String> = existing.filter { it !in keep }
}
