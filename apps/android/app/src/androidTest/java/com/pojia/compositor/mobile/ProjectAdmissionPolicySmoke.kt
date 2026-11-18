package com.pojia.compositor.mobile

internal object ProjectAdmissionPolicySmoke {
    fun run(): String {
        val mib = 1024L * 1024
        val generous = ProjectAdmissionPolicy.calculate(12 * mib, 2048 * mib, 128 * mib)
        check(generous.limitBytes == 512 * mib)
        check(generous.residentBytes == 12 * mib + ProjectAdmissionPolicy.EXTERNAL_RESERVE)
        val constrained = ProjectAdmissionPolicy.calculate(12 * mib,
            ProjectAdmissionPolicy.EXTERNAL_RESERVE + 128 * mib + 32 * mib, 128 * mib)
        check(constrained.limitBytes - constrained.residentBytes == 32 * mib)
        check(runCatching { ProjectAdmissionPolicy.calculate(400 * mib, 2048 * mib, 0) }.isFailure)
        check(runCatching { ProjectAdmissionPolicy.calculate(0, 256 * mib, 128 * mib) }.isFailure)
        check(runCatching { ProjectAdmissionPolicy.calculate(Long.MAX_VALUE, Long.MAX_VALUE, 0) }.isFailure)
        check(runCatching { ProjectAdmissionPolicy.calculate(-1, 2048 * mib, 0) }.isFailure)
        val cancelled = ProjectOpenCancellation()
        check(cancelled.cancel())
        var entered = false
        check(runCatching { cancelled.loadCandidate { entered = true } }.isFailure && !entered)
        val finishing = ProjectOpenCancellation()
        finishing.loadCandidate { check(finishing.loadingCandidate && !finishing.cancel()); entered = true }
        check(entered)
        return "Admission policy: device headroom, retained payload, managed cap, low memory and overflow passed"
    }
}
