package app.lynli.klang.lyrics

import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val wordTimestamp = Regex("<(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?>")
    private fun millis(match: MatchResult): Long = match.groupValues[1].toLong() * 60_000 +
        match.groupValues[2].toLong() * 1000 + match.groupValues[3].padEnd(3, '0').take(3).toLong()

    fun record(json: JSONObject, durationMs: Long): LyricsDocument {
        val yaml = json.optString("lyricsfile").takeUnless { it.isBlank() || it == "null" }
        val fromYaml = yaml?.let { runCatching { lyricsfile(it, durationMs) }.getOrNull() }
        val plain = json.optString("plainLyrics").takeUnless { it == "null" }.orEmpty().ifBlank { fromYaml?.plain.orEmpty() }
        val lines = fromYaml?.lines?.takeIf { it.isNotEmpty() } ?: lrc(json.optString("syncedLyrics"), durationMs)
        return LyricsDocument("lrclib", plain.ifBlank { lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text } },
            lines, json.optBoolean("instrumental") || fromYaml?.instrumental == true)
    }

    fun lyricsfile(source: String, durationMs: Long): LyricsDocument {
        val options = LoaderOptions().apply { codePointLimit = 2_000_000; maxAliasesForCollections = 0; nestingDepthLimit = 30 }
        val root = Yaml(SafeConstructor(options)).load<Any>(source) as? Map<*, *> ?: error("Invalid Lyricsfile")
        val metadata = root["metadata"] as? Map<*, *>
        val duration = (metadata?.get("duration_ms") as? Number)?.toLong()?.takeIf { it > 0 } ?: durationMs
        val raw = (root["lines"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            .filter { (it["start_ms"] as? Number)?.toLong()?.let { start -> start >= 0 } == true }
            .sortedBy { (it["start_ms"] as Number).toLong() }
        val lines = raw.mapIndexed { index, line ->
            val start = (line["start_ms"] as Number).toLong()
            val next = raw.getOrNull(index + 1)?.get("start_ms") as? Number
            val end = ((line["end_ms"] as? Number)?.toLong() ?: next?.toLong() ?: duration.takeIf { it > start } ?: (start + 5000))
                .coerceAtLeast(start + 1)
            val rawWords = (line["words"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                .filter { it["text"] is String && (it["start_ms"] as? Number)?.toLong()?.let { time -> time in start until end } == true }
                .sortedBy { (it["start_ms"] as Number).toLong() }
            val words = rawWords.mapIndexed { i, word ->
                val wordStart = (word["start_ms"] as Number).toLong()
                val wordEnd = ((word["end_ms"] as? Number)?.toLong()
                    ?: (rawWords.getOrNull(i + 1)?.get("start_ms") as? Number)?.toLong() ?: end).coerceIn(wordStart + 1, end)
                LyricWord(word["text"] as String, wordStart, wordEnd)
            }
            LyricLine(line["text"] as? String ?: words.joinToString("") { it.text }, start, end, words)
        }
        return LyricsDocument("lrclib", root["plain"] as? String ?: "", lines, metadata?.get("instrumental") == true)
    }

    fun lrc(source: String, durationMs: Long): List<LyricLine> {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val raw = source.lineSequence().flatMap { row ->
            val stamps = timestamp.findAll(row).toList()
            if (stamps.isEmpty()) emptySequence() else {
                val text = row.substring(stamps.last().range.last + 1).trim()
                stamps.asSequence().map { stamp -> (millis(stamp) - offset).coerceAtLeast(0) to text }
            }
        }.sortedBy { it.first }.distinct().toList()
        return raw.mapIndexed { index, (start, text) ->
            val end = (raw.getOrNull(index + 1)?.first ?: durationMs.takeIf { it > start } ?: (start + 5000)).coerceAtLeast(start + 1)
            val markers = wordTimestamp.findAll(text).toList()
            val words = markers.mapIndexedNotNull { i, marker ->
                val wordStart = (millis(marker) - offset).coerceAtLeast(0)
                if (wordStart !in start until end) null else {
                    val wordEnd = (markers.getOrNull(i + 1)?.let(::millis)?.minus(offset) ?: end).coerceIn(wordStart + 1, end)
                    LyricWord(text.substring(marker.range.last + 1, markers.getOrNull(i + 1)?.range?.first ?: text.length), wordStart, wordEnd)
                }
            }
            LyricLine(wordTimestamp.replace(text, ""), start, end, words)
        }
    }

}
