package app.foliodock

/** Formats rendered by the bundled LibreOffice layout engine, not the text parser. */
object OfficeFormats {
    val extensions = setOf(
        "doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "ott", "fodt", "rtf",
        "xls", "xlsx", "xlsm", "xlt", "xltx", "xltm", "xlsb", "ods", "ots", "fods",
        "ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm",
        "odp", "otp", "fodp", "odg", "otg", "fodg"
    )

    fun supports(name: String): Boolean = DocumentParser.extension(name) in extensions
}
