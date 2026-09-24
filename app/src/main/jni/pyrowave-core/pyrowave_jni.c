// PyroWave's header refuses to compile unless the Vulkan API is already declared.
// Nothing here calls Vulkan; the declarations are all the header wants.
#include <vulkan/vulkan_core.h>

#include <pyrowave/pyrowave.h>

#include <jni.h>
#include <stdio.h>

JNIEXPORT jstring JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeApiVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    uint32_t major = 0, minor = 0, patch = 0;
    pyrowave_get_api_version(&major, &minor, &patch);

    char version[32];
    snprintf(version, sizeof(version), "%u.%u.%u", major, minor, patch);
    return (*env)->NewStringUTF(env, version);
}
