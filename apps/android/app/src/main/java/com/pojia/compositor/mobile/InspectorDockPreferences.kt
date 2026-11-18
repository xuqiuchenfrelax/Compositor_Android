package com.pojia.compositor.mobile

import android.content.Context

/**
 * Small persistent workspace preference for the inspector dock.
 *
 * The document and its undo history stay in the project/recovery stores. Dock
 * presentation state is application-level state, so it belongs in
 * SharedPreferences and must never be serialized into a .comp file.
 */
internal object InspectorDockPreferences {
    private const val FILE = "compositor_workspace"
    private const val PANEL = "inspector_panel"
    private const val COLLAPSED = "inspector_collapsed"

    data class State(val panelName: String, val collapsed: Boolean)

    fun load(context: Context): State {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val panel = preferences.getString(PANEL, InspectorPanel.LAYERS.name)
            ?.let { value -> runCatching { InspectorPanel.valueOf(value).name }.getOrNull() }
            ?: InspectorPanel.LAYERS.name
        return State(panel, preferences.getBoolean(COLLAPSED, false))
    }

    fun save(context: Context, panelName: String, collapsed: Boolean) {
        val panel = runCatching { InspectorPanel.valueOf(panelName).name }
            .getOrDefault(InspectorPanel.LAYERS.name)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(PANEL, panel)
            .putBoolean(COLLAPSED, collapsed)
            .apply()
    }
}
