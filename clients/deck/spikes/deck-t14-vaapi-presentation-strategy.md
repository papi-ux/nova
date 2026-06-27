# Deck-T14 VAAPI presentation strategy decision

Status: accepted local/offline decision artifact for Deck-T14. This document does not start host streaming and does not add `LiStartConnection`, host discovery, pairing, credentials, app launch, fake streaming UI, or Android changes. `pushed=false`; `android_touched=false`.

## Decision

Choose the public Qt Quick OpenGL/EGLImage bridge as the next implementation path:

1. Keep the existing FFmpeg VAAPI decode and DRM_PRIME export seam.
2. Add a hard runtime gate that only enables the presenter when the Qt Quick scene graph is `QSGRendererInterface::OpenGLRhi`.
3. Import the DRM_PRIME/dmabuf planes into `EGLImageKHR` with `EGL_LINUX_DMA_BUF_EXT`, bind them to GL textures, and wrap the GL texture for Qt Quick with `QNativeInterface::QSGOpenGLTexture::fromNativeExternalOES()` or `fromNative()`.
4. Fall back to the current honest `UnsupportedPublicQtLinuxDmabufImport` status when the scene graph is not OpenGL, required EGL extensions are missing, plane/modifier metadata is incomplete, or the import fails.

This is the safest next step because it stays on public Qt Quick API for the scene-graph handoff while using standard Linux EGL/GL dmabuf interop at the boundary Qt actually exposes publicly. It also preserves the current Qt shell, overlay lane, Game Mode/gamescope friendliness, and no-network streaming guardrails.

## Evidence gathered

### Current in-tree seam

- `clients/deck/src/stream/deck_stream_media_adapters.h` already retains a `DeckQrhiVaapiFrameLease`, exports a `DeckQrhiVaapiDrmPrimeDescriptor`, and reports `DeckQrhiVaapiImportStatus::UnsupportedPublicQtLinuxDmabufImport` when public QRhi import is blocked.
- `clients/deck/src/stream/deck_stream_media_adapters.cpp` maps VAAPI frames to DRM_PRIME through `av_hwframe_map()` and stores object/layer counts, but deliberately does not fake texture import.
- `clients/deck/tests/deck_stream_media_adapters_test.cpp` exercises a real decoded VAAPI frame when runtime VAAPI is available and asserts DRM_PRIME object/layer export succeeds before reporting the public-Qt block.

### Local Qt/API facts

Commands run from `/home/papi/Documents/github/nova`:

```text
pkg-config --modversion Qt6Quick Qt6Gui Qt6Multimedia
=> 6.11.1 / 6.11.1 / 6.11.1

rpm -q qt6-qtbase-devel qt6-qtbase-private-devel qt6-qtmultimedia-devel qt6-qtmultimedia-private-devel
=> qt6-qtbase-devel-6.11.1-1.fc44.x86_64
=> package qt6-qtbase-private-devel is not installed
=> qt6-qtmultimedia-devel-6.11.1-1.fc44.x86_64
=> package qt6-qtmultimedia-private-devel is not installed
```

Header evidence:

- `/usr/include/qt6/QtQuick/qsgtexture_platform.h` exposes public GL texture wrapping:
  - `QNativeInterface::QSGOpenGLTexture::fromNative(GLuint, QQuickWindow*, QSize, ...)`
  - `QNativeInterface::QSGOpenGLTexture::fromNativeExternalOES(GLuint, QQuickWindow*, QSize, ...)`
- `/usr/include/qt6/QtQuick/qquickwindow.h` exposes the public scene-graph backend gate:
  - `QQuickWindow::setGraphicsApi(QSGRendererInterface::GraphicsApi)`
  - `QQuickWindow::graphicsApi()`
  - `QQuickWindow::rendererInterface()`
- `/usr/include/qt6/QtQuick/qsgrendererinterface.h` exposes `OpenGLRhi`, `VulkanRhi`, and `RhiResource`, but no public Linux dmabuf/VASurface-to-QRhi texture import function.
- `/usr/include/qt6/QtMultimedia/qvideoframe.h` exposes only `QVideoFrame::NoHandle` and `QVideoFrame::RhiTextureHandle`; it does not expose a public dmabuf/VASurface constructor or handle type.
- `/usr/include/qt6/QtMultimedia/6.11.1/QtMultimedia/private/qvideoframe_p.h` has a private `QVideoFramePrivate::hasDmaBuf()` helper, and `/usr/include/qt6/QtMultimedia/6.11.1/QtMultimedia/private/qhwvideobuffer_p.h` has private `QHwVideoBuffer::isDmaBuf()`. The warning in that header says it is not Qt API and can change or disappear.
- `/usr/include/EGL/eglext.h` exposes `eglCreateImageKHR` and `EGL_LINUX_DMA_BUF_EXT`.
- `/usr/include/GLES2/gl2ext.h` exposes `GL_TEXTURE_EXTERNAL_OES`.
- `/usr/include/va/va.h` exposes `vaExportSurfaceHandle()`, though the current Nova seam already gets DRM_PRIME via FFmpeg `av_hwframe_map()`.

### Compile probes

Probe sources were written under `/tmp/nova-deck-t14-probes`.

```text
qsg_opengl_bridge_probe: PASS
```

