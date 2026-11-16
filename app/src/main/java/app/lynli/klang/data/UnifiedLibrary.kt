package app.lynli.klang.data

/** A downloaded source song keeps its catalog identity without a second MediaStore row in the UI. */
internal fun <T> mergeMusicEntries(device: List<T>, sources: List<T>, identity: (T) -> String,
    offlineDeviceId: (T) -> String?): List<T> {
    val sourceEntries = sources.distinctBy(identity)
    val downloaded = sourceEntries.mapNotNull(offlineDeviceId).flatMap(::audioFileIds).toSet()
    return (device.filterNot { identity(it) in downloaded } + sourceEntries).distinctBy(identity)
}
