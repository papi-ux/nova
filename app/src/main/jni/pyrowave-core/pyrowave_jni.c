// PyroWave's header refuses to compile unless the Vulkan API is already declared.
// Nothing here calls Vulkan directly; the declarations are all the header wants.
#include <vulkan/vulkan_core.h>

#include <pyrowave/pyrowave.h>

#include "pyrowave_device_c.h"
#include "pyrowave_renderer_c.h"

#include <android/native_window_jni.h>

#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>

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

/**
 * Decode the bundled frame and put it on a Surface.
 *
 * The whole client path end to end except the network: library, borrowed device, decoder, colour
 * conversion, swapchain, present. Fed from the decoder's host memory path on purpose, so the only
 * thing being tested here is presentation.
 *
 * @return 0 when a frame reached the screen, or a negative code.
 */
JNIEXPORT jint JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativePresentSelfTest(
        JNIEnv *env, jclass clazz, jobject surface, jbyteArray bitstream) {
    (void) clazz;

    enum { Width = 34, Height = 30 };

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        return -20;
    }

    const jsize size = (*env)->GetArrayLength(env, bitstream);
    jbyte *payload = (*env)->GetByteArrayElements(env, bitstream, NULL);
    if (payload == NULL || size <= 0) {
        ANativeWindow_release(window);
        return -21;
    }

    uint8_t luma[Height][Width];
    uint8_t cb[Height / 2][Width / 2];
    uint8_t cr[Height / 2][Width / 2];
    jint outcome = -1;

    pyrowave_device device = NULL;
    pyrowave_decoder decoder = NULL;
    void *owned = NULL;
    void *renderer = NULL;

    do {
        owned = pyrowave_device_acquire(false, &device);
        if (owned == NULL) { outcome = -22; break; }

        pyrowave_decoder_create_info info = {0};
        info.device = device;
        info.width = Width;
        info.height = Height;
        info.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
        info.fragment_path = false;
        if (pyrowave_decoder_create(&info, &decoder) != PYROWAVE_SUCCESS) { outcome = -23; break; }

        if (pyrowave_decoder_push_packet(decoder, payload, (size_t) size) != PYROWAVE_SUCCESS ||
            !pyrowave_decoder_decode_is_ready(decoder, false)) {
            outcome = -24;
            break;
        }

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
            outcome = -25;
            break;
        }

        renderer = pyrowave_renderer_create(window, Width, Height);
        if (renderer == NULL) { outcome = -26; break; }

        // Twice, because the second frame is the one that proves the barriers are right: the first
        // transitions from UNDEFINED and would paper over a wrong layout on the way back.
        if (!pyrowave_renderer_present(renderer, &luma[0][0], &cb[0][0], &cr[0][0]) ||
            !pyrowave_renderer_present(renderer, &luma[0][0], &cb[0][0], &cr[0][0])) {
            outcome = -27;
            break;
        }

        LOGI("present self test: a frame reached the screen");
        outcome = 0;
    } while (0);

    if (renderer != NULL) pyrowave_renderer_destroy(renderer);
    if (decoder != NULL) pyrowave_decoder_destroy(decoder);
    if (owned != NULL) pyrowave_device_release(owned);
    (*env)->ReleaseByteArrayElements(env, bitstream, payload, JNI_ABORT);
    ANativeWindow_release(window);
    return outcome;
}

/**
 * What a streaming session holds: the renderer, the window it draws into, and somewhere to put a
 * frame on its way through.
 *
 * The self tests build and tear down a renderer inside one call, which a session cannot. It keeps
 * one for as long as its surface lives and pushes thousands of frames through it, so these three
 * have one lifetime and one handle. The renderer does not own the window, its header says the
 * caller retains it, so something has to and this is it.
 */
typedef struct {
    void *renderer;
    ANativeWindow *window;
    uint8_t *scratch;
    jint scratch_size;
} pyrowave_session;

/**
 * Make a renderer a stream can keep, and hand back a handle for it.
 *
 * @return the handle, or 0 when a renderer could not be made.
 */
