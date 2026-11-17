package app.lynli.klang.playback

/** Next/previous navigation comes from the player's timeline, including shuffle and repeat. */
internal fun queuePreparation(current: Int, count: Int, next: (Int) -> Int, previous: (Int) -> Int,
    upcoming: Int = 10, history: Int = 2): List<Int> {
    if (current !in 0 until count) return emptyList()
    val result = linkedSetOf<Int>()
    val visitedNext = mutableSetOf(current)
    val visitedPrevious = mutableSetOf(current)
    var forward = current
    var back = current
    repeat(maxOf(upcoming, history)) { step ->
        if (step < upcoming && forward >= 0) {
            forward = next(forward)
            if (forward in 0 until count && visitedNext.add(forward)) result.add(forward) else forward = -1
        }
        if (step < history && back >= 0) {
            back = previous(back)
            if (back in 0 until count && visitedPrevious.add(back)) result.add(back) else back = -1
        }
    }
    return result.toList()
}
