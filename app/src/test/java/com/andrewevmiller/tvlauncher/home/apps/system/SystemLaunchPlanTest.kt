package com.andrewevmiller.tvlauncher.home.apps.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLaunchPlanTest {

    /** Scripted executor: answers each step from [outcomes] (default: skipped) and records what was tried. */
    private class ScriptedExecutor(private val outcomes: Map<LaunchStep, StepOutcome>) : StepExecutor {
        val attempted = ArrayList<LaunchStep>()
        override fun attempt(step: LaunchStep): StepOutcome {
            attempted.add(step)
            return outcomes[step] ?: StepOutcome.Skipped("not resolvable")
        }
    }

    private fun launchWith(
        action: SystemTileAction,
        outcomes: Map<LaunchStep, StepOutcome>,
        label: String? = null,
    ): Triple<LaunchResult, ScriptedExecutor, List<String>> {
        val executor = ScriptedExecutor(outcomes)
        val log = ArrayList<String>()
        val result = runLaunchChain(
            what = label ?: "action",
            plan = planLaunch(action),
            executor = executor,
            failureMessage = failureMessage(action, label),
            log = { log.add(it) },
        )
        return Triple(result, executor, log)
    }

    private val wifi = SystemTileAction.OpenSettingsPanel(SettingsPanel.WIFI)
    private val settings = SystemTileAction.OpenSettingsPanel(SettingsPanel.MAIN)
    private val hdmi1 = SystemTileAction.PassthroughInput("mtk/.Hdmi/HW1")

    // ---- plans -------------------------------------------------------------------------------

    @Test
    fun passthroughInputTriesTifThenHisenseApps() {
        val plan = planLaunch(hdmi1)
        assertEquals(
            listOf(
                LaunchStep.PassthroughView("mtk/.Hdmi/HW1"),
                LaunchStep.LaunchablePackage("net.vidaatv.hisense.android.tv"),
                LaunchStep.LaunchablePackage("com.vt.tv.customerlauncher"),
            ),
            plan.steps,
        )
        assertEquals(1, plan.exactSteps)
    }

    @Test
    fun tunerInputTriesChannelsThenHisenseApps() {
        val plan = planLaunch(SystemTileAction.TunerInput("mtk/.Dvb/HW0"))
        assertEquals(LaunchStep.ChannelsView, plan.steps.first())
        assertEquals(HisenseTvPackages.map { LaunchStep.LaunchablePackage(it) }, plan.steps.drop(1))
        assertEquals(1, plan.exactSteps)
    }

    @Test
    fun wifiTriesWifiSettingsThenSettingsThenMainSettingsComponent() {
        val plan = planLaunch(wifi)
        assertEquals(
            listOf(
                LaunchStep.ImplicitAction("android.settings.WIFI_SETTINGS"),
                LaunchStep.ImplicitAction("android.settings.SETTINGS"),
                LaunchStep.Component("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
            ),
            plan.steps,
        )
        assertEquals(1, plan.exactSteps)
    }

    @Test
    fun settingsTriesSettingsThenMainSettingsComponent() {
        val plan = planLaunch(settings)
        assertEquals(
            listOf(
                LaunchStep.ImplicitAction("android.settings.SETTINGS"),
                LaunchStep.Component("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
            ),
            plan.steps,
        )
        assertEquals(plan.steps.size, plan.exactSteps)
    }

    @Test
    fun searchTriesAssistThenWebSearchThenKatniss() {
        val plan = planLaunch(SystemTileAction.Search)
        assertEquals(
            listOf(
                LaunchStep.ImplicitAction("android.intent.action.ASSIST"),
                LaunchStep.ImplicitAction("android.speech.action.WEB_SEARCH"),
                LaunchStep.ImplicitAction("com.google.android.katniss.action.SEARCH"),
            ),
            plan.steps,
        )
        assertEquals(plan.steps.size, plan.exactSteps)
    }

    @Test
    fun hisenseFallbackOrderIsVidaaTvAppFirst() {
        assertEquals("net.vidaatv.hisense.android.tv", HisenseTvPackages.first())
        assertEquals("com.vt.tv.customerlauncher", HisenseTvPackages.last())
    }

    // ---- running the chain -------------------------------------------------------------------

    @Test
    fun firstStepThatStartsEndsTheChain() {
        val tif = LaunchStep.PassthroughView("mtk/.Hdmi/HW1")
        val (result, executor, log) = launchWith(hdmi1, mapOf(tif to StepOutcome.Started("-> tv/.Main")), "HDMI 1")
        assertEquals(LaunchResult.Started(via = tif.describe(), approximate = false), result)
        assertEquals(listOf<LaunchStep>(tif), executor.attempted)
        assertTrue(log.any { it.contains("STARTED") && it.contains("tv/.Main") })
    }

    @Test
    fun skippedAndFailedStepsFallThroughToTheNext() {
        val steps = planLaunch(wifi).steps
        val (result, executor, log) = launchWith(
            wifi,
            mapOf(
                steps[0] to StepOutcome.Failed("ActivityNotFoundException: nope"),
                steps[1] to StepOutcome.Started("-> com.android.tv.settings/.MainSettings"),
            ),
        )
        assertEquals(LaunchResult.Started(via = steps[1].describe(), approximate = true), result)
        assertEquals(steps.take(2), executor.attempted)
        assertTrue(log.any { it.contains("[1/3]") && it.contains("FAILED") && it.contains("ActivityNotFoundException") })
        assertTrue(log.any { it.contains("[2/3]") && it.contains("STARTED") && it.contains("fallback") })
    }

    @Test
    fun hisensePackageFallbackIsApproximate() {
        val pkg = LaunchStep.LaunchablePackage("net.vidaatv.hisense.android.tv")
        val (result, _, _) = launchWith(hdmi1, mapOf(pkg to StepOutcome.Started("-> net.vidaatv.hisense.android.tv/.Main")))
        assertEquals(true, (result as LaunchResult.Started).approximate)
    }

    @Test
    fun exactSettingsFallbackIsNotApproximate() {
        val steps = planLaunch(settings).steps
        val (result, _, _) = launchWith(settings, mapOf(steps[1] to StepOutcome.Started("-> tv.settings")))
        assertEquals(false, (result as LaunchResult.Started).approximate)
    }

    @Test
    fun totalFailureReturnsFailedWithTheShortMessageAndTriesEveryStep() {
        val (result, executor, log) = launchWith(hdmi1, emptyMap(), "HDMI 1")
        assertEquals(LaunchResult.Failed("Couldn't open HDMI 1."), result)
        assertEquals(planLaunch(hdmi1).steps, executor.attempted)
        assertEquals(3, log.count { it.contains("skipped") })
        assertTrue(log.last().contains("every step failed"))
    }

    @Test
    fun anExecutorThatThrowsCountsAsAFailedStep() {
        val steps = planLaunch(SystemTileAction.Search).steps
        val executor = StepExecutor { step ->
            if (step == steps[0]) throw IllegalStateException("boom") else StepOutcome.Started("-> search")
        }
        val log = ArrayList<String>()
        val result = runLaunchChain("Search", planLaunch(SystemTileAction.Search), executor, "x", { log.add(it) })
        assertEquals(LaunchResult.Started(via = steps[1].describe(), approximate = false), result)
        assertTrue(log.any { it.contains("FAILED") && it.contains("IllegalStateException") && it.contains("boom") })
    }

    @Test
    fun theTraceNamesEveryStepInOrder() {
        val (_, _, log) = launchWith(wifi, emptyMap(), "Wi-Fi")
        assertEquals(1 + 3 + 1, log.size)
        assertTrue(log[0].contains("\"Wi-Fi\"") && log[0].contains("3 step(s)"))
        assertTrue(log[1].startsWith("[1/3]") && log[1].contains("android.settings.WIFI_SETTINGS"))
        assertTrue(log[2].startsWith("[2/3]") && log[2].contains("android.settings.SETTINGS"))
        assertTrue(log[3].startsWith("[3/3]") && log[3].contains("com.android.tv.settings/com.android.tv.settings.MainSettings"))
    }

    // ---- messages ----------------------------------------------------------------------------

    @Test
    fun failureMessageUsesTheTileLabelOrADefault() {
        assertEquals("Couldn't open HDMI 2.", failureMessage(hdmi1, "HDMI 2"))
        assertEquals("Couldn't open that input.", failureMessage(hdmi1, null))
        assertEquals("Couldn't open Wi-Fi settings.", failureMessage(wifi, null))
        assertEquals("Couldn't open Settings.", failureMessage(settings, null))
        assertEquals("Couldn't open Search.", failureMessage(SystemTileAction.Search, null))
    }

    @Test
    fun approximateNoteOnlyForInputsAndWifi() {
        assertEquals("Opened the TV app. Choose HDMI 1 there.", approximateNote(hdmi1, "HDMI 1"))
        assertEquals("Opened the TV app. Choose the input there.", approximateNote(hdmi1, null))
        assertEquals("Opened Settings. Choose Wi-Fi there.", approximateNote(wifi, "Wi-Fi"))
        assertNull(approximateNote(settings, "Settings"))
        assertNull(approximateNote(SystemTileAction.Search, "Search"))
    }

    @Test
    fun failureLinesAreShortSingleLines() {
        val all = listOf(hdmi1, wifi, settings, SystemTileAction.Search)
        for (a in all) {
            val m = failureMessage(a, null)
            assertFalse(m.contains('\n'))
            assertTrue("too long: $m", m.length <= 40)
        }
    }

    @Test
    fun logTagIsTheDocumentedOne() {
        assertEquals("FieldSystemTiles", SYSTEM_TILES_LOG_TAG)
    }
}
