#include "stream/deck_stream_media_adapters.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_drm.h>
#include <libavutil/log.h>
#include <libavutil/pixfmt.h>
#include <libavutil/version.h>
#include <pipewire/pipewire.h>
#include <pulse/version.h>
#include <va/va.h>
}

#include <QtQuick/QQuickItem>
#include <QtQuick/QQuickWindow>
#include <QtQuick/QSGRendererInterface>
#include <QtQuick/QSGTexture>
#include <QtQuick/qsgtexture_platform.h>
#include <QDebug>
#include <QtGui/QMatrix4x4>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <libdrm/drm_fourcc.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace nova::deck::stream {

namespace {

std::string ffmpegErrorString(const int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

AVPixelFormat vaapiHardwareFormatCallback(AVCodecContext*, const AVPixelFormat* pixelFormats) {
    for (const AVPixelFormat* format = pixelFormats; format != nullptr && *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == AV_PIX_FMT_VAAPI) {
            return *format;
        }
    }
    return AV_PIX_FMT_NONE;
}

std::vector<std::uint8_t> copyDecodeUnitBytes(PDECODE_UNIT decodeUnit) {
    std::vector<std::uint8_t> bytes;
    if (decodeUnit == nullptr || decodeUnit->fullLength <= 0 || decodeUnit->bufferList == nullptr) {
        return bytes;
    }
    bytes.reserve(static_cast<std::size_t>(decodeUnit->fullLength));
    for (PLENTRY entry = decodeUnit->bufferList; entry != nullptr; entry = entry->next) {
        if (entry->data == nullptr || entry->length <= 0) {
            return {};
        }
        const auto* first = reinterpret_cast<const std::uint8_t*>(entry->data);
        bytes.insert(bytes.end(), first, first + entry->length);
    }
    if (bytes.size() != static_cast<std::size_t>(decodeUnit->fullLength)) {
        return {};
    }
    return bytes;
}


bool extensionListContains(const char* extensionList, const char* extension) {
    if (extensionList == nullptr || extension == nullptr || *extension == '\0') {
        return false;
    }
    const char* cursor = extensionList;
    const std::size_t extensionLength = std::strlen(extension);
    while ((cursor = std::strstr(cursor, extension)) != nullptr) {
        const bool startsAtBoundary = cursor == extensionList || *(cursor - 1) == ' ';
        const char after = cursor[extensionLength];
        if (startsAtBoundary && (after == '\0' || after == ' ')) {
            return true;
        }
        cursor += extensionLength;
    }
    return false;
}

bool drmPrimeDescriptorHasExplicitModifier(const DeckQrhiVaapiDrmPrimeDescriptor& descriptor) {
    for (int objectIndex = 0; objectIndex < descriptor.objectCount; ++objectIndex) {
        if (descriptor.objects[objectIndex].formatModifier != DRM_FORMAT_MOD_INVALID) {
            return true;
        }
    }
    return false;
}

using EglCreateImageKhr = EGLImageKHR (*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);
using EglDestroyImageKhr = EGLBoolean (*)(EGLDisplay, EGLImageKHR);
using GlEglImageTargetTexture2DOes = void (*)(GLenum, GLeglImageOES);

std::string glErrorCodeDetail(const GLenum error) {
    std::ostringstream stream;
    stream << "GL error 0x" << std::hex << static_cast<unsigned int>(error);
    return stream.str();
}

bool currentContextUsesOpenGles() {
    const auto* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    return version != nullptr && std::string_view(version).find("OpenGL ES") != std::string_view::npos;
}

GLuint compilePresenterShader(const GLenum shaderType, const char* source) {
    const GLuint shader = glCreateShader(shaderType);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint createPresenterProgram(const char* fragmentShaderSource) {
    static constexpr const char* esVertexShaderSource =
        "attribute vec2 a_position;\n"
        "attribute vec2 a_texCoord;\n"
        "uniform mat4 u_projection;\n"
        "varying vec2 v_texCoord;\n"
        "void main() {\n"
        "    gl_Position = u_projection * vec4(a_position, 0.0, 1.0);\n"
        "    v_texCoord = a_texCoord;\n"
        "}\n";
    static constexpr const char* desktopVertexShaderSource =
        "#version 150\n"
        "in vec2 a_position;\n"
        "in vec2 a_texCoord;\n"
        "uniform mat4 u_projection;\n"
        "out vec2 v_texCoord;\n"
        "void main() {\n"
        "    gl_Position = u_projection * vec4(a_position, 0.0, 1.0);\n"
        "    v_texCoord = a_texCoord;\n"
        "}\n";
    const char* vertexShaderSource = currentContextUsesOpenGles() ? esVertexShaderSource : desktopVertexShaderSource;
    const GLuint vertexShader = compilePresenterShader(GL_VERTEX_SHADER, vertexShaderSource);
    const GLuint fragmentShader = compilePresenterShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
    if (vertexShader == 0 || fragmentShader == 0) {
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glBindAttribLocation(program, 0, "a_position");
    glBindAttribLocation(program, 1, "a_texCoord");
    glLinkProgram(program);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

GLuint createExternalOesPresenterProgram() {
    if (!currentContextUsesOpenGles()) {
        return 0;
    }
    static constexpr const char* fragmentShaderSource =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "uniform samplerExternalOES u_texture;\n"
        "varying vec2 v_texCoord;\n"
        "void main() {\n"
        "    gl_FragColor = texture2D(u_texture, v_texCoord);\n"
        "}\n";
    return createPresenterProgram(fragmentShaderSource);
}

GLuint createTwoLayerYuvPresenterProgram() {
    static constexpr const char* esFragmentShaderSource =
        "precision mediump float;\n"
        "uniform sampler2D u_yTexture;\n"
        "uniform sampler2D u_uvTexture;\n"
        "varying vec2 v_texCoord;\n"
        "void main() {\n"
        "    float y = texture2D(u_yTexture, v_texCoord).r;\n"
        "    vec2 uv = texture2D(u_uvTexture, v_texCoord).rg - vec2(0.5, 0.5);\n"
        "    float r = y + 1.5748 * uv.y;\n"
        "    float g = y - 0.1873 * uv.x - 0.4681 * uv.y;\n"
        "    float b = y + 1.8556 * uv.x;\n"
        "    gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);\n"
        "}\n";
    static constexpr const char* desktopFragmentShaderSource =
        "#version 150\n"
        "uniform sampler2D u_yTexture;\n"
        "uniform sampler2D u_uvTexture;\n"
        "in vec2 v_texCoord;\n"
        "out vec4 fragColor;\n"
        "void main() {\n"
        "    float y = texture(u_yTexture, v_texCoord).r;\n"
        "    vec2 uv = texture(u_uvTexture, v_texCoord).rg - vec2(0.5, 0.5);\n"
        "    float r = y + 1.5748 * uv.y;\n"
        "    float g = y - 0.1873 * uv.x - 0.4681 * uv.y;\n"
        "    float b = y + 1.8556 * uv.x;\n"
        "    fragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);\n"
        "}\n";
    const char* fragmentShaderSource = currentContextUsesOpenGles() ? esFragmentShaderSource : desktopFragmentShaderSource;
    return createPresenterProgram(fragmentShaderSource);
}

bool renderPresenterTexture(DeckVaapiEglImagePresenter::Resource& resource, const QRectF& rect, const QMatrix4x4* projectionMatrix) {
    resource.shaderCompositionProved = false;
    resource.shaderCompositionDetail.clear();
    if (!resource.hasTexture() || projectionMatrix == nullptr) {
        resource.shaderCompositionDetail = !resource.hasTexture()
            ? "shader composition skipped because no imported EGLImage/GL texture resource is available"
            : "shader composition skipped because QSGRenderNode render state did not provide a projection matrix";
        return false;
    }
    if (resource.glProgram == 0) {
        resource.glProgram = resource.importedLayerCount == 2 ? createTwoLayerYuvPresenterProgram() : createExternalOesPresenterProgram();
        if (resource.glProgram == 0) {
            resource.shaderCompositionDetail = "shader composition program creation failed";
            return false;
        }
    }
    const GLfloat left = static_cast<GLfloat>(rect.left());
    const GLfloat right = static_cast<GLfloat>(rect.right());
    const GLfloat top = static_cast<GLfloat>(rect.top());
    const GLfloat bottom = static_cast<GLfloat>(rect.bottom());
    const GLfloat vertices[] = {
        left, top, 0.0f, 0.0f,
        right, top, 1.0f, 0.0f,
        left, bottom, 0.0f, 1.0f,
        right, bottom, 1.0f, 1.0f,
    };
    glUseProgram(resource.glProgram);
    glUniformMatrix4fv(glGetUniformLocation(resource.glProgram, "u_projection"), 1, GL_FALSE, projectionMatrix->constData());
    if (resource.importedLayerCount == 2) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, resource.glTextures[0]);
        glUniform1i(glGetUniformLocation(resource.glProgram, "u_yTexture"), 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, resource.glTextures[1]);
        glUniform1i(glGetUniformLocation(resource.glProgram, "u_uvTexture"), 1);
    } else {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, resource.glTexture);
        glUniform1i(glGetUniformLocation(resource.glProgram, "u_texture"), 0);
    }
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    if (resource.importedLayerCount == 2) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    } else {
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    }
    const GLenum drawError = glGetError();
    resource.shaderCompositionProved = drawError == GL_NO_ERROR;
    resource.shaderCompositionDetail = resource.shaderCompositionProved
        ? "shader composition draw completed without GL errors"
        : "shader composition draw failed with " + glErrorCodeDetail(drawError);
    return resource.shaderCompositionProved;
}

void destroyPresenterResource(QSGTexture*& qtTexture, void*& eglDisplay, void*& eglImage, unsigned int& glTexture, unsigned int& glProgram) {
    delete qtTexture;
    qtTexture = nullptr;
    if (glProgram != 0) {
        glDeleteProgram(glProgram);
        glProgram = 0;
    }
    if (glTexture != 0) {
        const GLuint texture = glTexture;
        glDeleteTextures(1, &texture);
        glTexture = 0;
    }
    if (eglDisplay != nullptr && eglImage != nullptr) {
        auto destroyImage = reinterpret_cast<EglDestroyImageKhr>(eglGetProcAddress("eglDestroyImageKHR"));
        if (destroyImage != nullptr) {
            destroyImage(static_cast<EGLDisplay>(eglDisplay), static_cast<EGLImageKHR>(eglImage));
        }
        eglImage = nullptr;
    }
    eglDisplay = nullptr;
}

void destroyPresenterResource(DeckVaapiEglImagePresenter::Resource& resource) {
    EGLDisplay layerDisplay = static_cast<EGLDisplay>(resource.eglDisplay);
    destroyPresenterResource(resource.qtTexture, resource.eglDisplay, resource.eglImage, resource.glTexture, resource.glProgram);
    if (layerDisplay == EGL_NO_DISPLAY) {
        layerDisplay = eglGetCurrentDisplay();
    }
    auto destroyImage = reinterpret_cast<EglDestroyImageKhr>(eglGetProcAddress("eglDestroyImageKHR"));
    for (int layerIndex = 0; layerIndex < static_cast<int>(resource.glTextures.size()); ++layerIndex) {
        if (resource.glTextures[layerIndex] != 0) {
            const GLuint texture = resource.glTextures[layerIndex];
            glDeleteTextures(1, &texture);
            resource.glTextures[layerIndex] = 0;
        }
        if (resource.eglImages[layerIndex] != nullptr && layerDisplay != EGL_NO_DISPLAY && destroyImage != nullptr) {
            destroyImage(layerDisplay, static_cast<EGLImageKHR>(resource.eglImages[layerIndex]));
        }
        resource.eglImages[layerIndex] = nullptr;
    }
    resource.importedLayerCount = 0;
    resource.shaderCompositionProved = false;
    resource.shaderCompositionDetail.clear();
    resource.eglDisplay = nullptr;
}

