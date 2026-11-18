package com.pojia.compositor.mobile

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** App-owned packages: explicit copies get unique names; Save replaces only an existing local source. */
internal object LocalProjectLibrary {
    private const val MAX_PROJECTS = 512
    private fun root(context: Context): File = NativeProjectIO.ownedPath(context, File(context.filesDir, "saved-projects"))

    fun projects(context: Context): List<File> {
        val directory = root(context)
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "The saved project folder is unavailable" }
        val files = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                check(++count <= MAX_PROJECTS) { "The saved project library has reached its current limit" }
                check(!Files.isSymbolicLink(entry)) { "The saved project library contains an unsupported link" }
                if (Files.isDirectory(entry) && entry.fileName.toString().endsWith(".comp")) files += entry.toFile()
            }
        }
        return files.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
    }

    fun existingTarget(context: Context, sourceIdentity: String): File {
        check(sourceIdentity.startsWith("local:")) { "The project is not a local saved project" }
        val name = sourceIdentity.removePrefix("local:")
        check(ImageExportNames.validProjectName(name) == name) { "The saved project name is invalid" }
        val directory = root(context)
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The saved project folder is unavailable" }
        val target = File(directory, name)
        check(!Files.isSymbolicLink(target.toPath()) &&
            Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            target.canonicalFile.parentFile == directory.canonicalFile) { "The saved project is unavailable" }
        return target
    }

    fun newTarget(context: Context, suggestedName: String? = null): File {
        check(projects(context).size < MAX_PROJECTS) { "The saved project library has reached its current limit" }
        val directory = root(context)
        if (!directory.exists()) {
            check(directory.mkdir()) { "The saved project folder could not be created" }
            val parent = Os.open(context.filesDir.canonicalPath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(parent).st_mode)) { "The project library parent is not a directory" }
                Os.fsync(parent)
            } finally { Os.close(parent) }
        }
        if (suggestedName != null) {
            val name = ProjectCopyNames.available(suggestedName) { candidate ->
                Files.exists(File(directory, candidate).toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            return File(directory, name)
        }
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date())
        return File(directory, "${timestamp}_${UUID.randomUUID()}.comp")
    }

    fun label(project: File): String = ProjectCopyNames.label(project.name)
}
