package com.pojia.compositor.mobile

internal object ProjectCopyNames {
    private const val MAX_BYTES = 240
    private val legacy = Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_[0-9a-fA-F-]{36}\\.comp")

    fun filename(input: String): String? {
        val trimmed = input.trim()
        val stem = if (trimmed.endsWith(".comp", ignoreCase = true)) trimmed.dropLast(5) else trimmed
        if (ImageDocumentSource.validName(stem) == null) return null
        val name = "$stem.comp"
        return name.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    fun suggested(projectName: String?, imageName: String?): String {
        val project = ImageExportNames.validProjectName(projectName)
        val source = ImageDocumentSource.validName(imageName)
        val stem = project?.removeSuffix(".comp") ?: source?.let {
            val dot = it.lastIndexOf('.')
            if (dot > 0) it.substring(0, dot) else it
        } ?: "Untitled"
        return filename("${truncate(stem, MAX_BYTES - 5)}.comp") ?: "Untitled.comp"
    }

    private fun truncate(stem: String, maxBytes: Int): String = buildString {
        var bytes = 0
        for (codePoint in stem.codePoints().toArray()) {
            val character = String(Character.toChars(codePoint))
            val count = character.toByteArray(Charsets.UTF_8).size
            if (bytes + count > maxBytes) break
            append(character)
            bytes += count
        }
    }

    fun available(input: String, exists: (String) -> Boolean): String {
        val initial = requireNotNull(filename(input)) { "Invalid project copy name" }
        val stem = initial.removeSuffix(".comp")
        for (number in 1..513) {
            val suffix = " ($number).comp"
            val candidate = if (number == 1) initial else truncate(stem, MAX_BYTES - suffix.length) + suffix
            if (!exists(candidate)) return candidate
        }
        error("No available project copy name")
    }

    fun label(name: String): String = if (legacy.matches(name))
        name.substringBeforeLast('_').replace('_', ' ') + " · " + name.substringAfterLast('_').take(8)
        else name.removeSuffix(".comp")
}
