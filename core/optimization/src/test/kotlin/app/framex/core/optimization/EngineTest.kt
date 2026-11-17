package app.framex.core.optimization

import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.datastore.EngineSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private class MemStorage : EngineStorage {
        var snap: EngineSnapshot? = null
        override suspend fun load() = snap
        override suspend fun save(snapshot: EngineSnapshot) { snap = snapshot }
        override suspend fun clear() { snap = null }
    }
    private class Log : OperationLogger {
        val entries = mutableListOf<Pair<OptAction, ResultStatus>>()
        override suspend fun log(profile: String, action: OptAction, status: ResultStatus, message: String) { entries += action to status }
    }
    private class FakeExec(val failApply: Boolean = false) : ActionExecutor {
        val applied = mutableListOf<OptAction>(); val restored = mutableListOf<String?>()
        override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
            applied += action
            return if (failApply) ExecResult(ResultStatus.Failed, "boom") else ExecResult(ResultStatus.Applied, "ok", "prev-$action")
        }
        override suspend fun restore(action: OptAction, token: String?): ExecResult { restored += token; return ExecResult(ResultStatus.Restored, "back") }
    }
    private fun caps(vararg unsupported: OptAction) = object : CapabilityProvider {
        override fun detect() = OptAction.entries.associateWith {
            if (it in unsupported) Capability.Unsupported(UnsupportedReason.NeedsShizuku) else Capability.Supported
        }
    }
    private fun engine(exec: FakeExec, storage: MemStorage, log: Log, c: CapabilityProvider = caps()) =
        OptimizationEngine(OptAction.entries.associateWith { exec }, c, storage, log) { 123L }

    @Test fun gamingAppliesSupportedActionsAndStoresTokens() = runTest {
        val st = MemStorage(); val ex = FakeExec()
        val rep = engine(ex, st, Log()).apply(OptimizationProfile.Gaming, ApplyRequest(stopPackages = listOf("a.b")))
        assertEquals(setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps, OptAction.LowLatencyWifi), ex.applied.toSet())
        assertTrue(rep.anyApplied)
        assertEquals("prev-DoNotDisturb", st.snap!!.tokens[OptAction.DoNotDisturb])
        assertEquals(123L, st.snap!!.appliedAtMs)
    }

    @Test fun unsupportedActionsAreSkippedNotExecuted() = runTest {
        val ex = FakeExec()
        val rep = engine(ex, MemStorage(), Log(), caps(OptAction.DoNotDisturb))
            .apply(OptimizationProfile.Gaming, ApplyRequest(stopPackages = listOf("a.b")))
        assertTrue(OptAction.DoNotDisturb !in ex.applied)
        val skipped = rep.results.first { it.action == OptAction.DoNotDisturb }
        assertEquals(ResultStatus.Skipped, skipped.status)
        assertEquals(UnsupportedReason.NeedsShizuku, skipped.reason)
    }

    @Test fun gameModeNeedsTargetGame() = runTest {
        val ex = FakeExec()
        val rep = engine(ex, MemStorage(), Log()).apply(OptimizationProfile.Performance, ApplyRequest(stopPackages = listOf("x")))
        assertEquals(UnsupportedReason.NeedsTargetGame, rep.results.first { it.action == OptAction.GameModePerformance }.reason)
        val rep2 = engine(ex, MemStorage(), Log()).apply(OptimizationProfile.Performance, ApplyRequest("com.game", listOf("x")))
        assertEquals(ResultStatus.Applied, rep2.results.first { it.action == OptAction.GameModePerformance }.status)
    }

    @Test fun failedActionIsReportedAndNothingSaved() = runTest {
        val st = MemStorage()
        val rep = engine(FakeExec(failApply = true), st, Log()).apply(OptimizationProfile.Custom, ApplyRequest(customActions = setOf(OptAction.DoNotDisturb)))
        assertEquals(ResultStatus.Failed, rep.results.single().status)
        assertNull(st.snap)
    }

    @Test fun restoreUsesSavedTokensAndClears() = runTest {
        val st = MemStorage(); val ex = FakeExec(); val e = engine(ex, st, Log())
        e.apply(OptimizationProfile.Custom, ApplyRequest(customActions = setOf(OptAction.DoNotDisturb)))
        val rep = e.restore()!!
        assertEquals(listOf<String?>("prev-DoNotDisturb"), ex.restored)
        assertEquals(ResultStatus.Restored, rep.results.single().status)
        assertNull(st.snap)
        assertNull(e.restore())
    }

    @Test fun balancedOnlyRestores() = runTest {
        val st = MemStorage(); val ex = FakeExec(); val e = engine(ex, st, Log())
        e.apply(OptimizationProfile.Custom, ApplyRequest(customActions = setOf(OptAction.DoNotDisturb)))
        ex.applied.clear()
        e.apply(OptimizationProfile.Balanced, ApplyRequest())
        assertTrue(ex.applied.isEmpty()); assertEquals(1, ex.restored.size); assertNull(st.snap)
    }

    @Test fun capabilityEvaluation() {
        val none = CapabilityEvaluator.evaluate(policyAccess = false, shellAvailable = false, sdk = 35)
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsPolicyAccess), none[OptAction.DoNotDisturb])
        assertEquals(Capability.Supported, none[OptAction.StopBackgroundApps])
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsShizuku), none[OptAction.BatterySaver])
        val old = CapabilityEvaluator.evaluate(true, true, 30)
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsAndroid12), old[OptAction.GameModePerformance])
        val full = CapabilityEvaluator.evaluate(true, true, 35, canWriteSettings = true, wifiSupported = true)
        assertTrue(full.values.all { it == Capability.Supported })
    }

    @Test fun smartBoostRules() {
        assertEquals(OptimizationProfile.Balanced, SmartBoost.recommend(46f, 0.9f, true).profile)
        assertEquals(SmartReason.HighMemory, SmartBoost.recommend(35f, 0.9f, false).reason)
        assertEquals(OptimizationProfile.Performance, SmartBoost.recommend(35f, 0.3f, true).profile)
        assertEquals(SmartReason.Default, SmartBoost.recommend(null, null, false).reason)
    }

    @Test fun newCapabilities() {
        val c = CapabilityEvaluator.evaluate(true, false, 35, canWriteSettings = false, wifiSupported = false)
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsWriteSettings), c[OptAction.ReduceBrightness])
        assertEquals(Capability.Unsupported(UnsupportedReason.NoWifi), c[OptAction.LowLatencyWifi])
    }

    private fun f(temp: Float? = 33f, charging: Boolean = false, ram: Float? = 0.5f, saver: Boolean = false, hz: Float? = 90f, max: Float? = 90f, priv: Boolean = false) =
        Findings(temp, charging, ram, saver, hz, max, priv)

    @Test fun plannerPicksProfileFromRealState() {
        assertEquals(OptimizationProfile.Gaming, GamePlanner.plan(f()).profile)
        assertEquals(OptimizationProfile.Performance, GamePlanner.plan(f(priv = true)).profile)
        assertEquals(OptimizationProfile.Gaming, GamePlanner.plan(f(ram = 0.9f, priv = true)).profile)
        assertEquals(OptimizationProfile.BatterySaver, GamePlanner.plan(f(temp = 46f, priv = true)).profile)
    }

    @Test fun plannerAdvisories() {
        assertTrue(GamePlanner.plan(f()).advisories.isEmpty())
        val a = GamePlanner.plan(f(saver = true, hz = 60f, max = 90f, temp = 46f, ram = 0.9f)).advisories
        assertTrue(Advisory.BatterySaverOn in a && Advisory.RefreshBelowMax in a && Advisory.DeviceHot in a && Advisory.HighMemory in a)
        assertEquals(listOf(Advisory.WarmWhileCharging), GamePlanner.plan(f(temp = 41f, charging = true)).advisories)
        assertTrue(GamePlanner.plan(f(hz = null, max = null, temp = null, ram = null)).advisories.isEmpty()) // unknown is never guessed
    }

    @Test fun balancedAndPlannerProfilesHaveNoSurpriseActions() {
        assertTrue(Profiles.actionsFor(OptimizationProfile.Balanced, emptySet()).isEmpty())
        assertTrue(OptAction.ReduceBrightness !in Profiles.actionsFor(OptimizationProfile.Gaming, emptySet()))
    }

    @Test fun rankingDropsNeverStopAndKeepsRecentlyUsed() {
        val a = listOf(BackgroundApp("a.one", "Zed"), BackgroundApp("b.two", "Alpha"), BackgroundApp("c.three", "Mid"), BackgroundApp("d.four", "Old"))
        val used = mapOf("a.one" to 300L, "b.two" to 100L, "c.three" to 200L) // d.four not used in 24 h
        val r = AppRanking.select(a, used, neverStop = setOf("c.three"), limit = 10)
        assertEquals(listOf("a.one", "b.two"), r.map { it.packageName }) // recent first, never-stop and idle ones dropped
        assertEquals(1, AppRanking.select(a, used, emptySet(), limit = 1).size)
    }

    @Test fun rankingWithoutUsageDataIsAlphabeticalAndLimited() {
        val a = listOf(BackgroundApp("a.one", "Zed"), BackgroundApp("b.two", "Alpha"), BackgroundApp("c.three", "Mid"))
        assertEquals(listOf("b.two", "c.three"), AppRanking.select(a, emptyMap(), setOf("a.one"), limit = 5).map { it.packageName })
        assertEquals(listOf("b.two"), AppRanking.select(a, emptyMap(), emptySet(), limit = 1).map { it.packageName })
    }

    @Test fun brawlStarsIsPacingFirstWithoutAResolutionCut() {
        assertNull(GameBoosts.plan("com.supercell.brawlstars", BoostLevel.Off))
        val strong = GameBoosts.plan("com.supercell.brawlstars", BoostLevel.Strong, listOf(60, 90))!!
        assertNull(strong.renderScale)                                       // light on the GPU: lowering resolution would only blur it
        assertTrue(OptAction.RenderScale !in strong.extraActions)
        assertEquals(setOf(OptAction.RefreshLock, OptAction.WifiLowLatencyMode, OptAction.FixedPerformance), strong.extraActions)
        assertEquals(60, strong.refreshHz)                                   // 60 FPS game: 60 Hz is even, 90 Hz would judder
        assertEquals(60, strong.spec.knownCapFps)
        assertEquals(GameStrategy.PacingFirst, strong.spec.strategy)
    }

    @Test fun heavyGpuGamesLowerResolutionAndMaxDimsTheScreen() {
        val strong = GameBoosts.plan("com.miHoYo.GenshinImpact", BoostLevel.Strong, listOf(60, 90))!!
        assertEquals(0.8f, strong.renderScale!!, 0f)
        assertTrue(OptAction.ReduceBrightness !in strong.extraActions)
        val max = GameBoosts.plan("com.miHoYo.GenshinImpact", BoostLevel.Max, listOf(60, 90))!!
        assertEquals(0.7f, max.renderScale!!, 0f)
        assertTrue(OptAction.ReduceBrightness in max.extraActions && OptAction.RenderScale in max.extraActions)
    }

    @Test fun competitiveGamesGetLowLatencyAndTheHighestRefreshWhenUncapped() {
        val p = GameBoosts.plan("com.tencent.ig", BoostLevel.Safe, listOf(60, 90, 120))!!
        assertTrue(OptAction.WifiLowLatencyMode in p.extraActions)
        assertEquals(120, p.refreshHz)                                       // no known cap: highest rate
        assertNull(GameBoosts.plan("com.roblox.client", BoostLevel.Safe)!!.refreshHz) // unknown rates: executor picks the maximum
    }

    @Test fun refreshChoiceMatchesTheGamesCap() {
        val spec = GameBoosts.specFor("com.supercell.brawlstars")!!
        assertEquals(120, GameBoosts.refreshFor(spec, listOf(60, 90, 120)))  // 120 is a multiple of 60
        assertEquals(60, GameBoosts.refreshFor(spec, listOf(60, 90)))
        assertEquals(90, GameBoosts.refreshFor(spec, listOf(90)))            // no multiple: the next rate up
        assertNull(GameBoosts.refreshFor(spec, emptyList()))
    }

    @Test fun unsupportedGamesGetNoSpecialBoostAtAnyLevel() {
        assertTrue(!GameBoosts.isSpecialized("com.some.game"))
        assertNull(GameBoosts.specFor("com.some.game"))
        BoostLevel.entries.forEach { assertNull(GameBoosts.plan("com.some.game", it, listOf(60, 90))) }
        assertTrue(GameBoosts.isSpecialized("com.roblox.client"))
    }

    @Test fun extraActionsAreAppliedOnTopOfTheProfileAndRenderScaleNeedsAGame() = runTest {
        val ex = FakeExec(); val e = engine(ex, MemStorage(), Log())
        val rep = e.apply(OptimizationProfile.Balanced, ApplyRequest(extraActions = setOf(OptAction.RefreshLock, OptAction.RenderScale), renderScale = 0.8f))
        assertEquals(ResultStatus.Applied, rep.results.first { it.action == OptAction.RefreshLock }.status)
        assertEquals(UnsupportedReason.NeedsTargetGame, rep.results.first { it.action == OptAction.RenderScale }.reason)
        val rep2 = engine(FakeExec(), MemStorage(), Log()).apply(OptimizationProfile.Balanced, ApplyRequest("com.g.a", extraActions = setOf(OptAction.RenderScale), renderScale = 0.8f))
        assertEquals(ResultStatus.Applied, rep2.results.single().status)
    }

    @Test fun newCapabilitiesNeedTheRightAndroid() {
        val old = CapabilityEvaluator.evaluate(true, true, 32)
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsAndroid13), old[OptAction.RenderScale])
        assertEquals(Capability.Supported, old[OptAction.FixedPerformance])
        assertEquals(Capability.Supported, CapabilityEvaluator.evaluate(true, true, 33)[OptAction.RenderScale])
        assertEquals(Capability.Unsupported(UnsupportedReason.NeedsShizuku), CapabilityEvaluator.evaluate(true, false, 35)[OptAction.RefreshLock])
    }
}
