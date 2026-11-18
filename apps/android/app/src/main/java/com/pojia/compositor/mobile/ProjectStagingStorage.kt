package com.pojia.compositor.mobile

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.UUID

internal data class ProjectStagingPolicy(val rootEntries: Int = 4096,
    val cleanupEntries: Int = 40_064, val cleanupDepth: Int = 4) {
    init { require(rootEntries > 0 && cleanupEntries > 0 && cleanupDepth > 0) }
}

/** Cleanup is best effort. Retained entries still count against the transport's unchanged disk budget. */
internal data class ProjectStagingReclaim(val removed: Int, val active: Int,
    val retained: Int, val scanLimited: Boolean)

/** Private transient packages only. This store never opens provider URIs or the saved-project library. */
internal class ProjectStagingStorage(private val root: File,
    private val policy: ProjectStagingPolicy = ProjectStagingPolicy()) {
    companion object {
        // Java file locks overlap within one JVM rather than blocking. Serialize the registry in-process too.
        private val registryGate = Any()
        private val liveLeases = HashSet<String>()
        private val packageName = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.comp")
        private val leaseName = Regex("\\.[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.lease")
    }

    /** Safe while other instances/processes have live staging; held leases are skipped, never waited on. */
    fun reclaimOrphans(cancellation: ProjectTransportCancellation = ProjectTransportCancellation()): ProjectStagingReclaim {
        cancellation.check()
        var removed = 0
        var active = 0
        var retained = 0
        var limited = false
        val budget = CleanupBudget(policy.cleanupEntries, cancellation)
        try {
            registry { directory, guard ->
                Files.newDirectoryStream(directory.pinned.toPath()).use { entries ->
                    var scanned = 0
                    val processed = HashSet<String>()
                    for (entry in entries) {
                        cancellation.check()
                        if (++scanned > policy.rootEntries) { limited = true; break }
                        val name = entry.fileName.toString()
                        if (!packageName.matches(name) && !leaseName.matches(name)) {
                            if (name != ".registry.lock") ++retained
                            continue
                        }
                        val project = if (packageName.matches(name)) name else name.substring(1).removeSuffix(".lease") + ".comp"
                        if (!processed.add(project)) continue
                        if (File(directory.location, project).path in liveLeases) { ++active; continue }
                        val lease = ".${project.removeSuffix(".comp")}.lease"
                        var handle: LockedFile? = null
                        try {
                            directory.checkIdentity(); guard.checkIdentity()
                            handle = LockedFile(File(directory.pinned, lease))
                            if (!handle.tryLock()) { ++active; continue }
                            directory.checkIdentity(); guard.checkIdentity(); handle.checkIdentity()
                            val packageFile = File(directory.pinned, project)
                            if (existsWithoutFollowing(packageFile)) {
                                removeOwnedTree(packageFile, 0, budget, directory, guard)
                                ++removed
                            }
                            directory.checkIdentity(); guard.checkIdentity(); handle.checkIdentity()
                            // Only unlink after holding this exact lease under the registry. Active leases stay linked.
                            Files.delete(File(directory.pinned, lease).toPath())
                        } catch (error: Exception) {
                            cancellation.check()
                            ++retained
                            if (error is CleanupLimit) { limited = true; break }
                        } finally { handle?.close() }
                    }
                }
            }
        } catch (error: Exception) {
            cancellation.check()
            ++retained
        }
        return ProjectStagingReclaim(removed, active, retained, limited)
    }

    /** Call on the import worker; one lease remains held until the returned handle is closed. */
    fun create(): ProjectStagingLease = registry { directory, guard ->
        directory.checkIdentity(); guard.checkIdentity()
        val name = "${UUID.randomUUID()}.comp"
        val leaseFile = File(directory.pinned, ".${name.removeSuffix(".comp")}.lease")
        val lease = LockedFile(leaseFile, File(directory.location, leaseFile.name))
        var handedOff = false
        var locked = false
        try {
            locked = lease.tryLock()
            check(locked) { "Unique project staging lease is already held" }
            lease.checkIdentity(); directory.checkIdentity(); guard.checkIdentity()
            val staging = File(directory.pinned, name)
            check(staging.mkdir()) { "Cannot create unique project staging" }
            val packageIdentity = Os.lstat(staging.path)
            val nativeDirectory = File(directory.location, name)
            val result = ProjectStagingLease(nativeDirectory, lease,
                { held -> release(name, packageIdentity, held) },
                { synchronized(registryGate) { liveLeases.remove(nativeDirectory.path) } })
            liveLeases.add(nativeDirectory.path)
            handedOff = true
            result
        } finally {
            if (!handedOff) {
                if (locked) runCatching { lease.checkIdentity(); Files.deleteIfExists(leaseFile.toPath()) }
                lease.close()
            }
        }
    }

    private fun release(name: String, expected: StructStat, lease: LockedFile) {
        // Cleanup failure conservatively leaves a now-unlocked orphan for a later bounded sweep.
        runCatching {
            registry { directory, guard ->
                directory.checkIdentity(); guard.checkIdentity(); lease.checkIdentity()
                val file = File(directory.pinned, name)
                if (existsWithoutFollowing(file)) {
                    check(sameIdentity(expected, Os.lstat(file.path))) { "Project staging identity changed" }
                    removeOwnedTree(file, 0, CleanupBudget(policy.cleanupEntries, null), directory, guard)
                }
                directory.checkIdentity(); guard.checkIdentity(); lease.checkIdentity()
                Files.delete(File(directory.pinned, ".${name.removeSuffix(".comp")}.lease").toPath())
            }
        }
    }

    private fun <T> registry(action: (PinnedDirectory, LockedFile) -> T): T = synchronized(registryGate) {
        val supplied = root.absoluteFile.normalize()
        check(supplied == supplied.canonicalFile) { "Project staging root contains a symbolic alias" }
        check(supplied.isDirectory || supplied.mkdirs()) { "Cannot create project staging root" }
        PinnedDirectory(supplied).use { directory ->
            LockedFile(File(directory.pinned, ".registry.lock")).use { guard ->
                guard.lock()
                directory.checkIdentity(); guard.checkIdentity()
                action(directory, guard)
            }
        }
    }

    private fun removeOwnedTree(file: File, depth: Int, budget: CleanupBudget,
        rootDirectory: PinnedDirectory, guard: LockedFile) {
        budget.visit()
        rootDirectory.checkIdentity(); guard.checkIdentity()
        val stat = Os.lstat(file.path)
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            if (depth >= policy.cleanupDepth) throw CleanupLimit()
            PinnedDirectory(file).use { directory ->
                Files.newDirectoryStream(directory.pinned.toPath()).use { entries ->
                    for (entry in entries) {
                        directory.checkIdentity()
                        removeOwnedTree(entry.toFile(), depth + 1, budget, rootDirectory, guard)
                    }
                }
                directory.checkIdentity()
            }
        }
        rootDirectory.checkIdentity(); guard.checkIdentity()
        check(sameIdentity(stat, Os.lstat(file.path))) { "Project staging entry changed during cleanup" }
        // Symlinks are terminal entries here, including a UUID.comp link. Never descend through them.
        Files.delete(file.toPath())
    }

    private class CleanupLimit : IllegalStateException("Project staging cleanup reached its bounded work limit")
    private class CleanupBudget(private var remaining: Int, private val cancellation: ProjectTransportCancellation?) {
        fun visit() { cancellation?.check(); if (remaining-- <= 0) throw CleanupLimit() }
    }

    private class PinnedDirectory(val location: File) : Closeable {
        private val descriptor: ParcelFileDescriptor
        val pinned: File
        private val identity: StructStat
        init {
            val fd = Os.open(location.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                identity = Os.fstat(fd)
                check(OsConstants.S_ISDIR(identity.st_mode)) { "Project staging path is not a directory" }
                descriptor = ParcelFileDescriptor.dup(fd)
            } finally { Os.close(fd) }
            // Operations use the opened directory, not a root path that can be replaced after validation.
            pinned = File("/proc/self/fd/${descriptor.fd}")
            try { checkIdentity() } catch (error: Throwable) { descriptor.close(); throw error }
        }
        fun checkIdentity() { check(sameIdentity(identity, Os.lstat(location.path))) { "Project staging directory was replaced" } }
        override fun close() { descriptor.close() }
    }

    internal class LockedFile(openLocation: File, private val location: File = openLocation) : Closeable {
        private val output: FileOutputStream
        private val identity: StructStat
        private var held: FileLock? = null
        init {
            val fd = Os.open(openLocation.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NONBLOCK or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180) // 0600
            try {
                identity = Os.fstat(fd)
                check(OsConstants.S_ISREG(identity.st_mode) && identity.st_nlink == 1L) { "Project staging lock is not a unique regular file" }
                output = FileOutputStream(fd)
            } catch (error: Throwable) { Os.close(fd); throw error }
            try { checkIdentity() } catch (error: Throwable) { output.close(); throw error }
        }
        fun checkIdentity() { check(sameIdentity(identity, Os.lstat(location.path))) { "Project staging lock was replaced" } }
        fun tryLock(): Boolean {
            held = try { output.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            return held != null
        }
        fun lock() { held = output.channel.lock() }
        override fun close() { try { held?.release() } finally { output.close() } }
    }
}

/** Held across provider copying and native candidate loading. Close is idempotent and never touches the source. */
internal class ProjectStagingLease internal constructor(val directory: File,
    private val lock: ProjectStagingStorage.LockedFile, private val cleanup: (ProjectStagingStorage.LockedFile) -> Unit,
    private val released: () -> Unit) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { cleanup(lock) } finally { try { lock.close() } finally { released() } }
    }
}

private fun sameIdentity(first: StructStat, second: StructStat): Boolean = first.st_dev == second.st_dev && first.st_ino == second.st_ino
private fun existsWithoutFollowing(file: File): Boolean = Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