void appendPlaneAttributes(std::vector<EGLint>& attributes, const int attributePlane, const int fd, const std::int64_t offset, const std::int64_t pitch, const std::uint64_t modifier, const bool includeModifier) {
    static constexpr std::array<EGLint, 4> fdAttributes = { EGL_DMA_BUF_PLANE0_FD_EXT, EGL_DMA_BUF_PLANE1_FD_EXT, EGL_DMA_BUF_PLANE2_FD_EXT, EGL_DMA_BUF_PLANE3_FD_EXT };
    static constexpr std::array<EGLint, 4> offsetAttributes = { EGL_DMA_BUF_PLANE0_OFFSET_EXT, EGL_DMA_BUF_PLANE1_OFFSET_EXT, EGL_DMA_BUF_PLANE2_OFFSET_EXT, EGL_DMA_BUF_PLANE3_OFFSET_EXT };
    static constexpr std::array<EGLint, 4> pitchAttributes = { EGL_DMA_BUF_PLANE0_PITCH_EXT, EGL_DMA_BUF_PLANE1_PITCH_EXT, EGL_DMA_BUF_PLANE2_PITCH_EXT, EGL_DMA_BUF_PLANE3_PITCH_EXT };
    static constexpr std::array<EGLint, 4> modifierLoAttributes = { EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT };
    static constexpr std::array<EGLint, 4> modifierHiAttributes = { EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT };
    attributes.push_back(fdAttributes[attributePlane]);
    attributes.push_back(fd);
    attributes.push_back(offsetAttributes[attributePlane]);
    attributes.push_back(static_cast<EGLint>(offset));
    attributes.push_back(pitchAttributes[attributePlane]);
    attributes.push_back(static_cast<EGLint>(pitch));
    if (includeModifier) {
        attributes.push_back(modifierLoAttributes[attributePlane]);
        attributes.push_back(static_cast<EGLint>(modifier & 0xffffffffu));
        attributes.push_back(modifierHiAttributes[attributePlane]);
        attributes.push_back(static_cast<EGLint>(modifier >> 32u));
    }
}

DeckVaapiPresenterReadinessState readinessStateForImportStatus(const DeckQrhiVaapiImportStatus status) {
    switch (status) {
    case DeckQrhiVaapiImportStatus::NotAttempted:
        return DeckVaapiPresenterReadinessState::NotAttempted;
    case DeckQrhiVaapiImportStatus::DrmPrimeExported:
        return DeckVaapiPresenterReadinessState::HardwarePresenterPlanned;
    case DeckQrhiVaapiImportStatus::MissingFrameLease:
        return DeckVaapiPresenterReadinessState::MissingFrameLease;
    case DeckQrhiVaapiImportStatus::InvalidVaapiFrame:
        return DeckVaapiPresenterReadinessState::InvalidVaapiFrame;
    case DeckQrhiVaapiImportStatus::MissingHardwareFramesContext:
        return DeckVaapiPresenterReadinessState::MissingHardwareFramesContext;
    case DeckQrhiVaapiImportStatus::DrmPrimeMapFailed:
        return DeckVaapiPresenterReadinessState::DrmPrimeMapFailed;
    case DeckQrhiVaapiImportStatus::MissingRenderState:
        return DeckVaapiPresenterReadinessState::MissingRenderState;
    case DeckQrhiVaapiImportStatus::MissingQrhiCommandBuffer:
        return DeckVaapiPresenterReadinessState::MissingQrhiCommandBuffer;
    case DeckQrhiVaapiImportStatus::MissingRenderContext:
        return DeckVaapiPresenterReadinessState::MissingRenderContext;
    case DeckQrhiVaapiImportStatus::DeckTargetUnavailable:
        return DeckVaapiPresenterReadinessState::DeckTargetUnavailable;
    case DeckQrhiVaapiImportStatus::UnsupportedNonOpenGlSceneGraph:
        return DeckVaapiPresenterReadinessState::UnsupportedNonOpenGlSceneGraph;
    case DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions:
        return DeckVaapiPresenterReadinessState::MissingEglDmabufExtensions;
    case DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata:
        return DeckVaapiPresenterReadinessState::IncompleteDrmPrimeMetadata;
    case DeckQrhiVaapiImportStatus::UnsupportedMultiLayerDrmPrimeImport:
        return DeckVaapiPresenterReadinessState::UnsupportedMultiLayerDrmPrimeImport;
    case DeckQrhiVaapiImportStatus::UnsupportedDrmPrimeFormat:
        return DeckVaapiPresenterReadinessState::UnsupportedDrmPrimeFormat;
    case DeckQrhiVaapiImportStatus::EglImageCreationFailed:
        return DeckVaapiPresenterReadinessState::EglImageCreationFailed;
    case DeckQrhiVaapiImportStatus::GlTextureBindFailed:
        return DeckVaapiPresenterReadinessState::GlTextureBindFailed;
    case DeckQrhiVaapiImportStatus::EglImageShaderCompositionFailed:
        return DeckVaapiPresenterReadinessState::EglImageShaderCompositionFailed;
    case DeckQrhiVaapiImportStatus::UnsupportedPublicQtLinuxDmabufImport:
        return DeckVaapiPresenterReadinessState::UnsupportedPublicQtLinuxDmabufImport;
    }
    return DeckVaapiPresenterReadinessState::NotAttempted;
}

std::string_view readinessStatusCode(const DeckVaapiPresenterReadinessState state) {
    switch (state) {
    case DeckVaapiPresenterReadinessState::NotAttempted:
        return "not-attempted";
    case DeckVaapiPresenterReadinessState::Ready:
        return "ready";
    case DeckVaapiPresenterReadinessState::HardwarePresenterPlanned:
        return "hardware-presenter-planned";
    case DeckVaapiPresenterReadinessState::HardwareFrameReady:
        return "hardware-frame-ready";
    case DeckVaapiPresenterReadinessState::MissingFrameLease:
        return "missing-frame-lease";
    case DeckVaapiPresenterReadinessState::InvalidVaapiFrame:
        return "invalid-vaapi-frame";
    case DeckVaapiPresenterReadinessState::MissingHardwareFramesContext:
        return "missing-hardware-frames-context";
    case DeckVaapiPresenterReadinessState::DrmPrimeMapFailed:
        return "drm-prime-map-failed";
    case DeckVaapiPresenterReadinessState::MissingRenderState:
        return "missing-render-state";
    case DeckVaapiPresenterReadinessState::MissingQrhiCommandBuffer:
        return "missing-qrhi-command-buffer";
    case DeckVaapiPresenterReadinessState::MissingRenderContext:
        return "missing-render-context";
    case DeckVaapiPresenterReadinessState::DeckTargetUnavailable:
        return "deck-target-unavailable";
    case DeckVaapiPresenterReadinessState::UnsupportedNonOpenGlSceneGraph:
        return "unsupported-non-opengl-scene-graph";
    case DeckVaapiPresenterReadinessState::MissingEglDmabufExtensions:
        return "missing-egl-dmabuf-extensions";
    case DeckVaapiPresenterReadinessState::IncompleteDrmPrimeMetadata:
        return "incomplete-drm-prime-metadata";
    case DeckVaapiPresenterReadinessState::UnsupportedMultiLayerDrmPrimeImport:
        return "unsupported-multilayer-drm-prime-import";
    case DeckVaapiPresenterReadinessState::UnsupportedDrmPrimeFormat:
        return "unsupported-drm-prime-format";
    case DeckVaapiPresenterReadinessState::EglImageCreationFailed:
        return "eglimage-creation-failed";
    case DeckVaapiPresenterReadinessState::GlTextureBindFailed:
        return "gl-texture-bind-failed";
    case DeckVaapiPresenterReadinessState::EglImageShaderCompositionFailed:
        return "eglimage-shader-composition-failed";
    case DeckVaapiPresenterReadinessState::UnsupportedPublicQtLinuxDmabufImport:
        return "unsupported-public-qt-linux-dmabuf-import";
    }
    return "not-attempted";
}

std::string_view readinessLabel(const DeckVaapiPresenterReadinessState state) {
    switch (state) {
    case DeckVaapiPresenterReadinessState::NotAttempted:
        return "Presenter check not attempted";
    case DeckVaapiPresenterReadinessState::Ready:
        return "Ready: EGLImage GL presenter has a Qt texture";
    case DeckVaapiPresenterReadinessState::HardwarePresenterPlanned:
        return "Hardware presenter planned: DRM_PRIME metadata can be imported";
    case DeckVaapiPresenterReadinessState::HardwareFrameReady:
        return "Hardware frame ready: VAAPI decoded DRM_PRIME metadata";
    case DeckVaapiPresenterReadinessState::MissingFrameLease:
        return "Missing VAAPI frame lease";
    case DeckVaapiPresenterReadinessState::InvalidVaapiFrame:
        return "Invalid VAAPI frame";
    case DeckVaapiPresenterReadinessState::MissingHardwareFramesContext:
        return "Missing VAAPI hardware frames context";
    case DeckVaapiPresenterReadinessState::DrmPrimeMapFailed:
        return "DRM_PRIME export failed";
    case DeckVaapiPresenterReadinessState::MissingRenderState:
        return "Missing Qt Quick render state";
    case DeckVaapiPresenterReadinessState::MissingQrhiCommandBuffer:
        return "Missing QRhi command buffer";
    case DeckVaapiPresenterReadinessState::MissingRenderContext:
        return "Missing current render-thread OpenGL/EGL context";
    case DeckVaapiPresenterReadinessState::DeckTargetUnavailable:
        return "Missing Deck Qt Quick render target";
    case DeckVaapiPresenterReadinessState::UnsupportedNonOpenGlSceneGraph:
        return "Unsupported scene graph: OpenGLRhi required";
    case DeckVaapiPresenterReadinessState::MissingEglDmabufExtensions:
        return "Missing EGL dmabuf import extensions";
    case DeckVaapiPresenterReadinessState::IncompleteDrmPrimeMetadata:
        return "Incomplete DRM_PRIME metadata";
    case DeckVaapiPresenterReadinessState::UnsupportedMultiLayerDrmPrimeImport:
        return "Unsupported multi-layer DRM_PRIME import";
    case DeckVaapiPresenterReadinessState::UnsupportedDrmPrimeFormat:
        return "Unsupported DRM_PRIME layer format";
    case DeckVaapiPresenterReadinessState::EglImageCreationFailed:
        return "EGLImage creation failed";
    case DeckVaapiPresenterReadinessState::GlTextureBindFailed:
        return "GL texture bind failed";
    case DeckVaapiPresenterReadinessState::EglImageShaderCompositionFailed:
        return "EGLImage shader composition failed";
    case DeckVaapiPresenterReadinessState::UnsupportedPublicQtLinuxDmabufImport:
        return "Unsupported public Qt Linux dmabuf import";
    }
    return "Presenter check not attempted";
}

} // namespace

