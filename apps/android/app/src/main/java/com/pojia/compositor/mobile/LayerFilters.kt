package com.pojia.compositor.mobile

import java.util.Locale

internal enum class LayerKind(val nativeValue: Int) {
    IMAGE(0), SHAPE(1), TEXT(2), FOLDER(3), ADJUSTMENT(4);

    companion object {
        fun fromNative(value: Long) = entries.firstOrNull { it.nativeValue.toLong() == value }
            ?: error("Unknown native layer kind: $value")
    }
}

internal enum class LayerFilterType { IMAGES, SHAPES, TEXT, FOLDERS, ADJUSTMENTS, SELECTED }

/**
 * Matches the desktop panel: name AND any selected type, with matching folders including
 * their descendants. Filtering searches collapsed folders without changing the saved collapse state.
 */
internal fun filteredDisplayedLayers(
    layers: List<LayerState>,
    collapsedGroups: Set<Long>,
    query: String,
    types: Set<LayerFilterType>,
    selectedRoots: Set<Long>,
): List<LayerState> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty() && types.isEmpty()) return displayedLayers(layers, collapsedGroups)

    val children = layers.asReversed().groupBy { it.parentId }
    val matches = layers.filter { layer ->
        val nameMatches = normalized.isEmpty() || layer.name.lowercase(Locale.ROOT).contains(normalized)
        val type = when (layer.kind) {
            LayerKind.IMAGE -> LayerFilterType.IMAGES
            LayerKind.SHAPE -> LayerFilterType.SHAPES
            LayerKind.TEXT -> LayerFilterType.TEXT
            LayerKind.FOLDER -> LayerFilterType.FOLDERS
            LayerKind.ADJUSTMENT -> LayerFilterType.ADJUSTMENTS
        }
        val typeMatches = types.isEmpty() || type in types ||
            (LayerFilterType.SELECTED in types && layer.id in selectedRoots)
        nameMatches && typeMatches
    }
    if (matches.isEmpty()) return emptyList()

    val visible = LinkedHashSet<Long>()
    fun includeDescendants(id: Long) {
        children[id].orEmpty().forEach { child ->
            if (visible.add(child.id) && child.isGroup) includeDescendants(child.id)
        }
    }
    matches.forEach { layer ->
        visible.add(layer.id)
        if (layer.isGroup) includeDescendants(layer.id)
    }

    // Keep the regular top-first order and indentation, including matches inside collapsed folders.
    return displayedLayers(layers, emptySet()).filter { it.id in visible }
}
