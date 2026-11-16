package app.framex.core.optimization

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.framex.core.common.OptAction
import app.framex.core.database.OptimizationLogRepository
import app.framex.core.datastore.EngineSnapshot
import app.framex.core.datastore.EngineStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: PrivilegedShell,
) : CapabilityProvider {
    override fun detect(): Map<OptAction, Capability> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return CapabilityEvaluator.evaluate(nm.isNotificationPolicyAccessGranted, shell.isAvailable(), Build.VERSION.SDK_INT)
    }
}

class StoreStorage(private val store: EngineStateStore) : EngineStorage {
    override suspend fun load() = store.load()
    override suspend fun save(snapshot: EngineSnapshot) = store.save(snapshot)
    override suspend fun clear() = store.clear()
}

class RoomLogger(private val repo: OptimizationLogRepository) : OperationLogger {
    override suspend fun log(profile: String, action: OptAction, status: ResultStatus, message: String) =
        repo.log(profile, action.name, status.name, message)
}

/** Do Not Disturb: reversible, needs the user to grant "Do Not Disturb access" to FrameX. */
class DndExecutor(private val context: Context) : ActionExecutor {
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        if (!nm.isNotificationPolicyAccessGranted) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_dnd_no_access))
        val previous = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val ok = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        return if (ok) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_dnd_on), previous.toString())
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_dnd_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val prev = token?.toIntOrNull() ?: return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        nm.setInterruptionFilter(prev)
        val ok = nm.currentInterruptionFilter == prev
        return ExecResult(if (ok) ResultStatus.Restored else ResultStatus.Failed,
            context.getString(if (ok) R.string.opt_dnd_restored else R.string.opt_dnd_not_confirmed))
    }
}

/** Stops background processes of apps the user confirmed. Android does not confirm per-app, so only the memory change is reported. */
class KillAppsExecutor(private val context: Context) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = avail(am)
        request.stopPackages.forEach { am.killBackgroundProcesses(it) }
        delay(800)
        val deltaMb = (avail(am) - before) / 1_048_576
        return ExecResult(ResultStatus.Applied, context.getString(R.string.opt_kill_done, request.stopPackages.size, deltaMb.toInt()))
    }

    override suspend fun restore(action: OptAction, token: String?) =
        ExecResult(ResultStatus.Restored, context.getString(R.string.opt_kill_nothing))

    private fun avail(am: ActivityManager) = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
}

/** Game mode through the privileged shell (Shizuku). Read back to validate. */
class GameModeExecutor(private val context: Context, private val shell: PrivilegedShell) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val pkg = request.targetPackage ?: return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_game))
        val level = if (action == OptAction.GameModePerformance) GameModeLevel.Performance else GameModeLevel.Battery
        val previous = shell.getGameMode(pkg)
        val r = shell.setGameMode(pkg, level)
        if (!r.ok) return ExecResult(ResultStatus.Failed, r.output.take(120))
        val now = shell.getGameMode(pkg)
        return if (now != null && now.contains(level.cmdName, ignoreCase = true))
            ExecResult(ResultStatus.Applied, context.getString(R.string.opt_game_mode_set, level.cmdName), "$pkg|${previous ?: ""}")
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val pkg = token?.substringBefore('|').orEmpty()
        val prev = token?.substringAfter('|', "").orEmpty()
        if (pkg.isEmpty()) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        val level = GameModeLevel.entries.firstOrNull { prev.contains(it.cmdName, ignoreCase = true) } ?: GameModeLevel.Standard
        val r = shell.setGameMode(pkg, level)
        return ExecResult(if (r.ok) ResultStatus.Restored else ResultStatus.Failed, if (r.ok) context.getString(R.string.opt_game_mode_restored) else r.output.take(120))
    }
}

class BatterySaverExecutor(private val context: Context, private val shell: PrivilegedShell) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val previous = shell.isBatterySaverOn()
        val r = shell.setBatterySaver(true)
        if (!r.ok) return ExecResult(ResultStatus.Failed, r.output.take(120))
        return if (shell.isBatterySaverOn() == true) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_saver_on), (previous ?: false).toString())
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val r = shell.setBatterySaver(token == "true")
        return ExecResult(if (r.ok) ResultStatus.Restored else ResultStatus.Failed, if (r.ok) context.getString(R.string.opt_saver_restored) else r.output.take(120))
    }
}


@Module
@InstallIn(SingletonComponent::class)
object OptimizationModule {
    @Provides @Singleton
    fun capabilities(impl: AndroidCapabilityProvider): CapabilityProvider = impl

    @Provides @Singleton
    fun engine(
        @ApplicationContext context: Context,
        shell: PrivilegedShell,
        capabilities: CapabilityProvider,
        store: EngineStateStore,
        log: OptimizationLogRepository,
    ): OptimizationEngine {
        val game = GameModeExecutor(context, shell)
        val executors = mapOf(
            OptAction.DoNotDisturb to DndExecutor(context),
            OptAction.StopBackgroundApps to KillAppsExecutor(context),
            OptAction.GameModePerformance to game,
            OptAction.GameModeBattery to game,
            OptAction.BatterySaver to BatterySaverExecutor(context, shell),
        )
        return OptimizationEngine(executors, capabilities, StoreStorage(store), RoomLogger(log))
    }
}
