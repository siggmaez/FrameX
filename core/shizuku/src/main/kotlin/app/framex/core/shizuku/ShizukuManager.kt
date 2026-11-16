package app.framex.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.framex.core.common.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

data class ShizukuStatus(
    val state: ShizukuState = ShizukuState.NotInstalled,
    val serverVersion: Int? = null,
    /** 0 = root, 2000 = shell */
    val uid: Int? = null,
    val serviceConnected: Boolean = false,
)

/** Owns the Shizuku binder connection: detection, permission, binder death, reconnection and the shell user service. */
@Singleton
class ShizukuManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
        .processNameSuffix("shell").daemon(false).version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            publish()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; binding = false; publish() }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { service = null; binding = false; refresh() }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refresh()
    }

    fun shellService(): IShellService? = service

    fun requestPermission() {
        if (_status.value.state == ShizukuState.NeedsPermission || _status.value.state == ShizukuState.PermissionDenied) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    /** Re-reads everything; also called when the app comes to the front. */
    fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrNull() else null
        val granted = alive && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val denied = alive && !granted && runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val state = ShizukuStateResolver.resolve(isInstalled(), alive, version, granted, denied)
        if (state == ShizukuState.Ready) {
            if (service == null && !binding) bind()
        } else if (service != null) {
            service = null
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
        _status.value = ShizukuStatus(state, version, if (alive) runCatching { Shizuku.getUid() }.getOrNull() else null, service != null)
    }

    private fun publish() { _status.value = _status.value.copy(serviceConnected = service != null) }

    private fun bind() {
        binding = true
        try { Shizuku.bindUserService(userServiceArgs, connection) } catch (_: Throwable) { binding = false }
    }

    private fun isInstalled() = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    } catch (_: Exception) { false }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
