package com.andrewevmiller.spike

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E3 run storage: one SharedPreferences string per field, key "e3.<id>.<field>" (see [SleepReport.key]).
 * The alarm receiver, SleepDimActivity and MainActivity all share one process, so they all see the same
 * in-memory prefs object; the receiver also commits to disk so the summary survives the process dying.
 */
class SleepStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Spike.PREFS, Context.MODE_PRIVATE)

    fun nextId(): Int {
        val next = prefs.getInt(Spike.PREF_E3_LAST_ID, 0) + 1
        prefs.edit().putInt(Spike.PREF_E3_LAST_ID, next).apply()
        return next
    }

    /** [sync] = commit() (use off the main thread); otherwise apply(), which is in memory at once. */
    fun put(id: Int, field: String, value: String, sync: Boolean = true) {
        putAll(id, mapOf(field to value), sync)
    }

    fun putAll(id: Int, values: Map<String, String>, sync: Boolean = true) {
        val e = prefs.edit()
        for ((field, value) in values) e.putString(SleepReport.key(id, field), value)
        if (sync) e.commit() else e.apply()
    }

    fun get(id: Int, field: String): String? = prefs.getString(SleepReport.key(id, field), null)

    fun allRuns(): List<SleepRun> {
        val flat = HashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(SleepReport.KEY_PREFIX) && v != null) flat[k] = v.toString()
        }
        return SleepReport.parseRuns(flat)
    }

    fun latest(): SleepRun? = allRuns().lastOrNull()

    /** A new schedule replaces the pending alarm (same PendingIntent), so mark the old run as replaced. */
    fun supersedePending() {
        for (r in allRuns()) {
            if (!r.has(E3Field.FIRED_AT) && !r.has(E3Field.DONE)) put(r.id, E3Field.DONE, "superseded", sync = false)
        }
    }
}

/** What [SleepProbe.schedule] did. [api] is null when nothing could be scheduled ([error] says why). */
class Scheduled(val id: Int, val firesAt: Long, val api: String?, val error: String?)

/**
 * E3: the two questions behind the sleep timer, asked from where the real feature will ask them, a
 * BroadcastReceiver fired by an alarm while another app is in front.
 *
 *  1. Which method pauses the media another app is playing?  ([fire], step 1: the pause ladder)
 *  2. Which method can put a dim full-screen surface up from the background?  ([fire], step 2)
 *
 * Framework API only, minSdk 31. Everything the owner needs is written to logcat tag FieldSpike and, per
 * run, to SharedPreferences (see [SleepStore]).
 */
class SleepProbe(context: Context, private val emit: (String) -> Unit) {

