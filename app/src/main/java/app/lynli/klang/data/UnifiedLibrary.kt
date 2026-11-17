package app.lynli.klang.data

/** A downloaded source song keeps its catalog identity without a second MediaStore row in the UI. */
internal fun <T> mergeMusicEntries(device: List<T>, sources: List<T>, identity: (T) -> String,
    offlineDeviceId: (T) -> String?): List<T> {
    val linked = mutableSetOf<String>()
    val sourceEntries = sources.distinctBy(identity).filter { entry ->
        val local = offlineDeviceId(entry)?.let(::audioFileIds).orEmpty()
        if (local.isEmpty()) true else if (local.any { it in linked }) false else { linked.addAll(local); true }
    }
    val downloaded = sourceEntries.mapNotNull(offlineDeviceId).flatMap(::audioFileIds).toSet()
    return (device.filterNot { identity(it) in downloaded } + sourceEntries).distinctBy(identity)
}

/** Private account membership cannot carry into another account; owned offline audio can. */
internal fun retainSourceSong(offline: Boolean, enabled: Boolean, itemRevision: String?, currentRevision: String?): Boolean =
    offline || enabled && !itemRevision.isNullOrBlank() && itemRevision == currentRevision
