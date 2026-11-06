package app.foliodock

/** Explicit format chips plus familiar Office-family shortcuts. */
object LibraryFilters {
    val labels = listOf("All", "PDF", "DOCX", "PPTX", "XLSX", "Word", "Excel", "PowerPoint",
        "DOC", "PPT", "XLS", "ODT", "ODS", "ODP", "TXT", "CSV", "RTF", "MD", "TSV")
    private val word = setOf("doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "ott", "fodt", "rtf")
    private val excel = setOf("xls", "xlsx", "xlsm", "xlt", "xltx", "xltm", "xlsb", "ods", "ots", "fods")
    private val presentation = setOf("ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm", "odp", "otp", "fodp")
    fun matches(label: String, name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return when (label) {
            "All" -> true
            "Word" -> extension in word
            "Excel" -> extension in excel
            "PowerPoint" -> extension in presentation
            else -> extension == label.lowercase(java.util.Locale.ROOT)
        }
    }
}
