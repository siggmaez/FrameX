package app.framex.core.optimization

import app.framex.core.common.OptAction
import app.framex.core.datastore.EngineSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OptimizationEngine(
    private val executors: Map<OptAction, ActionExecutor>,
    private val capabilities: CapabilityProvider,
    private val storage: EngineStorage,
    private val logger: OperationLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Applies [profile]. Anything applied earlier is restored first so the saved "previous" values stay the originals. */
    suspend fun apply(profile: app.framex.core.common.OptimizationProfile, request: ApplyRequest): EngineReport = mutex.withLock {
        val results = mutableListOf<ActionResult>()
        storage.load()?.let { results += restoreInternal(it) }
        val caps = capabilities.detect()
        val tokens = LinkedHashMap<OptAction, String?>()
        for (action in Profiles.actionsFor(profile, request.customActions) + request.extraActions) {
            val cap = caps[action] ?: Capability.Unsupported(UnsupportedReason.Unknown)
            val result = when {
                cap is Capability.Unsupported -> ActionResult(action, ResultStatus.Skipped, "", cap.reason)
                action.needsTarget() && request.targetPackage == null ->
                    ActionResult(action, ResultStatus.Skipped, "", UnsupportedReason.NeedsTargetGame)
                action == OptAction.StopBackgroundApps && request.stopPackages.isEmpty() ->
                    ActionResult(action, ResultStatus.Skipped, "", null)
                else -> runApply(action, request, tokens)
            }
            results += result
            logger.log(profile.name, action, result.status, result.message.ifEmpty { result.reason?.name.orEmpty() })
        }
        if (results.any { it.status == ResultStatus.Applied && it.action in tokens.keys }) {
            storage.save(EngineSnapshot(profile, clock(), tokens, request.targetPackage))
        } else storage.clear()
        EngineReport(profile, results)
    }

    /**
     * If FrameX stopped while the screen resolution was lowered, put everything back. A normal profile is left alone,
     * so this never undoes something you applied on purpose.
     */
    suspend fun restoreIfHard(): EngineReport? = mutex.withLock {
        val snap = storage.load() ?: return null
        if (OptAction.ScreenResolution !in snap.tokens) return null
        EngineReport(snap.profile, restoreInternal(snap))
    }

    /** Puts every changed setting back to what it was before FrameX applied a profile. */
    suspend fun restore(): EngineReport? = mutex.withLock {
        val snap = storage.load() ?: return null
        EngineReport(snap.profile, restoreInternal(snap))
    }

    private suspend fun runApply(action: OptAction, request: ApplyRequest, tokens: MutableMap<OptAction, String?>): ActionResult {
        val exec = executors[action] ?: return ActionResult(action, ResultStatus.Failed, "No executor", UnsupportedReason.Unknown)
        return try {
            val r = exec.apply(action, request)
            if (r.status == ResultStatus.Applied) tokens[action] = r.token
            ActionResult(action, r.status, r.message)
        } catch (e: Exception) {
            ActionResult(action, ResultStatus.Failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun restoreInternal(snap: EngineSnapshot): List<ActionResult> {
        val out = mutableListOf<ActionResult>()
        for ((action, token) in snap.tokens) {
            val r = try {
                executors[action]?.restore(action, token) ?: ExecResult(ResultStatus.Failed, "No executor")
            } catch (e: Exception) {
                ExecResult(ResultStatus.Failed, e.message ?: e.javaClass.simpleName)
            }
            out += ActionResult(action, r.status, r.message)
            logger.log(snap.profile.name, action, r.status, r.message)
        }
        storage.clear()
        return out
    }

    private fun OptAction.needsTarget() = this == OptAction.GameModePerformance || this == OptAction.GameModeBattery || this == OptAction.RenderScale
}
