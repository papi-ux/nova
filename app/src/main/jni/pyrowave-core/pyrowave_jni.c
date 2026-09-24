// PyroWave's header refuses to compile unless the Vulkan API is already declared.
// Nothing here calls Vulkan directly; the declarations are all the header wants.
#include <vulkan/vulkan_core.h>

#include <pyrowave/pyrowave.h>

#include "pyrowave_device_c.h"

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
    void *owned = pyrowave_device_acquire(false, &device);
    if (owned == NULL || device == NULL) {
        LOGI("no usable Vulkan device");
        return PROBE_UNUSABLE;
    }

    // Upstream recommends the fragment path for mobile GPUs with weak compute. On the devices this
    // was written against it recommends it and then decodes visibly wrong there, so the answer is
    // recorded rather than obeyed, and the caller decides.
    const bool prefers_fragment = pyrowave_decoder_device_prefers_fragment_path(device);
    pyrowave_device_release(owned);

    // Presentation is a second question: a device the codec accepts is not automatically one that
    // carries VK_KHR_swapchain. Asked here so a device that can decode but never show it says so
    // now rather than at the first frame.
    pyrowave_device presenting = NULL;
    void *presenting_owned = pyrowave_device_acquire(true, &presenting);
    LOGI("presentation capable: %d", presenting_owned != NULL ? 1 : 0);
    if (presenting_owned != NULL) {
        pyrowave_device_release(presenting_owned);
    }

    LOGI("usable Vulkan device, prefers fragment path: %d", (int) prefers_fragment);
    return prefers_fragment ? PROBE_FRAGMENT : PROBE_COMPUTE;
}

/**
 * Decode one known frame and report how far off it came out.
 *
 * The frame is 34x30 of a fixed gradient, encoded on a desktop and shipped with the app, and the
 * expected pixels are regenerated here from the same formula rather than shipped beside it. So this
 * answers the only question that matters before any of the streaming work is worth doing: can this
 * device decode what a host will send, exactly.
 *
 * It decodes to a CPU buffer, which a real session never will. That is deliberate: it removes the
 * swapchain, the surface and the presentation clock from the answer, so a failure here is the codec
 * and nothing else.
 *
 * @return the largest luma error in the frame, 0 meaning exact, or a negative code on failure.
 */
JNIEXPORT jint JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeDecodeSelfTest(
        JNIEnv *env, jclass clazz, jbyteArray bitstream, jboolean fragment_path) {
    (void) clazz;

    enum { Width = 34, Height = 30 };

    const jsize size = (*env)->GetArrayLength(env, bitstream);
    if (size <= 0) {
        return -10;
    }
    jbyte *payload = (*env)->GetByteArrayElements(env, bitstream, NULL);
    if (payload == NULL) {
        return -11;
    }

    jint outcome = -1;
    pyrowave_device device = NULL;
    pyrowave_decoder decoder = NULL;
    void *owned = NULL;

    do {
        owned = pyrowave_device_acquire(false, &device);
        if (owned == NULL || device == NULL) {
            outcome = -12;
            break;
        }

        pyrowave_decoder_create_info info = {0};
        info.device = device;
        info.width = Width;
        info.height = Height;
        info.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
        info.fragment_path = fragment_path ? true : false;
        if (pyrowave_decoder_create(&info, &decoder) != PYROWAVE_SUCCESS || decoder == NULL) {
            outcome = -13;
            break;
        }

        if (pyrowave_decoder_push_packet(decoder, payload, (size_t) size) != PYROWAVE_SUCCESS) {
            outcome = -14;
            break;
        }
        if (!pyrowave_decoder_decode_is_ready(decoder, false)) {
            outcome = -15;
            break;
        }

        uint8_t luma[Height][Width];
        uint8_t cb[Height][Width];
        uint8_t cr[Height][Width];

        pyrowave_cpu_buffer out = {0};
        out.format = PYROWAVE_CPU_BUFFER_FORMAT_YUV420P;
        out.width = Width;
        out.height = Height;
        out.data[0] = &luma[0][0];
        out.data[1] = &cb[0][0];
        out.data[2] = &cr[0][0];
        out.row_stride_in_bytes[0] = sizeof(luma[0]);
        out.row_stride_in_bytes[1] = sizeof(cb[0]);
        out.row_stride_in_bytes[2] = sizeof(cr[0]);
        out.plane_size_in_bytes[0] = sizeof(luma);
        out.plane_size_in_bytes[1] = sizeof(cb);
        out.plane_size_in_bytes[2] = sizeof(cr);

        if (pyrowave_decoder_decode_cpu_buffer_synchronous(decoder, &out) != PYROWAVE_SUCCESS) {
            outcome = -16;
            break;
        }

        int worst = 0;
        for (int y = 0; y < Height; y++) {
            for (int x = 0; x < Width; x++) {
                const int expected = (uint8_t) (3 * x + 5 * y);
                const int delta = (int) luma[y][x] - expected;
                const int magnitude = delta < 0 ? -delta : delta;
                if (magnitude > worst) {
                    worst = magnitude;
                }
            }
        }
        LOGI("decode self test: worst luma error %d (fragment path %d)", worst, (int) fragment_path);
        outcome = worst;
    } while (0);

    if (decoder != NULL) {
        pyrowave_decoder_destroy(decoder);
    }
    if (owned != NULL) {
        pyrowave_device_release(owned);
    }
    (*env)->ReleaseByteArrayElements(env, bitstream, payload, JNI_ABORT);
    return outcome;
}
