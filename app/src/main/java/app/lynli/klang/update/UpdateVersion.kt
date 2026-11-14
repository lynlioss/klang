package app.lynli.klang.update

/** Numeric major.minor.patch parts; a "-beta" style suffix is ignored. */
internal fun versionParts(value: String): List<Int> =
    value.trim().removePrefix("v").split(".").map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

/** True when [candidate] is strictly newer than [current]. */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val newer = versionParts(candidate)
    val installed = versionParts(current)
    for (index in 0 until maxOf(newer.size, installed.size, 3)) {
        val left = newer.getOrElse(index) { 0 }
        val right = installed.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}
