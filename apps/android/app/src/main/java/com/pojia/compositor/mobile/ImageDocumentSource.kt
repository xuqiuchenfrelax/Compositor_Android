package com.pojia.compositor.mobile

import java.util.Base64
import java.util.Locale

/** A recoverable UI origin, without a provider URI or write permission. */
internal data class ImageDocumentSource(val name: String) {
    val format: ImageExportFormat? get() = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> ImageExportFormat.PNG
        "jpg", "jpeg", "jpe" -> ImageExportFormat.JPEG
        "tif", "tiff" -> ImageExportFormat.TIFF
        else -> null
    }
    fun identity(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))

    companion object {
        private const val PREFIX = "image:v1:"
        fun validName(name: String?): String? = name?.takeIf {
            it.length in 1..240 && it.isNotBlank() && it != "." && it != ".." &&
                String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == it &&
                it.none { character -> character == '/' || character == '\\' || character.isISOControl() }
        }
        fun fromName(name: String?): ImageDocumentSource = ImageDocumentSource(validName(name) ?: "Untitled")
        fun decode(identity: String?): ImageDocumentSource? = runCatching {
            if (identity == null || !identity.startsWith(PREFIX) || identity.length > 1400) return@runCatching null
            val raw = identity.removePrefix(PREFIX)
            val name = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            val result = ImageDocumentSource(validName(name) ?: return@runCatching null)
            // Reject malformed UTF-8 and noncanonical encodings instead of renaming a recovered document.
            result.takeIf { it.identity() == identity }
        }.getOrNull()
    }
}
