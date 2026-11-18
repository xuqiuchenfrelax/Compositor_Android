package com.pojia.compositor.mobile

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** OS lease release is the crash/restart boundary; no clock or provider timestamp decides ownership. */
internal fun projectStagingSmoke(context: Context): String {
    val parent = File(context.cacheDir.canonicalFile, "project-staging-smoke-${System.nanoTime()}").apply { check(mkdirs()) }
    val root = File(parent, "staging")
    var checks = 0
    fun project(): File = File(root, "${UUID.randomUUID()}.comp").apply { check(mkdir()) }
    fun lease(file: File): File = File(root, ".${file.name.removeSuffix(".comp")}.lease")
    try {
        val first = ProjectStagingStorage(root)
        val second = ProjectStagingStorage(root)
        first.create().use { live ->
            val manifest = File(live.directory, "manifest.json").apply { writeText("active") }
            val orphan = project().apply { File(this, "partial.png").writeText("orphan") }
            val unknown = File(root, "user-notes").apply { mkdir(); File(this, "keep").writeText("keep") }
            val before = first.reclaimOrphans()
            check(before.active == 1 && before.removed == 1 && before.retained >= 1)
            check(live.directory.isDirectory && manifest.readText() == "active" && !orphan.exists() && unknown.isDirectory)
            val concurrent = second.reclaimOrphans()
            check(concurrent.active == 1 && live.directory.isDirectory)
            ++checks
        }
        check(root.listFiles().orEmpty().none { it.name.endsWith(".comp") || it.name.endsWith(".lease") })
        ++checks

        first.create().use { live ->
            val marker = lease(live.directory)
            check(marker.delete())
            marker.writeText("replaced inode")
            check(second.reclaimOrphans().active == 1 && live.directory.isDirectory)
            ++checks
        }
        check(first.reclaimOrphans().removed == 1) // A stale lock identity is preserved until its active owner releases.
        ++checks

        // A file lock held by another owner must remain linked. Closing that OS handle models owner death.
        val abandoned = project().apply { File(this, "manifest.json").writeText("partial") }
        val leaseFile = lease(abandoned)
        FileOutputStream(leaseFile).use { output ->
            val held = output.channel.lock()
            try {
                val report = second.reclaimOrphans()
                check(report.active == 1 && abandoned.isDirectory && leaseFile.isFile)
                ++checks
            } finally { held.release() }
        }
        val restarted = ProjectStagingStorage(root)
        check(restarted.reclaimOrphans().removed == 1 && !abandoned.exists() && !leaseFile.exists())
        ++checks

        val external = File(parent, "external").apply { mkdir(); File(this, "sentinel").writeText("untouched") }
        val linked = File(root, "${UUID.randomUUID()}.comp")
        Os.symlink(external.path, linked.path)
        val inner = project().apply { Os.symlink(external.path, File(this, "images").path) }
        check(first.reclaimOrphans().removed == 2)
        check(!linked.exists() && !inner.exists() && File(external, "sentinel").readText() == "untouched")
        ++checks

        val guarded = project().apply { File(this, "manifest.json").writeText("retained") }
        Os.symlink(File(external, "sentinel").path, lease(guarded).path)
        check(first.reclaimOrphans().retained >= 1 && guarded.isDirectory)
        check(File(external, "sentinel").readText() == "untouched")
        lease(guarded).delete()
        first.reclaimOrphans()
        ++checks

        val deep = project()
        File(deep, "a/b/c/d/e").apply { mkdirs(); File(this, "keep").writeText("deep") }
        val small = ProjectStagingStorage(root, ProjectStagingPolicy(cleanupEntries = 2, cleanupDepth = 2))
        val bounded = small.reclaimOrphans()
        check(bounded.scanLimited && bounded.retained >= 1 && deep.isDirectory)
        small.create().use { check(it.directory.isDirectory) } // A retained large orphan cannot block a new small stage.
        first.reclaimOrphans()
        ++checks
        check(ProjectStagingStorage(root, ProjectStagingPolicy(rootEntries = 1)).reclaimOrphans().scanLimited)
        ++checks

        // Root and registry links are rejected, not canonicalized into external directories/files.
        val aliasRoot = File(parent, "root-alias").also { Os.symlink(external.path, it.path) }
        val aliasStorage = ProjectStagingStorage(aliasRoot)
        check(aliasStorage.reclaimOrphans().retained > 0)
        check(runCatching { aliasStorage.create() }.isFailure)
        check(File(external, "sentinel").readText() == "untouched")
        ++checks

        val replaceRoot = File(parent, "replace-root")
        val replaceStorage = ProjectStagingStorage(replaceRoot)
        val pending = replaceStorage.create()
        val movedRoot = File(parent, "moved-root")
        check(replaceRoot.renameTo(movedRoot)); check(replaceRoot.mkdir())
        File(replaceRoot, "sentinel").writeText("replacement")
        pending.close()
        check(File(replaceRoot, "sentinel").readText() == "replacement" &&
            File(movedRoot, pending.directory.name).isDirectory) // Changed root is retained, never traversed by a stale handle.
        ++checks
        val corruptRoot = File(parent, "registry-link").apply { mkdir() }
        Os.symlink(File(external, "sentinel").path, File(corruptRoot, ".registry.lock").path)
        check(ProjectStagingStorage(corruptRoot).reclaimOrphans().retained > 0)
        check(runCatching { ProjectStagingStorage(corruptRoot).create() }.isFailure)
        check(File(external, "sentinel").readText() == "untouched")
        ++checks

        val cancelled = ProjectTransportCancellation().apply { cancel() }
        check(runCatching { first.reclaimOrphans(cancelled) }.exceptionOrNull().let {
            it is ProjectTransportException && it.failure == ProjectTransportFailure.CANCELLED
        })
        ++checks
        return "Staging storage: $checks lease/orphan/link/bounded cleanup checks; held leases survive sweeps and released owners are reclaimable"
    } finally {
        // The fixture's aliases point into this same scratch parent; remove links before recursive teardown.
        parent.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach {
            if (java.nio.file.Files.isSymbolicLink(it.toPath())) it.delete()
        }
        parent.deleteRecursively()
    }
}
