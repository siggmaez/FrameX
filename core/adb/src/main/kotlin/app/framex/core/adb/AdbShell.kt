package app.framex.core.adb

import app.framex.core.optimization.GameModeLevel
import app.framex.core.optimization.PrivilegedShell
import app.framex.core.optimization.ShellResult
import app.framex.core.shizuku.ShizukuShell
import app.framex.core.shizuku.TypedShell
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Shell access through FrameX's own wireless-debugging connection. */
@Singleton
class AdbShell @Inject constructor(private val adb: AdbController) : TypedShell() {
    override fun isAvailable() = adb.isConnected()
    override suspend fun ensureReady() = adb.ensureConnected()

    override suspend fun exec(command: String?): ShellResult =
        if (command == null) ShellResult(false, "rejected: invalid input") else adb.run(command)
}

/** Uses Shizuku if it is ready, otherwise FrameX's own wireless-debugging connection. */
@Singleton
class CompositeShell @Inject constructor(private val shizuku: ShizukuShell, private val adb: AdbShell) : PrivilegedShell {
    private fun pick(): PrivilegedShell = if (shizuku.isAvailable()) shizuku else adb

    override fun isAvailable() = shizuku.isAvailable() || adb.isAvailable()
    override suspend fun ensureReady() { if (!shizuku.isAvailable()) adb.ensureReady() }
    override suspend fun setGameMode(pkg: String, level: GameModeLevel) = pick().setGameMode(pkg, level)
    override suspend fun getGameMode(pkg: String) = pick().getGameMode(pkg)
    override suspend fun forceStop(pkg: String) = pick().forceStop(pkg)
    override suspend fun forceStopMany(pkgs: List<String>) = pick().forceStopMany(pkgs)
    override suspend fun setBatterySaver(on: Boolean) = pick().setBatterySaver(on)
    override suspend fun isBatterySaverOn() = pick().isBatterySaverOn()
    override suspend fun surfaceFlingerLayers() = pick().surfaceFlingerLayers()
    override suspend fun surfaceFlingerLatency(layer: String) = pick().surfaceFlingerLatency(layer)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AdbModule {
    @Binds abstract fun shell(impl: CompositeShell): PrivilegedShell
}
