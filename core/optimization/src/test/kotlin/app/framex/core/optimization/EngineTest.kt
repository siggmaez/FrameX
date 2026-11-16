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
        assertEquals(setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps), ex.applied.toSet())
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
        val full = CapabilityEvaluator.evaluate(true, true, 35)
        assertTrue(full.values.all { it == Capability.Supported })
    }

    @Test fun smartBoostRules() {
        assertEquals(OptimizationProfile.Balanced, SmartBoost.recommend(46f, 0.9f, true).profile)
        assertEquals(SmartReason.HighMemory, SmartBoost.recommend(35f, 0.9f, false).reason)
        assertEquals(OptimizationProfile.Performance, SmartBoost.recommend(35f, 0.3f, true).profile)
        assertEquals(SmartReason.Default, SmartBoost.recommend(null, null, false).reason)
    }
}
