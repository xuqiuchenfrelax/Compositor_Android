package com.pojia.compositor.mobile

import android.content.Context
import java.io.File

internal class ProjectImportException(val status: Int, val renderUnsupported: Boolean = false) : IllegalStateException(
    if (renderUnsupported) "The project uses layer rendering settings this version does not support" else when (status) {
    3 -> "The project operation exceeds its reserved memory budget"
    5 -> "The project contains an unsupported format or feature"
    6 -> "The project operation encountered an internal error"
    7 -> "The project could not be read or written"
    8 -> "The project contains damaged data"
    9 -> "Project operation was cancelled"
    else -> "Project operation failed ($status)"
})

/** Shared codecs/builders load app-owned staging only. Provider transport is a separate operation. */
internal object NativeProjectIO {
    private const val RENDER_UNSUPPORTED = -1L
    init { System.loadLibrary("compositor_android") }
    fun load(context: Context, directory: File, limitBytes: Long, residentBytes: Long,
        preCancelledForTest: Boolean = false): Long {
        require(limitBytes > 0 && residentBytes in 0..limitBytes) { "Invalid project import reservation" }
        val path = ownedPath(context, directory)
        require(path.isDirectory) { "Project staging directory is missing" }
        val result = loadCandidate(path.path.toByteArray(Charsets.UTF_8), limitBytes, residentBytes, preCancelledForTest)
        check(result.size == 2)
        if (result[0] != 0L) {
            check(result[1] == 0L) { "A failed import returned a live candidate" }
            if (result[0] == RENDER_UNSUPPORTED) throw ProjectImportException(5, renderUnsupported = true)
            throw ProjectImportException(result[0].toInt())
        }
        check(result[1] != 0L) { "Project import returned no candidate" }
        return result[1]
    }
    fun ownedPath(context: Context, directory: File): File {
        val path = directory.canonicalFile
        val supplied = directory.absoluteFile.normalize()
        // Context directories may themselves have Android system aliases (/data/user/0).
        // Only trust those roots; reject symbolic aliases introduced below either root.
        val roots = listOf(context.cacheDir, context.filesDir).flatMap {
            listOf(it.absoluteFile.normalize(), it.canonicalFile)
        }.distinct()
        require(roots.any { root ->
            supplied.path.startsWith(root.path + File.separator) &&
                File(root.canonicalFile, supplied.relativeTo(root).path).normalize() == path
        }) {
            "Project IO requires a native path inside this app's cache or files folder"
        }
        return path
    }
    private external fun loadCandidate(path: ByteArray, limitBytes: Long, residentBytes: Long,
        preCancelledForTest: Boolean): LongArray
}
