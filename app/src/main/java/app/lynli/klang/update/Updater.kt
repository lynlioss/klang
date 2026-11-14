package app.lynli.klang.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.lynli.klang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** A newer release published on GitHub. */
data class UpdateInfo(val version: String, val url: String)

/**
 * Reads the latest GitHub release and installs its APK. The check is silent on
 * failure: a missing release, a private repository or no network all mean "no
 * update", never an error dialog.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/lynlioss/klang/releases/latest"

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URI(RELEASES).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent",
                "Klang v${BuildConfig.VERSION_NAME} (https://github.com/lynlioss/klang)")
            if (connection.responseCode !in 200..299) return@withContext null
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.optString("tag_name").removePrefix("v")
            if (version.isBlank()) return@withContext null
            val url = apkUrl(release) ?: return@withContext null
            if (isNewerVersion(version, BuildConfig.VERSION_NAME)) UpdateInfo(version, url) else null
        } finally {
            connection.disconnect()
        }
    }

    private fun apkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        val files = (0 until assets.length()).map { assets.getJSONObject(it) }
        val asset = files.firstOrNull { it.optString("name") == "klang.apk" }
            ?: files.firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return null
        return asset.optString("browser_download_url").takeIf(String::isNotEmpty)
    }

    /**
     * Downloads the release APK into the cache directory that FileProvider shares.
     * [onProgress] receives 0f..1f as bytes arrive, so the button can fill up.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "klang-${info.version}.apk")
            val connection = URI(info.url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Klang v${BuildConfig.VERSION_NAME}")
                if (connection.responseCode !in 200..299) throw IOException("Download failed (${connection.responseCode})")
                val total = connection.contentLengthLong
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                onProgress(1f)
            } catch (error: IOException) {
                target.delete()
                throw error
            } finally {
                connection.disconnect()
            }
            if (target.length() == 0L) throw IOException("Downloaded file is empty")
            target
        }

    /**
     * Removes APKs left by a previous update. The updated app calls this on
     * launch, after the installer has consumed the file, so only the newest
     * download ever occupies storage.
     */
    suspend fun clearDownloads(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** Hands the APK to the system installer. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.Main) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
