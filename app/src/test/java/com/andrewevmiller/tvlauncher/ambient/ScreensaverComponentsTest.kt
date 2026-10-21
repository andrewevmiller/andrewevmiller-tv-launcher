package com.andrewevmiller.tvlauncher.ambient

import com.andrewevmiller.tvlauncher.settings.Screensaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreensaverComponentsTest {
    private val own = "com.andrewevmiller.tvlauncher"

    @Test
    fun oxyfieldMapsToOurDreamService() {
        val c = ScreensaverComponents.componentFor(Screensaver.OXYFIELD, own)
        assertEquals(own, c.packageName)
        assertEquals("com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService", c.className)
        assertEquals(
            "com.andrewevmiller.tvlauncher/com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService",
            ScreensaverComponents.settingValueFor(Screensaver.OXYFIELD, own),
        )
        assertEquals("com.andrewevmiller.tvlauncher/.ambient.OxyfieldDreamService", c.flattenShort())
    }

    @Test
    fun googlePhotosMapsToGoogleTvAmbientMode() {
        val c = ScreensaverComponents.componentFor(Screensaver.GOOGLE_PHOTOS, own)
        assertEquals("com.google.android.apps.tv.dreamx", c.packageName)
        assertEquals("com.google.android.apps.tv.dreamx.service.Backdrop", c.className)
        assertEquals(
            "com.google.android.apps.tv.dreamx/com.google.android.apps.tv.dreamx.service.Backdrop",
            ScreensaverComponents.settingValueFor(Screensaver.GOOGLE_PHOTOS, own),
        )
        // The form seen in the 2026-10-21 probe.
        assertEquals("com.google.android.apps.tv.dreamx/.service.Backdrop", c.flattenShort())
    }

    @Test
    fun theOxyfieldClassNameMatchesTheKotlinPackageLayout() {
        // Guards the manifest entry: ".ambient.OxyfieldDreamService" under the app package.
        assertEquals("$own.ambient.OxyfieldDreamService", ScreensaverComponents.OXYFIELD_CLASS)
    }

    @Test
    fun parseAcceptsFullAndShortForms() {
        val full = ComponentRef.parse("com.a.b/com.a.b.C")
        val short = ComponentRef.parse("com.a.b/.C")
        assertEquals(ComponentRef("com.a.b", "com.a.b.C"), full)
        assertEquals(full, short)
        assertEquals(ComponentRef("x.y", "other.pkg.Z"), ComponentRef.parse(" x.y/other.pkg.Z "))
    }

    @Test
    fun parseRejectsMalformedValues() {
        assertNull(ComponentRef.parse(""))
        assertNull(ComponentRef.parse("nopkgslash"))
        assertNull(ComponentRef.parse("/Cls"))
        assertNull(ComponentRef.parse("pkg/"))
    }

    @Test
    fun theFirstEntryOfTheListIsTheActiveDream() {
        val value = "com.google.android.apps.tv.dreamx/.service.Backdrop, com.other/.Dream"
        assertEquals(
            ComponentRef("com.google.android.apps.tv.dreamx", "com.google.android.apps.tv.dreamx.service.Backdrop"),
            ScreensaverComponents.activeComponent(value),
        )
        assertEquals(2, ScreensaverComponents.parseList(value).size)
    }

    @Test
    fun unsetOrBlankSettingHasNoActiveDream() {
        assertNull(ScreensaverComponents.activeComponent(null))
        assertNull(ScreensaverComponents.activeComponent(""))
        assertNull(ScreensaverComponents.activeComponent("  ,  "))
    }

    @Test
    fun choiceForReadsBackWhatApplyWrote() {
        for (choice in Screensaver.values()) {
            val written = ScreensaverComponents.settingValueFor(choice, own)
            assertEquals(choice, ScreensaverComponents.choiceFor(written, own))
        }
    }

    @Test
    fun choiceForUnderstandsTheShortFormTheProbeSaw() {
        assertEquals(
            Screensaver.GOOGLE_PHOTOS,
            ScreensaverComponents.choiceFor("com.google.android.apps.tv.dreamx/.service.Backdrop", own),
        )
        assertEquals(
            Screensaver.OXYFIELD,
            ScreensaverComponents.choiceFor("com.andrewevmiller.tvlauncher/.ambient.OxyfieldDreamService", own),
        )
    }

    @Test
    fun choiceForIsNullForForeignOrMissingDreams() {
        assertNull(ScreensaverComponents.choiceFor(null, own))
        assertNull(ScreensaverComponents.choiceFor("com.android.deskclock/.Screensaver", own))
        assertNull(ScreensaverComponents.choiceFor("garbage", own))
    }
}
