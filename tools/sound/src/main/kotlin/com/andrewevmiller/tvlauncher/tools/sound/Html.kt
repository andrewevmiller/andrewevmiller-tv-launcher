package com.andrewevmiller.tvlauncher.tools.sound

import com.andrewevmiller.tvlauncher.sound.Waveform
import java.util.Locale

/*
 * index.html for listening: one section per waveform, one <audio controls> per WAV. Plain markup, system fonts,
 * no external resources, so it opens from disk in any browser.
 */

internal class IndexEntry(val relPath: String, val label: String, val durationMs: Double, val peakDb: Double)

internal class IndexSection(val waveform: Waveform, val entries: List<IndexEntry>)

internal fun indexHtml(sections: List<IndexSection>, gainDb: Double): String {
    val sb = StringBuilder()
    sb.append("<!doctype html>\n")
    sb.append("<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<title>Field Launcher sounds</title>\n")
    sb.append(
        """
        <style>
          :root { color-scheme: light dark; }
          body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; margin: 24px auto; max-width: 900px; padding: 0 16px; line-height: 1.4; }
          h1 { font-size: 22px; margin: 0 0 4px; }
          h2 { font-size: 18px; margin: 32px 0 8px; border-top: 1px solid currentColor; padding-top: 12px; opacity: 0.9; }
          .note { font-size: 14px; opacity: 0.75; margin: 0 0 16px; }
          nav a { margin-right: 12px; }
          .row { display: grid; grid-template-columns: 1fr 340px; gap: 12px; align-items: center; padding: 6px 0; border-bottom: 1px solid rgba(128, 128, 128, 0.25); }
          .label { font-size: 15px; }
          .meta { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; opacity: 0.7; }
          audio { width: 100%; }
          @media (max-width: 640px) { .row { grid-template-columns: 1fr; } }
        </style>
        """.trimIndent(),
    )
    sb.append("\n</head>\n<body>\n")
    sb.append("<h1>Field Launcher sounds</h1>\n")
    val intro = String.format(
        Locale.ROOT,
        "Rendered by tools/sound with the app's own sound code: 48 kHz, 16-bit mono, output gain %+.1f dB, " +
            "peaks at or below -1 dBFS. Launch motifs are shown without the per-launch humanize.",
        gainDb,
    )
    sb.append("<p class=\"note\">${escapeHtml(intro)}</p>\n")
    sb.append("<nav>")
    for (s in sections) {
        sb.append("<a href=\"#${folderName(s.waveform)}\">${escapeHtml(s.waveform.name)}</a>")
    }
    sb.append("</nav>\n")

    for (s in sections) {
        val title = if (s.waveform == Waveform.SAW) "${s.waveform.name} (default)" else s.waveform.name
        sb.append("<section id=\"${folderName(s.waveform)}\">\n<h2>${escapeHtml(title)}</h2>\n")
        for (e in s.entries) {
            val file = e.relPath.substringAfterLast('/')
            val meta = String.format(
                Locale.ROOT,
                "%s | %.1f ms | peak %s dBFS",
                file,
                e.durationMs,
                if (e.peakDb.isInfinite()) "-inf" else String.format(Locale.ROOT, "%.1f", e.peakDb),
            )
            sb.append("<div class=\"row\">\n")
            sb.append("<div><div class=\"label\">${escapeHtml(e.label)}</div><div class=\"meta\">${escapeHtml(meta)}</div></div>\n")
            sb.append("<audio controls preload=\"none\" src=\"${escapeHtml(e.relPath)}\"></audio>\n")
            sb.append("</div>\n")
        }
        sb.append("</section>\n")
    }
    sb.append("</body>\n</html>\n")
    return sb.toString()
}

private fun escapeHtml(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}
