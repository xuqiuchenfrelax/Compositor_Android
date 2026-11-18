package com.pojia.compositor.mobile

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** A bounded private package uses the existing import admission and installation transaction. */
internal object BlankCanvasProject {
    // Pinned to ProjectManifest.current and docs/project-format.md in format-authority.json.
    const val FORMAT_VERSION = 13
    fun write(directory: File, spec: NewCanvasSpec, admission: ProjectAdmission) {
        require(spec.width in 1..30000 && spec.height in 1..30000 && spec.resolution.isFinite() &&
            spec.resolution in 1.0..9600.0)
        check(directory.isDirectory) { "New canvas staging is unavailable" }
        val images = File(directory, "images").apply { check(mkdir()) }
        val layerId = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        val layer = JSONObject().put("id", layerId).put("name",
            if (spec.background == NewCanvasBackground.TRANSPARENT) "Layer 1" else "Background")
            .put("isVisible", true).put("isGroup", false).put("opacity", 1.0).put("blendMode", "Normal")
            .put("transform", JSONObject().put("origin", JSONArray(listOf(0, 0)))
                .put("size", JSONArray(listOf(spec.width, spec.height))).put("rotation", 0)
                // Current mobile rendering supports Nearest; High quality parity remains separate work.
                .put("flipX", false).put("flipY", false).put("sampling", "Nearest"))
        if (spec.background != NewCanvasBackground.TRANSPARENT) {
            // The temporary bitmap is released before native decoding. Reserve it against the same job allowance.
            val bytes = Math.multiplyExact(Math.multiplyExact(spec.width.toLong(), spec.height.toLong()), 4)
            check(bytes <= (admission.limitBytes - admission.residentBytes - 16L * 1024 * 1024).coerceAtLeast(0)) {
                "This background is too large for available memory. Use a smaller canvas or Transparent."
            }
            val name = "$layerId.png"
            val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(if (spec.background == NewCanvasBackground.WHITE) Color.WHITE else Color.BLACK)
                File(images, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            layer.put("imageFile", name)
        }
        val manifest = JSONObject().put("format", "com.compositor.project").put("version", FORMAT_VERSION)
            .put("colorSpace", "sRGB").put("documentID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
            .put("width", spec.width).put("height", spec.height).put("resolution", spec.resolution)
            .put("activeLayerID", layerId).put("layers", JSONArray().put(layer))
        File(directory, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
    }
}
