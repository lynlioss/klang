package app.lynli.klang.plugins

/** Escape both braces: Android's ICU regex engine rejects the JVM's bare closing brace. */
internal object PluginTemplates {
    private val placeholder = Regex("\\{([^{}]+)\\}")

    fun tokens(template: String): Sequence<String> = placeholder.findAll(template).map { it.groupValues[1] }

    fun render(template: String, value: (String) -> String): String =
        placeholder.replace(template) { value(it.groupValues[1]) }
}
