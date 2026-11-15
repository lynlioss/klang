package app.lynli.klang.data

/** Earlier imports repeated track credits in album_artist; guest credits must not split those albums. */
internal fun albumGroupingArtist(albumArtist: String, trackArtist: String): String {
    val credits = albumArtist.trim().takeIf { it.isNotBlank() && it != "<unknown>" && it != "Unknown artist" }
    if (credits != null && !credits.equals(trackArtist.trim(), ignoreCase = true)) return credits
    return trackArtist.substringBefore(", ")
        .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "")
        .trim()
}
