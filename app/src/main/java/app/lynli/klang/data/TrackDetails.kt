package app.lynli.klang.data

/**
 * Technical facts about one audio file. Anything the platform cannot report stays null so the
 * properties sheet can omit it instead of guessing.
 */
data class TrackDetails(
    val durationMs: Long,
    val format: String?,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val sizeBytes: Long?,
    val location: String?,
) {
    val quality: String?
        get() {
            if (mimeType?.startsWith("audio/") == true && mimeType in LOSSLESS_MIME) return "Lossless"
            // Bitrate alone cannot measure audible quality across codecs or source masters.
            return if (format in setOf("OPUS", "MP3", "AAC", "M4A", "OGG") ||
                mimeType in setOf("audio/opus", "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg")) "Lossy" else null
        }

    private companion object {
        val LOSSLESS_MIME = setOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
            "audio/aiff", "audio/x-aiff", "audio/alac", "audio/x-alac")
    }
}

internal fun formatLabel(mimeType: String?, filename: String?): String? {
    val mime = mimeType?.lowercase()
    // Ogg is the container; the .opus suffix identifies the audio codec.
    if (mime in listOf(null, "audio/ogg", "application/ogg", "audio/opus") &&
        filename?.substringAfterLast('.')?.equals("opus", true) == true) return "OPUS"
    val fromMime = when (mime) {
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "M4A"
        "audio/aac", "audio/aac-adts" -> "AAC"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/ogg", "application/ogg" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
        "audio/aiff", "audio/x-aiff" -> "AIFF"
        "audio/alac", "audio/x-alac" -> "ALAC"
        else -> null
    }
    if (fromMime != null) return fromMime
    val extension = filename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 }
    return extension?.uppercase()
}
