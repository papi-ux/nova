LOCAL_PATH := $(call my-dir)

# PyroWave, an intra-only wavelet codec that decodes as plain Vulkan compute.
#
# Prebuilt per ABI rather than built from source, because upstream is CMake and this tree is
# ndk-build. Built from https://github.com/Themaister/pyrowave with the same NDK this app pins,
# then stripped. See README.md for the exact command.
#
# It carries its own C++ runtime and finds Vulkan through dlopen, so it needs no APP_STL here and no
# -lvulkan, and an APK holding it still installs on a device with no Vulkan at all.
include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libpyrowave-shared.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_SHARED_LIBRARY)

# The thin C layer Kotlin calls. Separate from moonlight-core because it has nothing to do with the
# streaming protocol and everything to do with a codec, and because a build without the codec should
# be one module lighter rather than one #ifdef deeper.
include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave-jni
LOCAL_SRC_FILES := pyrowave_jni.c
LOCAL_SHARED_LIBRARIES := pyrowave
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
