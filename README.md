# libde265-kmp

Kotlin Multiplatform bindings for [libde265](https://github.com/strukturag/libde265):
HEVC/H.265 decoding on JVM, Android, Kotlin/Native and the web.

`libde265` is included as a Git submodule and compiled from source for every
target. One C++ ABI ([`jni/include/de265_kmp.h`](jni/include/de265_kmp.h))
drives the JNI bridge, the Kotlin/Native cinterop and the WebAssembly build, so
every platform shares the same input validation and packed result format. SIMD
kernels are disabled: the scalar decoder runs everywhere, which keeps one code
path across x86, ARM, Android, MinGW and WebAssembly.

Binding version: **1.1.3.1** (libde265 **1.1.3**, decoder only).

## Supported targets

| Platform | Kotlin target | Native artifact |
|---|---|---|
| JVM | `jvm` | `libde265-kmp-jni-jvm-{linux,darwin}-{x86_64,aarch64}`, `libde265-kmp-jni-jvm-windows-x86_64` |
| Android | `android` | `libde265-kmp` AAR with `libde265_jni.so` for every ABI |
| Android Native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | static archive embedded in the cinterop klib |
| Linux | `linuxX64`, `linuxArm64` | static archive embedded in the cinterop klib |
| Windows | `mingwX64` | static archive embedded in the cinterop klib |
| macOS | `macosX64`, `macosArm64` | static archive embedded in the cinterop klib |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | static archive embedded in the cinterop klib |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` | static archive embedded in the cinterop klib |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | static archive embedded in the cinterop klib |
| Kotlin/JS | `js` | embedded WebAssembly module |
| Kotlin/Wasm | `wasmJs` | embedded WebAssembly module |

The web targets compile libde265 to a standalone `.wasm` module with
Emscripten, embed the binary into the published klib and instantiate it
synchronously through a small JS bridge. No resource lookup or async
initialization is required at runtime. The web build is single-threaded; every
other target can start libde265 worker threads through
`Libde265Decoder.workerThreads`.

This build is 8-bit only. The decoded frames are planar 8-bit YUV.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.libde265:libde265-kmp:1.1.3.1")
}
```

The Android AAR and the JVM artifacts contain the native libraries. JVM
consumers automatically receive the per-OS/arch JNI artifacts and
`NativeLoader` extracts the matching one from the classpath.

### Decoding

```kotlin
import cn.enaium.libde265.*

Libde265Decoder.create().use { decoder ->
    // accessUnit is Annex-B: start codes, VPS/SPS/PPS either in-band or sent
    // earlier. MediaCodec/WebCodecs encoders produce exactly this.
    decoder.decode(accessUnit, pts = presentationTimeUs).forEach { frame ->
        println("${frame.width}x${frame.height} ${frame.chroma}, pts=${frame.pts}")
        // Tightly packed planes: frame.y, frame.u, frame.v.
        // 4:2:0 chroma is (width+1)/2 x (height+1)/2.
        render(frame.y, frame.u, frame.v, frame.width, frame.height)
    }

    // Drain pictures libde265 still holds for reordering.
    decoder.flush().forEach { render(it.y, it.u, it.v, it.width, it.height) }
}
```

`decode()` returns every picture the decoder completed for the access unit;
the list is empty when libde265 needs more input. A fatal error raises
`De265Exception`; non-fatal problems are available as
`Libde265Decoder.lastWarning` / `lastWarningText`.

`Libde265.version`, `versionNumber`, `versionMajor`, `versionMinor` and
`versionMaintenance` expose the linked library version.

### Feeding hardware encoders

MediaCodec exposes HEVC parameter sets as an `hvcC`
`HEVCDecoderConfigurationRecord` in `csd-0`, and output buffers are
length-prefixed NAL units. Convert both to Annex-B before calling `decode()`;
the Android examples do this in `HevcAnnexB`. WebCodecs is configured with
`hevc: { format: "annexb" }`, which already provides in-band parameter sets.

## Examples

### `examples/android-jvm`

CameraX `ImageAnalysis` (YUV_420_888) → libyuv I420 → MediaCodec HEVC
**encoder** (no software encoding) → `Libde265Decoder` → libyuv RGBA →
`Bitmap` drawn by a `View`.

MediaCodec HEVC `csd-0` is parsed from `hvcC` and prepended to every keyframe;
length-prefixed output buffers are converted to Annex-B.

```bash
./gradlew :examples:android-jvm:assembleDebug
```

### `examples/android-native`

Camera2 NDK (`AImageReader`, YUV_420_888) → libyuv I420 → NDK `AMediaCodec`
HEVC encoder (NV12 input) → `Libde265Decoder` → libyuv RGBA → SDL3/ImGui
preview.

The desktop build of the same module has no MediaCodec; it decodes an embedded
HEVC stream with the same binding and presents the pictures, so the decoder
path can be run without a device:

```bash
./gradlew :examples:android-native:linkDebugExecutableMacosArm64
DE265_KMP_FRAMES=120 ./examples/android-native/build/bin/macosArm64/debugExecutable/android-native.kexe

./gradlew :examples:android-native:android:assembleDebug
```

### `examples/browser`

`getUserMedia` → WebCodecs `VideoEncoder` with
`hevc: { format: "annexb" }` → `Libde265Decoder` (the same embedded
WebAssembly module the published js artifact uses) → canvas.

Chrome only exposes an HEVC `VideoEncoder` when the platform provides one
(VideoToolbox on macOS, MediaFoundation on Windows, and only in builds where
HEVC encoding is enabled). When `VideoEncoder.isConfigSupported()` reports no
HEVC encoder, the demo says so and decodes an embedded HEVC stream with the
binding instead, so the decoder is still exercised on the page.

```bash
./gradlew :examples:browser:jsBrowserDistribution
# serve examples/browser/build/dist/js/productionExecutable
```

## Building and testing

The repository requires a JDK 21+, CMake and Ninja. The web targets require
Emscripten (`EMSDK` or `~/emsdk`). Cross-compiling for Linux/Windows requires
the matching GCC toolchains; Android builds require the NDK pinned in
`gradle.properties`.

```bash
./gradlew :libde265-kmp:jvmTest
./gradlew :libde265-kmp:macosArm64Test
./gradlew :libde265-kmp:jsNodeTest :libde265-kmp:wasmJsNodeTest
./gradlew :libde265-kmp:testAndroidHostTest
./gradlew :examples:browser:jsBrowserDistribution
```

The tests decode a real 64x64, two-frame Annex-B HEVC stream (embedded in
`commonTest`) and assert dimensions, chroma format, plane sizes and non-empty
luma/chroma.

## License

The bindings are distributed under the **LGPL-3.0-or-later**, matching
libde265's own license; see [LICENSE](LICENSE) (upstream `COPYING`). libde265's
sample applications are MIT licensed, but the decoder library and therefore
these bindings are LGPL. If you distribute a binary that links the native
library, comply with the LGPL (including the relinking/notice requirements).