    private val ctx: Context = context.applicationContext ?: context
    val store = SleepStore(ctx)
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)
    private val alarms: AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    // ---- E3.0 status ------------------------------------------------------------------------------

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(ctx)

    private fun overlayOpMode(): Int {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), ctx.packageName)
    }

    private fun appopText(): String = SleepReport.appopName(overlayOpMode())

    /** E3.0: everything that can be read without firing anything. */
    @Suppress("DEPRECATION")
    fun status() {
        emit("--- E3.0 status")
        emit("  Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT)
        emit("  Settings.canDrawOverlays = " + canDrawOverlays())
        emit("  appop SYSTEM_ALERT_WINDOW = " + appopText() + "   (adb shell appops get " + ctx.packageName + " SYSTEM_ALERT_WINDOW)")
        emit("  AudioManager.isMusicActive = " + audio.isMusicActive)
        emit("  AudioManager.isVolumeFixed = " + audio.isVolumeFixed)
        emit("  active playbacks: " + playbacksText())
        emit("  AlarmManager.canScheduleExactAlarms = " + alarms.canScheduleExactAlarms() +
            "   (only matters for the setExactAndAllowWhileIdle fallback; setAlarmClock is exempt)")
        emit("  alarm-clock alarm: " + alarmClockTest())
        try {
            val usage = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            emit("  app standby bucket = " + SleepReport.bucketName(usage.appStandbyBucket))
        } catch (e: Throwable) {
            emit("  app standby bucket: " + e.javaClass.simpleName + ": " + e.message)
        }
        val pm = ctx.packageManager
        try {
            val r = pm.getReceiverInfo(ComponentName(ctx, SleepAlarmReceiver::class.java), 0)
            emit("  SleepAlarmReceiver declared: exported=" + r.exported + " enabled=" + r.enabled)
        } catch (e: Throwable) {
            emit("  SleepAlarmReceiver: " + e.javaClass.simpleName + ": " + e.message)
        }
        try {
            val a = pm.getActivityInfo(ComponentName(ctx, SleepDimActivity::class.java), 0)
            emit("  SleepDimActivity declared: exported=" + a.exported + " enabled=" + a.enabled)
        } catch (e: Throwable) {
            emit("  SleepDimActivity: " + e.javaClass.simpleName + ": " + e.message)
        }
        emit("  WRITE_SECURE_SETTINGS / system screensaver: see E2.0 (method A uses Somnambulator, no grant needed)")
    }

    /** Sets a far-future alarm clock, reads it back, cancels it. This is the real "can we schedule one" test. */
    private fun alarmClockTest(): String {
        return try {
            val at = System.currentTimeMillis() + 3_600_000L
            val pi = PendingIntent.getBroadcast(
                ctx, Spike.REQ_E3_TEST,
                Intent(ctx, SleepAlarmReceiver::class.java).putExtra(Spike.EXTRA_METHOD, E3Method.TEST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), pi)
            val next = alarms.nextAlarmClock
            alarms.cancel(pi)
            "YES. setAlarmClock accepted a test alarm 1 h ahead (nextAlarmClock " +
                (if (next != null && next.triggerTime == at) "read back correctly" else "did not read back as ours: " + next) +
                "), then cancelled it"
        } catch (e: Throwable) {
            "NO. setAlarmClock threw " + e.javaClass.simpleName + ": " + e.message
        }
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        ctx, Spike.REQ_E3_SHOW, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Which audio attributes other apps' players report (counts only: package names are anonymised for us). */
    private fun playbacksText(): String {
        return try {
            val list = audio.activePlaybackConfigurations
            if (list.isEmpty()) {
                "none"
            } else {
                list.size.toString() + " (" + list.joinToString(", ") {
                    "usage=" + it.audioAttributes.usage + "/content=" + it.audioAttributes.contentType
                } + ")"
            }
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + e.message
        }
    }

    // ---- schedule ---------------------------------------------------------------------------------

    /** Schedules the receiver [Spike.E3_DELAY_MS] ahead. Never throws. */
    fun schedule(method: String): Scheduled {
        store.supersedePending()
        val id = store.nextId()
        val now = System.currentTimeMillis()
        val firesAt = now + Spike.E3_DELAY_MS
        store.putAll(
            id,
            mapOf(
                E3Field.METHOD to method,
                E3Field.SCHEDULED_AT to now.toString(),
                E3Field.FIRES_AT to firesAt.toString(),
                E3Field.OVERLAY_SCHED to canDrawOverlays().toString(),
            ),
            sync = false,
        )
        val pi = PendingIntent.getBroadcast(
            ctx, Spike.REQ_E3_ALARM,
            Intent(ctx, SleepAlarmReceiver::class.java)
                .putExtra(Spike.EXTRA_METHOD, method)
                .putExtra(Spike.EXTRA_RUN, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        var api: String? = null
        var error: String? = null
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(firesAt, showIntent()), pi)
            api = "setAlarmClock"
        } catch (e: Throwable) {
            val first = e.javaClass.simpleName + ": " + e.message
            emit("  setAlarmClock threw " + first + "; trying setExactAndAllowWhileIdle")
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firesAt, pi)
                api = "setExactAndAllowWhileIdle (fallback, setAlarmClock threw $first)"
            } catch (e2: Throwable) {
                error = "setAlarmClock: $first; setExactAndAllowWhileIdle: " + e2.javaClass.simpleName + ": " + e2.message
            }
        }
        if (api != null) {
            store.put(id, E3Field.ALARM_API, api, sync = false)
        } else {
            store.putAll(id, mapOf(E3Field.ALARM_API to "FAILED", E3Field.DONE to "error: $error"), sync = false)
        }
        Log.i(Spike.TAG, "E3#" + id + " scheduled method=" + method + " api=" + api + " firesAt=" + SleepReport.clock(firesAt) +
            (if (error != null) " ERROR " + error else ""))
        return Scheduled(id, firesAt, api, error)
    }

    // ---- fire: runs on a worker thread, called from SleepAlarmReceiver ------------------------------

    /**
     * Step 1 (pause ladder), then step 2 (dim surface by [method]). Blocks for up to about 10 s.
     * [releaseBroadcast] is called as soon as nothing else needs the receiver to stay alive: before the
     * 3 s detection wait, so the broadcast is held for the pause ladder only (at most ~6.5 s).
     */
    fun fire(id: Int, method: String, releaseBroadcast: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        val lines = ArrayList<String>()
        var released = false

        fun log(s: String) {
            val stamped = "+" + SleepReport.durationText(SystemClock.elapsedRealtime() - t0) + " " + s
            Log.i(Spike.TAG, "E3#$id $stamped")
            synchronized(lines) { lines.add(stamped) }
        }

        fun flushLog() = store.put(id, E3Field.LOG, synchronized(lines) { lines.joinToString("\n") })

        fun release() {
            if (released) return
            released = true
            store.put(id, E3Field.RECV_MS, (SystemClock.elapsedRealtime() - t0).toString())
            releaseBroadcast()
        }

        try {
            val overlay = canDrawOverlays()
            val musicBefore = audio.isMusicActive
            store.putAll(
                id,
                mapOf(
                    E3Field.METHOD to method,
                    E3Field.FIRED_AT to System.currentTimeMillis().toString(),
                    E3Field.OVERLAY_FIRE to overlay.toString(),
                    E3Field.OP_MODE to appopText(),
                    E3Field.MUSIC_BEFORE to musicBefore.toString(),
                ),
            )
            log("FIRE method=$method (" + E3Method.title(method) + ")")
            log("canDrawOverlays=$overlay appop=" + appopText() + " isMusicActive=$musicBefore isVolumeFixed=" + audio.isVolumeFixed)
            log("active playbacks: " + playbacksText())
            val note = SleepReport.overlayNote(method, overlay)
            if (note.isNotEmpty()) log("NOTE: $note")
            flushLog()

            pauseLadder(id, musicBefore, ::log)
            flushLog()

            dimStep(id, method, ::log, ::release)

            val musicEnd = audio.isMusicActive
            log("end: isMusicActive=$musicEnd")
            store.put(id, E3Field.MUSIC_END, musicEnd.toString())
            store.put(id, E3Field.DONE, "ok")
            flushLog()
        } catch (e: Throwable) {
            log("FAILED " + e.javaClass.simpleName + ": " + e.message)
            store.put(id, E3Field.DONE, "error: " + e.javaClass.simpleName + ": " + e.message)
            flushLog()
        } finally {
            release()
        }
    }

    // ---- step 1: pause ladder ---------------------------------------------------------------------

    private fun pauseLadder(id: Int, activeBefore: Boolean, log: (String) -> Unit) {
        val detail = ArrayList<String>()
        var after2: Boolean? = null
        var after3: Boolean? = null

        // P1: media key, down then up.
        val before1 = audio.isMusicActive
        log("P1 media key PAUSE (isMusicActive before=$before1)")
        sendPauseKey(log)
        Thread.sleep(Spike.E3_RUNG_WAIT_MS)
        val after1 = audio.isMusicActive
        log("P1 isMusicActive after 1.5 s = $after1")
        detail.add("P1 $before1->$after1")

        if (activeBefore && after1) {
            // P2: the same key once more.
            log("P2 media key PAUSE again (isMusicActive before=$after1)")
            sendPauseKey(log)
            Thread.sleep(Spike.E3_RUNG_WAIT_MS)
            val a2 = audio.isMusicActive
            after2 = a2
            log("P2 isMusicActive after 1.5 s = $a2")
            detail.add("P2 true->$a2")

            if (a2) {
                // P3: take audio focus; a well-behaved player pauses on AUDIOFOCUS_LOSS.
                log("P3 requestAudioFocus(GAIN, USAGE_MEDIA, CONTENT_TYPE_MUSIC) (isMusicActive before=true)")
                val result = holdAudioFocus(log)
                after3 = result.second
                detail.add("P3 true->" + result.second)
                store.put(id, E3Field.FOCUS_RESULT, result.first)
            }
        }

        val rung = SleepReport.pauseRung(activeBefore, after1, after2, after3)
        log("pause result: " + SleepReport.rungLabel(rung))
        log("Did the picture stop? (note it with the E3 mark buttons)")
        store.putAll(
            id,
            mapOf(
                E3Field.PAUSE_RUNG to rung,
                E3Field.PAUSE_DETAIL to "isMusicActive per rung: " + detail.joinToString("; "),
            ),
        )
    }

    private fun sendPauseKey(log: (String) -> Unit) {
        try {
            val down = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            audio.dispatchMediaKeyEvent(
                KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
            )
        } catch (e: Throwable) {
            log("dispatchMediaKeyEvent threw " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    /** Requests focus, holds it for [Spike.E3_FOCUS_HOLD_MS], reads isMusicActive, abandons. Returns (result text, active). */
    private fun holdAudioFocus(log: (String) -> Unit): Pair<String, Boolean> {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change -> log("our focus listener: change=$change") }, Handler(Looper.getMainLooper()))
            .build()
        val code = try {
            audio.requestAudioFocus(request)
        } catch (e: Throwable) {
            log("requestAudioFocus threw " + e.javaClass.simpleName + ": " + e.message)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        val text = SleepReport.focusResultName(code)
        log("P3 requestAudioFocus -> $text")
        Thread.sleep(Spike.E3_FOCUS_HOLD_MS)
        val active = audio.isMusicActive
        log("P3 isMusicActive after 3 s holding focus = $active")
        try {
            audio.abandonAudioFocusRequest(request)
        } catch (e: Throwable) {
            log("abandonAudioFocusRequest threw " + e.javaClass.simpleName + ": " + e.message)
        }
        Thread.sleep(500)
        log("P3 isMusicActive 0.5 s after abandoning = " + audio.isMusicActive + " (informational: does it resume?)")
        return Pair(text, active)
    }

    // ---- step 2: dim surface ----------------------------------------------------------------------

    private fun dimStep(id: Int, method: String, log: (String) -> Unit, release: () -> Unit) {
        when (method) {
            E3Method.D -> {
                log("dim: skipped (control run)")
                store.put(id, E3Field.DIM_OUTCOME, SleepReport.DIM_SKIPPED)
                release()
            }
            E3Method.A -> dimWithSomnambulator(id, log, release)
            else -> dimWithOwnActivity(id, method, log, release)
        }
    }

    /** A: the way the launcher starts the screensaver today (E2.a). Our window is not in front, so use the system's signal. */
    private fun dimWithSomnambulator(id: Int, log: (String) -> Unit, release: () -> Unit) {
        val dream = DreamProbe(ctx) { s -> log(s.trim()) }
        log("dim A: screensaver_components = " + dream.currentScreensaver())
        val latch = CountDownLatch(1)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log("ACTION_DREAMING_STARTED received")
                latch.countDown()
            }
        }
        // Registered on the application context: the receiver's own context may not register receivers.
        var registered = false
        try {
            ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_DREAMING_STARTED))
            registered = true
        } catch (e: Throwable) {
            log("could not listen for ACTION_DREAMING_STARTED: " + e.javaClass.simpleName + ": " + e.message)
        }
        try {
            val call = dream.fire(dream.intentSomnambulator())
            log("dim A: Somnambulator call -> $call")
            store.put(id, E3Field.DIM_CALL, call)
            release()
            val seen = latch.await(Spike.E3_DIM_WINDOW_MS, TimeUnit.MILLISECONDS)
            val threw = if (call.startsWith("OK")) null else call
            val outcome = SleepReport.classifyDim(E3Method.A, threw, null, null, seen, Spike.E3_DIM_WINDOW_MS)
            log("dim A: dream started signal within 3 s = $seen -> $outcome (the owner's eyes decide: mark it)")
            store.putAll(id, mapOf(E3Field.DREAM_SIGNAL to (if (seen) "yes" else "no"), E3Field.DIM_OUTCOME to outcome))
        } finally {
            if (registered) {
                try {
                    ctx.unregisterReceiver(rx)
                } catch (e: Throwable) {
                    log("unregisterReceiver: " + e.javaClass.simpleName)
                }
            }
        }
    }

    /** B and C (same code; C only differs by the overlay appop having been allowed over adb beforehand). */
    private fun dimWithOwnActivity(id: Int, method: String, log: (String) -> Unit, release: () -> Unit) {
        val requestedAt = SystemClock.elapsedRealtime()
        store.put(id, E3Field.DIM_REQUESTED_AT, requestedAt.toString())
        var threw: String? = null
        try {
            ctx.startActivity(
                Intent(ctx, SleepDimActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Spike.EXTRA_RUN, id),
            )
            log("dim $method: startActivity(SleepDimActivity, NEW_TASK) returned without an exception")
            store.put(id, E3Field.DIM_CALL, "startActivity returned without an exception")
        } catch (e: Throwable) {
            threw = e.javaClass.simpleName + ": " + e.message
            log("dim $method: startActivity threw $threw")
            store.put(id, E3Field.DIM_CALL, "threw $threw")
        }
        release()

        // Android 10+ drops a blocked background start silently, so look for onCreate instead.
        var createdAt: Long? = null
        while (threw == null && SystemClock.elapsedRealtime() - requestedAt < Spike.E3_DIM_WINDOW_MS) {
            createdAt = store.get(id, E3Field.DIM_CREATED_AT)?.toLongOrNull()
            if (createdAt != null) break
            Thread.sleep(100)
        }
        if (createdAt == null) createdAt = store.get(id, E3Field.DIM_CREATED_AT)?.toLongOrNull()
        val outcome = SleepReport.classifyDim(method, threw, requestedAt, createdAt, null, Spike.E3_DIM_WINDOW_MS)
        log("dim $method: " + outcome + (if (createdAt != null) " (onCreate " + (createdAt - requestedAt) + " ms after the request)" else ""))
        store.put(id, E3Field.DIM_OUTCOME, outcome)
    }
}
