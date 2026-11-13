package app.lynli.klang

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import app.lynli.klang.ui.KlangApp
import app.lynli.klang.ui.KlangTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: MusicViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importFiles(it) }
            KlangTheme(state.theme, state.dynamicColor) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    window.isNavigationBarContrastEnforced = false
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                KlangApp(state, model, onPermission = {
                    if (state.permission) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else permissions.launch(buildList {
                        add(Manifest.permission.READ_MEDIA_AUDIO)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }, onImport = { importer.launch(arrayOf("audio/*")) })
            }
        }
    }
}
