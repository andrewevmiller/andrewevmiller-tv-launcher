package com.andrewevmiller.tvlauncher.ui.icons

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [IconPaths] honest against the mockup. The mockup's `const ICON = {...}` object is the source of
 * truth; this test re-reads it, converts each SVG primitive with the same rules, and compares.
 *
 * Conversion rules: `<path d>` is copied verbatim; `<circle cx cy r>` becomes
 * `M{cx-r},{cy} a{r},{r} 0 1,0 {2r},0 a{r},{r} 0 1,0 {-2r},0 z`; a rounded `<rect x y width height rx>` becomes a closed
 * path (see rectAsPath); `fill="currentColor"` marks a filled primitive.
 */
class IconPathsTest {
    private val expectedNames = setOf(
        "play", "note", "pad", "tool", "wave", "port", "gear", "wifi", "search", "folder", "grid", "moon", "points",
    )

    @Test
    fun hasExactlyTheThirteenNamedIcons() {
        assertEquals(13, IconPaths.all.size)
        assertEquals(expectedNames, IconPaths.all.keys)
    }

    @Test
    fun everyIconMatchesTheMockup() {
        val mockup = mockupIcons(findMockup().readText())
        assertEquals("mockup icon names", expectedNames, mockup.keys)
        for (name in expectedNames) {
            assertEquals("icon '$name' drifted from the mockup", mockup.getValue(name), IconPaths.all.getValue(name))
        }
    }

    @Test
    fun everyPathIsNonEmptyAndParseableSvgPathData() {
        for ((name, paths) in IconPaths.all) {
            assertTrue("$name has no primitives", paths.isNotEmpty())
            for (p in paths) {
                assertTrue("$name: empty path data", p.d.isNotBlank())
                assertTrue("$name: characters outside SVG path syntax in '${p.d}'", allowedChars.matches(p.d))
                assertTrue("$name: malformed SVG path data '${p.d}'", isWellFormedPathData(p.d))
            }
        }
    }

    // ---- mockup reader ----

    private fun findMockup(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, MOCKUP_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$MOCKUP_PATH not found in any directory above $start")
    }

    private fun mockupIcons(html: String): Map<String, List<IconPath>> {
        val body = iconObject.find(html)?.let { it.groupValues[1] }
            ?: throw AssertionError("'const ICON = {' not found in the mockup")
        val icons = LinkedHashMap<String, List<IconPath>>()
        for (entry in iconEntry.findAll(body)) {
            icons[entry.groupValues[1]] = primitivesOf(entry.groupValues[2])
        }
        return icons
    }

    private fun primitivesOf(markup: String): List<IconPath> =
        primitive.findAll(markup).map { m ->
            val tag = m.groupValues[1]
            val attrs = attribute.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            require(attrs.keys.all { it in knownAttributes }) { "unexpected attribute in <$tag>: ${attrs.keys}" }
            val fill = attrs["fill"]
            require(fill == null || fill == "none" || fill == "currentColor") { "unexpected fill '$fill'" }
            val filled = fill == "currentColor"
            when (tag) {
                "path" -> IconPath(attrs.getValue("d"), filled)
                "circle" -> IconPath(
                    circleAsArc(
                        cx = BigDecimal(attrs.getValue("cx")),
                        cy = BigDecimal(attrs.getValue("cy")),
                        r = BigDecimal(attrs.getValue("r")),
                    ),
                    filled,
                )
                "rect" -> IconPath(
                    rectAsPath(
                        x = BigDecimal(attrs.getValue("x")),
                        y = BigDecimal(attrs.getValue("y")),
                        w = BigDecimal(attrs.getValue("width")),
                        h = BigDecimal(attrs.getValue("height")),
                        rx = BigDecimal(attrs.getValue("rx")),
                    ),
                    filled,
                )
                else -> throw AssertionError("unsupported primitive <$tag>")
            }
        }.toList()

    /** Same arc template as the spec: `M{cx-r},{cy} a{r},{r} 0 1,0 {2r},0 a{r},{r} 0 1,0 {-2r},0 z`. */
    private fun circleAsArc(cx: BigDecimal, cy: BigDecimal, r: BigDecimal): String {
        val rs = plain(r)
        val twoR = r.multiply(BigDecimal(2))
        return "M${plain(cx.subtract(r))},${plain(cy)} a$rs,$rs 0 1,0 ${plain(twoR)},0 " +
            "a$rs,$rs 0 1,0 ${plain(twoR.negate())},0 z"
    }

    /** Rounded rect (ry = rx) as a closed path, clockwise from the top edge. */
    private fun rectAsPath(x: BigDecimal, y: BigDecimal, w: BigDecimal, h: BigDecimal, rx: BigDecimal): String {
        val r = plain(rx)
        val left = x
        val right = x.add(w)
        val top = y
        val bottom = y.add(h)
        return "M${plain(left.add(rx))},${plain(top)} H${plain(right.subtract(rx))} " +
            "A$r,$r 0 0 1 ${plain(right)},${plain(top.add(rx))} V${plain(bottom.subtract(rx))} " +
            "A$r,$r 0 0 1 ${plain(right.subtract(rx))},${plain(bottom)} H${plain(left.add(rx))} " +
            "A$r,$r 0 0 1 ${plain(left)},${plain(bottom.subtract(rx))} V${plain(top.add(rx))} " +
            "A$r,$r 0 0 1 ${plain(left.add(rx))},${plain(top)} z"
    }

    /** Plain decimal with no trailing zeros, so 15.4 prints as "15.4" and 10 as "10". */
    private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    // ---- SVG path syntax ----

    /** Tokenizes like an SVG path parser: each command letter takes a whole number of argument groups. */
    private fun isWellFormedPathData(d: String): Boolean {
        if (pathToken.replace(d, "").any { it != ' ' && it != ',' }) return false
        val tokens = pathToken.findAll(d).map { it.value }.toList()
        if (tokens.isEmpty() || (tokens.first()[0] != 'M' && tokens.first()[0] != 'm')) return false
        var current: Char? = null
        var numbers = 0
        for (token in tokens) {
            val c = token[0]
            if (c.isLetter()) {
                if (current != null && !argumentsFit(current, numbers)) return false
                if (argumentCount[c.uppercaseChar()] == null) return false
                current = c
                numbers = 0
            } else {
                numbers++
            }
        }
        return current != null && argumentsFit(current, numbers)
    }

    private fun argumentsFit(command: Char, numbers: Int): Boolean {
        val n = argumentCount[command.uppercaseChar()] ?: return false
        return if (n == 0) numbers == 0 else numbers > 0 && numbers % n == 0
    }

    private companion object {
        const val MOCKUP_PATH = "docs/mockup/field-launcher-mockup.html"
        val iconObject = Regex("""const ICON = \{(.*?)\n\s*\};""", RegexOption.DOT_MATCHES_ALL)
        val iconEntry = Regex("""(\w+): '([^']*)'""")
        val primitive = Regex("""<(path|circle|rect)\b([^>]*?)/>""")
        val attribute = Regex("""(\w+)="([^"]*)"""")
        val knownAttributes = setOf("d", "cx", "cy", "r", "fill", "x", "y", "width", "height", "rx")
        val allowedChars = Regex("""[MmLlHhVvCcSsQqTtAaZz0-9 ,.-]+""")
        val pathToken = Regex("""[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)""")
        val argumentCount = mapOf(
            'M' to 2, 'L' to 2, 'T' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'A' to 7, 'Z' to 0,
        )
    }
}
