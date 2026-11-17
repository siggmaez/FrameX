package app.framex.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.adb.AdbState
import app.framex.core.designsystem.FrameXCard

/** Shell access without the Shizuku app: FrameX connects to this phone's own wireless debugging. */
@Composable
fun AdbCard(vm: SettingsViewModel) {
    val st by vm.adbStatus.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var port by remember { mutableStateOf("") }
    FrameXCard {
        Text(stringResource(R.string.adb_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (st.state) {
            AdbState.Disconnected -> R.string.adb_s_disconnected
            AdbState.Connecting -> R.string.adb_s_connecting
            AdbState.Connected -> R.string.adb_s_connected
            AdbState.NeedsPairing -> R.string.adb_s_needs_pairing
            AdbState.NotFound -> R.string.adb_s_not_found
            AdbState.Failed -> R.string.adb_s_failed
        }), style = MaterialTheme.typography.titleLarge)
        st.detail?.takeIf { st.state == AdbState.Failed }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.adb_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (st.state != AdbState.Connected) {
            Text(stringResource(R.string.adb_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.state == AdbState.Connected) {
                OutlinedButton(onClick = vm::adbDisconnect) { Text(stringResource(R.string.adb_disconnect)) }
            } else {
                Button(onClick = {
                    vm.adbStartPairing()
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.adb_pair)) }
                OutlinedButton(onClick = { vm.adbConnect() }, enabled = st.state != AdbState.Connecting) { Text(stringResource(R.string.adb_connect)) }
            }
        }
        if (st.state != AdbState.Connected) {
            Text(stringResource(R.string.adb_port_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true,
                    label = { Text(stringResource(R.string.adb_port)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { port.toIntOrNull()?.let { vm.adbConnect(it) } }, enabled = port.length in 4..5) { Text(stringResource(R.string.adb_connect_port)) }
            }
        }
    }
}