DeckQrhiVaapiDrmPrimeDescriptor::DeckQrhiVaapiDrmPrimeDescriptor(AVFrame* mappedFrame)
    : mappedFrame_(mappedFrame) {
    if (mappedFrame_ == nullptr || mappedFrame_->format != AV_PIX_FMT_DRM_PRIME || mappedFrame_->data[0] == nullptr) {
        status = DeckQrhiVaapiImportStatus::DrmPrimeMapFailed;
        detail = "VAAPI frame did not map to an AV_PIX_FMT_DRM_PRIME descriptor";
        return;
    }

    const auto* drmDescriptor = reinterpret_cast<const AVDRMFrameDescriptor*>(mappedFrame_->data[0]);
    if (drmDescriptor->nb_objects <= 0 || drmDescriptor->nb_layers <= 0 || drmDescriptor->nb_objects > static_cast<int>(objects.size()) ||
        drmDescriptor->nb_layers > static_cast<int>(layers.size())) {
        objectCount = std::clamp(drmDescriptor->nb_objects, 0, static_cast<int>(objects.size()));
        layerCount = std::clamp(drmDescriptor->nb_layers, 0, static_cast<int>(layers.size()));
        status = DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata;
        detail = "DRM_PRIME descriptor object/layer count is missing or exceeds EGL import limits";
        return;
    }
    objectCount = std::clamp(drmDescriptor->nb_objects, 0, static_cast<int>(objects.size()));
    layerCount = std::clamp(drmDescriptor->nb_layers, 0, static_cast<int>(layers.size()));
    for (int objectIndex = 0; objectIndex < objectCount; ++objectIndex) {
        objects[objectIndex] = DeckVaapiDrmPrimeObject{
            .fd = drmDescriptor->objects[objectIndex].fd,
            .formatModifier = drmDescriptor->objects[objectIndex].format_modifier,
        };
    }
    for (int layerIndex = 0; layerIndex < layerCount; ++layerIndex) {
        DeckVaapiDrmPrimeLayer layer{};
        layer.format = drmDescriptor->layers[layerIndex].format;
        if (drmDescriptor->layers[layerIndex].nb_planes <= 0 || drmDescriptor->layers[layerIndex].nb_planes > static_cast<int>(layer.planes.size())) {
            status = DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata;
            detail = "DRM_PRIME descriptor plane count is missing or exceeds EGL import limits";
            return;
        }
        layer.planeCount = drmDescriptor->layers[layerIndex].nb_planes;
        for (int planeIndex = 0; planeIndex < layer.planeCount; ++planeIndex) {
            layer.planes[planeIndex] = DeckVaapiDrmPrimePlane{
                .objectIndex = drmDescriptor->layers[layerIndex].planes[planeIndex].object_index,
                .offset = static_cast<std::int64_t>(drmDescriptor->layers[layerIndex].planes[planeIndex].offset),
                .pitch = static_cast<std::int64_t>(drmDescriptor->layers[layerIndex].planes[planeIndex].pitch),
            };
        }
        layers[layerIndex] = layer;
    }
    status = DeckQrhiVaapiImportStatus::DrmPrimeExported;
    detail = "FFmpeg exported DRM_PRIME dmabuf metadata for public Qt Quick OpenGL/EGLImage import";
}

DeckQrhiVaapiDrmPrimeDescriptor::~DeckQrhiVaapiDrmPrimeDescriptor() {
    reset();
}

DeckQrhiVaapiDrmPrimeDescriptor::DeckQrhiVaapiDrmPrimeDescriptor(DeckQrhiVaapiDrmPrimeDescriptor&& other) noexcept
    : status(other.status)
    , objectCount(other.objectCount)
    , layerCount(other.layerCount)
    , objects(other.objects)
    , layers(other.layers)
    , detail(std::move(other.detail))
    , mappedFrame_(other.mappedFrame_) {
    other.status = DeckQrhiVaapiImportStatus::NotAttempted;
    other.objectCount = 0;
    other.layerCount = 0;
    other.objects = {};
    other.layers = {};
    other.mappedFrame_ = nullptr;
}

DeckQrhiVaapiDrmPrimeDescriptor& DeckQrhiVaapiDrmPrimeDescriptor::operator=(DeckQrhiVaapiDrmPrimeDescriptor&& other) noexcept {
    if (this != &other) {
        reset();
        status = other.status;
        objectCount = other.objectCount;
        layerCount = other.layerCount;
        objects = other.objects;
        layers = other.layers;
        detail = std::move(other.detail);
        mappedFrame_ = other.mappedFrame_;
        other.status = DeckQrhiVaapiImportStatus::NotAttempted;
        other.objectCount = 0;
        other.layerCount = 0;
        other.objects = {};
        other.layers = {};
        other.mappedFrame_ = nullptr;
    }
    return *this;
}

void DeckQrhiVaapiDrmPrimeDescriptor::reset() {
    if (mappedFrame_ != nullptr) {
        av_frame_free(&mappedFrame_);
    }
}


DeckVaapiEglImagePresenter::Resource::~Resource() {
    destroyPresenterResource(*this);
}

DeckVaapiEglImagePresenter::Resource::Resource(Resource&& other) noexcept
    : qtTexture(other.qtTexture), eglDisplay(other.eglDisplay), eglImage(other.eglImage), glTexture(other.glTexture), glProgram(other.glProgram), eglImages(other.eglImages), glTextures(other.glTextures), importedLayerCount(other.importedLayerCount), shaderCompositionProved(other.shaderCompositionProved), shaderCompositionDetail(std::move(other.shaderCompositionDetail)) {
    other.qtTexture = nullptr;
    other.eglDisplay = nullptr;
    other.eglImage = nullptr;
    other.glTexture = 0;
    other.glProgram = 0;
    other.eglImages = {};
    other.glTextures = {};
    other.importedLayerCount = 0;
    other.shaderCompositionProved = false;
    other.shaderCompositionDetail.clear();
}

DeckVaapiEglImagePresenter::Resource& DeckVaapiEglImagePresenter::Resource::operator=(Resource&& other) noexcept {
    if (this != &other) {
        destroyPresenterResource(*this);
        qtTexture = other.qtTexture;
        eglDisplay = other.eglDisplay;
        eglImage = other.eglImage;
        glTexture = other.glTexture;
        glProgram = other.glProgram;
        eglImages = other.eglImages;
        glTextures = other.glTextures;
        importedLayerCount = other.importedLayerCount;
        shaderCompositionProved = other.shaderCompositionProved;
        shaderCompositionDetail = std::move(other.shaderCompositionDetail);
        other.qtTexture = nullptr;
        other.eglDisplay = nullptr;
        other.eglImage = nullptr;
        other.glTexture = 0;
        other.glProgram = 0;
        other.eglImages = {};
        other.glTextures = {};
        other.importedLayerCount = 0;
        other.shaderCompositionProved = false;
        other.shaderCompositionDetail.clear();
    }
    return *this;
}

bool DeckVaapiEglImagePresenter::Resource::hasTexture() const {
    if (importedLayerCount > 0) {
        if (importedLayerCount > static_cast<int>(eglImages.size())) {
            return false;
        }
        for (int layerIndex = 0; layerIndex < importedLayerCount; ++layerIndex) {
            if (eglImages[layerIndex] == nullptr || glTextures[layerIndex] == 0) {
                return false;
            }
        }
        return true;
    }
    return qtTexture != nullptr && glTexture != 0 && eglImage != nullptr;
}

DeckQrhiVaapiImportPlan DeckVaapiEglImagePresenter::validateDrmPrimeMetadata(const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor) {
    if (drmPrimeDescriptor.status != DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        return DeckQrhiVaapiImportPlan{ .status = drmPrimeDescriptor.status, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = drmPrimeDescriptor.detail.empty() ? "DRM_PRIME export did not produce importable metadata" : drmPrimeDescriptor.detail };
    }
    if (drmPrimeDescriptor.objectCount <= 0 || drmPrimeDescriptor.layerCount <= 0) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "DRM_PRIME descriptor has no dmabuf objects or layers" };
    }
    if (drmPrimeDescriptor.layerCount > 2) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::UnsupportedMultiLayerDrmPrimeImport, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "DRM_PRIME descriptor has more than the supported Deck two-layer Y/UV shape; no layer is truncated or silently ignored" };
    }
    if (drmPrimeDescriptor.layerCount == 2 &&
        (drmPrimeDescriptor.layers[0].format != DRM_FORMAT_R8 || drmPrimeDescriptor.layers[1].format != DRM_FORMAT_GR88)) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::UnsupportedDrmPrimeFormat, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "Only the real Deck two-layer DRM_PRIME Y/UV shape is supported for shader composition: layer 0 DRM_FORMAT_R8 luma and layer 1 DRM_FORMAT_GR88 chroma" };
    }
    int importedPlaneCount = 0;
    for (int layerIndex = 0; layerIndex < drmPrimeDescriptor.layerCount; ++layerIndex) {
        const DeckVaapiDrmPrimeLayer& layer = drmPrimeDescriptor.layers[layerIndex];
        if (layer.format == 0 || layer.planeCount <= 0) {
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "DRM_PRIME layer is missing format or plane metadata" };
        }
        for (int planeIndex = 0; planeIndex < layer.planeCount; ++planeIndex) {
            const DeckVaapiDrmPrimePlane& plane = layer.planes[planeIndex];
            if (plane.objectIndex < 0 || plane.objectIndex >= drmPrimeDescriptor.objectCount || drmPrimeDescriptor.objects[plane.objectIndex].fd < 0 || plane.pitch <= 0 || plane.offset < 0 || importedPlaneCount >= 4) {
                return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "DRM_PRIME plane is missing fd, object index, pitch, offset, or exceeds EGL plane limits" };
            }
            ++importedPlaneCount;
        }
    }
    return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DrmPrimeExported, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = drmPrimeDescriptor.layerCount == 2 ? "2-layer DRM_PRIME YUV dmabuf metadata is complete for separate EGLImage imports and explicit YUV-to-RGB shader composition" : "DRM_PRIME dmabuf metadata is complete for EGLImage import" };
}

DeckQrhiVaapiImportPlan DeckVaapiEglImagePresenter::planOpenGlTextureImport(QQuickWindow* targetWindow, const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor, const QSize& size) {
    const DeckQrhiVaapiImportPlan metadataPlan = validateDrmPrimeMetadata(drmPrimeDescriptor);
    if (metadataPlan.status != DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        return metadataPlan;
    }
    if (targetWindow == nullptr || size.width() <= 0 || size.height() <= 0) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DeckTargetUnavailable, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "Qt Quick target window and positive texture size are required for EGLImage presentation" };
    }
    QSGRendererInterface* rendererInterface = targetWindow->rendererInterface();
    if (rendererInterface == nullptr) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DeckTargetUnavailable, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "Qt Quick target has no renderer interface available on the render thread" };
    }
    if (rendererInterface->graphicsApi() != QSGRendererInterface::OpenGLRhi) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::UnsupportedNonOpenGlSceneGraph, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "VAAPI EGLImage dmabuf import is gated to Qt Quick OpenGLRhi scene graph; current Qt Quick graphicsApi=" + std::to_string(static_cast<int>(rendererInterface->graphicsApi())) + " so render-thread EGLImage import is not attempted" };
    }
    const EGLDisplay eglDisplay = eglGetCurrentDisplay();
    if (eglDisplay == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingRenderContext, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "No current EGL display/context is bound for the Deck Qt Quick render thread" };
    }
    const char* eglExtensions = eglQueryString(eglDisplay, EGL_EXTENSIONS);
    const bool hasDmabufImport = extensionListContains(eglExtensions, "EGL_EXT_image_dma_buf_import");
    const bool needsModifierImport = drmPrimeDescriptorHasExplicitModifier(drmPrimeDescriptor);
    const bool hasModifierImport = extensionListContains(eglExtensions, "EGL_EXT_image_dma_buf_import_modifiers");
    if (!hasDmabufImport || (needsModifierImport && !hasModifierImport)) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "EGL display lacks required dmabuf import extension(s) for DRM_PRIME modifiers" };
    }
    if (eglGetProcAddress("eglCreateImageKHR") == nullptr || eglGetProcAddress("eglDestroyImageKHR") == nullptr || eglGetProcAddress("glEGLImageTargetTexture2DOES") == nullptr) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "EGL/GL image import entry points are unavailable" };
    }
    return metadataPlan;
}

