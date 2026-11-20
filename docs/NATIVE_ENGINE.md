# 预编译原生引擎

Android 界面通过 JNI 调用 C/C++ 引擎。当前仓库附带 `arm64-v8a` 和 `x86_64` 两份 `libcompositor_android.so`，方便开发者检出代码后直接打包。原生接口版本为 **21**，与下表中的 Android CPU 架构是两个不同概念。

## 打包与校验

安装 JDK 17 和 Android SDK Platform 35。在仓库根目录执行下列命令，无需安装 NDK 或 CMake：

```sh
./apps/android/gradlew -p apps/android :app:verifyNativeEngine :app:assembleDebug
python3 scripts/android-native-check.py --prebuilt apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Gradle 在 `preBuild` 时验证 `apps/android/app/src/main/jniLibs/` 下两份库的 SHA-256。APK 检查脚本核对 ELF 段与 ZIP 对齐；`--prebuilt` 还要求 APK 包含这两种 ABI，并核对 ELF 架构和打包前后的 SHA-256。更新库时应同时核对 JNI 绑定和 Gradle 中固定的哈希，并验证工程打开和导出。

## 当前制品

两份库来自 P210 引擎构建，编译时将源码路径映射为相对路径，并在入库前剥离调试符号。Gradle 打包时保留已校验的库字节。

| Android ABI | SHA-256 of `libcompositor_android.so` |
| --- | --- |
| `arm64-v8a` | `7c99794c3624701a01b65f8586768933e039d493c13a3bcbacf8e78d14a1e1bd` |
| `x86_64` | `49e38548af1fa6b13d293fedd9dc5c8677e359a6a88128b7523aab158a6fa8b5` |

这两份库使用 Android NDK `27.2.12479018` 与 CMake `3.22.1` 构建，并启用灵活页大小。仓库内的上游和编解码器许可声明会随 APK 打包为 `COMPOSITOR_LICENSE.txt` 和 `THIRD_PARTY_NOTICES.txt`。

## 源码发布

当前公开检出可以独立打包 APK，完整引擎尚不能从此检出重建。后续将固定 JNI 契约与跨端测试样本，逐步公开原生模块、构建脚本和 CI；过渡期继续提供预编译库。发布顺序和验收条件见[原生引擎开源路线](ROADMAP.md#原生引擎开源路线)。
