#include "stream/deck_stream_media_adapters.h"

#include <QGuiApplication>
#include <QImage>
#include <QOpenGLContext>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QSurfaceFormat>
#include <QThread>
#include <GLES2/gl2.h>

#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::stream;

namespace {
std::atomic<int> compositions{0};

void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

// Actual GL textures exercise the product shader under Qt's scene graph. They
// deliberately carry no EGLImages: this is not VAAPI or dmabuf acceptance.
class VideoNode final : public QSGRenderNode {
public:
    VideoNode() : geometry_({.width = 160, .height = 90}) {}
    void resize(const QSizeF& size) { geometry_.setItemSize(size); }
    void scaling(const QString& mode, bool pattern) {
        geometry_.setVideoScaleMode(*deckVideoScaleMode(mode));
        if (pattern_ != pattern) { resource_ = {}; pattern_ = pattern; }
    }
    QRectF rect() const override { return geometry_.rect(); }
    RenderingFlags flags() const override { return BoundedRectRendering; }
    StateFlags changedStates() const override { return geometry_.changedStates(); }
    void releaseResources() override { resource_ = {}; }

    void render(const RenderState* state) override {
        if (resource_.glTextures[0] == 0) {
            const auto* context = QOpenGLContext::currentContext();
            require(context != nullptr && context->format().profile() == QSurfaceFormat::CoreProfile,
                    "pixel regression must exercise a desktop OpenGL core profile");
            glGenTextures(2, resource_.glTextures.data());
            const unsigned char y[] = {224, 224, 224, 224, 64, 64, 64, 64};
            const unsigned char scaleY[] = {224,64,64,224,224,128,128,224};
            const unsigned char uv[] = {128, 128, 128, 128};
            // R8/RG8 textures match the two-layer NV12 import's shader inputs.
            for (int layer = 0; layer < 2; ++layer) {
                glBindTexture(GL_TEXTURE_2D, resource_.glTextures[layer]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                constexpr int r8 = 0x8229, rg8 = 0x822b, red = 0x1903, rg = 0x8227;
                glTexImage2D(GL_TEXTURE_2D, 0, layer == 0 ? r8 : rg8,
                             layer == 0 ? 4 : 2, layer == 0 ? 2 : 1, 0,
                             layer == 0 ? red : rg, GL_UNSIGNED_BYTE, layer == 0 ? (pattern_ ? scaleY : y) : uv);
            }
            resource_.importedLayerCount = 2;
        }
        require(DeckVaapiEglImagePresenter::composeOpenGlTexture(resource_, *this, *state, geometry_.sourceRect()),
                resource_.shaderCompositionDetail.c_str());
        require(!resource_.hasTexture(), "synthetic textures must not claim EGL import");
        ++compositions;
    }

private:
    DeckVaapiEglImagePresenter::Resource resource_;
    DeckQtQuickRhiVaapiRenderNode geometry_;
    bool pattern_ = false;
};
} // namespace

class TestVideoItem : public QQuickItem {
    Q_OBJECT
public:
    TestVideoItem() { setFlag(ItemHasContents); }
    Q_INVOKABLE void setMode(const QString& mode) { mode_=mode; pattern_=true; update(); }
protected:
    void geometryChange(const QRectF& next, const QRectF& previous) override {
        QQuickItem::geometryChange(next, previous);
        update();
    }
    QSGNode* updatePaintNode(QSGNode* old, UpdatePaintNodeData*) override {
        auto* node = static_cast<VideoNode*>(old);
        if (node == nullptr) node = new VideoNode;
        node->resize(size());
        node->scaling(mode_,pattern_);
        return node;
    }
private:
    QString mode_="fit";
    bool pattern_=false;
};

namespace {
QImage capture(QQuickWindow& window) {
    for (int tick = 0; tick < 10; ++tick) {
        QCoreApplication::processEvents();
        QThread::msleep(10);
    }
    auto image = window.grabWindow();
    require(!image.isNull(), "Qt failed to capture the OpenGL scene");
    require(image.size() == QSize(400, 300), "unexpected capture dimensions");
    return image;
}

void pixel(const QImage& image, int x, int y, QColor expected, const char* reason) {
    const auto actual = image.pixelColor(x, y);
    if (std::abs(actual.red() - expected.red()) > 4 ||
        std::abs(actual.green() - expected.green()) > 4 ||
        std::abs(actual.blue() - expected.blue()) > 4) {
        std::cerr << reason << " at " << x << ',' << y << ": expected "
                  << expected.name().toStdString() << ", got " << actual.name().toStdString() << '\n';
        image.save("video-composition-failure.png");
        std::exit(1);
    }
}
} // namespace

int main(int argc, char** argv) {
    qputenv("QT_SCALE_FACTOR", "1");
    QSurfaceFormat format;
    format.setRenderableType(QSurfaceFormat::OpenGL);
    format.setVersion(3, 3);
    format.setProfile(QSurfaceFormat::CoreProfile);
    format.setDepthBufferSize(24);
    format.setStencilBufferSize(8);
    QSurfaceFormat::setDefaultFormat(format);
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    QGuiApplication app(argc, argv);
    qmlRegisterType<TestVideoItem>("Nova.TestVideo", 1, 0, "TestVideo");
    QQmlEngine engine;
    QQmlComponent component(&engine);
    component.setData(R"(
        import QtQuick
        import Nova.TestVideo
        Item {
            width: 400; height: 300
            Item {
                objectName: "clip"
                x: 40; y: 30; width: 240; height: 180
                clip: true; opacity: 0.5
                TestVideo {
                    objectName: "video"
                    x: -20; y: 20; width: 320; height: 180
                    scale: 0.75; transformOrigin: Item.TopLeft
                }
            }
            Rectangle { x: 100; y: 80; width: 60; height: 35; color: "#ff0040"; opacity: 0.75 }
            Rectangle { x: 320; y: 20; width: 30; height: 30; color: "white" }
        }
    )", QUrl());
    require(component.isReady(), qPrintable(component.errorString()));
    QQuickWindow window;
    window.setColor(QColor("#102030"));
    window.resize(400, 300);
    auto* root = qobject_cast<QQuickItem*>(component.create());
    require(root != nullptr, "failed to create the video test scene");
    root->setParentItem(window.contentItem());
    root->setParent(&window);
    window.show();
    auto image = capture(window);
    require(compositions > 0, "product GL composition was never exercised");
    const QColor background("#102030");
    pixel(image, 30, 80, background, "parent scissor clip");
    pixel(image, 50, 40, background, "translated top edge");
    pixel(image, 70, 70, QColor(121, 128, 137), "transformed top luma and inherited opacity");
    pixel(image, 70, 160, QColor(41, 48, 57), "texture orientation and lower luma");
    pixel(image, 270, 70, background, "scaled right edge");
    pixel(image, 110, 90, QColor(222, 32, 82), "Qt overlay composited above video");
    pixel(image, 330, 30, Qt::white, "Qt scissor state restored for later items");

    auto* clip = root->findChild<QQuickItem*>("clip");
    auto* video = root->findChild<QQuickItem*>("video");
    require(clip && video, "missing scene items");
    // Resize without a new video frame: fit 16:9 into a square, with bars.
    clip->setOpacity(1);
    video->setPosition(QPointF(0, 0));
    video->setScale(1);
    video->setSize(QSizeF(180, 180));
    image = capture(window);
    pixel(image, 60, 45, background, "letterbox top bar after resize");
    pixel(image, 60, 85, QColor(225, 224, 225), "resized top luma");
    pixel(image, 60, 165, QColor(65, 64, 65), "resized bottom luma");
    pixel(image, 60, 190, background, "letterbox bottom bar after resize");

    // A rotated clipping parent uses the stencil path instead of an axis-aligned scissor.
    clip->setRotation(25);
    video->setPosition(QPointF(-80, -80));
    video->setSize(QSizeF(400, 300));
    image = capture(window);
    const auto inside = clip->mapToScene(QPointF(20, 50)).toPoint();
    const auto outside = clip->mapToScene(QPointF(-15, 50)).toPoint();
    pixel(image, inside.x(), inside.y(), QColor(225, 224, 225), "rotated stencil interior");
    pixel(image, outside.x(), outside.y(), background, "rotated stencil exterior");
    pixel(image, 330, 30, Qt::white, "later Qt item survives stencil draw");
    clip->setRotation(0); video->setPosition({0,0}); video->setSize({80,160});
    QMetaObject::invokeMethod(video,"setMode",Q_ARG(QString,"fit")); image=capture(window);
    pixel(image,44,40,background,"Fit bars lost after mode change");
    pixel(image,44,110,QColor(225,224,225),"Fit cropped the source edge");
    QMetaObject::invokeMethod(video,"setMode",Q_ARG(QString,"fill")); image=capture(window);
    pixel(image,44,70,QColor(65,64,65),"Fill did not crop source UVs");
    QMetaObject::invokeMethod(video,"setMode",Q_ARG(QString,"stretch")); image=capture(window);
    pixel(image,44,70,QColor(225,224,225),"Stretch cropped the source edge");
    QMetaObject::invokeMethod(video,"setMode",Q_ARG(QString,"fit")); image=capture(window);
    pixel(image,44,40,background,"Fit left stale Stretch pixels");
    window.hide();
    window.releaseResources();
    std::cout << "OpenGL pixels passed: transform, opacity, overlays, clipping, resize and aspect ratio\n";
}

#include "deck_video_composition_test.moc"
