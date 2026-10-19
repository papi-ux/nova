#include "runtime/deck_play_settings.h"
#include "stream/deck_stream_media_adapters.h"
#include "deck_preview_fixture.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <QFile>
#include <QFont>
#include <QImage>
#include <QSettings>
#include <iostream>
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8());
    qputenv("QT_QUICK_CONTROLS_STYLE", "Material");
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("Density");
    // Seed actual native INI bytes before QSettings or a QML singleton reads
    // them. A same-process setValue() fixture retains numeric QVariant types.
    require(QDir().mkpath(directory.filePath("NovaDeckTests")), "cold settings directory failed");
    const QByteArray coldSettings("[InGameControls]\nmenuOpacityPercent=25\n");
    QFile coldFile(directory.filePath("NovaDeckTests/Density.conf"));
    require(coldFile.open(QIODevice::WriteOnly) && coldFile.write(coldSettings) == coldSettings.size(), "cold settings fixture failed");
    coldFile.close();
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    PreviewSession session; PreviewPlayers players(session);
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264 = {4096,4096}, .hevc = {4096,4096}});
    session.startConfigured("fixture-host", "fixture-game", {});
    session.transition("active", true, "Your game is connected.");
    QQmlEngine engine; engine.rootContext()->setContextProperty("testSession", &session);
    engine.rootContext()->setContextProperty("testPlayers", &players); engine.rootContext()->setContextProperty("testSettings", &settings);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width:1280; height:800; visible:true
    NativeStreamPreview { id:preview; session:testSession; inputHub:testPlayers; settingsProvider:testSettings
        hostId:"fixture-host"; gameId:"fixture-game"; gameTitle:"Moonlit Harbor"; hostName:"Living Room PC" }
    function largeText() { NovaTheme.setFontScale(1.3) }
    function defaultText() { NovaTheme.setFontScale(0.8) }
    function menuOpacity(value) {
        if (typeof NovaStreamPreferences.setMenuOpacity !== "function") return false
        NovaStreamPreferences.setMenuOpacity(value)
        return true
    }
    function hudOpacity() { return NovaHudPreferences.panelOpacity }
    function menuOpacityValue() { return NovaStreamPreferences.menuOpacityPercent }
    Component.onCompleted: { preview.open(); preview.attempted=true }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/CommandTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "missing Command Center window"); QTest::qWait(150);
    QVariant restoredOpacity;
    QMetaObject::invokeMethod(root.get(), "menuOpacityValue", Q_RETURN_ARG(QVariant, restoredOpacity));
    require(restoredOpacity.toInt() == 25, "cold native INI menu opacity was discarded");
    const auto item = [&](const char* name) { auto* found = root->findChild<QQuickItem*>(name); require(found && found->isVisible(), name); return found; };
    const auto rect = [&](const char* name) { return item(name)->mapRectToScene(item(name)->boundingRect()); };
    const auto capture = [&](const char* name) { if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QDir(argv[1]).filePath(name)), "Command Center capture failed"); } };
    const auto actionText = [&](auto&& self, QQuickItem* parent, const QString& text) -> QQuickItem* {
        for (auto* child : parent->childItems()) {
            if (child->isVisible() && child->property("text").toString() == text
                && child->property("contentWidth").isValid()) return child;
            if (auto* found = self(self, child, text)) return found;
        }
        return nullptr;
    };
    const auto checkActionText = [&] {
        int titlePixels = 0, captionPixels = 0;
        for (const char* name : {"native-preview-action", "native-disconnect-action", "native-end-action"}) {
            auto* owner = item(name);
            const auto ownerBounds = rect(name).adjusted(-1, -1, 1, 1);
            require(owner->height() >= 48 && rect(name).top() >= 0
                && rect(name).bottom() <= window->height() && rect(name).left() >= 0
                && rect(name).right() <= window->width()
                && rect("native-command-center").adjusted(-1, -1, 1, 1).contains(rect(name)),
                "Command Center action escaped its panel/viewport or touch minimum");
            auto* title = actionText(actionText, owner, owner->property("text").toString());
            require(title, "Command Center action has no rendered title");
            const int pixels = title->property("font").value<QFont>().pixelSize();
            if (titlePixels) require(pixels == titlePixels, "Command Center action title scales disagree");
            titlePixels = pixels;
            require(title->property("lineCount").toInt() == 1, "Command Center split an action title instead of fitting its columns");
            const QString caption = owner->property("caption").toString();
            auto* detail = caption.isEmpty() ? nullptr : actionText(actionText, owner, caption);
            require(caption.isEmpty() || detail, "Command Center action has no rendered caption");
            if (detail) {
                const int pixels = detail->property("font").value<QFont>().pixelSize();
                if (captionPixels) require(pixels == captionPixels, "Command Center caption scales disagree");
                captionPixels = pixels;
            }
            for (auto* text : {title, detail}) {
                if (!text) continue;
                const auto bounds = text->mapRectToScene(QRectF(0, 0, text->width(), text->height()));
                require(text->width() > 0 && text->height() > 0 && ownerBounds.contains(bounds)
                    && ownerBounds.contains(text->mapRectToScene(text->boundingRect())),
                    "Command Center rendered text escaped its action");
                require(text->property("contentWidth").toReal() <= text->width() + 1
                    && text->property("contentHeight").toReal() <= text->height() + 1,
                    "Command Center clipped actual label or caption content");
                std::cout << window->width() << 'x' << window->height() << ' ' << name << ' '
                    << text->property("text").toString().toStdString() << " font="
                    << text->property("font").value<QFont>().pixelSize() << " lines="
                    << text->property("lineCount").toInt() << " width=" << text->width()
                    << " content=" << text->property("contentWidth").toReal() << '\n';
            }
            if (detail) require(title->mapRectToScene(title->boundingRect()).bottom()
                <= detail->mapRectToScene(detail->boundingRect()).top(), "Command Center label and caption overlap");
        }
        return titlePixels;
    };
    window->resize(1920, 1080); QTest::qWait(150);
    capture("command-center-frame-1920.png");
    const int normalPixels = checkActionText();
    QMetaObject::invokeMethod(root.get(), "largeText"); QTest::qWait(150);
    capture("command-center-frame-large-1920.png");
    require(checkActionText() > normalPixels, "Command Center ignored larger accessibility text");
    window->resize(1280, 800); QMetaObject::invokeMethod(root.get(), "defaultText"); QTest::qWait(150);
    require(checkActionText() == normalPixels, "Command Center enlarged default text on a wider Frame display");
    capture("command-center-800p.png");
    const auto hud = rect("native-hud-settings");
    std::cout << "800p first-page HUD bottom=" << hud.bottom() << '\n';
    require(hud.bottom() <= window->height() && hud.top() >= 0, "800p Command Center hid NovaHUD below first page");
    require(rect("native-preview-action").bottom() <= rect("native-players-reassign").top(), "header and Players actions overlap");
    window->resize(960,600); QMetaObject::invokeMethod(root.get(), "largeText"); QTest::qWait(150);
    for (const char* name : {"native-players-reassign", "native-appearance", "native-video-scale", "native-hud-settings", "native-doctor"}) {
        auto* target = item(name); target->forceActiveFocus(); QTest::qWait(80); const auto bounds = rect(name);
        require(window->activeFocusItem() == target && bounds.top() >= 0 && bounds.bottom() <= window->height(), "large-text focused action escaped scroll viewport");
        require(target->height() >= 48, "compact Command Center shrank a touch target below 48px");
    }
    capture("command-center-large-960.png");
    require(session.starts == 1 && session.stops == 0 && session.disconnects == 0 && session.controlsVisible(), "layout navigation changed stream lifecycle");
    // Observe the real streaming panel and actions, not a token arithmetic mirror.
    auto* center = item("native-command-center");
    require(center->width() <= 560, "parity Command Center still occupies a desktop-wide panel");
    window->resize(360, 640); QTest::qWait(150);
    checkActionText();
    const auto close = rect("native-preview-action"), disconnect = rect("native-disconnect-action"), end = rect("native-end-action");
    require(close.bottom() <= disconnect.top() && disconnect.bottom() <= end.top(),
        "narrow Command Center did not stack its three distinct session actions");
    for (const auto* name : {"native-preview-action", "native-disconnect-action", "native-end-action"}) {
        const auto bounds = rect(name);
        require(bounds.left() >= 0 && bounds.right() <= window->width() && item(name)->height() >= 48,
            "narrow Command Center clipped or shrank an interaction owner");
    }
    item("native-hud-settings")->forceActiveFocus(); QTest::qWait(80);
    require(rect("native-hud-settings").bottom() <= window->height(), "narrow panel did not reveal its focused row");
    capture("command-center-portrait-360.png");
    auto* disconnectOwner = item("native-disconnect-action");
    const QString originalCaption = disconnectOwner->property("caption").toString();
    const QString wrappedCaption = "Keep this game open on your PC while you disconnect from this device";
    disconnectOwner->setProperty("caption", wrappedCaption); QTest::qWait(150);
    checkActionText();
    auto* wrapped = actionText(actionText, disconnectOwner, wrappedCaption);
    require(wrapped && wrapped->property("lineCount").toInt() > 1, "Command Center caption did not wrap at its actual assigned width");
    require(rect("native-preview-action").bottom() <= rect("native-disconnect-action").top()
        && rect("native-disconnect-action").bottom() <= rect("native-end-action").top(),
        "wrapped Command Center caption overlapped another action");
    capture("command-center-wrapped-caption-360.png");
    disconnectOwner->setProperty("caption", originalCaption); QTest::qWait(150);
    window->resize(960, 600); QTest::qWait(100);
    const QPointF sample = center->mapToScene(QPointF(8, center->height() - 8));
    QColor body[4]; int opacityValues[] = {0, 25, 64, 100};
    for (int i = 0; i < 4; ++i) {
        QVariant changed;
        QMetaObject::invokeMethod(root.get(), "menuOpacity", Q_RETURN_ARG(QVariant, changed), Q_ARG(QVariant, opacityValues[i]));
        require(changed.toBool(), "independent menu opacity setter is missing");
        QTest::qWait(60);
        const auto rendered = window->grabWindow(); require(!rendered.isNull(), "menu opacity capture failed");
        const QPoint pixel(sample.x() * rendered.width() / window->width(), sample.y() * rendered.height() / window->height());
        require(rendered.rect().contains(pixel), "menu panel-body sample escaped actual capture");
        body[i] = rendered.pixelColor(pixel);
        require(item("native-preview-action")->isVisible() && item("native-hud-settings")->isVisible(),
            "menu opacity hid labels or interaction owners");
    }
    require(body[0] != body[1] && body[1] != body[2] && body[2] != body[3],
        "menu opacity did not produce four distinct actual panel-body pixels");
    QVariant ignored, hudOpacity;
    QMetaObject::invokeMethod(root.get(), "menuOpacity", Q_RETURN_ARG(QVariant, ignored), Q_ARG(QVariant, 64));
    QMetaObject::invokeMethod(root.get(), "hudOpacity", Q_RETURN_ARG(QVariant, hudOpacity));
    QSettings saved;
    require(saved.value("InGameControls/menuOpacityPercent").toInt() == 64 && hudOpacity.toInt() == 64,
        "menu opacity did not persist independently of HUD opacity");
    require(session.starts == 1 && session.stops == 0 && session.disconnects == 0 && session.controlsVisible(),
        "presentation choices changed stream lifecycle");
    std::cout << "800p first page and 960px large-text focus/touch/lifecycle checks passed\n";
}