DeckQrhiVaapiImportPlan DeckVaapiEglImagePresenter::importOpenGlTextureForCurrentContext(const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor, const QSize& size, Resource& resource) {
    DeckQrhiVaapiImportPlan plan = validateDrmPrimeMetadata(drmPrimeDescriptor);
    if (plan.status != DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        return plan;
    }
    if (size.width() <= 0 || size.height() <= 0) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DeckTargetUnavailable, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "Positive texture size is required for current-context EGLImage presentation" };
    }
    const EGLDisplay eglDisplay = eglGetCurrentDisplay();
    if (eglDisplay == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingRenderContext, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "No current EGL display/context is bound for live DRM_PRIME composition smoke" };
    }
    const char* eglExtensions = eglQueryString(eglDisplay, EGL_EXTENSIONS);
    const bool hasDmabufImport = extensionListContains(eglExtensions, "EGL_EXT_image_dma_buf_import");
    const bool needsModifierImport = drmPrimeDescriptorHasExplicitModifier(drmPrimeDescriptor);
    const bool hasModifierImport = extensionListContains(eglExtensions, "EGL_EXT_image_dma_buf_import_modifiers");
    if (!hasDmabufImport || (needsModifierImport && !hasModifierImport)) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "EGL display lacks required dmabuf import extension(s) for live DRM_PRIME composition smoke" };
    }
    auto createImage = reinterpret_cast<EglCreateImageKhr>(eglGetProcAddress("eglCreateImageKHR"));
    auto imageTargetTexture = reinterpret_cast<GlEglImageTargetTexture2DOes>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (createImage == nullptr || eglGetProcAddress("eglDestroyImageKHR") == nullptr || imageTargetTexture == nullptr) {
        return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "EGL/GL image import entry points are unavailable for live DRM_PRIME composition smoke" };
    }

    const bool includeModifiers = drmPrimeDescriptorHasExplicitModifier(drmPrimeDescriptor);
    const GLenum textureTarget = drmPrimeDescriptor.layerCount == 2 ? GL_TEXTURE_2D : GL_TEXTURE_EXTERNAL_OES;
    Resource pendingResource;
    pendingResource.eglDisplay = static_cast<void*>(eglDisplay);

    for (int layerIndex = 0; layerIndex < drmPrimeDescriptor.layerCount; ++layerIndex) {
        const DeckVaapiDrmPrimeLayer& layer = drmPrimeDescriptor.layers[layerIndex];
        const int layerWidth = drmPrimeDescriptor.layerCount == 2 && layerIndex == 1 ? (size.width() + 1) / 2 : size.width();
        const int layerHeight = drmPrimeDescriptor.layerCount == 2 && layerIndex == 1 ? (size.height() + 1) / 2 : size.height();
        std::vector<EGLint> attributes{ EGL_WIDTH, layerWidth, EGL_HEIGHT, layerHeight, EGL_LINUX_DRM_FOURCC_EXT, static_cast<EGLint>(layer.format) };
        for (int planeIndex = 0; planeIndex < layer.planeCount; ++planeIndex) {
            const DeckVaapiDrmPrimePlane& plane = layer.planes[planeIndex];
            const DeckVaapiDrmPrimeObject& object = drmPrimeDescriptor.objects[plane.objectIndex];
            appendPlaneAttributes(attributes, planeIndex, object.fd, plane.offset, plane.pitch, object.formatModifier, includeModifiers);
        }
        attributes.push_back(EGL_NONE);

        const EGLImageKHR eglImage = createImage(eglDisplay, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attributes.data());
        if (eglImage == EGL_NO_IMAGE_KHR) {
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::EglImageCreationFailed, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT) failed for live DRM_PRIME layer " + std::to_string(layerIndex) };
        }

        GLuint glTexture = 0;
        glGenTextures(1, &glTexture);
        glBindTexture(textureTarget, glTexture);
        glTexParameteri(textureTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(textureTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        imageTargetTexture(textureTarget, static_cast<GLeglImageOES>(eglImage));
        if (glGetError() != GL_NO_ERROR) {
            void* ownedDisplay = static_cast<void*>(eglDisplay);
            void* ownedImage = static_cast<void*>(eglImage);
            unsigned int ownedTexture = glTexture;
            QSGTexture* noTexture = nullptr;
            unsigned int noProgram = 0;
            destroyPresenterResource(noTexture, ownedDisplay, ownedImage, ownedTexture, noProgram);
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::GlTextureBindFailed, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "glEGLImageTargetTexture2DOES failed while binding live DRM_PRIME layer " + std::to_string(layerIndex) };
        }
        glBindTexture(textureTarget, 0);
        pendingResource.eglImages[layerIndex] = static_cast<void*>(eglImage);
        pendingResource.glTextures[layerIndex] = glTexture;
        pendingResource.importedLayerCount = layerIndex + 1;
    }

    destroyPresenterResource(resource);
    resource = std::move(pendingResource);
    return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DrmPrimeExported, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = drmPrimeDescriptor.layerCount == 2 ? "2-layer DRM_PRIME Y/UV dmabuf imported as separate EGLImages and GL textures in a live EGL context; awaiting shader composition proof" : "DRM_PRIME dmabuf imported into an EGLImage and GL texture in a live EGL context; awaiting shader composition proof" };
}

bool DeckVaapiEglImagePresenter::proveOpenGlShaderCompositionForCurrentContext(Resource& resource, const QSize& size) {
    resource.shaderCompositionProved = false;
    if (eglGetCurrentDisplay() == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT || !resource.hasTexture() || size.width() <= 0 || size.height() <= 0) {
        return false;
    }

    GLint priorFramebuffer = 0;
    GLint priorViewport[4] = {};
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &priorFramebuffer);
    glGetIntegerv(GL_VIEWPORT, priorViewport);

    GLuint framebuffer = 0;
    GLuint colorRenderbuffer = 0;
    const int renderWidth = std::max(1, size.width());
    const int renderHeight = std::max(1, size.height());
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glGenRenderbuffers(1, &colorRenderbuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, colorRenderbuffer);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA4, renderWidth, renderHeight);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRenderbuffer);
    const bool framebufferComplete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glViewport(0, 0, renderWidth, renderHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    QMatrix4x4 projection;
    projection.ortho(0.0f, static_cast<float>(renderWidth), static_cast<float>(renderHeight), 0.0f, -1.0f, 1.0f);
    const bool rendered = framebufferComplete && renderPresenterTexture(
        resource,
        QRectF(0.0, 0.0, static_cast<qreal>(renderWidth), static_cast<qreal>(renderHeight)),
        &projection);
    glFinish();
    const bool proved = rendered && glGetError() == GL_NO_ERROR;
    resource.shaderCompositionProved = proved;

    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(priorFramebuffer));
    glViewport(priorViewport[0], priorViewport[1], priorViewport[2], priorViewport[3]);
    if (colorRenderbuffer != 0) {
        glDeleteRenderbuffers(1, &colorRenderbuffer);
    }
    if (framebuffer != 0) {
        glDeleteFramebuffers(1, &framebuffer);
    }
    return proved;
}

DeckQrhiVaapiImportPlan DeckVaapiEglImagePresenter::importOpenGlTexture(QQuickWindow* targetWindow, const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor, const QSize& size, Resource& resource) {
    DeckQrhiVaapiImportPlan plan = planOpenGlTextureImport(targetWindow, drmPrimeDescriptor, size);
    if (plan.status != DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        return plan;
    }

    const EGLDisplay eglDisplay = eglGetCurrentDisplay();
    auto createImage = reinterpret_cast<EglCreateImageKhr>(eglGetProcAddress("eglCreateImageKHR"));
    auto imageTargetTexture = reinterpret_cast<GlEglImageTargetTexture2DOes>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    const bool includeModifiers = drmPrimeDescriptorHasExplicitModifier(drmPrimeDescriptor);
    const GLenum textureTarget = drmPrimeDescriptor.layerCount == 2 ? GL_TEXTURE_2D : GL_TEXTURE_EXTERNAL_OES;
    Resource pendingResource;
    pendingResource.eglDisplay = static_cast<void*>(eglDisplay);

    for (int layerIndex = 0; layerIndex < drmPrimeDescriptor.layerCount; ++layerIndex) {
        const DeckVaapiDrmPrimeLayer& layer = drmPrimeDescriptor.layers[layerIndex];
        const int layerWidth = drmPrimeDescriptor.layerCount == 2 && layerIndex == 1 ? (size.width() + 1) / 2 : size.width();
        const int layerHeight = drmPrimeDescriptor.layerCount == 2 && layerIndex == 1 ? (size.height() + 1) / 2 : size.height();
        std::vector<EGLint> attributes{ EGL_WIDTH, layerWidth, EGL_HEIGHT, layerHeight, EGL_LINUX_DRM_FOURCC_EXT, static_cast<EGLint>(layer.format) };
        for (int planeIndex = 0; planeIndex < layer.planeCount; ++planeIndex) {
            const DeckVaapiDrmPrimePlane& plane = layer.planes[planeIndex];
            const DeckVaapiDrmPrimeObject& object = drmPrimeDescriptor.objects[plane.objectIndex];
            appendPlaneAttributes(attributes, planeIndex, object.fd, plane.offset, plane.pitch, object.formatModifier, includeModifiers);
        }
        attributes.push_back(EGL_NONE);

        const EGLImageKHR eglImage = createImage(eglDisplay, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attributes.data());
        if (eglImage == EGL_NO_IMAGE_KHR) {
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::EglImageCreationFailed, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT) failed for DRM_PRIME layer " + std::to_string(layerIndex) };
        }

        GLuint glTexture = 0;
        glGenTextures(1, &glTexture);
        glBindTexture(textureTarget, glTexture);
        glTexParameteri(textureTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(textureTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        imageTargetTexture(textureTarget, static_cast<GLeglImageOES>(eglImage));
        if (glGetError() != GL_NO_ERROR) {
            void* ownedDisplay = static_cast<void*>(eglDisplay);
            void* ownedImage = static_cast<void*>(eglImage);
            unsigned int ownedTexture = glTexture;
            QSGTexture* noTexture = nullptr;
            unsigned int noProgram = 0;
            destroyPresenterResource(noTexture, ownedDisplay, ownedImage, ownedTexture, noProgram);
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::GlTextureBindFailed, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "glEGLImageTargetTexture2DOES failed while binding DRM_PRIME layer " + std::to_string(layerIndex) };
        }
        glBindTexture(textureTarget, 0);
        pendingResource.eglImages[layerIndex] = static_cast<void*>(eglImage);
        pendingResource.glTextures[layerIndex] = glTexture;
        pendingResource.importedLayerCount = layerIndex + 1;
    }

    if (drmPrimeDescriptor.layerCount == 1) {
        QSGTexture* qtTexture = QNativeInterface::QSGOpenGLTexture::fromNativeExternalOES(pendingResource.glTextures[0], targetWindow, size);
        if (qtTexture == nullptr) {
            return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::GlTextureBindFailed, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = "Qt Quick public QSGOpenGLTexture wrapper refused the external OES texture" };
        }
        pendingResource.qtTexture = qtTexture;
        pendingResource.eglImage = pendingResource.eglImages[0];
        pendingResource.glTexture = pendingResource.glTextures[0];
        pendingResource.eglImages[0] = nullptr;
        pendingResource.glTextures[0] = 0;
        pendingResource.importedLayerCount = 0;
    }

    destroyPresenterResource(resource);
    resource = std::move(pendingResource);
    return DeckQrhiVaapiImportPlan{ .status = DeckQrhiVaapiImportStatus::DrmPrimeExported, .drmPrimeObjectCount = drmPrimeDescriptor.objectCount, .drmPrimeLayerCount = drmPrimeDescriptor.layerCount, .detail = drmPrimeDescriptor.layerCount == 2 ? "2-layer DRM_PRIME Y/UV dmabuf imported as separate EGLImages and GL textures; awaiting shader composition proof" : "DRM_PRIME dmabuf imported into EGLImage, GL external texture, and public QSGOpenGLTexture wrapper" };
}

DeckVaapiPresenterReadinessReport DeckVaapiEglImagePresenter::readinessReportForPlan(const DeckQrhiVaapiImportPlan& plan) {
    const DeckVaapiPresenterReadinessState state = readinessStateForImportStatus(plan.status);
    const bool planned = state == DeckVaapiPresenterReadinessState::HardwarePresenterPlanned;
    return DeckVaapiPresenterReadinessReport{
        .state = state,
        .importPlan = plan,
        .statusCode = std::string(readinessStatusCode(state)),
        .label = std::string(readinessLabel(state)),
        .detail = plan.detail.empty() ? std::string(readinessLabel(state)) : plan.detail,
        .ready = false,
        .hardwarePresenterPlanned = planned,
    };
}

DeckVaapiPresenterReadinessReport DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(
    const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor) {
    DeckQrhiVaapiImportPlan plan{
        .status = drmPrimeDescriptor.status,
        .drmPrimeObjectCount = drmPrimeDescriptor.objectCount,
        .drmPrimeLayerCount = drmPrimeDescriptor.layerCount,
        .detail = drmPrimeDescriptor.detail,
    };
    DeckVaapiPresenterReadinessReport report = readinessReportForPlan(plan);
    if (drmPrimeDescriptor.status == DeckQrhiVaapiImportStatus::DrmPrimeExported &&
        drmPrimeDescriptor.objectCount > 0 && drmPrimeDescriptor.layerCount > 0) {
        report.state = DeckVaapiPresenterReadinessState::HardwareFrameReady;
        report.statusCode = std::string(readinessStatusCode(report.state));
        report.label = std::string(readinessLabel(report.state));
        report.detail = "Hardware-backed VAAPI frame decoded and exported as DRM_PRIME dmabuf metadata; Qt Quick render target is still required before EGLImage texture import";
        report.ready = false;
        report.hardwarePresenterPlanned = true;
    }
    return report;
}

