# PyroWave

[PyroWave](https://github.com/Themaister/pyrowave) by Hans-Kristian Arntzen, MIT licensed, vendored
as a prebuilt shared library per ABI.

Upstream builds with CMake and this tree builds with ndk-build, so building it in place would mean
converting the whole native build for one module. The libraries here were produced with the NDK this
app already pins:

```bash
git clone --recurse-submodules https://github.com/Themaister/pyrowave
cd pyrowave && bash checkout_granite.sh
NDK=$ANDROID_HOME/ndk/27.0.12077973
for abi in arm64-v8a x86_64 armeabi-v7a; do
  cmake -S . -B build-$abi -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PWD/build-$abi/output
  ninja -C build-$abi install
  $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded \
    build-$abi/output/lib/libpyrowave-shared.so
done
```

Each stripped library is about 1.3 MB, and Nova splits APKs by ABI, so an install carries one.

The library needs only libdl, liblog, libm and libc: it carries its own C++ runtime and finds Vulkan
through dlopen, so it adds no `APP_STL` requirement, needs no `-lvulkan`, and an APK holding it still
installs and runs on a device with no Vulkan at all.

Its C API is not ABI stable before 1.0, so `pyrowave.h` here must be the header these libraries were
built from. `PyroWave.apiVersion()` reads the version out of the library rather than trusting that.
