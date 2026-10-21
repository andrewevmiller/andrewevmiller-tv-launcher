package com.andrewevmiller.spike

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns

/**
 * E1: can a third-party app read the Watch Next / preview-channel rows that other apps publish?
 *
 * Only public platform API (android.media.tv.TvContract + ContentResolver). Nothing is written.
 * Every query is wrapped so a SecurityException becomes a reported outcome, not a crash.
 */
class EpgProbe(private val context: Context) {

    private class Target(
        val name: String,
        val uri: Uri,
        val titleColumn: String,
        /** Optional extra column shown in the sample rows (channels: "type", to spot TYPE_PREVIEW). */
        val extraColumn: String? = null,
    )

    private val targets: List<Target> = listOf(
        Target("WatchNext", TvContract.WatchNextPrograms.CONTENT_URI, "title"),
        Target("Preview", TvContract.PreviewPrograms.CONTENT_URI, "title"),
        // Channels have no "title"; the human name is "display_name". The channels table also holds
        // ordinary broadcast channels of TV inputs, so show "type" to tell those from TYPE_PREVIEW.
        Target("Channels", TvContract.Channels.CONTENT_URI, "display_name", "type"),
    )

    fun readGranted(): Boolean =
        context.checkSelfPermission(Spike.PERM_READ_EPG) == PackageManager.PERMISSION_GRANTED

    fun allGranted(): Boolean =
        context.checkSelfPermission(Spike.PERM_ACCESS_ALL_EPG) == PackageManager.PERMISSION_GRANTED

    fun state(): Int = ReportFormat.permissionState(readGranted(), allGranted())

    /** Permission grant status plus how the system defines each permission (dangerous vs signature). */
    fun permissionLines(): List<String> {
        val lines = ArrayList<String>()
        lines.add("package: " + context.packageName)
        lines.add("READ_EPG_DATA        granted=" + readGranted() + "  " + describePermission(Spike.PERM_READ_EPG))
        lines.add("ACCESS_ALL_EPG_DATA  granted=" + allGranted() + "  " + describePermission(Spike.PERM_ACCESS_ALL_EPG))
        lines.add("=> " + ReportFormat.stateTitle(state()))
        return lines
    }

    private fun describePermission(name: String): String {
        return try {
            val pi = context.packageManager.getPermissionInfo(name, 0)
            val base = when (pi.protection) {
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous (runtime, pm grant works)"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                else -> "other(" + pi.protection + ")"
            }
            val flags = pi.protectionFlags
            val priv = if ((flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) "+privileged" else ""
            "protection=" + base + priv + " flags=0x" + Integer.toHexString(flags) + " definedBy=" + pi.packageName
        } catch (e: PackageManager.NameNotFoundException) {
            "NOT DEFINED on this device"
        } catch (e: Throwable) {
            "lookup failed: " + e.javaClass.simpleName + ": " + e.message
        }
    }

    /** Runs all three queries in the current permission state. Safe to call off the main thread. */
    fun runAll(): List<TableResult> = targets.map { query(it) }

    private fun query(t: Target): TableResult {
        val uriText = t.uri.toString()
        val own = context.packageName
        var cursor: Cursor? = null
        var note = ""
        try {
            val columns = ArrayList<String>()
            columns.add(BaseColumns._ID)
            columns.add("package_name")
            columns.add(t.titleColumn)
            if (t.extraColumn != null) columns.add(t.extraColumn)
            val projection = columns.toTypedArray()
            cursor = try {
                context.contentResolver.query(t.uri, projection, null, null, null)
            } catch (e: IllegalArgumentException) {
                note = "projection rejected (" + e.message + "); retried with null projection"
                context.contentResolver.query(t.uri, null, null, null, null)
            }
            if (cursor == null) {
                return TableResult(t.name, uriText, Outcome.NULL_CURSOR, 0, emptyMap(), emptyList(),
                    "query() returned null" + (if (note.isEmpty()) "" else "; $note"))
            }

            val pkgIdx = cursor.getColumnIndex("package_name")
            val titleIdx = cursor.getColumnIndex(t.titleColumn)
            val extraIdx = if (t.extraColumn != null) cursor.getColumnIndex(t.extraColumn) else -1
            var previewTyped = 0
            if (pkgIdx < 0) note += (if (note.isEmpty()) "" else "; ") + "no package_name column; columns=" +
                cursor.columnNames.joinToString(",")

            var total = 0
            val perPackage = LinkedHashMap<String, Int>()
            val sample = ArrayList<String>()
            while (cursor.moveToNext()) {
                total++
                val pkg = (if (pkgIdx >= 0) cursor.getString(pkgIdx) else null) ?: "?"
                perPackage[pkg] = (perPackage[pkg] ?: 0) + 1
                val extra = if (extraIdx >= 0) cursor.getString(extraIdx) else null
                if (extra == "TYPE_PREVIEW") previewTyped++
                if (sample.size < SAMPLE_MAX) {
                    val title = (if (titleIdx >= 0) cursor.getString(titleIdx) else null) ?: "(no title)"
                    sample.add(if (extra != null) "$pkg | $title | ${t.extraColumn}=$extra" else "$pkg | $title")
                }
                if (total >= SCAN_MAX) {
                    note += (if (note.isEmpty()) "" else "; ") + "scan stopped at $SCAN_MAX rows"
                    break
                }
            }
            if (extraIdx >= 0) note += (if (note.isEmpty()) "" else "; ") + "rows with type=TYPE_PREVIEW: $previewTyped"
            val outcome = ReportFormat.classify(total, perPackage, own)
            return TableResult(t.name, uriText, outcome, total, perPackage, sample, note)
        } catch (e: SecurityException) {
            return TableResult(t.name, uriText, Outcome.SECURITY_EXCEPTION, 0, emptyMap(), emptyList(),
                "SecurityException: " + e.message)
        } catch (e: Throwable) {
            return TableResult(t.name, uriText, Outcome.OTHER_ERROR, 0, emptyMap(), emptyList(),
                e.javaClass.simpleName + ": " + e.message)
        } finally {
            cursor?.close()
        }
    }

    private companion object {
        const val SAMPLE_MAX = 5
        const val SCAN_MAX = 5000
    }
}
