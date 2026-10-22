package com.andrewevmiller.spike

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * E3 (sleep timer) pure formatting and classification. No Android imports, in the style of ReportFormat.kt,
 * so the result text is easy to reason about and could be unit-tested later.
 *
 * A run is stored in SharedPreferences as one string per field, key "e3.<id>.<field>", so the alarm receiver
 * (worker thread), SleepDimActivity (main thread) and MainActivity (owner marks) can each write their own
 * fields without a read-modify-write race.
 */

/** Field names of one E3 run. */
object E3Field {
    const val METHOD = "method"
    const val ALARM_API = "alarmApi"

    /** Wall clock ms: when the button was pressed, when the alarm was planned for, when the receiver ran. */
    const val SCHEDULED_AT = "scheduledAt"
    const val FIRES_AT = "firesAt"
    const val FIRED_AT = "firedAt"

    const val OVERLAY_SCHED = "overlaySched"
    const val OVERLAY_FIRE = "overlayFire"
    const val OP_MODE = "opMode"

    const val MUSIC_BEFORE = "musicBefore"
    const val PAUSE_RUNG = "pauseRung"
    const val PAUSE_DETAIL = "pauseDetail"
    const val FOCUS_RESULT = "focusResult"
    const val MUSIC_END = "musicEnd"

    /** SystemClock.elapsedRealtime() ms, comparable across our components because they share one process. */
    const val DIM_REQUESTED_AT = "dimRequestedAt"
    const val DIM_CREATED_AT = "dimCreatedAt"
    const val DIM_FOCUS_AT = "dimFocusAt"
    const val DIM_DISMISSED = "dimDismissed"
    const val DIM_CALL = "dimCall"
    const val DIM_OUTCOME = "dimOutcome"

    /** Method A only: did ACTION_DREAMING_STARTED arrive within the window? ("yes" / "no"). */
    const val DREAM_SIGNAL = "dreamSignal"

    /** The owner's eyes: "STOPPED" / "PLAYING" and "SHOWED" / "NOTHING". */
    const val OWNER_PICTURE = "ownerPicture"
    const val OWNER_DIM = "ownerDim"

    const val RECV_MS = "recvMs"

    /** "ok", "superseded" or "error: ...". Absent while the run is still going. */
    const val DONE = "done"
    const val LOG = "log"
}

/** The five things the alarm can be asked to do. */
object E3Method {
    const val A = "A"
    const val B = "B"
    const val C = "C"
    const val D = "D"

    /** A far-future alarm the status button sets and cancels at once. The receiver ignores it. */
    const val TEST = "TEST"

    fun title(method: String): String = when (method) {
        A -> "pause, then the system screensaver (Somnambulator)"
        B -> "pause, then our own dim activity"
        C -> "pause, then our own dim activity (overlay appop allowed by adb)"
        D -> "pause only (control)"
        else -> "method $method"
    }
}

/** One stored run: its id and every field written so far. */
class SleepRun(val id: Int, private val values: Map<String, String>) {
    fun get(field: String): String? = values[field]
    fun has(field: String): Boolean = values.containsKey(field)
    fun long(field: String): Long? = values[field]?.toLongOrNull()
    val method: String get() = values[E3Field.METHOD] ?: "?"
}

object SleepReport {

    // ---- storage keys -----------------------------------------------------------------------------

    const val KEY_PREFIX = "e3."

    fun key(id: Int, field: String): String = KEY_PREFIX + id + "." + field

    /** Groups "e3.<id>.<field>" entries into runs, oldest first. Anything else in [all] is ignored. */
    fun parseRuns(all: Map<String, String>): List<SleepRun> {
        val byId = HashMap<Int, HashMap<String, String>>()
        for ((k, v) in all) {
            if (!k.startsWith(KEY_PREFIX)) continue
            val parts = k.split('.', limit = 3)
            if (parts.size != 3) continue
            val id = parts[1].toIntOrNull() ?: continue
            byId.getOrPut(id) { HashMap() }[parts[2]] = v
        }
        return byId.entries.sortedBy { it.key }.map { SleepRun(it.key, it.value) }
    }

    // ---- small formatters -------------------------------------------------------------------------

