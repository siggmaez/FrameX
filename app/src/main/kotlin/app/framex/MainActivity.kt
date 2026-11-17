package app.framex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.designsystem.FrameXTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var adb: app.framex.core.adb.AdbController

    override fun onStart() {
        super.onStart()
        adb.ensureConnectedAsync() // paired once = never again, even after the app was closed or the phone restarted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            FrameXTheme(mode) { FrameXApp() }
        }
    }
}
