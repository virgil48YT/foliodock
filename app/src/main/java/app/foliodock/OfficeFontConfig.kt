package app.foliodock

/** Rewrite only the private runtime copy of the trusted upstream fontconfig asset. */
object OfficeFontConfig {
    fun configure(original: String, cacheDirectory: String, bundledFonts: String): String {
        require(cacheDirectory.startsWith("/") && bundledFonts.startsWith("/")) { "Font paths must be absolute." }
        val root = Regex("""<fontconfig(?:\s[^>]*)?>""").find(original)
            ?: throw IllegalArgumentException("The bundled font configuration has no root element.")
        val caches = Regex("""<cachedir(?:\s[^>]*)?>[\s\S]*?</cachedir>""")
        require(caches.containsMatchIn(original)) { "The bundled font configuration has no cache directory." }
        val withFonts = original.replaceRange(root.range.last + 1, root.range.last + 1,
            "\n\t<!-- FolioDock private runtime font directory -->\n\t<dir>${escape(bundledFonts)}</dir>\n")
        return caches.replace(withFonts) { "<cachedir>${escape(cacheDirectory)}</cachedir>" }
    }
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
