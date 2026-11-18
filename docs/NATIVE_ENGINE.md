# 预编译原生引擎

Android 界面通过 JNI 调用 C/C++ 引擎。当前仓库附带 `arm64-v8a` 和 `x86_64` 两份 `libcompositor_android.so`，方便开发者检出代码后直接打包。原生接口版本为 **21**，与下表中的 Android CPU 架构是两个不同概念。

## 打包与校验

安装 JDK 17 和 Android SDK Platform 35。在仓库根目录执行下列命令，无需安装 NDK 或 CMake：

```sh
./apps/android/gradlew -p apps/android :app:verifyNativeEngine :app:assembleDebug
python3 scripts/android-native-check.py apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Gradle 在 `preBuild` 时验证 `apps/android/app/src/main/jniLibs/` 下两份库的 SHA-256。APK 检查脚本核对库内容、ELF 段与 ZIP 对齐。库缺失或校验不一致时，先确认检出完整；更新库时应同时更新 JNI 绑定和 Gradle 中固定的哈希，并验证真机启动、工程打开和导出。

## 当前制品

两份库来自 P164 引擎构建，编译时将源码路径映射为相对路径。提取库所用的参考 APK 的 SHA-256 为 `5978d7ae3a946aca69d6f8cdc2c2911413fecbe98b92642863896ee3e4b05893`；从公开仓库打包所得 APK 有自己的哈希。

| Android ABI | SHA-256 of `libcompositor_android.so` |
| --- | --- |
| `arm64-v8a` | `114f484b32d850752305f3823c80d66af0a9bf2f562992ce8d100edf5c22a757` |
| `x86_64` | `4fcb68fda99eef8cc55f4e29129b1c212f7235367298c0e631176621d425fa15` |

这两份库使用 Android NDK `27.2.12479018` 与 CMake `3.22.1` 构建，并启用灵活页大小。仓库内的上游和编解码器许可声明会随 APK 打包为 `COMPOSITOR_LICENSE.txt` 和 `THIRD_PARTY_NOTICES.txt`。

## 源码发布

当前公开检出可以独立打包 APK，完整引擎尚不能从此检出重建。后续将固定 JNI 契约与跨端测试样本，逐步公开原生模块、构建脚本和 CI；过渡期继续提供预编译库。发布顺序和验收条件见[原生引擎开源路线](ROADMAP.md#原生引擎开源路线)。
