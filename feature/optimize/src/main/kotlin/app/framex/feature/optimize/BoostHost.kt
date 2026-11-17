package app.framex.feature.optimize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.optimization.BoostProgress
import app.framex.core.optimization.BoostRunner
import app.framex.core.optimization.ItemStatus
import app.framex.core.optimization.StopMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BoostHostViewModel @Inject constructor(private val runner: BoostRunner) : ViewModel() {
    val progress = runner.progress
    fun cancel() = runner.cancel()
    fun dismiss() = runner.dismiss()
}

/** Full-screen boost progress, shown wherever a boost runs. Every row reflects a real stop call. */
@Composable
fun BoostHost(vm: BoostHostViewModel = hiltViewModel()) {
    val p by vm.progress.collectAsStateWithLifecycle()
    p?.let { BoostScreen(it, vm::cancel, vm::dismiss) }
}

@Composable
private fun BoostScreen(p: BoostProgress, onCancel: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (p.finished) R.string.boost_done_title else R.string.boost_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(when (p.method) {
                    StopMethod.ShizukuForceStop -> R.string.boost_m_shizuku
                    StopMethod.AccessibilityForceStop -> R.string.boost_m_accessibility
                    StopMethod.BackgroundKill -> R.string.boost_m_background
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { if (p.items.isEmpty()) 0f else p.done / p.items.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.boost_count, p.done, p.items.size), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(p.items, key = { it.packageName }) { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            Text(stringResource(when (item.status) {
                                ItemStatus.Pending -> R.string.boost_s_pending
                                ItemStatus.Working -> R.string.boost_s_working
                                ItemStatus.Stopped -> R.string.boost_s_stopped
                                ItemStatus.NotRunning -> R.string.boost_s_not_running
                                ItemStatus.Requested -> R.string.boost_s_requested
                                ItemStatus.Failed -> R.string.boost_s_failed
                            }), style = MaterialTheme.typography.bodyMedium,
                                color = if (item.status == ItemStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (p.finished) {
                    Text(stringResource(R.string.boost_summary, p.stopped, p.items.size, (p.freedMb ?: 0L).toInt()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.boost_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.boost_close)) }
                } else {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.opt_cancel)) }
                }
            }
        }
    }
}
