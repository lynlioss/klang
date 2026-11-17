package app.lynli.klang.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Matching is deliberately conservative: edits, live recordings and remixes keep their titles. */
internal data class RecordingIdentity(val title: String, val artist: String, val album: String,
    val durationMs: Long, val isrc: String? = null, val explicit: Boolean? = null)

internal class LocalRecordings<T>(entries: List<T>, private val recording: (T) -> RecordingIdentity) {
    private val byTitle = entries.groupBy { titleKey(recording(it).title) }
    private val byIsrc = entries.mapNotNull { entry -> recording(entry).isrc?.takeIf(String::isNotBlank)?.let { it.uppercase(Locale.ROOT) to entry } }
        .groupBy({ it.first }, { it.second })

    fun match(song: RecordingIdentity): T? {
        val title = titleKey(song.title)
        val artist = artistKey(song.artist)
        if (title.isBlank() || artist.isBlank() || artist == "unknown artist") return null
        val candidates = song.isrc?.takeIf(String::isNotBlank)?.let { byIsrc[it.uppercase(Locale.ROOT)] }
            ?: byTitle[title].orEmpty()
        return candidates.filter { candidate ->
            val local = recording(candidate)
            titleKey(local.title) == title && artistKey(local.artist) == artist &&
                (song.explicit == null || local.explicit == null || song.explicit == local.explicit) &&
                local.durationMs > 0 && song.durationMs > 0 &&
                abs(local.durationMs - song.durationMs) <= maxOf(2_500L, minOf(5_000L, song.durationMs / 100))
        }.minWithOrNull(compareBy<T> { normalize(recording(it).album) != normalize(song.album) }
            .thenBy { abs(recording(it).durationMs - song.durationMs) })
    }

    private companion object {
        fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun titleKey(value: String) = normalize(value.replace(Regex(
            "\\s*(?:\\(|\\[)(?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]+(?:\\)|\\])", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), ""))
        fun artistKey(value: String) = normalize(value.substringBefore(',').substringBefore(';')
            .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), ""))
    }
}

/** Old favorites and playlists keep resolving after a device row is merged with a catalog row. */
internal fun List<Track>.byIdentity(): Map<String, Track> = buildMap {
    for (track in this@byIdentity) for (id in track.identities) putIfAbsent(id, track)
}

internal fun Track.sameSong(other: Track?): Boolean = other != null && identities.any { it in other.identities }