DeckVaapiPresenterReadinessReport DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(
    const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor,
    const DeckQrhiVaapiImportPlan& renderTargetPlan) {
    DeckVaapiPresenterReadinessReport frameReport = readinessReportForDecodedFrameProof(drmPrimeDescriptor);
    if (frameReport.state != DeckVaapiPresenterReadinessState::HardwareFrameReady) {
        return frameReport;
    }

    DeckQrhiVaapiImportPlan plan = renderTargetPlan;
    plan.drmPrimeObjectCount = drmPrimeDescriptor.objectCount;
    plan.drmPrimeLayerCount = drmPrimeDescriptor.layerCount;

    DeckVaapiPresenterReadinessReport report = readinessReportForPlan(plan);
    report.importPlan = plan;
    report.hardwarePresenterPlanned = true;
    report.ready = false;
    if (plan.status == DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        report.detail = "Hardware-backed VAAPI frame decoded and Qt Quick OpenGLRhi render target is ready; EGLImage texture import can be attempted behind the public EGL/GL capability gates";
    } else {
        const std::string targetDetail = plan.detail.empty() ? std::string(readinessLabel(report.state)) : plan.detail;
        report.detail = "Hardware-backed VAAPI frame decoded and exported as DRM_PRIME dmabuf metadata; Qt Quick render-target readiness is blocked: " + targetDetail;
    }
    return report;
}

DeckVaapiPresenterReadinessReport DeckVaapiEglImagePresenter::readinessReportForResource(const DeckQrhiVaapiImportPlan& plan, const Resource& resource) {
    DeckVaapiPresenterReadinessReport report = readinessReportForPlan(plan);
    if (plan.status == DeckQrhiVaapiImportStatus::DrmPrimeExported && resource.hasTexture()) {
        report.hardwarePresenterPlanned = true;
        if (resource.shaderCompositionProved) {
            report.state = DeckVaapiPresenterReadinessState::Ready;
            report.statusCode = std::string(readinessStatusCode(report.state));
            report.label = std::string(readinessLabel(report.state));
            report.detail = plan.drmPrimeLayerCount == 2 ? "2-layer DRM_PRIME Y/UV dmabuf is imported into EGLImages, GL textures, and passed explicit shader composition proof" : "DRM_PRIME dmabuf is imported into an EGLImage, GL external texture, and Qt Quick texture wrapper after shader composition proof";
            report.ready = true;
        } else {
            report.state = DeckVaapiPresenterReadinessState::HardwarePresenterPlanned;
            report.statusCode = std::string(readinessStatusCode(report.state));
            report.label = std::string(readinessLabel(report.state));
            report.detail = "DRM_PRIME dmabuf import produced GL texture resources, but texture-ready is gated until presenter shader composition succeeds";
            if (!resource.shaderCompositionDetail.empty()) {
                report.detail += ": " + resource.shaderCompositionDetail;
            }
            report.ready = false;
        }
    }
    return report;
}

DeckLinuxMediaProbe DeckLinuxMediaProbe::detect() {
    const AVHWDeviceType vaapiType = av_hwdevice_find_type_by_name("vaapi");
    AVBufferRef* hardwareDevice = nullptr;
    const int priorLogLevel = av_log_get_level();
    av_log_set_level(AV_LOG_QUIET);
    const int hardwareDeviceResult = av_hwdevice_ctx_create(&hardwareDevice, AV_HWDEVICE_TYPE_VAAPI, nullptr, nullptr, 0);
    av_log_set_level(priorLogLevel);
    if (hardwareDevice != nullptr) {
        av_buffer_unref(&hardwareDevice);
    }
    return DeckLinuxMediaProbe{
        .ffmpegLibavcodecHeadersLinked = avcodec_version() > 0,
        .ffmpegLibavutilHeadersLinked = avutil_version() > 0,
        .vaapiHeadersLinked = VA_MAJOR_VERSION >= 1,
        .qtQuickRhiPresentationBoundary = QQuickItem::staticMetaObject.className() != nullptr,
        .h264DecoderAvailable = avcodec_find_decoder(AV_CODEC_ID_H264) != nullptr,
        .runtimeVaapiDeviceAvailable = hardwareDeviceResult == 0,
        .hardwareDeviceTypeName = vaapiType == AV_HWDEVICE_TYPE_VAAPI ? "vaapi" : "missing-vaapi",
        .runtimeStatus = hardwareDeviceResult == 0 ? "vaapi runtime device opened" : "av_hwdevice_ctx_create(VAAPI) failed: " + ffmpegErrorString(hardwareDeviceResult),
    };
}

DeckQrhiVaapiFrameLease::DeckQrhiVaapiFrameLease(AVFrame* frame)
    : frame_(frame) {}

DeckQrhiVaapiFrameLease::~DeckQrhiVaapiFrameLease() {
    if (frame_ != nullptr) {
        av_frame_free(&frame_);
    }
}

std::shared_ptr<DeckQrhiVaapiFrameLease> DeckQrhiVaapiFrameLease::cloneHardwareFrame(const AVFrame& frame) {
    if (frame.format != AV_PIX_FMT_VAAPI) {
        return nullptr;
    }
    AVFrame* clonedFrame = av_frame_clone(&frame);
    if (clonedFrame == nullptr) {
        return nullptr;
    }
    return std::shared_ptr<DeckQrhiVaapiFrameLease>(new DeckQrhiVaapiFrameLease(clonedFrame));
}

bool DeckQrhiVaapiFrameLease::valid() const {
    return frame_ != nullptr && frame_->format == AV_PIX_FMT_VAAPI &&
        (frame_->data[3] != nullptr || frame_->hw_frames_ctx != nullptr);
}

std::uintptr_t DeckQrhiVaapiFrameLease::surfaceId() const {
    const std::uintptr_t vaSurfaceId = valid() ? reinterpret_cast<std::uintptr_t>(frame_->data[3]) : 0;
    return vaSurfaceId == 0 && valid() ? 1 : vaSurfaceId;
}

DeckQrhiVaapiDrmPrimeDescriptor DeckQrhiVaapiFrameLease::exportDrmPrimeDescriptor() const {
    if (frame_ == nullptr) {
        DeckQrhiVaapiDrmPrimeDescriptor descriptor;
        descriptor.status = DeckQrhiVaapiImportStatus::MissingFrameLease;
        descriptor.detail = "VAAPI frame lease is empty";
        return descriptor;
    }
    if (!valid()) {
        DeckQrhiVaapiDrmPrimeDescriptor descriptor;
        descriptor.status = DeckQrhiVaapiImportStatus::InvalidVaapiFrame;
        descriptor.detail = "frame lease does not contain a valid AV_PIX_FMT_VAAPI surface";
        return descriptor;
    }
    if (frame_->hw_frames_ctx == nullptr) {
        DeckQrhiVaapiDrmPrimeDescriptor descriptor;
        descriptor.status = DeckQrhiVaapiImportStatus::MissingHardwareFramesContext;
        descriptor.detail = "VAAPI frame has no AVHWFramesContext; cannot map to DRM_PRIME";
        return descriptor;
    }

    AVFrame* drmPrimeFrame = av_frame_alloc();
    if (drmPrimeFrame == nullptr) {
        DeckQrhiVaapiDrmPrimeDescriptor descriptor;
        descriptor.status = DeckQrhiVaapiImportStatus::DrmPrimeMapFailed;
        descriptor.detail = "av_frame_alloc() failed before DRM_PRIME map";
        return descriptor;
    }
    drmPrimeFrame->format = AV_PIX_FMT_DRM_PRIME;
    const int mapResult = av_hwframe_map(drmPrimeFrame, frame_, AV_HWFRAME_MAP_READ);
    if (mapResult < 0) {
        av_frame_free(&drmPrimeFrame);
        DeckQrhiVaapiDrmPrimeDescriptor descriptor;
        descriptor.status = DeckQrhiVaapiImportStatus::DrmPrimeMapFailed;
        descriptor.detail = "av_hwframe_map(VAAPI -> DRM_PRIME) failed: " + ffmpegErrorString(mapResult);
        return descriptor;
    }

    return DeckQrhiVaapiDrmPrimeDescriptor(drmPrimeFrame);
}

void DeckQrhiVaapiPresentationHandoff::setSink(std::shared_ptr<DeckQtQuickRhiPresentationSink> sink) {
    sink_ = std::move(sink);
    borrowedSink_ = nullptr;
}

void DeckQrhiVaapiPresentationHandoff::setBorrowedSink(DeckQtQuickRhiPresentationSink* sink) {
    sink_.reset();
    borrowedSink_ = sink;
}

void DeckQrhiVaapiPresentationHandoff::clearSink() {
    sink_.reset();
    borrowedSink_ = nullptr;
}

bool DeckQrhiVaapiPresentationHandoff::presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) {
    const std::shared_ptr<DeckQtQuickRhiPresentationSink> sink = sink_.lock();
    DeckQtQuickRhiPresentationSink* activeSink = sink != nullptr ? sink.get() : borrowedSink_;
    if (activeSink == nullptr) {
        return false;
    }
    if (!descriptor.hardwareBacked || descriptor.surfaceId == 0 || descriptor.width <= 0 || descriptor.height <= 0) {
        activeSink->presentVaapiSurface(descriptor);
        return false;
    }
    if (!activeSink->presentVaapiSurface(descriptor)) {
        return false;
    }
    ++presentedFrames_;
    return true;
}

int DeckQrhiVaapiPresentationHandoff::presentedFrames() const {
    return presentedFrames_;
}

DeckVaapiPreviewFramePump::DeckVaapiPreviewFramePump(DeckQrhiVaapiPresentationHandoff& handoff)
    : handoff_(handoff) {}

bool DeckVaapiPreviewFramePump::isValidPreviewFrame(const DeckQrhiVaapiPresentationDescriptor& descriptor) {
    return descriptor.hardwareBacked && descriptor.surfaceId != 0 && descriptor.width > 0 && descriptor.height > 0 &&
        descriptor.frameLease != nullptr && descriptor.frameLease->valid();
}

bool DeckVaapiPreviewFramePump::enqueueDecodedFrame(DeckQrhiVaapiPresentationDescriptor descriptor) {
    if (!isValidPreviewFrame(descriptor)) {
        clearPending();
        ++invalidatedFrames_;
        handoff_.presentVaapiSurface(descriptor);
        return false;
    }
    if (hasPendingDescriptor_) {
        ++coalescedFrames_;
    }
    pendingDescriptor_ = std::move(descriptor);
    hasPendingDescriptor_ = true;
    ++queuedFrames_;
    return true;
}

bool DeckVaapiPreviewFramePump::flushNewest() {
    if (!hasPendingDescriptor_) {
        return false;
    }
    DeckQrhiVaapiPresentationDescriptor descriptor = std::move(pendingDescriptor_);
    clearPending();
    if (!handoff_.presentVaapiSurface(descriptor)) {
        return false;
    }
    ++flushedFrames_;
    return true;
}

void DeckVaapiPreviewFramePump::clearPending() {
    pendingDescriptor_ = {};
    hasPendingDescriptor_ = false;
}

int DeckVaapiPreviewFramePump::queuedFrames() const {
    return queuedFrames_;
}

int DeckVaapiPreviewFramePump::coalescedFrames() const {
    return coalescedFrames_;
}

int DeckVaapiPreviewFramePump::flushedFrames() const {
    return flushedFrames_;
}

int DeckVaapiPreviewFramePump::invalidatedFrames() const {
    return invalidatedFrames_;
}

int DeckVaapiPreviewFramePump::pendingFrames() const {
    return hasPendingDescriptor_ ? 1 : 0;
}

DeckProductPreviewPipeline::DeckProductPreviewPipeline() = default;

void DeckProductPreviewPipeline::attachSink(std::shared_ptr<DeckQtQuickRhiPresentationSink> sink) {
    handoff_.setSink(std::move(sink));
}

