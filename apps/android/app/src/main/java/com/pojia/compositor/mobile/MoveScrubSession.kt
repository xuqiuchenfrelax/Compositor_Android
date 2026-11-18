package com.pojia.compositor.mobile

/** One label gesture owns its fixed geometry and may wait for an earlier field's main acknowledgment. */
internal class MoveScrubSession internal constructor(internal val viewEpoch: Long,
    internal var inputEpoch: Long, internal val viewportVersion: Long,
    internal val screenOrigin: MovePoint,
    internal val selection: NativeSelection, internal val workspaceToken: WorkspaceToken?,
    internal val target: TransformTarget, internal var original: Transform64,
    internal var scaleBasis: MoveScaleBasis?, internal var numeric: TransformScrubBasis,
    internal var sampling: Int, internal val persistent: Boolean,
    internal var owner: TransformController.Owner? = null) {
    internal var waiting = false
    internal var expectedRevision = selection.revision
    internal var expectedGeneration = selection.generation
    internal var latestTranslationDp: Double? = null
    internal var ended = false
    internal var completed = false
}
