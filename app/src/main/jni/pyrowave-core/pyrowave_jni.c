// PyroWave's header refuses to compile unless the Vulkan API is already declared.
// Nothing here calls Vulkan directly; the declarations are all the header wants.
#include <vulkan/vulkan_core.h>

#include <pyrowave/pyrowave.h>

#include <android/log.h>
#include <jni.h>
#include <stdio.h>

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Kept in step with PyroWave.Probe in Kotlin.
#define PROBE_UNUSABLE (-1)
#define PROBE_COMPUTE 0
#define PROBE_FRAGMENT 1

JNIEXPORT jstring JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeApiVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    uint32_t major = 0, minor = 0, patch = 0;
    pyrowave_get_api_version(&major, &minor, &patch);

    char version[32];
    snprintf(version, sizeof(version), "%u.%u.%u", major, minor, patch);
    return (*env)->NewStringUTF(env, version);
}

/**
 * Make a device, ask it one question, throw it away.
 *
 * The only honest way to answer "can this decode": the codec needs subgroup size control, 16-bit
 * integers and 8-bit storage, and a driver either provides them or it does not. Creating the device
 * is the check. It costs about a tenth of a second, which is why the Kotlin side remembers.
 */
JNIEXPORT jint JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeProbeDecoder(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    pyrowave_device device = NULL;
    pyrowave_result result = pyrowave_create_default_device(&device);
    if (result != PYROWAVE_SUCCESS || device == NULL) {
        LOGI("no usable Vulkan device (result %d)", (int) result);
        return PROBE_UNUSABLE;
    }

    // Upstream recommends the fragment path for mobile GPUs with weak compute. On the devices this
    // was written against it recommends it and then decodes visibly wrong there, so the answer is
    // recorded rather than obeyed, and the caller decides.
    const bool prefers_fragment = pyrowave_decoder_device_prefers_fragment_path(device);
    pyrowave_device_destroy(device);

    LOGI("usable Vulkan device, prefers fragment path: %d", (int) prefers_fragment);
    return prefers_fragment ? PROBE_FRAGMENT : PROBE_COMPUTE;
}
