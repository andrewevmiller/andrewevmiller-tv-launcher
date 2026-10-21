package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the list of launchable apps current without redoing the expensive part on every return Home.
 *
 * Each [refresh] runs on one background-priority thread:
 * 1. First time in this process: show the saved snapshot (labels, plus artwork from files) at once.
 * 2. One cheap call reads every package's update time and enabled state. If their digest matches the
 *    last scan, stop: nothing about the tiles can have changed.
 * 3. Otherwise rescan, but rebuild only the apps whose package changed; unchanged apps keep the same
 *    [LauncherApp] instance so Compose skips their tiles.
 * 4. Fill in any artwork still missing (not yet decoded, or purged from the cache directory).
 *
 * Artwork is rasterized at the size its tile needs ([ArtLimits]) and kept as hardware bitmaps, so the
 * pixels live in GPU memory and are uploaded here on the loader thread, not on the first frame that draws
 * them. The PNG cache is always written from the software bitmap first, because a hardware bitmap can't be
 * compressed.
 *
 * A newer [refresh] makes an older one in flight stop early, so quick back-to-back resumes do one scan.
 * [publish] is always called on the main thread.
 */
class AppLoader(context: Context, private val publish: (List<LauncherApp>) -> Unit) {
    private class Held(val entry: SnapshotEntry, val app: LauncherApp)

