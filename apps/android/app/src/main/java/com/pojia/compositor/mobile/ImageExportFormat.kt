package com.pojia.compositor.mobile

/** Only flattened formats supported by the mobile Export As workflow. */
internal enum class ImageExportFormat {
    PNG, JPEG, TIFF;

    companion object {
        fun restored(value: String?): ImageExportFormat = entries.firstOrNull { it.name == value } ?: PNG
    }
}

/** Document choice wins over the cross-document preference until the document is replaced. */
internal class ImageExportHistory(initial: ImageExportFormat) {
    var lastFormat = initial
        private set
    private var documentGeneration: Long? = null
    private var documentFormat: ImageExportFormat? = null

    fun startingFormat(generation: Long, sourceFormat: ImageExportFormat? = null): ImageExportFormat =
        if (documentGeneration == generation) documentFormat ?: sourceFormat ?: lastFormat else sourceFormat ?: lastFormat

    fun confirm(generation: Long, format: ImageExportFormat) {
        documentGeneration = generation
        documentFormat = format
        lastFormat = format
    }
}
