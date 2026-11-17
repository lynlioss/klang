package app.lynli.klang.plugins

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import app.lynli.klang.playback.SourceAudioCache

/** API 1 settings bridge. Each installed source can inspect/clear only its own playback cache. */
class SourceCacheProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val provider = requireNotNull(arg)
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        val installed = PluginRegistry(context).entries.firstOrNull { entry ->
            entry.manifest.id == provider && entry.manifest.musicSource?.packageName in packages
        }
        if (installed == null) throw SecurityException("Only this source's installed settings may manage its cache.")
        val cache = SourceAudioCache.get(context)
        if (method == "cache.clear") {
            SourceStreams.get(context).invalidate()
            cache.clear(provider)
        } else require(method == "cache.stats") { "Unsupported source cache operation." }
        val stats = cache.stats(provider)
        return Bundle().apply {
            putLong("audioBytes", stats.getLong("bytes")); putInt("audioSongs", stats.getInt("songs"))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
