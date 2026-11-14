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

The standalone module, the JS glue and the licence are also published as the
`@enaium/libde265-kmp` npm package (`:libde265-kmp:assembleWasmNpm` stages it in
`build/npm`, `:libde265-kmp:publishWasmNpm` publishes it). npm only accepts
three-component semver, so `1.1.3.1` is published as `1.1.3-1` under
the `latest` tag. Plain JavaScript apps instantiate it without Kotlin:

```js
import { initDe265Kmp } from "@enaium/libde265-kmp";

const module = await initDe265Kmp();
console.log(module.versionString());
```

The npm token is read from `npm.publish.key` in `~/.gradle/gradle.properties`
(or `-Pnpm.publish.key=<token>`); CI reads the `NPM_PUBLISH_KEY` secret.

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

The examples share one SDL3 + Dear ImGui app in `examples/common` (targets:
`jvm`, macOS/Linux/Windows native, Android JVM, Android Native and `wasmJs`).
Camera frames come from [camera-kmp](https://github.com/Enaium/camera-kmp):
Camera2 on Android, Camera2 NDK and V4L2 on Android Native,
AVFoundation/V4L2/Media Foundation on the desktops and `getUserMedia` in the
browser, so the same code runs on every target instead of an Android-only
camera implementation.

The UI shows the camera-kmp capture next to the embedded two-frame HEVC stream
decoded by `Libde265Decoder`; the decoder runs even when no camera is
available (CI, denied permission) by using a deterministic test pattern.

`examples/common` is also the wasmJs browser host: its Emscripten runtimes
(sdl-kmp, imgui-kmp and camera-kmp) are installed as npm packages and served
next to the Kotlin module, and `requestAnimationFrame` drives the shared loop.

The two Android host modules are thin wrappers around the shared code:

- `examples/android-jvm` - an Android app whose `SDLActivity.main()` calls the
  shared `runExample()`.
- `examples/android-native` - an Android app that packages the `libmain.so`
  built by `examples/common`; it carries no camera or UI code of its own.

```bash
# desktop entry of the shared module
./gradlew :examples:common:linkDebugExecutableMacosArm64
./examples/common/build/bin/macosArm64/debugExecutable/common.kexe

./gradlew :examples:android-jvm:assembleDebug
./gradlew -PandroidAbis=arm64-v8a :examples:android-native:assembleDebug

./gradlew :examples:common:wasmJsBrowserDistribution
python3 -m http.server 8080 --directory examples/common/build/dist/wasmJs/productionExecutable
# open http://localhost:8080/ - serve over HTTPS or localhost for the camera
```

The SDL3 and imgui-kmp APIs are published on Maven Central
(`cn.enaium.sdl:sdl-kmp:3.4.18.2`, `cn.enaium.imgui:imgui-kmp:1.93.0.2`); the
browser runtimes are `@enaium/sdl-kmp@3.4.18-2`, `@enaium/imgui-kmp@1.93.0-2`
and `@enaium/camera-kmp@1.0.0`.

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
./gradlew :examples:common:wasmJsBrowserDistribution
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
