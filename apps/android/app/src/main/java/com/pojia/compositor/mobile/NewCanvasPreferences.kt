package com.pojia.compositor.mobile

import android.content.Context
import androidx.core.content.edit

/** Remember successful dimensions in pixels; print settings are deliberately not reused. */
internal object NewCanvasPreferences {
    private const val FILE = "compositor_new_canvas"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"

    fun load(context: Context): NewCanvasDraft {
        val preferences = runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }.getOrNull()
        val width = runCatching { preferences?.getInt(WIDTH, 1920) ?: 1920 }.getOrDefault(1920)
        val height = runCatching { preferences?.getInt(HEIGHT, 1080) ?: 1080 }.getOrDefault(1080)
        return NewCanvasDraft.rememberedPixels(width, height)
    }

    fun save(context: Context, spec: NewCanvasSpec) {
        if (spec.width !in 1..30000 || spec.height !in 1..30000) return
        // Dimension memory is optional; it must not strand an already-installed canvas in the busy state.
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
                putInt(WIDTH, spec.width)
                putInt(HEIGHT, spec.height)
            }
        } catch (failure: Exception) {
            android.util.Log.w("CompositorPreferences", "Could not remember new canvas dimensions", failure)
        }
    }
}