Compiled a public Qt Quick probe using `QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGLRhi)` and `QNativeInterface::QSGOpenGLTexture::fromNativeExternalOES()` with `pkg-config --cflags Qt6Quick Qt6Gui`.

```text
qvideoframe_public_probe: PASS
```

Compiled a public Qt Multimedia probe showing custom public `QAbstractVideoBuffer` frames are still `QVideoFrame::NoHandle`; public `QVideoFrame` gives `RhiTextureHandle`, not dmabuf/VASurface handles.

```text
qvideoframe_private_dmabuf_probe: PASS
```

Compiled only after adding private Qt Multimedia include paths. This proves the dmabuf helper exists, but it is private API and depends on private headers.

### Build/test verification

```text
cmake -S clients/deck -B build/deck-t14 -DNOVA_DECK_BUILD_QT_SHELL=ON
cmake --build build/deck-t14
ctest --test-dir build/deck-t14 --output-on-failure
```

Result: 5/5 tests passed locally:

- `nova_deck_controller_library_smoke`
- `nova_deck_stream_core_test`
- `nova_deck_stream_media_adapters_test`
- `nova_deck_gamemode_capture_harness_test`
- `nova_deck_qt_shell_smoke`

### Steam Deck probe attempt

```text
ssh -o BatchMode=yes -o ConnectTimeout=8 deck@10.0.0.39 '...'
=> ssh: connect to host 10.0.0.39 port 22: Connection timed out
```

No Deck/container probe was completed during T14 because the Deck SSH target was unreachable. This does not invalidate the strategy choice because Deck-T13 already confirmed AMD/radeonsi VAAPI decode and real VAAPI-to-DRM_PRIME export on Deck hardware. T15 should rerun the rootless podman CTest plus a GL/EGL extension probe on Deck before accepting any implementation.

## Options compared

| Option | Public API status | Zero/low-copy potential | Main risk | Verdict |
|---|---|---:|---|---|
| Private Qt/QRhi dmabuf import gate | Blocked on this host: Qt private QRhi headers are not installed; public Qt Quick exposes `RhiResource` but not dmabuf import. | High if it worked | Private API churn, package availability, backend-specific QRhi internals, brittle SteamOS upgrades | Reject for next implementation; keep only as last-resort spike if public GL route fails |
| Public EGLImage/GL texture bridge | Public Qt Quick can force/gate `OpenGLRhi` and wrap native GL/OES textures. EGL/GL/VAAPI dmabuf symbols are present. | High, if dmabuf modifiers/planes import cleanly | Requires OpenGL scene graph; must manage EGL image/GL texture lifetime and extension checks carefully | Choose for T15 |
| Qt Multimedia/QVideoFrame native-frame integration | Public `QVideoFrame` only exposes `NoHandle`/`RhiTextureHandle`; dmabuf detection and hardware-buffer mapping live in private headers. | Medium/high through private backend internals | Private API, FFmpeg backend ownership mismatch, harder to preserve Moonlight decode-unit pacing and Nova overlay timing | Defer/reject for T15; revisit only if a public dmabuf producer API appears |
| Raw DRM/KMS or gamescope bypass | Public Linux APIs exist outside Qt | High | Fights Qt shell, focus, overlays, suspend/resume, and Game Mode integration | Reject for first stream path |
| Software readback/upload | Public and simple | Low | Loses hardware-backed objective and likely latency/battery performance | Diagnostic fallback only |

## Implementation guardrails for T15

- Do not start host streaming; keep using deterministic local H.264/VAAPI decode input.
- Do not call `LiStartConnection`, host discovery, pairing, credentials, app launch, socket setup, or Android code.
- Keep the existing `DeckQrhiVaapiImportStatus` honesty: every disabled/failing route must report why it is disabled instead of presenting a fake streaming surface.
- Add a small GL/EGL presenter object behind the existing `DeckQtQuickRhiVaapiRenderNode` boundary; do not pull this into general app shell code.
- Gate on `QSGRendererInterface::OpenGLRhi` at runtime and fail closed on Vulkan/software/null scene graphs.
- Probe required extensions before import: `EGL_EXT_image_dma_buf_import`, modifier support if needed, and OES/external texture support if using `GL_TEXTURE_EXTERNAL_OES`.
- Preserve dmabuf fd and frame lease lifetime until Qt has finished the render pass/frame using it.
- Test unsupported paths first: non-OpenGL scene graph, missing frame lease, missing hardware context, failed EGL import, and release cleanup.

## Recommended next card

Deck-T15 public EGLImage/GL VAAPI presenter gate: implement a no-network, local/offline presenter behind the existing Deck Qt Quick render node that imports the current FFmpeg DRM_PRIME descriptor into EGLImage/GL texture only when Qt Quick is running `OpenGLRhi`; wrap that texture with public `QNativeInterface::QSGOpenGLTexture`, preserve the VAAPI frame lease and GL/EGL resources through render cleanup, and report explicit unsupported status for every failed gate. Required verification: RED tests for non-OpenGL/missing-extension/fd-lifetime cleanup, GREEN local Deck CMake/CTest, Steam Deck rootless podman CTest plus GL/EGL extension probe, `git diff --check`, no Android changes, `pushed=false`, and independent review before commit.
