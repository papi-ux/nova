# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-21

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Real-time decoding and packet recovery need optimized code even in a debuggable APK. NDK_DEBUG
# still controls symbols/debuggability and NOVA_NATIVE_DEBUG_CHECKS still controls LC_DEBUG.
# Override with APP_OPTIM=debug (Gradle: -PnovaNativeOptimization=debug) for source-level debugging.
APP_OPTIM := release

# The Vulkan presentation path is C++: a renderer juggles dozens of handles with paired create and
# destroy calls, and doing that in C is how one gets leaked on an error path. Static, so no runtime
# ships beside the app; the prebuilt codec already carries its own and the boundary between them is a
# C API, so no C++ object crosses it.
APP_STL := c++_static
