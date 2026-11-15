package app.lynli.klang.importing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.lynli.klang.MainActivity
import app.lynli.klang.R

/** Completion alerts are separate from Android's required, silent foreground-service notice. */
internal object DownloadNotifications {
    @Volatile var appVisible: Boolean = false

    fun finished(context: Context, batch: ImportBatch) {
        if (batch.pending) return
        val prefs = context.getSharedPreferences("download_notifications", Context.MODE_PRIVATE)
        val key = "finished-${batch.id}"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!shouldNotifyDownloadCompletion(appVisible, allowed, batch.complete > 0 || batch.warnings)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("download_complete", "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, batch.id.hashCode(), Intent(context, MainActivity::class.java)
            .putExtra("showMusicImports", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val duplicates = batch.entries.count { it.phase == ImportPhase.DUPLICATE }
        val text = buildString {
            append("${batch.complete} ${if (batch.complete == 1) "song" else "songs"} downloaded")
            if (duplicates > 0) append(" · $duplicates already on this device")
            if (batch.failed > 0) append(" · ${batch.failed} need attention")
        }
        manager.notify(batch.id.hashCode(), NotificationCompat.Builder(context, "download_complete")
            .setSmallIcon(R.drawable.ic_import_notification).setContentTitle(batch.title)
            .setContentText(text).setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
}
internal fun shouldNotifyDownloadCompletion(appVisible: Boolean, permissionGranted: Boolean, hasOutcome: Boolean): Boolean =
    !appVisible && permissionGranted && hasOutcome