JNIEXPORT jlong JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeCreateRenderer(
        JNIEnv *env, jclass clazz, jobject surface, jint width, jint height) {
    (void) clazz;

    if (surface == NULL || width <= 0 || height <= 0) {
        return 0;
    }

    pyrowave_session *session = calloc(1, sizeof(*session));
    if (session == NULL) {
        return 0;
    }

    session->window = ANativeWindow_fromSurface(env, surface);
    if (session->window == NULL) {
        free(session);
        return 0;
    }

    session->renderer = pyrowave_renderer_create(session->window, (uint32_t) width, (uint32_t) height);
    if (session->renderer == NULL) {
        ANativeWindow_release(session->window);
        free(session);
        return 0;
    }

    LOGI("renderer for a session: %dx%d", width, height);
    return (jlong) (uintptr_t) session;
}

/**
 * One frame, decoded and shown.
 *
 * The bytes are copied into a buffer this side owns rather than pinned in place. Pinning would be
 * one copy fewer and would hold a critical region across a GPU submit and a fence wait, which is a
 * whole frame of the collector blocked to save a memcpy of a compressed frame.
 *
 * @return true when the frame reached the screen.
 */
JNIEXPORT jboolean JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeDecodeAndPresent(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray frame, jint length) {
    (void) clazz;

    pyrowave_session *session = (pyrowave_session *) (uintptr_t) handle;
    if (session == NULL || frame == NULL || length <= 0) {
        return JNI_FALSE;
    }

    if (length > session->scratch_size) {
        uint8_t *grown = realloc(session->scratch, (size_t) length);
        if (grown == NULL) {
            return JNI_FALSE;
        }
        session->scratch = grown;
        session->scratch_size = length;
    }

    (*env)->GetByteArrayRegion(env, frame, 0, length, (jbyte *) session->scratch);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    return pyrowave_renderer_decode_and_present(session->renderer, session->scratch, (size_t) length)
           ? JNI_TRUE : JNI_FALSE;
}

/**
 * Give back the renderer, the window it drew into and the buffer frames passed through.
 */
JNIEXPORT void JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeDestroyRenderer(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;

    pyrowave_session *session = (pyrowave_session *) (uintptr_t) handle;
    if (session == NULL) {
        return;
    }

    // The renderer first: it waits for the device to go idle, and the window has to outlive the
    // swapchain that is still pointing at it.
    pyrowave_renderer_destroy(session->renderer);
    ANativeWindow_release(session->window);
    free(session->scratch);
    free(session);
}

/**
 * The same frame, decoded on the GPU straight into the images the shader samples.
 *
 * What a stream uses, and the whole of it: the renderer owns the decoder, so the frame goes in as
 * bytes and comes out as pixels with nothing in host memory between them.
 *
 * Kept beside the host memory self test rather than replacing it, because the pair is a diagnosis.
 * The other one decodes through the codec's CPU entry point and uploads the result, so if this one
 * shows a wrong picture and that one shows a right one, the fault is in the decode targets or the
 * barriers around them, and not in the codec, the shader or the swapchain.
 *
 * @return 0 when a frame reached the screen, or a negative code.
 */
JNIEXPORT jint JNICALL
Java_com_papi_nova_binding_video_PyroWave_nativeGpuDecodeSelfTest(
        JNIEnv *env, jclass clazz, jobject surface, jbyteArray bitstream) {
    (void) clazz;

    enum { Width = 34, Height = 30 };

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        return -30;
    }

    const jsize size = (*env)->GetArrayLength(env, bitstream);
    jbyte *payload = (*env)->GetByteArrayElements(env, bitstream, NULL);
    if (payload == NULL || size <= 0) {
        ANativeWindow_release(window);
        return -31;
    }

    jint outcome = -1;
    void *renderer = pyrowave_renderer_create(window, Width, Height);

    if (renderer == NULL) {
        outcome = -32;
    }
    // Twice, because the second frame is the one that proves the barriers are right. The first
    // transitions the plane images from UNDEFINED, which is allowed to discard whatever was there
    // and would hide a missing dependency between the decode and the draw that reads it.
    else if (!pyrowave_renderer_decode_and_present(renderer, (const uint8_t *) payload, (size_t) size) ||
             !pyrowave_renderer_decode_and_present(renderer, (const uint8_t *) payload, (size_t) size)) {
        outcome = -33;
    }
    else {
        LOGI("decode self test: a frame reached the screen without touching host memory");
        outcome = 0;
    }

    if (renderer != NULL) pyrowave_renderer_destroy(renderer);
    (*env)->ReleaseByteArrayElements(env, bitstream, payload, JNI_ABORT);
    ANativeWindow_release(window);
    return outcome;
}
