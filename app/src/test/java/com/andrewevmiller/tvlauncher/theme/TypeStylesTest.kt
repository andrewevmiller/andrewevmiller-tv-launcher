package com.andrewevmiller.tvlauncher.theme

import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The named styles are the sizes the screens used to set inline; they must not drift. */
class TypeStylesTest {
    private val t = FieldTypography

    @Test fun sizesMatchTheOriginalInlineValues() {
        assertEquals(11.5.sp, t.sectionLabel.fontSize); assertEquals(14.sp, t.sectionLabel.lineHeight); assertEquals(0.08.em, t.sectionLabel.letterSpacing)
        assertEquals(14.sp, t.tileTitle.fontSize); assertEquals(17.sp, t.tileTitle.lineHeight)
        assertEquals(10.sp, t.tileCaption.fontSize); assertEquals(13.sp, t.tileCaption.lineHeight); assertEquals(0.06.em, t.tileCaption.letterSpacing)
        assertEquals(12.sp, t.chip.fontSize); assertEquals(0.04.em, t.chip.letterSpacing)
        assertEquals(30.sp, t.pageTitle.fontSize); assertEquals(32.sp, t.pageTitle.lineHeight)
        assertEquals(13.sp, t.clock.fontSize)
    }

    @Test fun eachNamedStyleKeepsItsBaseFamilyAndWeight() {
        assertEquals(t.label.fontFamily, t.sectionLabel.fontFamily); assertEquals(t.label.fontWeight, t.clock.fontWeight)
        assertEquals(t.bodyStrong.fontFamily, t.tileTitle.fontFamily); assertEquals(t.bodyStrong.fontWeight, t.systemLabel.fontWeight)
        assertEquals(t.heading.fontFamily, t.bandTitle.fontFamily); assertEquals(t.title.fontFamily, t.pageTitle.fontFamily)
        assertEquals(t.caption.fontWeight, t.chip.fontWeight); assertEquals(t.body.fontWeight, t.bodySmall.fontWeight)
    }

    @Test fun letterSpacingIsInheritedWhereTheOriginalDidNotSetIt() {
        assertEquals(t.title.letterSpacing, t.pageTitle.letterSpacing)
        assertEquals(t.label.letterSpacing, t.clock.letterSpacing)
        assertEquals(t.body.letterSpacing, t.bodySmall.letterSpacing)
    }
}
