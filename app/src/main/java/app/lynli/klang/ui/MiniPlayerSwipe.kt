package app.lynli.klang.ui

import kotlin.math.abs
import kotlin.math.sign

internal enum class MiniPlayerSwipeAxis { Horizontal, Vertical }
internal enum class MiniPlayerSwipeAction { None, Previous, Next, Dismiss }

/** Lock the gesture once it crosses touch slop; later diagonal movement cannot change its action. */
internal fun miniPlayerSwipeAxis(x: Float, y: Float): MiniPlayerSwipeAxis =
    if (abs(x) >= abs(y)) MiniPlayerSwipeAxis.Horizontal else MiniPlayerSwipeAxis.Vertical

/** A deliberate drag or directional fling completes an action; taps and reversed flings do not. */
internal fun miniPlayerSwipeAction(axis: MiniPlayerSwipeAxis?, distance: Float, velocity: Float,
    distanceThreshold: Float, minFlingDistance: Float, minFlingVelocity: Float,
    paused: Boolean, hasPrevious: Boolean, hasNext: Boolean): MiniPlayerSwipeAction {
    val deliberate = abs(distance) >= distanceThreshold ||
        (abs(distance) >= minFlingDistance && abs(velocity) >= minFlingVelocity && distance.sign == velocity.sign)
    if (!deliberate) return MiniPlayerSwipeAction.None
    return when (axis) {
        MiniPlayerSwipeAxis.Horizontal -> when {
            distance < 0f && hasNext -> MiniPlayerSwipeAction.Next
            distance > 0f && hasPrevious -> MiniPlayerSwipeAction.Previous
            else -> MiniPlayerSwipeAction.None
        }
        MiniPlayerSwipeAxis.Vertical -> if (paused && distance > 0f) MiniPlayerSwipeAction.Dismiss else MiniPlayerSwipeAction.None
        null -> MiniPlayerSwipeAction.None
    }
}
