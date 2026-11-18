package com.pojia.compositor.mobile

import java.util.concurrent.CompletableFuture

/** File publication and workspace bookkeeping are separate outcomes.
 * recoveryWrite is a native writer outcome or a caller admission/native/JNI preparation refusal with no writer
 * and no exposed revision; null also covers unavailable transport, not only an unattempted checkpoint. */
internal data class ProjectSaveResult(val write: ProjectWriteResult, val baselineUpdated: Boolean = false,
    val metadataDurabilityUncertain: Boolean = false, val error: String? = null,
    val recoveryWrite: CheckpointWriteResult? = null) {
    val capturedRevision: Long get() = write.capturedRevision
    val committed: Boolean get() = write.committed
    val durabilityConfirmed: Boolean get() = write.durabilityConfirmed
    val status: Int get() = write.status
    val successful: Boolean get() = write.successful
}
internal class ProjectSaveOperation internal constructor(val token: WorkspaceToken, private val publicationGate: Any) {
    private val gate = Any()
    private var cancelled = false
    private var control: NativeProjectWriteControl? = null
    val result = CompletableFuture<ProjectSaveResult>()
    fun cancel() = synchronized(publicationGate) { synchronized(gate) { cancelled = true; control?.cancel() } }
    internal fun attach(value: NativeProjectWriteControl) = synchronized(gate) {
        control = value; if (cancelled) value.cancel()
    }
    internal fun detach() = synchronized(gate) { control = null }
    internal val isCancelled: Boolean get() = synchronized(gate) { cancelled }
}
