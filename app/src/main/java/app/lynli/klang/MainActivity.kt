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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import app.lynli.klang.ui.KlangApp
import app.lynli.klang.ui.KlangTheme
import app.lynli.klang.ui.OnboardingScreen
import app.lynli.klang.ui.AnalyticsConsentScreen

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        app.lynli.klang.importing.DownloadNotifications.appVisible = true
    }
    override fun onStop() {
        app.lynli.klang.importing.DownloadNotifications.appVisible = false
        super.onStop()
    }
    private var importRequest by mutableIntStateOf(0)
    private var incomingMusicLink by mutableStateOf<String?>(null)

    private fun receiveImport(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { app.lynli.klang.plugins.ExtensionRuntime.openLink(it.toString()) }
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val link = shared?.let { text ->
            Regex("[A-Za-z][A-Za-z0-9+.-]*:[^\\s]+")
                .find(text)?.value?.let { runCatching { app.lynli.klang.importing.ImportLink.parse(it).url }.getOrNull() }
        }
        if (link != null || intent?.getBooleanExtra("showMusicImports", false) == true) {
            incomingMusicLink = link
            importRequest++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveImport(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveImport(intent)
        enableEdgeToEdge()
        setContent {
            val model: MusicViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            LifecycleStartEffect(Unit) { model.appOpened(); onStopOrDispose { } }
            LifecycleResumeEffect(Unit) { model.refresh(); model.refreshPluginServices(); onPauseOrDispose { } }
            val permissionPrefs = remember { getSharedPreferences("klang_permissions", MODE_PRIVATE) }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
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
                fun requestAccess(permission: String, alreadyGranted: Boolean, launch: () -> Unit) {
                    val permanentlyDenied = permissionPrefs.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)
                    if (alreadyGranted || permanentlyDenied) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else { permissionPrefs.edit().putBoolean(permission, true).apply(); launch() }
                }
                val musicAccess = { requestAccess(Manifest.permission.READ_MEDIA_AUDIO, state.permission) {
                    permissions.launch(Manifest.permission.READ_MEDIA_AUDIO)
                } }
                val notificationAccess = { requestAccess(Manifest.permission.POST_NOTIFICATIONS, state.notificationPermission) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } }
                val importFiles = { importer.launch(arrayOf("audio/*")) }
                if (!state.onboardingComplete) OnboardingScreen(state, musicAccess, notificationAccess, importFiles, model::completeOnboarding)
                else if (state.analyticsPromptPending) AnalyticsConsentScreen(model::setAnalyticsConsent)
                else if (!state.recommendedPluginsSeen) app.lynli.klang.ui.RecommendedPluginsScreen(model, complete = model::completePluginRecommendations)
                else KlangApp(state, model, onPermission = musicAccess, onImport = importFiles,
                    importRequest = importRequest, incomingMusicLink = incomingMusicLink)
            }
        }
    }
}
