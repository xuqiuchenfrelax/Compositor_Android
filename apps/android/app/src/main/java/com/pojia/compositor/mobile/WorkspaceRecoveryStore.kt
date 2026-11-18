package com.pojia.compositor.mobile

import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32

internal data class RecoveryTarget(val generation: Long, val fileName: String,
    val documentUuid: String, val projectBaseline: Long?, val sourceIdentity: String?,
    // null exists only when reading a v1 pointer whose relation must be derived after loading its checkpoint.
    val projectClean: Boolean? = null)
internal enum class RecoveryFault { NONE, BEFORE_RENAME, AFTER_RENAME }
internal data class PointerCommit(val committed: Boolean, val durabilityError: String? = null)
internal data class PublicationProbe(val beforeGate: (() -> Unit)? = null, val insideGate: (() -> Unit)? = null)
internal enum class RecoverySaveKind { BOOTSTRAP, AUTOSAVE, FLUSH, CLOSE, PROJECT_SAVE, OUTGOING_NEW, CANDIDATE_INSTALL }
internal data class RecoveryPublicationHooks(
    val beforeCheckpoint: ((RecoverySaveKind, RecoveryTarget, File) -> Unit)? = null,
    val afterCheckpoint: ((RecoverySaveKind, RecoveryTarget, File, Long) -> Unit)? = null,
    val pointerFault: ((RecoverySaveKind, RecoveryTarget) -> RecoveryFault)? = null,
    val publicationProbe: ((RecoverySaveKind, RecoveryTarget) -> PublicationProbe?)? = null,
)

/** The pointer is the commit point. All checkpoint attempts remain available; cleanup is separate. */
internal class WorkspaceRecoveryStore(private val directory: File) {
    val legacy = File(directory, "current.checkpoint")
    private val active = File(directory, "active.json")
    fun checkpoint(target: RecoveryTarget): File = File(directory, target.fileName)
    fun target(generation: Long, editor: NativeEditor, baseline: Long?, source: String?): RecoveryTarget {
        require(generation in 1 until Long.MAX_VALUE) { "Invalid recovery generation" }
        return RecoveryTarget(generation, freshFileName(),
            editor.state().document.uuid, baseline, source, false)
    }
    fun attempt(target: RecoveryTarget): RecoveryTarget {
        require(target.generation in 1 until Long.MAX_VALUE) { "Invalid recovery generation" }
        return target.copy(fileName = freshFileName())
    }
    private fun freshFileName(): String {
        var name: String
        do { name = "generation-${UUID.randomUUID()}.checkpoint" } while (File(directory, name).exists())
        return name
    }

    fun read(): RecoveryTarget? {
        if (!active.exists()) return null
        check(active.length() in 1..16_384) { "Recovery pointer exceeds its size limit" }
        val envelope = JSONObject(active.readText(Charsets.UTF_8))
        val version = envelope.getInt("version")
        check(version == 1 || version == 2) { "Unsupported recovery pointer version" }
        if (version == 2) {
            check(envelope.keys().asSequence().toSet() == setOf("version", "payload", "crc32")) {
                "Invalid recovery pointer fields"
            }
            val rawVersion = envelope.get("version")
            val rawChecksum = envelope.get("crc32")
            val checksum = if (rawChecksum is Number) rawChecksum.toString().toLongOrNull() else null
            check(rawVersion is Number && rawVersion.toString() == "2" &&
                checksum != null && checksum in 0L..0xffff_ffffL) {
                "Invalid recovery envelope numbers"
            }
        }
        val payload = envelope.getString("payload")
        check(crc(payload) == envelope.getLong("crc32")) { "Recovery pointer checksum failed" }
        val value = JSONObject(payload)
        if (version == 2) check(value.keys().asSequence().toSet() ==
            setOf("generation", "checkpoint", "documentUuid", "projectBaseline", "sourceIdentity", "projectClean")) {
            "Invalid recovery payload fields"
        }
        fun integer(name: String): Long {
            val raw = value.get(name)
            check(raw is Number && raw.toString().toLongOrNull() != null) { "Invalid recovery integer" }
            return raw.toString().toLong()
        }
        val generation = if (version == 2) integer("generation") else value.getLong("generation")
        val fileName = value.getString("checkpoint")
        val uuid = value.getString("documentUuid")
        val source = if (value.isNull("sourceIdentity")) null else value.getString("sourceIdentity")
        val baseline = if (value.isNull("projectBaseline")) null else
            if (version == 2) integer("projectBaseline") else value.getLong("projectBaseline")
        val clean = if (version == 1) null else value.get("projectClean").let {
            check(it is Boolean) { "Invalid recovery clean relation" }; it
        }
        check(clean != true || baseline != null) { "Clean recovery requires a project baseline" }
        check(generation in 1 until Long.MAX_VALUE && Regex("generation-[0-9a-fA-F-]{36}\\.checkpoint").matches(fileName)) {
            "Invalid recovery target"
        }
        check(UUID.fromString(uuid).toString().equals(uuid, ignoreCase = true)) { "Invalid recovery identity" }
        check(baseline == null || baseline >= 0) { "Invalid project baseline" }
        check(source == null || source.length <= 4096) { "Recovery source identity exceeds its limit" }
        val file = File(directory.canonicalFile, fileName)
        check(file.canonicalFile == file) {
            "Recovery target escapes its folder"
        }
        return RecoveryTarget(generation, fileName, uuid, baseline, source, clean)
    }

    fun commit(target: RecoveryTarget, fault: RecoveryFault = RecoveryFault.NONE,
        publish: (() -> Unit) -> Unit = { it() },
        probe: PublicationProbe? = null,
        canCommit: () -> Boolean = { true }): PointerCommit {
        check(target.projectClean != null && (target.projectClean != true || target.projectBaseline != null)) {
            "Recovery publication requires a captured clean relation"
        }
        val payload = JSONObject().put("generation", target.generation).put("checkpoint", target.fileName)
            .put("documentUuid", target.documentUuid).put("projectBaseline", target.projectBaseline ?: JSONObject.NULL)
            .put("sourceIdentity", target.sourceIdentity ?: JSONObject.NULL).put("projectClean", target.projectClean).toString()
        val bytes = JSONObject().put("version", 2).put("payload", payload).put("crc32", crc(payload))
            .toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 16_384) { "Recovery pointer exceeds its size limit" }
        val temporary = File(directory, ".active-${UUID.randomUUID()}.tmp")
        var committed = false
        return try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            syncDirectory()
            check(fault != RecoveryFault.BEFORE_RENAME) { "Injected failure before pointer commit" }
            probe?.beforeGate?.invoke()
            publish {
                check(canCommit()) { "Workspace installation was cancelled before commit" }
                probe?.insideGate?.invoke()
                Os.rename(temporary.path, active.path)
                committed = true
            }
            check(fault != RecoveryFault.AFTER_RENAME) { "Injected durability failure after pointer commit" }
            syncDirectory()
            PointerCommit(true)
        } catch (error: Exception) {
            if (committed) PointerCommit(true, error.message ?: "Recovery pointer durability is uncertain")
            else throw error
        } finally { temporary.delete() }
    }
    private fun syncDirectory() {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Recovery folder is not a directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
    fun preserveRejected() {
        for (file in listOf(active, legacy)) if (file.exists()) {
            check(file.renameTo(File(directory, "rejected-${System.nanoTime()}-${file.name}"))) {
                "The existing recovery data could not be preserved"
            }
        }
    }
    private fun crc(payload: String): Long = CRC32().apply { update(payload.toByteArray(Charsets.UTF_8)) }.value
}
