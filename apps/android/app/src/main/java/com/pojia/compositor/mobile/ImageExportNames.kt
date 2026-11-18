package com.pojia.compositor.mobile

/** Provider names are suggestions, never filesystem paths or document identities. */
internal object ImageExportNames {
    fun validProjectName(value: String?): String? = value?.takeIf {
        it.length in 1..240 && it.isNotBlank() && it != "." && it != ".." &&
            it.none { character -> character == '/' || character == '\\' || character.isISOControl() } &&
            it.endsWith(".comp") && it.length > 5
    }

    fun projectName(sourceIdentity: String?, rememberedName: String?): String? {
        if (sourceIdentity == null) return null
        return if (sourceIdentity.startsWith("local:")) validProjectName(sourceIdentity.removePrefix("local:"))
            else if (sourceIdentity.startsWith("saf:")) validProjectName(rememberedName) else null
    }

    fun outputName(projectName: String?, extension: String, sourceLayers: Boolean = false,
        imageSourceName: String? = null): String {
        require(extension in setOf("png", "jpeg", "tiff"))
        val name = validProjectName(projectName)
        val sourceName = ImageDocumentSource.validName(imageSourceName)
        val stem = name?.removeSuffix(".comp") ?: sourceName?.let {
            val dot = it.lastIndexOf('.')
            if (dot > 0) it.substring(0, dot) else it
        } ?: "Untitled"
        return stem + (if (sourceLayers) "-source-layers" else "") + "." + extension
    }
}

/** At most 64 provider project names survive restart; eviction affects only suggested names. */
internal class ProjectNameHistory(entries: List<Pair<String, String>> = emptyList()) {
    private val names = linkedMapOf<String, String>()

    init { entries.forEach { (identity, name) -> remember(identity, name) } }

    fun remember(identity: String, name: String) {
        if (!identity.matches(Regex("[0-9a-f]{64}")) || ImageExportNames.validProjectName(name) == null) return
        names.remove(identity)
        names[identity] = name
        while (names.size > 64) names.remove(names.keys.first())
    }

    fun name(identity: String): String? = names[identity]
    fun entries(): List<Pair<String, String>> = names.map { it.key to it.value }
}
