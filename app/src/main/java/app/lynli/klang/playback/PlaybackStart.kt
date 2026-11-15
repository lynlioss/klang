package app.lynli.klang.playback

import kotlin.random.Random

internal data class PlaybackStart(val index: Int, val shuffle: Boolean)

/** A selected song starts first; an unselected shuffled collection starts anywhere in its queue. */
internal fun collectionPlaybackStart(count: Int, selectedIndex: Int?, sourceId: String?, shuffled: Boolean,
    random: Random = Random.Default): PlaybackStart {
    require(count > 0)
    val shuffle = shuffled || sourceId == "mix"
    val index = selectedIndex?.coerceIn(0, count - 1) ?: if (shuffle) random.nextInt(count) else 0
    return PlaybackStart(index, shuffle)
}
