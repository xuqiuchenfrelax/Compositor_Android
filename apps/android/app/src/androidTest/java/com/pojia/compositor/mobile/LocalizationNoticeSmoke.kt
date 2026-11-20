package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.LocaleList

/** Locale catalogs resolve at presentation time; error strings are kept as diagnostics, never translation keys. */
internal fun localizationNoticeSmoke(instrumentation: Instrumentation): String {
    val base = instrumentation.targetContext
    fun context(tag: String) = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
    })
    val english = context("en")
    val chinese = context("zh-CN")
    val nativeCases = mapOf(
        3 to UiNoticeKey.MEMORY_LIMIT, 5 to UiNoticeKey.UNSUPPORTED_PROJECT,
        7 to UiNoticeKey.FILE_IO_FAILED, 8 to UiNoticeKey.CORRUPT_PROJECT,
        9 to UiNoticeKey.OPERATION_CANCELLED,
    )
    for ((status, expected) in nativeCases) {
        check(uiFailure(ProjectImportException(status), UiNoticeKey.PROJECT_OPEN_FAILED).key == expected)
    }
    val renderNotice = uiFailure(ProjectImportException(5, renderUnsupported = true), UiNoticeKey.PROJECT_OPEN_FAILED)
    check(renderNotice.key == UiNoticeKey.UNSUPPORTED_PROJECT_RENDER)
    check(renderNotice.text(english) == "This project uses layer rendering settings that this version cannot display. The project was not changed.")
    check(renderNotice.text(chinese) == "当前版本无法显示此工程的图层渲染设置。原工程未修改。")
    val transportCases = mapOf(
        ProjectTransportFailure.INVALID to UiNoticeKey.INVALID_PROJECT,
        ProjectTransportFailure.UNSUPPORTED to UiNoticeKey.UNSUPPORTED_PROJECT,
        ProjectTransportFailure.LIMIT to UiNoticeKey.PROJECT_LIMIT,
        ProjectTransportFailure.IO to UiNoticeKey.FILE_IO_FAILED,
        ProjectTransportFailure.CHANGED to UiNoticeKey.SOURCE_CHANGED,
        ProjectTransportFailure.CANCELLED to UiNoticeKey.OPERATION_CANCELLED,
        ProjectTransportFailure.DISK to UiNoticeKey.DISK_LIMIT,
    )
    for ((status, expected) in transportCases) {
        check(uiFailure(ProjectTransportException(status, "diagnostic sentinel"), UiNoticeKey.PROJECT_FAILED).key == expected)
    }
    check(uiFailure(SecurityException("permission sentinel"), UiNoticeKey.IMAGE_IMPORT_FAILED).key == UiNoticeKey.FILE_ACCESS_DENIED)
    check(uiFailure(CheckpointException(4), UiNoticeKey.RECOVERY_SAVE_FAILED).key == UiNoticeKey.PENDING_EDIT)
    val published = CheckpointWriteResult(7, 12, true, true, false)
    check(uiFailure(CheckpointException(7, published), UiNoticeKey.RECOVERY_SAVE_FAILED).key == UiNoticeKey.RECOVERY_DURABILITY)
    val unknown = IllegalStateException("unknown diagnostic sentinel")
    check(uiFailure(unknown, UiNoticeKey.IMAGE_EXPORT_FAILED).key == UiNoticeKey.IMAGE_EXPORT_FAILED)
    check(unknown.message == "unknown diagnostic sentinel")
    val pending = UiNotice(UiNoticeKey.COPYING_PROJECT_PROGRESS, 2, 5)
    check(pending.text(english) == "Copying project: 2 / 5 assets")
    check(pending.text(chinese) == "正在复制工程：2 / 5 个资源")
    val image = ImageRequestState(error = "original technical diagnostic", errorNotice = UiNotice(UiNoticeKey.IMAGE_BUSY))
    check(image.localizedError(chinese) == "另一个图像操作仍在进行，请等待其完成。")
    check(image.error == "original technical diagnostic")
    val recovery = WorkspaceState(recoveryError = "original recovery diagnostic",
        recoveryErrorNotice = UiNotice(UiNoticeKey.RECOVERY_OPEN_FAILED))
    check(recovery.localizedRecoveryError(chinese) == "无法恢复已保存的文档，已有文件已保留。")
    check(recovery.recoveryError == "original recovery diagnostic")
    for (key in UiNoticeKey.entries) {
        check(english.getString(key.resource).isNotBlank())
        check(chinese.getString(key.resource).isNotBlank())
    }
    return "nativeStatusCases=${nativeCases.size} transportCases=${transportCases.size} noticeKeys=${UiNoticeKey.entries.size} deferredLocale=true technicalDiagnosticsPreserved=true"
}
