package app.lynli.klang.importing

import app.lynli.klang.data.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Duplicate checks include hidden/device tracks and require known, close durations. */
internal fun normalizedRecording(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("\\s*(?:\\(|\\[)(?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]+(?:\\)|\\])", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*(?:\\(|\\[)(?:official\\s+)?(?:audio|video|music video)(?:\\)|\\])", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
internal fun sameRecording(title: String, artist: String, duration: Long, otherTitle: String, otherArtist: String, otherDuration: Long): Boolean =
    normalizedRecording(title).isNotBlank() && normalizedRecording(artist).isNotBlank() &&
        normalizedRecording(artist) != "unknown artist" && duration > 0 && otherDuration > 0 &&
        normalizedRecording(title) == normalizedRecording(otherTitle) && normalizedRecording(artist) == normalizedRecording(otherArtist) &&
        abs(duration - otherDuration) <= maxOf(3_000L, (duration * .02).toLong())

internal fun duplicateRecording(song: ImportSong, tracks: List<Track>): Track? = tracks.firstOrNull {
    sameRecording(song.title, song.artist, song.durationMs, it.title, it.artist, it.durationMs)
}
