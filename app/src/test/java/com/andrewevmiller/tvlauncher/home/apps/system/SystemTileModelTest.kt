package com.andrewevmiller.tvlauncher.home.apps.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTileModelTest {

    private fun hdmi(
        port: Int?,
        id: String = "tv/.Hdmi/HW$port",
        custom: String? = null,
        system: String? = null,
    ) = InputDescriptor(id = id, type = TvInputType.HDMI, customLabel = custom, hdmiPort = port, systemLabel = system)

    private fun input(type: Int, id: String, custom: String? = null, system: String? = null) =
        InputDescriptor(id = id, type = type, customLabel = custom, hdmiPort = null, systemLabel = system)

    private fun labels(inputs: List<InputDescriptor>) = buildSystemTiles(inputs).map { it.label }

    private val fixedLabels = listOf("Wi-Fi", "Search", "Settings")

    // ---- empty and fixed tiles ---------------------------------------------------------------

    @Test
    fun emptyListYieldsOnlyTheThreeFixedTiles() {
        val tiles = buildSystemTiles(emptyList())
        assertEquals(listOf("wifi", "search", "settings"), tiles.map { it.key })
        assertEquals(fixedLabels, tiles.map { it.label })
        assertEquals(
            listOf(SystemIconKey.WIFI, SystemIconKey.SEARCH, SystemIconKey.GEAR),
            tiles.map { it.icon },
        )
        assertEquals(
            listOf(
                SystemTileAction.OpenSettingsPanel(SettingsPanel.WIFI),
                SystemTileAction.Search,
                SystemTileAction.OpenSettingsPanel(SettingsPanel.MAIN),
            ),
            tiles.map { it.action },
        )
    }

    @Test
    fun fixedTilesAreAlwaysLastInOrder() {
        val l = labels(listOf(hdmi(2), input(TvInputType.VGA, "vga"), input(TvInputType.TUNER, "tuner"), hdmi(1)))
        assertEquals(fixedLabels, l.takeLast(3))
    }

    // ---- ordering ----------------------------------------------------------------------------

    @Test
    fun hdmiInputsAreOrderedByPortNumber() {
        assertEquals(
            listOf("HDMI 1", "HDMI 2", "HDMI 3") + fixedLabels,
            labels(listOf(hdmi(3), hdmi(1), hdmi(2))),
        )
    }

    @Test
    fun orderIsHdmiThenOtherConnectorsThenTunerThenFixed() {
        val inputs = listOf(
            input(TvInputType.TUNER, "tuner", system = "Antenna"),
            input(TvInputType.VGA, "vga"),
            hdmi(2),
            input(TvInputType.COMPONENT, "comp"),
            input(TvInputType.COMPOSITE, "av"),
            hdmi(1),
        )
        assertEquals(
            listOf("HDMI 1", "HDMI 2", "AV", "Component", "VGA", "Antenna") + fixedLabels,
            labels(inputs),
        )
    }

    @Test
    fun otherConnectorsFollowCompositeSvideoScartComponentVgaDviDisplayPort() {
        val inputs = listOf(
            input(TvInputType.DISPLAY_PORT, "dp"),
            input(TvInputType.DVI, "dvi"),
            input(TvInputType.VGA, "vga"),
            input(TvInputType.COMPONENT, "component"),
            input(TvInputType.SCART, "scart"),
            input(TvInputType.SVIDEO, "svideo"),
            input(TvInputType.COMPOSITE, "composite"),
        )
        assertEquals(
            listOf("AV", "S-Video", "SCART", "Component", "VGA", "DVI", "DisplayPort") + fixedLabels,
            labels(inputs),
        )
    }

    @Test
    fun hdmiWithUnknownPortComesAfterKnownPortsInGivenOrder() {
        val inputs = listOf(
            hdmi(null, id = "a", system = "Console"),
            hdmi(2),
            hdmi(null, id = "b", system = "Streamer"),
            hdmi(1),
        )
        assertEquals(listOf("HDMI 1", "HDMI 2", "Console", "Streamer") + fixedLabels, labels(inputs))
    }

    @Test
    fun equalTypesKeepTheOrderGiven() {
        val inputs = listOf(
            input(TvInputType.COMPOSITE, "av2", system = "AV 2"),
            input(TvInputType.COMPOSITE, "av1", system = "AV 1"),
        )
        assertEquals(listOf("AV 2", "AV 1") + fixedLabels, labels(inputs))
    }

    @Test
    fun unknownConnectorTypeSitsBetweenDisplayPortAndTuner() {
        val inputs = listOf(
            input(TvInputType.TUNER, "tuner"),
            input(1500, "future"),
            input(TvInputType.DISPLAY_PORT, "dp"),
        )
        assertEquals(listOf("DisplayPort", "Input", "Live TV") + fixedLabels, labels(inputs))
    }

    // ---- labels ------------------------------------------------------------------------------

    @Test
    fun hdmiLabelIsHdmiPortWhenUnnamed() {
        assertEquals("HDMI 4", inputLabel(hdmi(4)))
    }

    @Test
    fun customLabelWinsOverEverything() {
        assertEquals("Apple TV", inputLabel(hdmi(1, custom = "Apple TV", system = "HDMI 1")))
        assertEquals("Retro box", inputLabel(input(TvInputType.COMPOSITE, "av", custom = "Retro box", system = "AV")))
    }

    @Test
    fun customLabelIsTrimmedAndBlankMeansUnnamed() {
        assertEquals("PS5", inputLabel(hdmi(2, custom = "  PS5 ")))
        assertEquals("HDMI 2", inputLabel(hdmi(2, custom = "   ")))
        assertEquals("HDMI 2", inputLabel(hdmi(2, custom = "")))
    }

    @Test
    fun nonPositivePortCountsAsUnknown() {
        assertEquals("HDMI", inputLabel(hdmi(0, id = "zero")))
        assertEquals("HDMI", inputLabel(hdmi(-1, id = "neg")))
        assertEquals("ARC", inputLabel(hdmi(0, id = "zero", system = "ARC")))
    }

    @Test
    fun nonHdmiLabelsUseSystemLabelThenGenericName() {
        assertEquals("AV 1", inputLabel(input(TvInputType.COMPOSITE, "x", system = "AV 1")))
        assertEquals("AV", inputLabel(input(TvInputType.COMPOSITE, "x")))
        assertEquals("AV", inputLabel(input(TvInputType.COMPOSITE, "x", system = " ")))
        assertEquals("Component", inputLabel(input(TvInputType.COMPONENT, "x")))
        assertEquals("VGA", inputLabel(input(TvInputType.VGA, "x")))
        assertEquals("Live TV", inputLabel(input(TvInputType.TUNER, "x")))
    }

    // ---- actions and keys --------------------------------------------------------------------

    @Test
    fun inputTilesCarryTheInputIdAndPortIcon() {
        val tile = buildSystemTiles(listOf(hdmi(1, id = "mtk/.Hdmi/HW5"))).first()
        assertEquals("input:mtk/.Hdmi/HW5", tile.key)
        assertEquals(SystemIconKey.PORT, tile.icon)
        assertEquals(SystemTileAction.PassthroughInput("mtk/.Hdmi/HW5"), tile.action)
    }

    @Test
    fun tunerTileUsesTunerAction() {
        val tile = buildSystemTiles(listOf(input(TvInputType.TUNER, "mtk/.Dvb/HW0"))).first()
        assertEquals(SystemTileAction.TunerInput("mtk/.Dvb/HW0"), tile.action)
    }

    // ---- which inputs are physical -----------------------------------------------------------

    @Test
    fun hiddenInputsAreDropped() {
        val hidden = hdmi(1).copy(hidden = true)
        assertEquals(listOf("HDMI 2") + fixedLabels, labels(listOf(hidden, hdmi(2))))
        assertEquals(InputDrop.HIDDEN, inputDropReason(hidden))
    }

    @Test
    fun childInputsAreDropped() {
        val cecChild = InputDescriptor(
            id = "mtk/.Hdmi/HDMI100003", type = TvInputType.HDMI, customLabel = "Chromecast",
            hdmiPort = 1, hasParent = true,
        )
        val other = InputDescriptor(
            id = "x/.Child", type = TvInputType.OTHER, customLabel = null, hdmiPort = null, hasParent = true,
        )
        assertEquals(listOf("HDMI 1") + fixedLabels, labels(listOf(hdmi(1), cecChild, other)))
        assertEquals(InputDrop.HAS_PARENT, inputDropReason(cecChild))
    }

    @Test
    fun typeOtherAndUnrecognisedTypesAreDropped() {
        val other = input(TvInputType.OTHER, "app/.Virtual", system = "Some App")
        val weird = input(77, "weird")
        assertEquals(fixedLabels, labels(listOf(other, weird)))
        assertEquals(InputDrop.NOT_PHYSICAL, inputDropReason(other))
        assertEquals(InputDrop.NOT_PHYSICAL, inputDropReason(weird))
    }

    @Test
    fun systemTunerIsKeptButUserInstalledTunerIsDropped() {
        val system = input(TvInputType.TUNER, "mtk/.Dvb/HW0", system = "Antenna")
        val channelApp = input(TvInputType.TUNER, "pluto/.Tv", system = "Pluto TV").copy(fromSystemApp = false)
        assertEquals(listOf("Antenna") + fixedLabels, labels(listOf(system, channelApp)))
        assertNull(inputDropReason(system))
        assertEquals(InputDrop.THIRD_PARTY_TUNER, inputDropReason(channelApp))
    }

    @Test
    fun hiddenWinsOverOtherReasons() {
        val d = input(TvInputType.OTHER, "x").copy(hidden = true, hasParent = true)
        assertEquals(InputDrop.HIDDEN, inputDropReason(d))
    }

    // ---- duplicates --------------------------------------------------------------------------

    @Test
    fun sameIdTwiceYieldsOneTile() {
        val tiles = buildSystemTiles(listOf(hdmi(1, id = "dup"), hdmi(1, id = "dup")))
        assertEquals(listOf("HDMI 1") + fixedLabels, tiles.map { it.label })
        val selection = selectPhysicalInputs(listOf(hdmi(1, id = "dup"), hdmi(2, id = "dup")))
        assertEquals(1, selection.kept.size)
        assertEquals(InputDrop.DUPLICATE_ID, selection.dropped.single().second)
    }

    @Test
    fun twoInputsClaimingTheSameHdmiPortYieldOneTile() {
        val first = hdmi(2, id = "svc-a/HW2")
        val second = hdmi(2, id = "svc-b/HW9", custom = "Other")
        val selection = selectPhysicalInputs(listOf(first, second))
        assertEquals(listOf(first), selection.kept)
        assertEquals(listOf(second to InputDrop.DUPLICATE_PORT), selection.dropped)
    }

    @Test
    fun unknownPortHdmiInputsAreNotCollapsedTogether() {
        val inputs = listOf(hdmi(null, id = "a", system = "One"), hdmi(null, id = "b", system = "Two"))
        assertEquals(listOf("One", "Two") + fixedLabels, labels(inputs))
    }

    @Test
    fun allTileKeysAreUnique() {
        val tiles = buildSystemTiles(
            listOf(hdmi(1), hdmi(1, id = "other"), hdmi(2), input(TvInputType.VGA, "v"), input(TvInputType.VGA, "v")),
        )
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size)
    }

    @Test
    fun inputOrderDoesNotChangeTheResultForDistinctPorts() {
        val a = listOf(hdmi(1), hdmi(2), hdmi(3))
        assertEquals(buildSystemTiles(a), buildSystemTiles(a.reversed()))
    }

    // ---- helpers for the Android layer -------------------------------------------------------

    @Test
    fun parseHdmiPortReadsNumberedHdmiLabels() {
        assertEquals(1, parseHdmiPort("HDMI 1"))
        assertEquals(2, parseHdmiPort("HDMI2"))
        assertEquals(3, parseHdmiPort("hdmi-3"))
        assertEquals(1, parseHdmiPort("HDMI 1 (ARC)"))
        assertEquals(12, parseHdmiPort("HDMI 12"))
    }

    @Test
    fun parseHdmiPortRejectsEverythingElse() {
        assertNull(parseHdmiPort(null))
        assertNull(parseHdmiPort(""))
        assertNull(parseHdmiPort("Chromecast"))
        assertNull(parseHdmiPort("HDMI"))
        assertNull(parseHdmiPort("HDMI 0"))
        assertNull(parseHdmiPort("HDMI 123"))
        assertNull(parseHdmiPort("AV 1"))
    }

    @Test
    fun resolveHdmiPortPrefersTheFramework() {
        assertEquals(3, resolveHdmiPort(3, "HDMI 1"))
        assertEquals(1, resolveHdmiPort(null, "HDMI 1"))
        assertEquals(1, resolveHdmiPort(0, "HDMI 1"))
        assertNull(resolveHdmiPort(null, "Chromecast"))
        assertNull(resolveHdmiPort(null, null))
    }

    @Test
    fun cecChildIdsAreRecognised() {
        assertTrue(looksLikeCecChildId("com.mediatek.tvinput/.hdmi.HdmiInputService/HDMI100003"))
        assertTrue(looksLikeCecChildId("a.b/.C/HDMI0000ff"))
        assertFalse(looksLikeCecChildId("com.mediatek.tvinput/.hdmi.HdmiInputService/HW5"))
        assertFalse(looksLikeCecChildId("com.mediatek.dtv.tvinput.dvbtuner/.DvbTvInputService"))
        assertFalse(looksLikeCecChildId("a.b/.C/HDMI12"))
        assertFalse(looksLikeCecChildId("a.b/.C/HDMI1000030"))
    }

    // ---- constants and diagnostics -----------------------------------------------------------

    @Test
    fun inputTypeConstantsMatchTheFramework() {
        // android.media.tv.TvInputInfo; the same values are checked against the real class on the owner's machine.
        assertEquals(1000, TvInputType.OTHER)
        assertEquals(0, TvInputType.TUNER)
        assertEquals(1001, TvInputType.COMPOSITE)
        assertEquals(1002, TvInputType.SVIDEO)
        assertEquals(1003, TvInputType.SCART)
        assertEquals(1004, TvInputType.COMPONENT)
        assertEquals(1005, TvInputType.VGA)
        assertEquals(1006, TvInputType.DVI)
        assertEquals(1007, TvInputType.HDMI)
        assertEquals(1008, TvInputType.DISPLAY_PORT)
    }

    @Test
    fun describeSelectionExplainsKeptAndDroppedInputs() {
        val lines = describeSelection(
            selectPhysicalInputs(
                listOf(hdmi(1, custom = "Apple TV"), hdmi(2).copy(hidden = true), input(TvInputType.OTHER, "app/.X")),
            ),
        )
        assertEquals("tv inputs: 1 kept, 2 dropped", lines.first())
        assertTrue(lines.any { it.contains("KEEP") && it.contains("label='Apple TV'") })
        assertTrue(lines.any { it.contains("DROP  HIDDEN") })
        assertTrue(lines.any { it.contains("DROP  NOT_PHYSICAL") && it.contains("type=0(OTHER)") })
    }
}