    fun clock(wallMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wallMs))

    fun durationText(ms: Long): String =
        if (ms < 1000) ms.toString() + " ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

    fun yesNo(v: String?): String = when (v) {
        "true" -> "yes"
        "false" -> "no"
        null -> "?"
        else -> v
    }

    /** AppOpsManager.MODE_* as words. */
    fun appopName(mode: Int): String = when (mode) {
        0 -> "allow"
        1 -> "ignore"
        2 -> "deny (errored)"
        3 -> "default"
        4 -> "foreground"
        else -> "mode $mode"
    }

    /** UsageStatsManager.STANDBY_BUCKET_* as words. */
    fun bucketName(bucket: Int): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> "bucket $bucket"
    }

    /** AudioManager.AUDIOFOCUS_REQUEST_* as words. */
    fun focusResultName(code: Int): String = when (code) {
        0 -> "AUDIOFOCUS_REQUEST_FAILED"
        1 -> "AUDIOFOCUS_REQUEST_GRANTED"
        2 -> "AUDIOFOCUS_REQUEST_DELAYED"
        else -> "AUDIOFOCUS_REQUEST code $code"
    }

    // ---- pause ladder -----------------------------------------------------------------------------

    const val RUNG_P1 = "P1"
    const val RUNG_P2 = "P2"
    const val RUNG_P3 = "P3"
    const val RUNG_NEVER = "NEVER"
    const val RUNG_IDLE = "IDLE_AT_FIRE"

    /**
     * The rung at which isMusicActive() first read false. Each "after" argument is null when that rung was
     * skipped because an earlier one had already worked. If nothing was active when the alarm fired the
     * answer is [RUNG_IDLE]: audio was not a usable signal (already paused, or the app does not report it).
     */
    fun pauseRung(activeBefore: Boolean, afterP1: Boolean?, afterP2: Boolean?, afterP3: Boolean?): String = when {
        !activeBefore -> RUNG_IDLE
        afterP1 == false -> RUNG_P1
        afterP2 == false -> RUNG_P2
        afterP3 == false -> RUNG_P3
        else -> RUNG_NEVER
    }

    fun rungLabel(rung: String?): String = when (rung) {
        RUNG_P1 -> "P1 media key pause"
        RUNG_P2 -> "P2 media key pause, second try"
        RUNG_P3 -> "P3 audio focus (GAIN)"
        RUNG_NEVER -> "never (still active after P3)"
        RUNG_IDLE -> "nothing was playing audio at fire time (P1 sent anyway)"
        null -> "(not run yet)"
        else -> rung
    }

    private fun rungShort(rung: String?): String = when (rung) {
        RUNG_P1, RUNG_P2, RUNG_P3, RUNG_NEVER -> rung
        RUNG_IDLE -> "IDLE"
        else -> "-"
    }

    // ---- dim surface ------------------------------------------------------------------------------

    const val DIM_SHOWN = "REQUESTED_AND_SHOWN"
    const val DIM_BLOCKED = "REQUESTED_BUT_BLOCKED"
    const val DIM_UNVERIFIED = "REQUESTED_UNVERIFIED"
    const val DIM_SKIPPED = "SKIPPED"
    const val DIM_THREW_PREFIX = "THREW: "

    /**
     * Method A, B and C are decided by what happened inside the window; method D never asks.
     *
     * B and C: [createdAt] is the elapsedRealtime at which SleepDimActivity.onCreate ran (null = it never did).
     * A: nothing of ours is created, so the only objective signal is ACTION_DREAMING_STARTED ([dreamSeen]); without
     * it the answer is [DIM_UNVERIFIED] and the owner's eyes decide.
     */
    fun classifyDim(
        method: String,
        threw: String?,
        requestedAt: Long?,
        createdAt: Long?,
        dreamSeen: Boolean?,
        windowMs: Long,
    ): String {
        if (method == E3Method.D) return DIM_SKIPPED
        if (threw != null) return DIM_THREW_PREFIX + threw
        if (method == E3Method.A) return if (dreamSeen == true) DIM_SHOWN else DIM_UNVERIFIED
        if (requestedAt == null) return DIM_BLOCKED
        if (createdAt != null && createdAt - requestedAt in 0..windowMs) return DIM_SHOWN
        return DIM_BLOCKED
    }

    private fun dimShort(outcome: String?): String = when {
        outcome == null -> "-"
        outcome == DIM_SHOWN -> "SHOWN"
        outcome == DIM_BLOCKED -> "BLOCKED"
        outcome == DIM_UNVERIFIED -> "UNVERIF"
        outcome == DIM_SKIPPED -> "SKIPPED"
        outcome.startsWith(DIM_THREW_PREFIX) -> "THREW"
        else -> outcome
    }

    /**
     * Runs B and C use the same code; they differ only in whether the overlay appop was allowed first.
     * Flag a run whose method name does not match what was actually granted at fire time.
     */
    fun overlayNote(method: String, overlayAtFire: Boolean?): String = when {
        overlayAtFire == null -> ""
        method == E3Method.C && !overlayAtFire ->
            "Run C but canDrawOverlays was FALSE at fire time: this is really run B. Run adb appops set ... allow first."
        method == E3Method.B && overlayAtFire ->
            "Run B but canDrawOverlays was TRUE at fire time: this is really run C. Reset the appop to default."
        else -> ""
    }

    // ---- run text ---------------------------------------------------------------------------------

    /** The whole story of one run, for the live pane and the summary. */
    fun runLines(run: SleepRun, nowMs: Long): List<String> {
        val lines = ArrayList<String>()
        val method = run.method
        lines.add("#" + run.id + " " + method + "  " + E3Method.title(method))
        val api = run.get(E3Field.ALARM_API) ?: "?"
        val firesAt = run.long(E3Field.FIRES_AT)
        val firedAt = run.long(E3Field.FIRED_AT)
        val sched = run.long(E3Field.SCHEDULED_AT)
        var alarm = "  alarm: " + api
        if (sched != null) alarm += ", set " + clock(sched)
        if (firesAt != null) alarm += ", planned " + clock(firesAt)
        lines.add(alarm)
        if (firedAt == null) {
            lines.add("  " + notFiredText(firesAt, nowMs))
            return lines
        }
        if (firesAt != null) {
            lines.add("  fired " + clock(firedAt) + " (" + signedDuration(firedAt - firesAt) + " vs plan)" +
                (run.long(E3Field.RECV_MS)?.let { ", receiver held the broadcast " + durationText(it) } ?: ""))
        }
        lines.add("  canDrawOverlays at fire = " + yesNo(run.get(E3Field.OVERLAY_FIRE)) +
            " (at press: " + yesNo(run.get(E3Field.OVERLAY_SCHED)) + "), appop SYSTEM_ALERT_WINDOW = " +
            (run.get(E3Field.OP_MODE) ?: "?"))
        val note = overlayNote(method, run.get(E3Field.OVERLAY_FIRE)?.toBooleanStrictOrNull())
        if (note.isNotEmpty()) lines.add("  NOTE: " + note)

        lines.add("  PAUSE: " + rungLabel(run.get(E3Field.PAUSE_RUNG)) + "   (audio active before = " +
            yesNo(run.get(E3Field.MUSIC_BEFORE)) + ", after everything = " + yesNo(run.get(E3Field.MUSIC_END)) + ")")
        run.get(E3Field.PAUSE_DETAIL)?.let { lines.add("    " + it) }
        run.get(E3Field.FOCUS_RESULT)?.let { lines.add("    audio focus request: " + it) }

        lines.add("  DIM: " + (run.get(E3Field.DIM_OUTCOME) ?: "(not decided yet)"))
        run.get(E3Field.DIM_CALL)?.let { lines.add("    call: " + ReportFormat.ellipsize(it.replace('\n', ' '), 110)) }
        val requestedAt = run.long(E3Field.DIM_REQUESTED_AT)
        val createdAt = run.long(E3Field.DIM_CREATED_AT)
        val focusAt = run.long(E3Field.DIM_FOCUS_AT)
        if (requestedAt != null && createdAt != null) {
            lines.add("    SleepDimActivity.onCreate ran " + signedDuration(createdAt - requestedAt) + " after the request" +
                (if (createdAt - requestedAt > Spike.E3_DIM_WINDOW_MS) " (LATE: after the " + durationText(Spike.E3_DIM_WINDOW_MS) + " window)" else "") +
                (if (focusAt != null) ", got window focus " + signedDuration(focusAt - requestedAt) + " after it" else ", never got window focus"))
        } else if (method == E3Method.B || method == E3Method.C) {
            lines.add("    SleepDimActivity was never created")
        }
        run.get(E3Field.DREAM_SIGNAL)?.let { lines.add("    ACTION_DREAMING_STARTED seen within 3 s: " + it) }
        run.get(E3Field.DIM_DISMISSED)?.let { lines.add("    dismissed: " + it) }

        val pic = run.get(E3Field.OWNER_PICTURE)
        val dim = run.get(E3Field.OWNER_DIM)
        if (pic == null) lines.add("  Did the picture stop? (note it: 'E3 mark: picture STOPPED / KEPT PLAYING')")
        lines.add("  YOU: picture=" + (pic ?: "?") + "  dim=" + (dim ?: "?") +
            (if (dim == null && method != E3Method.D) "   (mark it: 'E3 mark: dim SHOWED / NOTHING')" else ""))
        return lines
    }

    private fun notFiredText(firesAt: Long?, nowMs: Long): String = when {
        firesAt == null -> "(no planned time stored)"
        nowMs < firesAt -> "waiting: fires at " + clock(firesAt) + ". Go to YouTube/Netflix and start a video."
        else -> "NOT FIRED: the planned time " + clock(firesAt) + " has passed with no receiver call " +
            "(alarm lost, TV was asleep, or the receiver is not declared)."
    }

    private fun signedDuration(ms: Long): String =
        (if (ms >= 0) "+" else "-") + durationText(if (ms >= 0) ms else -ms)

    /** One line for the purple status block at the top of the button column. */
    fun statusLine(run: SleepRun?, nowMs: Long): String {
        if (run == null) return "E3: no runs yet"
        val head = "E3 #" + run.id + " " + run.method
        val firesAt = run.long(E3Field.FIRES_AT)
        return when {
            run.get(E3Field.DONE) == "superseded" -> "$head: replaced by a later run"
            !run.has(E3Field.FIRED_AT) ->
                if (firesAt != null && nowMs < firesAt) "$head: fires at " + clock(firesAt) + ", go to the video"
                else "$head: alarm has not fired"
            !run.has(E3Field.DONE) -> "$head: running..."
            else -> head + ": pause=" + rungShort(run.get(E3Field.PAUSE_RUNG)) +
                " dim=" + dimShort(run.get(E3Field.DIM_OUTCOME))
        }
    }

    // ---- summary table ----------------------------------------------------------------------------

    fun summary(header: String, runs: List<SleepRun>, nowMs: Long): List<String> {
        val lines = ArrayList<String>()
        lines.add("E3 SUMMARY  $header")
        if (runs.isEmpty()) {
            lines.add("  (no runs yet)")
            return lines
        }
        lines.add(" #  M  pause   dim      ovl  you pic/dim")
        for (r in runs) {
            val pic = r.get(E3Field.OWNER_PICTURE)?.let { picShort(it) } ?: "?"
            val dim = r.get(E3Field.OWNER_DIM)?.let { dimMark(it) } ?: "?"
            val fired = r.has(E3Field.FIRED_AT)
            lines.add(
                r.id.toString().padStart(2) + "  " + r.method + "  " +
                    (if (fired) rungShort(r.get(E3Field.PAUSE_RUNG)) else "-").padEnd(6) + " " +
                    (if (fired) dimShort(r.get(E3Field.DIM_OUTCOME)) else "-").padEnd(8) + " " +
                    (if (fired) yesNo(r.get(E3Field.OVERLAY_FIRE)) else "-").padEnd(4) + " " +
                    pic + "/" + dim,
            )
        }
        lines.add("")
        for (r in runs) {
            lines.addAll(runLines(r, nowMs))
            lines.add("")
        }
        return lines
    }

    private fun picShort(v: String): String = when (v) {
        "STOPPED" -> "STOP"
        "PLAYING" -> "PLAY"
        else -> v
    }

    private fun dimMark(v: String): String = when (v) {
        "SHOWED" -> "SHOW"
        "NOTHING" -> "NONE"
        else -> v
    }
}
