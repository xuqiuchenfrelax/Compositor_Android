# Prebuilt native engine

The current checkout packages native libraries rebuilt from the P164 engine source with compiler source paths mapped to relative paths. The reference build used to extract the libraries has APK SHA-256 `5978d7ae3a946aca69d6f8cdc2c2911413fecbe98b92642863896ee3e4b05893`. That hash identifies the extraction build, not the APK produced by this public checkout. The libraries use native ABI version **21** and are stored in `apps/android/app/src/main/jniLibs/`.

| Android ABI | SHA-256 of `libcompositor_android.so` |
| --- | --- |
| `arm64-v8a` | `114f484b32d850752305f3823c80d66af0a9bf2f562992ce8d100edf5c22a757` |
| `x86_64` | `4fcb68fda99eef8cc55f4e29129b1c212f7235367298c0e631176621d425fa15` |

Gradle runs `:app:verifyNativeEngine` before building. The stripped library bytes are preserved in the APK, with uncompressed native packaging checked for ELF and ZIP alignment. JNI declarations, both native binaries, and the checksum pins must be updated together. A changed JNI contract also requires an explicit ABI review and device startup/export tests.

Original toolchain: Android NDK `27.2.12479018`, CMake `3.22.1`, shared C/C++ engine linked through JNI, with flexible page sizes enabled. This repository does not reproduce the native binaries from source yet. See [the roadmap](ROADMAP.md#原生引擎公开路线) for the transition to source builds. The attribution and codec notices are included in this checkout and packaged as `COMPOSITOR_LICENSE.txt` and `THIRD_PARTY_NOTICES.txt` in the APK assets.
