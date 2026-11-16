package app.framex.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.ThemeMode
import app.framex.core.designsystem.FrameXCard

@Composable
fun SettingsScreen(contentPadding: PaddingValues, extra: @Composable () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FrameXCard {
                Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_theme_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        val name = stringResource(when (m) {
                            ThemeMode.System -> R.string.set_theme_system
                            ThemeMode.Light -> R.string.set_theme_light
                            ThemeMode.Dark -> R.string.set_theme_dark
                        })
                        FilterChip(selected = s.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(name) })
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item { LanguageCard() }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_interval), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_interval_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000L, 2000L, 5000L).forEach { ms ->
                        FilterChip(selected = s.monitoringIntervalMs == ms, onClick = { vm.setInterval(ms) },
                            label = { Text(stringResource(R.string.set_seconds, (ms / 1000).toInt())) })
                    }
                }
            }
        }
        item { extra() }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_history), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_history_note))
                OutlinedButton(onClick = vm::clearHistory) { Text(stringResource(R.string.set_history_clear)) }
            }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_permissions), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_permissions_note))
            }
        }
    }
}

@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(Context.LOCALE_SERVICE) as LocaleManager }
    var tag by remember { mutableStateOf(lm.applicationLocales.toLanguageTags().substringBefore('-')) }
    FrameXCard {
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleMedium)
        Note(stringResource(R.string.set_language_note))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to stringResource(R.string.set_lang_system), "en" to "English", "ru" to "Русский").forEach { (code, name) ->
                FilterChip(selected = tag == code, onClick = {
                    tag = code
                    lm.applicationLocales = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
                }, label = { Text(name) })
            }
        }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
