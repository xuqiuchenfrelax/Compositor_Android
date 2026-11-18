package com.pojia.compositor.mobile

import android.content.Context
import androidx.core.content.edit

internal object ImageExportPreferences {
    private const val FILE = "image-export"
    private const val FORMAT = "last-format"

    fun load(context: Context): ImageExportFormat = ImageExportFormat.restored(runCatching {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(FORMAT, null)
    }.getOrNull())

    fun save(context: Context, format: ImageExportFormat) {
        remember(context) { putString(FORMAT, format.name) }
    }

    fun saveJpegQuality(context: Context, quality: Int) {
        if (quality !in 0..100) return
        remember(context) { putInt("jpeg-quality", quality) }
    }

    private fun remember(context: Context, action: android.content.SharedPreferences.Editor.() -> Unit) {
        // Failure to remember a default must not abort format confirmation or an already-launched picker.
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit(action = action)
        } catch (failure: Exception) {
            android.util.Log.w("CompositorPreferences", "Could not remember image export options", failure)
        }
    }
}