void DeckProductPreviewPipeline::attachBorrowedSink(DeckQtQuickRhiPresentationSink* sink) {
    handoff_.setBorrowedSink(sink);
}

bool DeckProductPreviewPipeline::presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) {
    const bool hasValidHardwareLease = descriptor.hardwareBacked && descriptor.surfaceId != 0 &&
        descriptor.width > 0 && descriptor.height > 0 && descriptor.frameLease != nullptr && descriptor.frameLease->valid();

    if (!hasValidHardwareLease) {
        previewFramePump_.enqueueDecodedFrame(descriptor);
        lastReadinessReport_ = DeckVaapiEglImagePresenter::readinessReportForPlan(DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::MissingFrameLease,
            .detail = "product Deck preview pipeline consumed a preview frame but stayed fail-closed because no valid decoded hardware VAAPI frame lease was available",
        });
        return false;
    }

    const bool queued = previewFramePump_.enqueueDecodedFrame(descriptor);
    const bool flushed = queued && previewFramePump_.flushNewest();
    if (!flushed) {
        lastReadinessReport_ = DeckVaapiEglImagePresenter::readinessReportForPlan(DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::DeckTargetUnavailable,
            .detail = "product Deck preview pipeline could not flush the decoded hardware frame into a Qt Quick VAAPI preview sink",
        });
        return false;
    }

    lastReadinessReport_ = DeckVaapiPresenterReadinessReport{
        .state = DeckVaapiPresenterReadinessState::HardwareFrameReady,
        .importPlan = DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::DrmPrimeExported,
            .detail = "product Deck preview pipeline flushed a decoded hardware VAAPI frame through the preview pump into the Qt Quick render-node seam; texture readiness still waits for render-thread DRM_PRIME import proof",
        },
        .statusCode = "hardware-frame-ready",
        .label = "Hardware frame ready: product Deck preview pipeline reached Qt Quick render seam",
        .detail = "product Deck preview pipeline consumed a decoded hardware frame through DeckVaapiPreviewFramePump and handed it to the Qt Quick VAAPI render-node seam; ready remains false until render-thread EGLImage shader composition proves the texture",
        .ready = false,
        .hardwarePresenterPlanned = true,
    };
    return true;
}

const DeckVaapiPresenterReadinessReport& DeckProductPreviewPipeline::lastReadinessReport() const {
    return lastReadinessReport_;
}

int DeckProductPreviewPipeline::queuedFrames() const {
    return previewFramePump_.queuedFrames();
}

int DeckProductPreviewPipeline::flushedFrames() const {
    return previewFramePump_.flushedFrames();
}

int DeckProductPreviewPipeline::invalidatedFrames() const {
    return previewFramePump_.invalidatedFrames();
}

int DeckProductPreviewPipeline::pendingFrames() const {
    return previewFramePump_.pendingFrames();
}

int DeckProductPreviewPipeline::presentedFrames() const {
    return handoff_.presentedFrames();
}

DeckQtQuickRhiVaapiRenderNode::DeckQtQuickRhiVaapiRenderNode(DeckQrhiVaapiPresentationDescriptor descriptor, QQuickWindow* targetWindow)
    : descriptor_(std::move(descriptor))
    , targetWindow_(targetWindow) {}

DeckQtQuickRhiVaapiRenderNode::~DeckQtQuickRhiVaapiRenderNode() {
    releaseResources();
}

const DeckQrhiVaapiPresentationDescriptor& DeckQtQuickRhiVaapiRenderNode::descriptor() const {
    return descriptor_;
}

void DeckQtQuickRhiVaapiRenderNode::replaceDescriptor(DeckQrhiVaapiPresentationDescriptor descriptor, QQuickWindow* targetWindow) {
    presenterResource_ = {};
    lastImportPlan_ = {};
    readinessReport_ = {};
    descriptor_ = std::move(descriptor);
    targetWindow_ = targetWindow;
    markDirty(QSGNode::DirtyGeometry | QSGNode::DirtyMaterial);
}

bool DeckQtQuickRhiVaapiRenderNode::hasFrameLease() const {
    return descriptor_.frameLease != nullptr && descriptor_.frameLease->valid();
}

DeckQrhiVaapiImportPlan DeckQtQuickRhiVaapiRenderNode::planQrhiImport(const RenderState* state) const {
    if (descriptor_.frameLease == nullptr) {
        return DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::MissingFrameLease,
            .detail = "render node has no retained VAAPI frame lease",
        };
    }
    if (!descriptor_.frameLease->valid()) {
        return DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::InvalidVaapiFrame,
            .detail = "render node retained frame lease is not a valid VAAPI surface",
        };
    }
    if (state == nullptr) {
        return DeckQrhiVaapiImportPlan{
            .status = DeckQrhiVaapiImportStatus::MissingRenderState,
            .detail = "QSGRenderNode render state is required before render-thread QRhi import planning",
        };
    }
    DeckQrhiVaapiDrmPrimeDescriptor drmPrimeDescriptor = descriptor_.frameLease->exportDrmPrimeDescriptor();
    return DeckVaapiEglImagePresenter::planOpenGlTextureImport(
        targetWindow_,
        drmPrimeDescriptor,
        QSize(descriptor_.width, descriptor_.height));
}

const DeckQrhiVaapiImportPlan& DeckQtQuickRhiVaapiRenderNode::lastImportPlan() const {
    return lastImportPlan_;
}

const DeckVaapiPresenterReadinessReport& DeckQtQuickRhiVaapiRenderNode::lastReadinessReport() const {
    return readinessReport_;
}

QSGRenderNode::StateFlags DeckQtQuickRhiVaapiRenderNode::changedStates() const {
    return ColorState | BlendState | ViewportState;
}

void DeckQtQuickRhiVaapiRenderNode::render(const RenderState* state) {
    lastImportPlan_ = planQrhiImport(state);
    readinessReport_ = DeckVaapiEglImagePresenter::readinessReportForPlan(lastImportPlan_);
    if (lastImportPlan_.status != DeckQrhiVaapiImportStatus::DrmPrimeExported || descriptor_.frameLease == nullptr) {
        if (descriptor_.frameLease != nullptr && lastImportPlan_.drmPrimeObjectCount > 0 && lastImportPlan_.drmPrimeLayerCount > 0) {
            DeckQrhiVaapiDrmPrimeDescriptor drmPrimeDescriptor = descriptor_.frameLease->exportDrmPrimeDescriptor();
            readinessReport_ = DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(drmPrimeDescriptor, lastImportPlan_);
        }
        qInfo().noquote() << "Nova Deck QSGRenderNode VAAPI/EGL render path"
                          << "status=" + QString::fromStdString(readinessReport_.statusCode)
                          << "objects=" + QString::number(readinessReport_.importPlan.drmPrimeObjectCount)
                          << "layers=" + QString::number(readinessReport_.importPlan.drmPrimeLayerCount)
                          << "ready=" + QString::number(readinessReport_.ready ? 1 : 0)
                          << "planned=" + QString::number(readinessReport_.hardwarePresenterPlanned ? 1 : 0)
                          << "readiness stayed false until shader composition proof"
                          << QString::fromStdString(readinessReport_.detail);
        qInfo().noquote() << "Nova Deck VAAPI/EGL presenter readiness"
                          << QString::fromStdString(readinessReport_.statusCode)
                          << QString::fromStdString(readinessReport_.detail);
        return;
    }
    DeckQrhiVaapiDrmPrimeDescriptor drmPrimeDescriptor = descriptor_.frameLease->exportDrmPrimeDescriptor();
    lastImportPlan_ = DeckVaapiEglImagePresenter::importOpenGlTexture(
        targetWindow_,
        drmPrimeDescriptor,
        QSize(descriptor_.width, descriptor_.height),
        presenterResource_);
    readinessReport_ = DeckVaapiEglImagePresenter::readinessReportForResource(lastImportPlan_, presenterResource_);
    if (lastImportPlan_.status == DeckQrhiVaapiImportStatus::DrmPrimeExported) {
        if (renderPresenterTexture(presenterResource_, rect(), projectionMatrix())) {
            readinessReport_ = DeckVaapiEglImagePresenter::readinessReportForResource(lastImportPlan_, presenterResource_);
        } else {
            lastImportPlan_.status = DeckQrhiVaapiImportStatus::EglImageShaderCompositionFailed;
            lastImportPlan_.detail = "DRM_PRIME texture layers imported, but GL shader composition proof failed";
            if (!presenterResource_.shaderCompositionDetail.empty()) {
                lastImportPlan_.detail += ": " + presenterResource_.shaderCompositionDetail;
            }
            readinessReport_ = DeckVaapiEglImagePresenter::readinessReportForPlan(lastImportPlan_);
        }
    }
    qInfo().noquote() << "Nova Deck QSGRenderNode VAAPI/EGL render path"
                      << "status=" + QString::fromStdString(readinessReport_.statusCode)
                      << "objects=" + QString::number(readinessReport_.importPlan.drmPrimeObjectCount)
                      << "layers=" + QString::number(readinessReport_.importPlan.drmPrimeLayerCount)
                      << "ready=" + QString::number(readinessReport_.ready ? 1 : 0)
                      << "planned=" + QString::number(readinessReport_.hardwarePresenterPlanned ? 1 : 0)
                      << "readiness stayed false until shader composition proof"
                      << QString::fromStdString(readinessReport_.detail);
    qInfo().noquote() << "Nova Deck VAAPI/EGL presenter readiness"
                      << QString::fromStdString(readinessReport_.statusCode)
                      << QString::fromStdString(readinessReport_.detail);
}

void DeckQtQuickRhiVaapiRenderNode::releaseResources() {
    presenterResource_ = {};
    descriptor_.frameLease.reset();
}

QSGRenderNode::RenderingFlags DeckQtQuickRhiVaapiRenderNode::flags() const {
    return BoundedRectRendering;
}

QRectF DeckQtQuickRhiVaapiRenderNode::rect() const {
    return QRectF(0.0, 0.0, static_cast<qreal>(descriptor_.width), static_cast<qreal>(descriptor_.height));
}

DeckQtQuickRhiVaapiItem::DeckQtQuickRhiVaapiItem(QQuickItem* parent)
    : QQuickItem(parent) {
    setFlag(ItemHasContents, true);
}

DeckQtQuickRhiVaapiItem::~DeckQtQuickRhiVaapiItem() = default;

bool DeckQtQuickRhiVaapiItem::presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) {
    if (!descriptor.hardwareBacked || descriptor.surfaceId == 0 || descriptor.width <= 0 || descriptor.height <= 0 ||
        descriptor.frameLease == nullptr || !descriptor.frameLease->valid()) {
        pendingDescriptor_ = {};
        hasPendingDescriptor_ = true;
        pendingDescriptorValid_ = false;
        update();
        return false;
    }
    pendingDescriptor_ = descriptor;
    hasPendingDescriptor_ = true;
    pendingDescriptorValid_ = true;
    ++presentedFrames_;
    update();
    return true;
}

int DeckQtQuickRhiVaapiItem::presentedFrames() const {
    return presentedFrames_;
}

QSGNode* DeckQtQuickRhiVaapiItem::updatePaintNode(QSGNode* oldNode, UpdatePaintNodeData* updatePaintNodeData) {
    (void)updatePaintNodeData;
    if (!hasPendingDescriptor_) {
        return oldNode;
    }

    DeckQrhiVaapiPresentationDescriptor descriptor = std::move(pendingDescriptor_);
    const bool descriptorValid = pendingDescriptorValid_;
    pendingDescriptor_ = {};
    hasPendingDescriptor_ = false;
    pendingDescriptorValid_ = false;

    if (!descriptorValid) {
        delete oldNode;
        return nullptr;
    }

    if (oldNode != nullptr && oldNode->type() == QSGNode::RenderNodeType) {
        auto* renderNode = static_cast<DeckQtQuickRhiVaapiRenderNode*>(oldNode);
        renderNode->replaceDescriptor(std::move(descriptor), window());
        return renderNode;
    }

    delete oldNode;
    return new DeckQtQuickRhiVaapiRenderNode(std::move(descriptor), window());
}