    /** A freshly rendered software bitmap waiting to be written to the cache; [recycle] once it is not on screen. */
    private class Pending(val entry: SnapshotEntry, val software: Bitmap, val recycle: Boolean)

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var closed = false
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "app-loader")
    }
    private val snapshotFile = File(appContext.filesDir, "apps.snapshot")
    private val artDir = File(appContext.cacheDir, "art")

    // Touched only on the loader thread.
    private var held: Map<String, Held> = emptyMap()
    private var last: ScanRecord? = null
    private var restored = false
    private var hardwareCount = 0

    /** Call from onResume. Cheap when nothing changed. */
    fun refresh() {
        if (closed) return
        val g = generation.incrementAndGet()
        try {
            executor.execute {
                if (g != generation.get()) return@execute
                try {
                    load(g)
                } catch (e: Exception) {
                    Log.w(TAG, "load failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Closed while this was being submitted.
        }
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun load(g: Int) {
        Trace.beginSection("AppLoader.load")
        try {
            val started = SystemClock.elapsedRealtime()
            hardwareCount = countHardware()
            if (!restored) {
                restored = true
                restore()
            }
            if (g != generation.get()) return
            val stamps = readStamps()
            val signature = AppStamps.signature(stamps.values, Locale.getDefault().toLanguageTag())
            val now = System.currentTimeMillis()
            if (AppStamps.needsRescan(last, signature, now)) {
                scan(g, stamps, signature, now)
            } else {
                Log.d(TAG, "scan skipped: ${held.size} apps unchanged")
            }
            if (g != generation.get()) return
            fillMissingArt(g)
            Log.d(TAG, "load finished in ${SystemClock.elapsedRealtime() - started} ms")
        } finally {
            Trace.endSection()
        }
    }

    // --- snapshot ---------------------------------------------------------------------------

    /** Shows the saved list straight away. Home's apps get their artwork now; the rest comes later. */
    private fun restore() {
        Trace.beginSection("AppLoader.restore")
        try {
            val started = SystemClock.elapsedRealtime()
            val snapshot = runCatching { SnapshotCodec.decode(snapshotFile.readBytes()) }.getOrNull() ?: return
            last = ScanRecord(snapshot.signature, snapshot.savedAt)
            val restoredHeld = LinkedHashMap<String, Held>()
            for (e in snapshot.entries) {
                restoredHeld[e.packageName] = Held(e, appFor(e, if (e.pinned) artFor(e) else null))
            }
            held = restoredHeld
            publishNow()
            Log.d(TAG, "restored ${snapshot.entries.size} apps from snapshot in ${SystemClock.elapsedRealtime() - started} ms")
            logArt()
        } finally {
            Trace.endSection()
        }
    }

    private fun persist(all: Collection<Held>, signature: String, now: Long, pending: List<Pending>) {
        Trace.beginSection("AppLoader.persist")
        try {
            val entries = all.map { it.entry }.sortedBy { it.label.lowercase() }
            runCatching {
                val temp = File(snapshotFile.path + ".tmp")
                temp.writeBytes(SnapshotCodec.encode(Snapshot(signature, now, entries)))
                if (!temp.renameTo(snapshotFile)) temp.delete()
            }.onFailure { Log.w(TAG, "snapshot write failed", it) }
            for (p in pending) {
                writeArt(p.entry, p.software)
                if (p.recycle) p.software.recycle()
            }
            val keep = entries.filter { it.art != ArtKind.NONE }.map { ArtFiles.name(it.packageName, it.lastUpdateTime) }.toSet()
            for (name in ArtFiles.stale(artDir.list()?.toList() ?: emptyList(), keep)) File(artDir, name).delete()
        } finally {
            Trace.endSection()
        }
    }

    // --- scanning ---------------------------------------------------------------------------

    /** One binder call for every package's update time and enabled state; far cheaper than a scan. */
    @Suppress("DEPRECATION")
    private fun readStamps(): Map<String, AppStamp> {
        Trace.beginSection("AppLoader.readStamps")
        try {
            return pm.getInstalledPackages(0).associate { info ->
                info.packageName to AppStamp(info.packageName, info.lastUpdateTime, info.applicationInfo?.enabled ?: true)
            }
        } finally {
            Trace.endSection()
        }
    }

    /** Leanback apps first (these fill Home), then regular launcher apps, one entry per package. */
    @Suppress("DEPRECATION")
    private fun scan(g: Int, stamps: Map<String, AppStamp>, signature: String, now: Long) {
        Trace.beginSection("AppLoader.scan")
        try {
            val started = SystemClock.elapsedRealtime()
            val self = appContext.packageName
            val seen = HashSet<String>()
            val next = LinkedHashMap<String, Held>()
            val fresh = ArrayList<Held>()
            val pending = ArrayList<Pending>()
            for ((pass, category) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).withIndex()) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (info in pm.queryIntentActivities(intent, 0)) {
                    if (g != generation.get()) return
                    val ai = info.activityInfo
                    if (ai.packageName == self || !seen.add(ai.packageName)) continue
                    val stamp = stamps[ai.packageName]?.lastUpdateTime ?: 0L
                    val pinned = pass == 0
                    val previous = held[ai.packageName]
                    if (previous != null && AppStamps.reusable(previous.entry, ai.packageName, ai.name, pinned, stamp)) {
                        next[ai.packageName] = previous
                        continue
                    }
                    val banner = runCatching { ai.loadBanner(pm) }.getOrNull()
                    val drawable = banner ?: runCatching { info.loadIcon(pm) }.getOrNull()
                    val software = drawable?.let { runCatching { InstalledApps.toBitmap(it, banner != null) }.getOrNull() }
                    val shown = software?.let { toGpu(it) }
                    val kind = when {
                        software == null -> ArtKind.NONE
                        banner != null -> ArtKind.BANNER
                        else -> ArtKind.ICON
                    }
                    val entry = SnapshotEntry(
                        packageName = ai.packageName,
                        className = ai.name,
                        label = info.loadLabel(pm).toString(),
                        category = InstalledApps.categoryOf(ai),
                        pinned = pinned,
                        art = kind,
                        lastUpdateTime = stamp,
                    )
                    val built = Held(entry, appFor(entry, shown?.asImageBitmap()))
                    next[ai.packageName] = built
                    fresh += built
                    if (software != null) pending += Pending(entry, software, recycle = shown !== software)
                }
            }
            if (g != generation.get()) return
            held = next
            last = ScanRecord(signature, now)
            publishNow()
            persist(next.values, signature, now, pending)
            Log.d(TAG, "scan: reused ${next.size - fresh.size}, rebuilt ${fresh.size}, took ${SystemClock.elapsedRealtime() - started} ms")
            logArt()
        } finally {
            Trace.endSection()
        }
    }

    // --- artwork ----------------------------------------------------------------------------

    /** Decodes artwork for apps that have some on record but not in memory yet. Safe to cut short. */
    private fun fillMissingArt(g: Int) {
        Trace.beginSection("AppLoader.fillMissingArt")
        try {
            val next = LinkedHashMap(held)
            var changed = false
            for ((pkg, h) in held) {
                if (h.entry.art == ArtKind.NONE || h.app.artwork != null) continue
                if (g != generation.get()) break
                val art = artFor(h.entry) ?: continue
                next[pkg] = Held(h.entry, h.app.copy(artwork = art))
                changed = true
            }
            if (changed) {
                held = next
                publishNow()
                logArt()
            }
        } finally {
            Trace.endSection()
        }
    }

    /**
     * The cached file if there is one, else render it from the package again (and cache that).
     * The result is a hardware bitmap when the budget allows, with no software copy kept.
     */
    private fun artFor(e: SnapshotEntry): ImageBitmap? {
        if (e.art == ArtKind.NONE) return null
        val file = File(artDir, ArtFiles.name(e.packageName, e.lastUpdateTime))
        if (file.exists()) {
            if (ArtLimits.hardwareAllowed(hardwareCount)) {
                // Decode straight into GPU memory: no heap copy to hold or recycle.
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
                val gpu = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
                if (gpu != null) {
                    if (gpu.config == Bitmap.Config.HARDWARE) hardwareCount++
                    return gpu.asImageBitmap()
                }
            }
            val decoded = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            if (decoded != null) return keepOnGpu(decoded).asImageBitmap()
        }
        val rendered = renderArt(e) ?: return null
        writeArt(e, rendered)
        return keepOnGpu(rendered).asImageBitmap()
    }

    /** [toGpu], then drop the software original if the copy replaced it. For bitmaps nothing else needs. */
    private fun keepOnGpu(software: Bitmap): Bitmap {
        val shown = toGpu(software)
        if (shown !== software) software.recycle()
        return shown
    }

    /** Copies [software] into GPU memory if the budget allows and the copy works; otherwise returns it unchanged. */
    private fun toGpu(software: Bitmap): Bitmap {
        if (!ArtLimits.hardwareAllowed(hardwareCount)) return software
        val gpu = runCatching { software.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return software
        if (gpu.config != Bitmap.Config.HARDWARE) {
            // The copy did not land in GPU memory, so it is no better than what we have; don't keep two.
            gpu.recycle()
            return software
        }
        hardwareCount++
        return gpu
    }

    private fun countHardware(): Int =
        held.values.count { it.app.artwork?.asAndroidBitmap()?.config == Bitmap.Config.HARDWARE }

    /** What the artwork costs and how many file descriptors the process holds, so the cap can be tuned from real numbers. */
    private fun logArt() {
        var gpu = 0
        var heap = 0
        var heapBytes = 0L
        for (h in held.values) {
            val b = h.app.artwork?.asAndroidBitmap() ?: continue
            if (b.config == Bitmap.Config.HARDWARE) {
                gpu++
            } else {
                heap++
                heapBytes += b.allocationByteCount
            }
        }
        val fds = File("/proc/self/fd").list()?.size ?: -1
        Log.d(TAG, "art: $gpu on the GPU, $heap in heap (${heapBytes / 1024} KB), $fds open file descriptors")
    }

    @Suppress("DEPRECATION")
    private fun renderArt(e: SnapshotEntry): Bitmap? = runCatching {
        val ai = pm.getActivityInfo(ComponentName(e.packageName, e.className), 0)
        val banner = if (e.art == ArtKind.BANNER) ai.loadBanner(pm) ?: return@runCatching null else null
        InstalledApps.toBitmap(banner ?: ai.loadIcon(pm), banner != null)
    }.getOrNull()

    private fun writeArt(e: SnapshotEntry, bitmap: Bitmap) {
        runCatching {
            artDir.mkdirs()
            val target = File(artDir, ArtFiles.name(e.packageName, e.lastUpdateTime))
            val temp = File(artDir, target.name + ".tmp")
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    // --- output -----------------------------------------------------------------------------

    private fun appFor(e: SnapshotEntry, art: ImageBitmap?) = LauncherApp(
        component = ComponentName(e.packageName, e.className),
        label = e.label,
        category = e.category,
        artwork = art,
        artworkIsBanner = e.art == ArtKind.BANNER,
        pinned = e.pinned,
    )

    private fun publishNow() {
        val list = held.values.sortedBy { it.entry.label.lowercase() }.map { it.app }
        main.post { if (!closed) publish(list) }
    }

    private companion object {
        const val TAG = "AppLoader"
    }
}
