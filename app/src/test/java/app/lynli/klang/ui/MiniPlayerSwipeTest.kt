package app.lynli.klang.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerSwipeTest {
    private fun action(axis: MiniPlayerSwipeAxis?, distance: Float, velocity: Float = 0f,
        paused: Boolean = true, previous: Boolean = true, next: Boolean = true) = miniPlayerSwipeAction(
        axis, distance, velocity, distanceThreshold = 64f, minFlingDistance = 20f,
        minFlingVelocity = 800f, paused = paused, hasPrevious = previous, hasNext = next)

    @Test fun horizontalSwipesSelectNextToTheLeftAndPreviousToTheRight() {
        assertEquals(MiniPlayerSwipeAction.Next, action(MiniPlayerSwipeAxis.Horizontal, -80f))
        assertEquals(MiniPlayerSwipeAction.Previous, action(MiniPlayerSwipeAxis.Horizontal, 80f))
        assertEquals(MiniPlayerSwipeAction.Next, action(MiniPlayerSwipeAxis.Horizontal, -80f, paused = false))
    }

    @Test fun downwardDismissRequiresPausedPlaybackAndUpwardSwipeDoesNothing() {
        assertEquals(MiniPlayerSwipeAction.Dismiss, action(MiniPlayerSwipeAxis.Vertical, 80f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Vertical, 80f, paused = false))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Vertical, -80f))
    }

    @Test fun tapsAndSmallDragsNeverSkipOrDismiss() {
        assertEquals(MiniPlayerSwipeAction.None, action(null, 100f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, 0f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, -30f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Vertical, 10f, 2_000f))
    }

    @Test fun deliberateFlingCanCompleteBeforeTheDistanceThreshold() {
        assertEquals(MiniPlayerSwipeAction.Next, action(MiniPlayerSwipeAxis.Horizontal, -30f, -1_000f))
        assertEquals(MiniPlayerSwipeAction.Dismiss, action(MiniPlayerSwipeAxis.Vertical, 30f, 1_000f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, -30f, 1_000f))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, -10f, -2_000f))
    }

    @Test fun queueBoundariesKeepThePlayerInPlace() {
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, 100f, previous = false))
        assertEquals(MiniPlayerSwipeAction.None, action(MiniPlayerSwipeAxis.Horizontal, -100f, next = false))
    }

    @Test fun diagonalDragsLockToTheDominantInitialDirection() {
        assertEquals(MiniPlayerSwipeAxis.Horizontal, miniPlayerSwipeAxis(-30f, 5f))
        assertEquals(MiniPlayerSwipeAxis.Vertical, miniPlayerSwipeAxis(8f, 30f))
        assertEquals(MiniPlayerSwipeAction.Next, action(miniPlayerSwipeAxis(-30f, 5f), -90f))
    }
}
