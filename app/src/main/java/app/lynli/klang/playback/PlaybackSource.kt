package app.lynli.klang.playback

internal const val PLAYBACK_SOURCE_KEY = "app.lynli.klang.PLAYBACK_SOURCE"

/** Shared tracks do not make a different playlist the owner of the active queue. */
internal fun canResumeCollection(activeSource: String?, requestedSource: String, hasCurrentItem: Boolean): Boolean =
    hasCurrentItem && activeSource != null && activeSource == requestedSource
