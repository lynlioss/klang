package app.lynli.klang.data

/** Imported files may use the primary-volume URI while the library scan uses its external alias. */
internal fun audioFileIds(id: String): Set<String> {
    val match = Regex("content://media/(?:external|external_primary)/audio/media/([0-9]+)").matchEntire(id)
        ?: return setOf(id)
    val row = match.groupValues[1]
    return setOf("content://media/external/audio/media/$row", "content://media/external_primary/audio/media/$row")
}
