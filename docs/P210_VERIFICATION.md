# P210 跨端回归

Mac 蒙版对照为 1.6.13（`1910aeabf73b9baf471548b4e23c27a3dd1a0b60`）。P210 修正独立栅格蒙版旋转至直角后，横向直边覆盖半个像素且另一轴正对纹素中心时的裁切和填充量化。变更不增加像素缓冲区，也不改变工程格式。

内部 77,395 例 Mac 对照中，相比 P209 修正了 3,486 个不同的 RGBA 字节，未新增或扩大误差；另有 4,800 个随机贴边样例输出不变。公开测试 APK 附带 `p193-folder-mask` 至 `p210-raster-mask` 的合成工程和 Mac 导出像素，逐字节比较画布、保存重开和 PNG 导出，并核对蒙版采样与变换是否保留。

先按 [README](../README.md#构建与验证) 构建两个调试 APK，再把它们安装到专用 Android 测试设备。以下命令从仓库根目录运行，假设 ADB 已指向该测试设备。v22 测试需要先把合成工程放入测试 APK 的专属目录，以便通过系统文件选择器打开：

```sh
runner=com.pojia.compositor.mobile.test/com.pojia.compositor.mobile.EngineSmokeInstrumentation
adb shell am instrument -w -r -e include rasterMaskHalfRow "$runner"
adb shell run-as com.pojia.compositor.mobile.test mkdir -p files/p165-v22-fixtures
tar -C compatibility/project_v22 -cf - . | adb shell -T run-as com.pojia.compositor.mobile.test tar -xf - -C files/p165-v22-fixtures
adb shell am instrument -w -r -e include projectV21Ui -e projectSourceVersion 22 "$runner"
adb shell am instrument -w -r -e include duplicateNudgeGroupUi "$runner"
```

结果应为 `INSTRUMENTATION_CODE: -1`。工程测试的 `projectV21Ui` 保留既有选择器名称，通过 `projectSourceVersion` 切换 v21/v22；测试 v21 时，将 `compatibility/project_v21` 导入 `files/p146-v21-fixtures`。v22 测试覆盖简体中文和英文的打开、编辑、撤销/重做、另存和重新打开，以及未支持工程的错误提示。

公开检出构建的调试 APK 在 Android 15 隔离模拟器上通过 26 项专项：18 项蒙版、3 项键盘复制移动、2 项工程版本、1 项本地化、2 项图层分组与关系。Lint 和双 ABI 打包检查通过。

剩余差异包括极窄蒙版轮廓贴边时的填充量化。例如一例直边样本在 Mac 导出 Alpha 为 2，Android 为 240；移动约 `10⁻¹³` 像素便会改变 Mac 的填充结果。当前没有能通过广泛回归的通用修正。Android 全量回归、真机长测和正式签名另行验收。