std::string_view DeckVaapiFfmpegRenderer::adapterName() const {
    return "ffmpeg-vaapi-h264-qt-rhi-prototype";
}

DeckVaapiFfmpegRenderer::~DeckVaapiFfmpegRenderer() {
    resetDecoder();
}

int DeckVaapiFfmpegRenderer::setup(
    const int videoFormat,
    const int width,
    const int height,
    const int redrawRate,
    void* context,
    const int drFlags) {
    (void)context;
    (void)drFlags;
    ++lifecycle_.setupCalls;
    lifecycle_.videoFormat = videoFormat;
    lifecycle_.width = width;
    lifecycle_.height = height;
    lifecycle_.redrawRate = redrawRate;
    lifecycle_.networkStartAllowed = false;
    lifecycle_.decodedHardwareFrames = 0;
    lifecycle_.presentedHardwareFrames = 0;
    lifecycle_.lastFrameWasHardwareBacked = false;
    lifecycle_.lastRuntimeError.clear();
    resetDecoder();

    const DeckLinuxMediaProbe probe = DeckLinuxMediaProbe::detect();
    lifecycle_.runtimeVaapiDeviceAvailable = probe.runtimeVaapiDeviceAvailable;
    lifecycle_.runtimeStatus = probe.runtimeStatus;
    if (videoFormat != VIDEO_FORMAT_H264) {
        lifecycle_.lastRuntimeError = "unsupported video format for Deck FFmpeg VA-API renderer";
        return DR_NEED_IDR;
    }

    int result = av_hwdevice_ctx_create(&hardwareDevice_, AV_HWDEVICE_TYPE_VAAPI, nullptr, nullptr, 0);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "av_hwdevice_ctx_create(VAAPI) failed: " + ffmpegErrorString(result);
        lifecycle_.runtimeStatus = lifecycle_.lastRuntimeError;
        resetDecoder();
        return DR_NEED_IDR;
    }
    lifecycle_.ownsHardwareDevice = true;
    lifecycle_.runtimeVaapiDeviceAvailable = true;
    lifecycle_.runtimeStatus = "vaapi runtime device opened and owned";

    const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (codec == nullptr) {
        lifecycle_.lastRuntimeError = "FFmpeg H.264 decoder unavailable";
        resetDecoder();
        return DR_NEED_IDR;
    }

    codecContext_ = avcodec_alloc_context3(codec);
    if (codecContext_ == nullptr) {
        lifecycle_.lastRuntimeError = "avcodec_alloc_context3(H.264) failed";
        resetDecoder();
        return DR_NEED_IDR;
    }
    codecContext_->width = width;
    codecContext_->height = height;
    codecContext_->get_format = vaapiHardwareFormatCallback;
    codecContext_->hw_device_ctx = av_buffer_ref(hardwareDevice_);
    if (codecContext_->hw_device_ctx == nullptr) {
        lifecycle_.lastRuntimeError = "av_buffer_ref(VAAPI device) failed";
        resetDecoder();
        return DR_NEED_IDR;
    }

    result = avcodec_open2(codecContext_, codec, nullptr);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "avcodec_open2(H.264 VA-API) failed: " + ffmpegErrorString(result);
        resetDecoder();
        return DR_NEED_IDR;
    }

    decodedFrame_ = av_frame_alloc();
    if (decodedFrame_ == nullptr) {
        lifecycle_.lastRuntimeError = "av_frame_alloc() failed";
        resetDecoder();
        return DR_NEED_IDR;
    }

    lifecycle_.ownsCodecContext = true;
    ready_ = true;
    return DR_OK;
}

void DeckVaapiFfmpegRenderer::start() {
    ++lifecycle_.startCalls;
}

void DeckVaapiFfmpegRenderer::stop() {
    ++lifecycle_.stopCalls;
}

void DeckVaapiFfmpegRenderer::cleanup() {
    ++lifecycle_.cleanupCalls;
    resetDecoder();
}

int DeckVaapiFfmpegRenderer::submitDecodeUnit(PDECODE_UNIT decodeUnit) {
    ++lifecycle_.submitCalls;
    lifecycle_.lastFrameWasHardwareBacked = false;
    if (!ready_ || decodeUnit == nullptr) {
        lifecycle_.acceptedNullDecodeUnit = false;
        return DR_NEED_IDR;
    }

    const std::vector<std::uint8_t> bytes = copyDecodeUnitBytes(decodeUnit);
    if (bytes.empty()) {
        lifecycle_.lastRuntimeError = "decode unit did not contain Annex-B H.264 bytes";
        return DR_NEED_IDR;
    }

    AVPacket* packet = av_packet_alloc();
    if (packet == nullptr) {
        lifecycle_.lastRuntimeError = "av_packet_alloc() failed";
        return DR_NEED_IDR;
    }
    int result = av_new_packet(packet, static_cast<int>(bytes.size()));
    if (result < 0) {
        lifecycle_.lastRuntimeError = "av_new_packet() failed: " + ffmpegErrorString(result);
        av_packet_free(&packet);
        return DR_NEED_IDR;
    }
    std::copy(bytes.begin(), bytes.end(), packet->data);

    result = avcodec_send_packet(codecContext_, packet);
    av_packet_free(&packet);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "avcodec_send_packet() failed: " + ffmpegErrorString(result);
        return DR_NEED_IDR;
    }

    while ((result = avcodec_receive_frame(codecContext_, decodedFrame_)) == 0) {
        const bool hardwareBacked = decodedFrame_->format == AV_PIX_FMT_VAAPI;
        lifecycle_.lastFrameWasHardwareBacked = hardwareBacked;
        if (hardwareBacked) {
            ++lifecycle_.decodedHardwareFrames;
            std::shared_ptr<DeckQrhiVaapiFrameLease> frameLease = DeckQrhiVaapiFrameLease::cloneHardwareFrame(*decodedFrame_);
            const DeckQrhiVaapiPresentationDescriptor descriptor{
                .width = lifecycle_.width,
                .height = lifecycle_.height,
                .redrawRate = lifecycle_.redrawRate,
                .surfaceId = frameLease == nullptr ? 0 : frameLease->surfaceId(),
                .hardwareBacked = frameLease != nullptr && frameLease->valid(),
                .frameLease = frameLease,
                .source = "ffmpeg-vaapi-h264",
            };
            if (previewFramePump_.enqueueDecodedFrame(descriptor) && previewFramePump_.flushNewest()) {
                ++lifecycle_.presentedHardwareFrames;
            }
            av_frame_unref(decodedFrame_);
            return DR_OK;
        }
        av_frame_unref(decodedFrame_);
    }

    if (result == AVERROR(EAGAIN)) {
        lifecycle_.lastRuntimeError = "H.264 packet accepted but no VA-API hardware frame was ready";
    } else if (result == AVERROR_EOF) {
        lifecycle_.lastRuntimeError = "H.264 decoder reached EOF before a VA-API hardware frame";
    } else {
        lifecycle_.lastRuntimeError = "avcodec_receive_frame() failed: " + ffmpegErrorString(result);
    }
    return DR_NEED_IDR;
}

const DeckRendererLifecycle& DeckVaapiFfmpegRenderer::lifecycle() const {
    return lifecycle_;
}

DeckQrhiVaapiPresentationHandoff& DeckVaapiFfmpegRenderer::presentationHandoff() {
    return presentationHandoff_;
}

const DeckQrhiVaapiPresentationHandoff& DeckVaapiFfmpegRenderer::presentationHandoff() const {
    return presentationHandoff_;
}

void DeckVaapiFfmpegRenderer::resetDecoder() {
    ready_ = false;
    if (decodedFrame_ != nullptr) {
        av_frame_free(&decodedFrame_);
    }
    if (codecContext_ != nullptr) {
        avcodec_free_context(&codecContext_);
    }
    if (hardwareDevice_ != nullptr) {
        av_buffer_unref(&hardwareDevice_);
    }
    lifecycle_.ownsHardwareDevice = hardwareDevice_ != nullptr;
    lifecycle_.ownsCodecContext = codecContext_ != nullptr;
}

DeckLinuxAudioProbe DeckLinuxAudioProbe::detect() {
    const char* pipeWireVersion = pw_get_headers_version();
    const char* pulseVersion = pa_get_headers_version();
    return DeckLinuxAudioProbe{
        .pipeWireHeadersLinked = pipeWireVersion != nullptr,
        .pulseFallbackHeadersLinked = pulseVersion != nullptr,
        .pipeWireHeaderVersion = pipeWireVersion == nullptr ? "" : pipeWireVersion,
        .pulseHeaderVersion = pulseVersion == nullptr ? "" : pulseVersion,
    };
}

std::string_view DeckPipeWireAudio::adapterName() const {
    return "pipewire-pcm-pulse-fallback-prototype";
}

int DeckPipeWireAudio::init(
    const int audioConfiguration,
    POPUS_MULTISTREAM_CONFIGURATION opusConfig,
    void* context,
    const int arFlags) {
    (void)context;
    (void)arFlags;
    ++lifecycle_.initCalls;
    lifecycle_.audioConfiguration = audioConfiguration;
    lifecycle_.samplesPerFrame = opusConfig == nullptr ? 0 : opusConfig->samplesPerFrame;
    lifecycle_.networkStartAllowed = false;

    const DeckLinuxAudioProbe probe = DeckLinuxAudioProbe::detect();
    ready_ = probe.pipeWireHeadersLinked && audioConfiguration != 0 && opusConfig != nullptr && opusConfig->samplesPerFrame > 0;
    return ready_ ? 0 : -1;
}

void DeckPipeWireAudio::start() {
    ++lifecycle_.startCalls;
}

void DeckPipeWireAudio::stop() {
    ++lifecycle_.stopCalls;
}

void DeckPipeWireAudio::cleanup() {
    ++lifecycle_.cleanupCalls;
    ready_ = false;
}

void DeckPipeWireAudio::decodeAndPlaySample(char* sampleData, const int sampleLength) {
    if (!ready_ || sampleData == nullptr || sampleLength <= 0) {
        return;
    }
    ++lifecycle_.sampleCalls;
    lifecycle_.lastSampleLength = sampleLength;
}

const DeckAudioLifecycle& DeckPipeWireAudio::lifecycle() const {
    return lifecycle_;
}

DeckGuardedStreamSessionPreviewProducer::DeckGuardedStreamSessionPreviewProducer()
    : session_(renderer_, audio_, input_, *this) {}

DeckGuardedStreamSessionPreviewProducer::~DeckGuardedStreamSessionPreviewProducer() = default;

void DeckGuardedStreamSessionPreviewProducer::attachProductPreviewPipeline(DeckProductPreviewPipeline& pipeline) {
    renderer_.presentationHandoff().setBorrowedSink(&pipeline);
}

DeckStreamTransition DeckGuardedStreamSessionPreviewProducer::prepareNoNetwork(const DeckStreamRequest& request) {
    return session_.prepare(request);
}

DeckStreamTransition DeckGuardedStreamSessionPreviewProducer::startNoNetwork() {
    return session_.startNoNetwork();
}

DeckStreamTransition DeckGuardedStreamSessionPreviewProducer::stop() {
    return session_.stop();
}

const DeckMoonlightBoundary& DeckGuardedStreamSessionPreviewProducer::moonlightBoundary() const {
    return session_.moonlightBoundary();
}

DeckVaapiFfmpegRenderer& DeckGuardedStreamSessionPreviewProducer::decodedFrameProducer() {
    return renderer_;
}

const DeckRendererLifecycle& DeckGuardedStreamSessionPreviewProducer::rendererLifecycle() const {
    return renderer_.lifecycle();
}

const std::vector<DeckStreamTransition>& DeckGuardedStreamSessionPreviewProducer::transitions() const {
    return transitions_;
}

std::string_view DeckGuardedStreamSessionPreviewProducer::NoopInput::adapterName() const {
    return "guarded-preview-noop-input";
}

void DeckGuardedStreamSessionPreviewProducer::NoopInput::rumble(
    const uint16_t controllerNumber,
    const uint16_t lowFreqMotor,
    const uint16_t highFreqMotor) {
    (void)controllerNumber;
    (void)lowFreqMotor;
    (void)highFreqMotor;
}

