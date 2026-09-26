# PyroWave

[PyroWave](https://github.com/Themaister/pyrowave) by Hans-Kristian Arntzen, MIT licensed, vendored
as a prebuilt shared library per ABI.

Upstream builds with CMake and this tree builds with ndk-build, so building it in place would mean
converting the whole native build for one module. The libraries here were produced with the NDK this
app already pins:

```bash
git clone --recurse-submodules https://github.com/Themaister/pyrowave
cd pyrowave && git checkout 186f0393b77f7755953b5ecde994bb1cec2e4155
bash checkout_granite.sh
NDK=$ANDROID_HOME/ndk/27.0.12077973
for abi in arm64-v8a x86_64 armeabi-v7a; do
  cmake -S . -B build-$abi -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PWD/build-$abi/output \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
  ninja -C build-$abi install
  $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded \
    build-$abi/output/lib/libpyrowave-shared.so
done
```

The linker flags are not optional. Android 15 brought devices with 16 KB memory pages, and a
library aligned to 4 KB will not load on one, so an APK carrying it cannot be installed at all.
`ndk-build` aligns what it links and these are `PREBUILT_SHARED_LIBRARY`, so nothing in this tree
applies that default for them: it has to be asked for here. v1.4.13-beta.3 shipped without it.

Confirm before committing a rebuild, because every other check passes on a misaligned APK:

```bash
readelf -lW build-$abi/output/lib/libpyrowave-shared.so | awk '$1=="LOAD"{print $NF}'   # want 0x4000
python3 tools/check_native_page_alignment.py app/src/main/jni/pyrowave-core/*/libpyrowave-shared.so
```

`tools/test_native_page_alignment.py` asserts it too, so a 4 KB library fails the build rather than a
tester's install.

Each stripped library is about 1.3 MB, and Nova splits APKs by ABI, so an install carries one.

The library needs only libdl, liblog, libm and libc: it carries its own C++ runtime and finds Vulkan
through dlopen, so it adds no `APP_STL` requirement, needs no `-lvulkan`, and an APK holding it still
installs and runs on a device with no Vulkan at all.

Its C API is not ABI stable before 1.0, so `pyrowave.h` here must be the header these libraries were
built from. `PyroWave.apiVersion()` reads the version out of the library rather than trusting that.

Nor is its bitstream, which is why the revision above is pinned rather than left at master, and why it
has to match Polaris's `third-party/pyrowave` submodule. That agreement is what
`PYROWAVE_PROFILE_TOKEN` carries in the RTSP handshake, so all three move together: this checkout,
that submodule, and the token. Two of them agreeing and the third not is a stream that decodes to
noise, which is the failure the token exists to turn into a refusal.

## Measuring the Android renderer

Native code uses `APP_OPTIM=release` even in debug APKs. Symbols and APK debuggability remain
available. For an unoptimized source debugger build, pass `-PnovaNativeOptimization=debug` to
Gradle. `-PnovaNativeDebugChecks=false` is a separate measurement setting: the default debug
checks deliberately inject FEC loss and must be disabled when measuring delivery performance.
The prebuilt codec is already a Release build and is unaffected by these switches.

GPU/CPU timing is off by default. Enable it on the test device before starting a new stream:

```sh
adb -s DEVICE shell setprop debug.nova.pyrowave_timing 1
adb -s DEVICE logcat -s PyroWave:I
```

Set the property to `0` and start a new stream to disable it. It is read at renderer creation;
changing it during a stream takes effect after reconnecting. No overlay is required.

The `timing` log line contains five-second windows and a final partial window at shutdown.
Each metric is **mean milliseconds / maximum milliseconds / sample count**. `-1/-1/0` means
unavailable, not zero work. CPU counts can differ after a refused frame and at window boundaries.

- `gpu_planes_ms`: start of GPU commands through plane decode (or the test path's plane upload),
  including its barriers.
- `gpu_draw_ms`: the following draw interval through command completion, including barriers and
  any GPU wait for the swapchain image. This is not display scanout or end-to-end latency.
- `cpu_fence_ms`: time in the renderer's existing wait for the previous submitted frame.
- `cpu_prepare_ms`: packet parsing/readiness checks, including the first-frame retry if needed.
- `cpu_record_ms`: CPU time recording plane decode/upload commands.
- `cpu_acquire_ms`, `cpu_submit_ms`, `cpu_present_ms`: time inside the corresponding Vulkan calls.

Timestamp reads occur only after the existing frame fence (or device idle at shutdown), without
`VK_QUERY_RESULT_WAIT_BIT`. Unavailable reads are counted separately. A device without timestamp
support, or whose query pool cannot be created, retains CPU timing and continues streaming.
Timestamps can perturb GPU scheduling; compare enabled and disabled runs with the same scene,
resolution, FPS, host settings and network before drawing performance conclusions.

Portable checks: `python3 -m unittest tools.test_pyrowave_timing tools.test_native_optimization`.
The latter needs initialized native submodules and the pinned NDK under `ANDROID_HOME`.
