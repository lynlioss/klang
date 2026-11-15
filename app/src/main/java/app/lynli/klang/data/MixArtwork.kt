package app.lynli.klang.data

import kotlin.random.Random

/** Sample albums uniformly, then one song per album; never repeat a cover to fill empty slots. */
internal fun <T> mixArtwork(tracks: List<T>, random: Random = Random.Default, album: (T) -> Pair<String, String>): List<T> = tracks
    .groupBy { album(it).let { (name, artist) -> name.trim().lowercase() to artist.trim().lowercase() } }
    .values.shuffled(random).take(3).map { it.random(random) }