void DeckGuardedStreamSessionPreviewProducer::NoopInput::setMotionEventState(
    const uint16_t controllerNumber,
    const uint8_t motionType,
    const uint16_t reportRateHz) {
    (void)controllerNumber;
    (void)motionType;
    (void)reportRateHz;
}

void DeckGuardedStreamSessionPreviewProducer::NoopInput::setControllerLed(
    const uint16_t controllerNumber,
    const uint8_t r,
    const uint8_t g,
    const uint8_t b) {
    (void)controllerNumber;
    (void)r;
    (void)g;
    (void)b;
}

void DeckGuardedStreamSessionPreviewProducer::onSessionEvent(
    const DeckStreamSessionState state,
    const std::string_view reason) {
    transitions_.push_back(DeckStreamTransition{
        .state = state,
        .reason = std::string(reason),
        .networkStarted = false,
    });
}

const DeckOperatorStartAuthorizationSnapshot& DeckOperatorStartAuthorizationPolicy::snapshot() const {
    return snapshot_;
}

void DeckOperatorStartAuthorizationPolicy::block(std::string reason) {
    snapshot_ = DeckOperatorStartAuthorizationSnapshot{
        .mode = DeckOperatorStartAuthorizationMode::Blocked,
        .statusCode = "operator-start-blocked",
        .reason = std::move(reason),
        .dryRunAuthorized = false,
        .startAuthorized = false,
        .tokenless = true,
        .networkStarted = false,
    };
}

void DeckOperatorStartAuthorizationPolicy::authorizeDryRun(std::string opaqueLocalStateId) {
    snapshot_ = DeckOperatorStartAuthorizationSnapshot{
        .mode = DeckOperatorStartAuthorizationMode::DryRunAuthorized,
        .statusCode = "operator-dry-run-authorized",
        .reason = "operator approved a tokenless dry-run contract; host/network start remains disabled",
        .opaqueLocalStateId = std::move(opaqueLocalStateId),
        .dryRunAuthorized = true,
        .startAuthorized = false,
        .tokenless = true,
        .networkStarted = false,
    };
}

void DeckOperatorStartAuthorizationPolicy::authorizeStart(std::string opaqueLocalStateId) {
    snapshot_ = DeckOperatorStartAuthorizationSnapshot{
        .mode = DeckOperatorStartAuthorizationMode::StartAuthorized,
        .statusCode = "operator-start-authorized",
        .reason = "operator approved a tokenless start contract, pending external host readiness checks",
        .opaqueLocalStateId = std::move(opaqueLocalStateId),
        .dryRunAuthorized = true,
        .startAuthorized = true,
        .tokenless = true,
        .networkStarted = false,
    };
}

namespace {

std::string operatorAuthorizationStateLabel(const DeckOperatorStartAuthorizationMode mode) {
    switch (mode) {
    case DeckOperatorStartAuthorizationMode::Blocked:
        return "blocked";
    case DeckOperatorStartAuthorizationMode::DryRunAuthorized:
        return "dry-run-authorized";
    case DeckOperatorStartAuthorizationMode::StartAuthorized:
        return "start-authorized";
    }
    return "blocked";
}

} // namespace

DeckGuardedPreviewLifecycleGate::DeckGuardedPreviewLifecycleGate(DeckGuardedStreamSessionPreviewProducer& producer)
    : producer_(producer) {}

void DeckGuardedPreviewLifecycleGate::attachProductPreviewPipeline(DeckProductPreviewPipeline& pipeline) {
    producer_.attachProductPreviewPipeline(pipeline);
    lastReport_ = DeckGuardedPreviewLifecycleReport{
        .state = DeckStreamSessionState::Idle,
        .statusCode = "idle-no-network",
        .reason = "guarded product preview pipeline attached; host/network start remains disabled until explicitly armed",
        .prepared = false,
        .armed = false,
        .operatorAuthorizationState = "blocked",
        .networkStartAllowed = producer_.moonlightBoundary().networkStartAllowed,
        .networkStarted = false,
        .transitionCount = producer_.transitions().size(),
    };
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::armNoNetwork(const DeckStreamRequest& request) {
    if (lastReport_.state == DeckStreamSessionState::Active && lastReport_.armed) {
        lastReport_.statusCode = "already-active-no-network";
        lastReport_.reason = "guarded product preview is already armed no-network; duplicate arm request stayed local and idempotent";
        lastReport_.networkStartAllowed = producer_.moonlightBoundary().networkStartAllowed;
        lastReport_.networkStarted = false;
        lastReport_.transitionCount = producer_.transitions().size();
        return lastReport_;
    }

    const auto prepared = producer_.prepareNoNetwork(request);
    if (prepared.state != DeckStreamSessionState::Preparing) {
        lastReport_ = reportForTransition(prepared, "prepare-denied-no-network", false, false, &request);
        return lastReport_;
    }

    const auto armed = producer_.startNoNetwork();
    lastReport_ = reportForTransition(
        armed,
        armed.state == DeckStreamSessionState::Active ? "active-no-network" : "arm-denied-no-network",
        true,
        armed.state == DeckStreamSessionState::Active,
        &request);
    return lastReport_;
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::requestGuardedHostNetworkStart() {
    lastReport_.statusCode = "host-network-start-blocked";
    lastReport_.reason = "guarded host/network start boundary is explicit but blocked pending operator authorization; no external host bootstrap or network start was attempted";
    lastReport_.dryRunPreflightRequested = false;
    lastReport_.hostStartBoundaryExplicit = true;
    lastReport_.hostStartContractAuthorized = false;
    lastReport_.operatorAuthorizationState = "blocked";
    lastReport_.networkStartAllowed = false;
    lastReport_.networkStarted = false;
    lastReport_.transitionCount = producer_.transitions().size();
    return lastReport_;
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::requestOperatorAuthorizedDryRun(
    const DeckOperatorStartAuthorizationSnapshot& authorization) {
    lastReport_.dryRunPreflightRequested = false;
    lastReport_.hostStartBoundaryExplicit = true;
    lastReport_.hostStartContractAuthorized = false;
    lastReport_.operatorAuthorizationState = operatorAuthorizationStateLabel(authorization.mode);
    lastReport_.networkStartAllowed = false;
    lastReport_.networkStarted = false;
    lastReport_.transitionCount = producer_.transitions().size();

    if (!authorization.dryRunAuthorized) {
        lastReport_.statusCode = "operator-dry-run-blocked";
        lastReport_.reason = "operator dry-run contract is blocked; no producer setup or host/network start was attempted";
        return lastReport_;
    }

    lastReport_.statusCode = "operator-dry-run-authorized";
    lastReport_.reason = "operator dry-run contract approved tokenlessly; report-only path stayed local and networkStarted=false";
    return lastReport_;
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::requestHostStartDryRunPreflight(
    const DeckOperatorStartAuthorizationSnapshot& authorization,
    const DeckStreamRequest& request) {
    lastReport_.hostId = request.hostId;
    lastReport_.gameId = request.gameId;
    lastReport_.width = request.width;
    lastReport_.height = request.height;
    lastReport_.fps = request.fps;
    lastReport_.bitrateKbps = request.bitrateKbps;
    lastReport_.dryRunPreflightRequested = true;
    lastReport_.hostStartBoundaryExplicit = true;
    lastReport_.hostStartContractAuthorized = false;
    lastReport_.operatorAuthorizationState = operatorAuthorizationStateLabel(authorization.mode);
    lastReport_.networkStartAllowed = false;
    lastReport_.networkStarted = false;
    lastReport_.transitionCount = producer_.transitions().size();

    if (request.hostId.empty()) {
        lastReport_.statusCode = "host-start-preflight-missing-host";
        lastReport_.reason = "host start dry-run preflight requires a missing host selection to be resolved before any host contract can be evaluated; no network path was attempted";
        return lastReport_;
    }

    if (!authorization.startAuthorized) {
        lastReport_.statusCode = "host-start-preflight-contract-blocked";
        lastReport_.reason = "operator start contract is blocked, so host start dry-run preflight stayed report-only and no producer setup or network path was attempted";
        return lastReport_;
    }

    lastReport_.statusCode = "host-start-dry-run-preflight-authorized";
    lastReport_.reason = "operator start contract approved the report-only host start dry-run preflight; requirements were summarized locally and networkStartAllowed=false";
    lastReport_.hostStartContractAuthorized = true;
    return lastReport_;
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::requestOperatorAuthorizedHostNetworkStart(
    const DeckOperatorStartAuthorizationSnapshot& authorization) {
    lastReport_.dryRunPreflightRequested = false;
    lastReport_.hostStartBoundaryExplicit = true;
    lastReport_.hostStartContractAuthorized = authorization.startAuthorized;
    lastReport_.operatorAuthorizationState = operatorAuthorizationStateLabel(authorization.mode);
    lastReport_.networkStartAllowed = false;
    lastReport_.networkStarted = false;
    lastReport_.transitionCount = producer_.transitions().size();

    if (!authorization.startAuthorized) {
        lastReport_.statusCode = "operator-start-blocked";
        lastReport_.reason = "operator start contract is blocked; no external host readiness or network path was attempted";
        return lastReport_;
    }

    lastReport_.statusCode = "operator-start-not-ready";
    lastReport_.reason = "operator start contract is approved, but external host readiness is not available and the Deck product route keeps network disabled; no network start was attempted";
    return lastReport_;
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::stop() {
    if (lastReport_.state == DeckStreamSessionState::Stopped) {
        lastReport_.statusCode = "already-stopped-no-network";
        lastReport_.reason = "guarded product preview is already stopped; duplicate stop request stayed local and idempotent";
        lastReport_.prepared = false;
        lastReport_.armed = false;
        lastReport_.dryRunPreflightRequested = false;
        lastReport_.networkStartAllowed = producer_.moonlightBoundary().networkStartAllowed;
        lastReport_.networkStarted = false;
        lastReport_.transitionCount = producer_.transitions().size();
        return lastReport_;
    }

    const auto stopped = producer_.stop();
    lastReport_ = reportForTransition(
        stopped,
        stopped.state == DeckStreamSessionState::Stopped ? "stopped-no-network" : "stop-denied-no-network",
        false,
        false);
    return lastReport_;
}

const DeckGuardedPreviewLifecycleReport& DeckGuardedPreviewLifecycleGate::lastReport() const {
    return lastReport_;
}

const std::vector<DeckStreamTransition>& DeckGuardedPreviewLifecycleGate::transitions() const {
    return producer_.transitions();
}

DeckGuardedPreviewLifecycleReport DeckGuardedPreviewLifecycleGate::reportForTransition(
    const DeckStreamTransition& transition,
    std::string statusCode,
    const bool prepared,
    const bool armed,
    const DeckStreamRequest* request) const {
    const DeckStreamRequest retainedRequest{
        .hostId = request != nullptr ? request->hostId : lastReport_.hostId,
        .gameId = request != nullptr ? request->gameId : lastReport_.gameId,
        .width = request != nullptr ? request->width : lastReport_.width,
        .height = request != nullptr ? request->height : lastReport_.height,
        .fps = request != nullptr ? request->fps : lastReport_.fps,
        .bitrateKbps = request != nullptr ? request->bitrateKbps : lastReport_.bitrateKbps,
    };
    return DeckGuardedPreviewLifecycleReport{
        .state = transition.state,
        .statusCode = std::move(statusCode),
        .reason = transition.reason,
        .hostId = retainedRequest.hostId,
        .gameId = retainedRequest.gameId,
        .width = retainedRequest.width,
        .height = retainedRequest.height,
        .fps = retainedRequest.fps,
        .bitrateKbps = retainedRequest.bitrateKbps,
        .prepared = prepared,
        .armed = armed,
        .dryRunPreflightRequested = false,
        .hostStartBoundaryExplicit = lastReport_.hostStartBoundaryExplicit,
        .hostStartContractAuthorized = false,
        .operatorAuthorizationState = lastReport_.operatorAuthorizationState,
        .networkStartAllowed = producer_.moonlightBoundary().networkStartAllowed,
        .networkStarted = transition.networkStarted,
        .transitionCount = producer_.transitions().size(),
    };
}

} // namespace nova::deck::stream
