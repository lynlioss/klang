package app.lynli.klang.playback

import org.junit.Assert.*
import org.junit.Test

class QueuePreparationTest {
    @Test fun preparesBothNeighborsBeforeDistantSongs() {
        assertEquals(listOf(4, 2, 5, 1, 6, 7), queuePreparation(3, 8, { if (it < 7) it + 1 else -1 }, { it - 1 }))
    }
    @Test fun followsShuffleOrderWithoutRepeatingTheCurrentSong() {
        val order = listOf(2, 4, 0, 3, 1)
        val next: (Int) -> Int = { order[(order.indexOf(it) + 1) % order.size] }
        val prev: (Int) -> Int = { order[(order.indexOf(it) + order.size - 1) % order.size] }
        assertEquals(listOf(3, 4, 1, 2), queuePreparation(0, 5, next, prev))
    }
    @Test fun repeatOneAndAnEmptyQueueNeverFetchTheSameSongAgain() {
        assertTrue(queuePreparation(0, 1, { it }, { it }).isEmpty())
        assertTrue(queuePreparation(-1, 0, { it }, { it }).isEmpty())
    }
}
