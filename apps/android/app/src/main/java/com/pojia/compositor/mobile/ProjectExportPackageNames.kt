package com.pojia.compositor.mobile

import java.util.UUID

/** Unique external package names preserve the source stem without granting cleanup authority. */
internal object ProjectExportPackageNames {
    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun finalName(id: String, suggestedName: String? = null): String {
        require(validId(id))
        if (suggestedName == null) return "Compositor-$id.comp"
        val stem = (ProjectCopyNames.filename(suggestedName) ?: "Untitled.comp").removeSuffix(".comp")
        val suffix = "--$id.comp"
        val prefix = buildString {
            var bytes = 0
            for (point in stem.codePoints().toArray()) {
                val character = String(Character.toChars(point))
                val size = character.toByteArray(Charsets.UTF_8).size
                if (bytes + size > 240 - suffix.length) break
                append(character); bytes += size
            }
        }
        return prefix + suffix
    }

    fun validReceiptName(version: Int, id: String, name: String): Boolean {
        if (!validId(id) || version !in 1..2) return false
        if (name == "Compositor-$id.comp") return true
        if (version == 1 || ProjectCopyNames.filename(name) != name) return false
        val suffix = "--$id.comp"
        return name.endsWith(suffix) && ImageDocumentSource.validName(name.removeSuffix(suffix)) != null
    }
}
