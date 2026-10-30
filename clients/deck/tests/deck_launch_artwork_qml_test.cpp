// A game's details wear its artwork's tint, and Play carries the details' ready
// hero into the launch screen. The hero shows only while the session reports
// one of its own launch stages, leaves on the same tick as anything else, and
// is served from the pixmap the library's hero already holds: the artwork
// provider is not asked again. LibraryBrowser and NativeStreamPreview are wired
// as Main.qml wires them.
//
// UNRUN: written for Nova #385 and not executed in the authoring session.
#include "deck_preview_fixture.h"
#include "runtime/deck_display_capabilities.h"
#include "runtime/deck_play_settings.h"
#include "stream/deck_stream_media_adapters.h"

#include <QColor>
#include <QElapsedTimer>
#include <QCoreApplication>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickImageProvider>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QThread>
#include <QUrl>

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <memory>

namespace {
void require(bool ok, const char* message) {
    // Qt rendering threads may still run on failure; leave the fixture directly.
    if (!ok) { std::cerr << message << '\n'; std::_Exit(1); }
}
void settle() {
    for (int tick = 0; tick < 10; ++tick) {
        QCoreApplication::processEvents();
        QThread::msleep(10);
    }
}
void key(QQuickWindow& window, int code) {
    QKeyEvent press(QEvent::KeyPress, code, Qt::NoModifier);
    QKeyEvent release(QEvent::KeyRelease, code, Qt::NoModifier);
    QCoreApplication::sendEvent(&window, &press);
    QCoreApplication::sendEvent(&window, &release);
    settle();
}

// Stands in for the library-art provider and counts every request it gets.
class CountingArtwork final : public QQuickImageProvider {
public:
    CountingArtwork() : QQuickImageProvider(QQuickImageProvider::Image, QQmlImageProviderBase::ForceAsynchronousImageLoading) {}
    QImage requestImage(const QString&, QSize* size, const QSize&) override {
        ++requests;
        QImage image(64, 40, QImage::Format_ARGB32);
        image.fill(QColor(0, 0, 200));
        if (size) *size = image.size();
        return image;
    }
    std::atomic<int> requests{0};
};
} // namespace

int main(int argc, char** argv) {
    QTemporaryDir qmlSettings;
    qputenv("XDG_CONFIG_HOME", qmlSettings.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("LaunchArtwork");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    PreviewSession session;
    PreviewPlayers players(session);
    QTemporaryDir directory;
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264 = {4096, 4096}, .hevc = {1920, 1200}});
    nova::deck::runtime::DeckDisplayCapabilities display([](QScreen*) { return 60.0; });
    QQmlEngine engine;
    auto* artwork = new CountingArtwork;
    engine.addImageProvider(QStringLiteral("library-art"), artwork);
    engine.rootContext()->setContextProperty("testSession", &session);
    engine.rootContext()->setContextProperty("testPlayers", &players);
    engine.rootContext()->setContextProperty("testSettings", &settings);
    engine.rootContext()->setContextProperty("testDisplay", &display);
    QQmlComponent component(&engine);
    const QByteArray imports = "import QtQuick\nimport QtQuick.Controls\nimport \"" +
        QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded() + "\"\n";
    component.setData(imports + R"(
        ApplicationWindow {
            width: 1280; height: 800; visible: true
            function reduceMotion(on) { NovaTheme.setReduceMotion(on) }
            function details() { library.openDetails(0) }
            function leaveDetails() { library.back() }
            // Stands in for novaArtworkTints: a lookup of what the provider read.
            QtObject {
                id: tints
                function tint(source) { return source === "image://library-art/hero-1" ? "#000096" : "" }
            }
            LibraryBrowser {
                id: library
                objectName: "library"
                anchors.fill: parent
                settingsProvider: testSettings
                artworkTints: tints
                host: ({id: "fixture-host", displayName: "Living Room PC"})
                refreshState: ({busy: false, automatic: false, failed: false, destinationPlayable: true, destinationName: "Desktop"})
                games: [{id: "fixture-game", title: "Moonlit Harbor", hero: "image://library-art/hero-1"}]
            }
            QtObject {
                id: observedSession
                property var state: ({visible:true,hostId:"fixture-host",gameId:"fixture-game"})
                function startConfigured(host, game, configuration) { return testSession.startConfigured(host, game, configuration) }
            }
            function onePressResume() { preview.resumeFromLibrary = true; preview.autoStartRequested = true; preview.open() }
            NativeStreamPreview {
                id: preview
                inputHub: testPlayers
                session: testSession
                resumeController: observedSession
                settingsProvider: testSettings
                displayCapabilities: testDisplay.state
                hostId: "fixture-host"; gameId: "fixture-game"
                hostName: "Living Room PC"; gameTitle: "Moonlit Harbor"
                launchArtwork: library.launchArtwork
                launchTint: library.artworkTint
            }
        }
    )", QUrl());
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create());
    require(root != nullptr, qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    require(window != nullptr, "missing launch window");
    window->requestActivate();
    auto* library = root->findChild<QObject*>("library");
    auto* wash = root->findChild<QQuickItem*>("game-detail-artwork-wash");
    require(library && wash, "missing library or its artwork wash");
    // The existing closed artwork editor also loads this hero with PreserveAspectFit;
    // cinematicHero uses Crop. Establish their settled baseline before checking that
    // the new carried Crop image adds no request, rather than counting both as one.
    int libraryRequests = 0;
    int stable = 0;
    for (int wait = 0; wait < 50 && stable < 5; ++wait) {
        settle();
        const int now = artwork->requests.load();
        stable = now > 0 && now == libraryRequests ? stable + 1 : 0;
        libraryRequests = now;
    }
    require(stable == 5 && libraryRequests > 0, "the library hero request baseline did not settle");
    std::cout << "library hero provider requests before details: " << libraryRequests << '\n';

    // Reduce Motion: the wash and the carried hero arrive still and at once.
    require(QMetaObject::invokeMethod(root.get(), "reduceMotion", Q_ARG(QVariant, true)), "cannot set Reduce Motion");
    settle();
    require(!wash->isVisible(), "the library itself is never tinted, only a game's details");
    require(QMetaObject::invokeMethod(root.get(), "details"), "cannot open details");
    settle();
    require(wash->isVisible() && qFuzzyCompare(wash->opacity(), 0.22)
        && wash->property("color").value<QColor>() == QColor("#000096"),
        "details did not wear the hero's tint at Aurora's strength");
    require(QMetaObject::invokeMethod(root.get(), "leaveDetails"), "cannot leave details");
    settle();
    require(!wash->isVisible(), "the tint stayed after leaving details");
    require(QMetaObject::invokeMethod(root.get(), "details"), "cannot reopen details");
    settle();
    require(library->property("launchArtwork").toString() == "image://library-art/hero-1",
        "details did not offer their ready hero to Play");

    auto* preview = root->findChild<QObject*>("native-stream-preview");
    require(preview != nullptr, "missing preview");
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open the preview");
    settle();
    auto* art = root->findChild<QQuickItem*>("native-launch-artwork");
    auto* primary = root->findChild<QQuickItem*>("native-preview-action");
    require(art && primary, "missing launch artwork or Play");
    require(art->property("fillMode").toInt() == 2 /* PreserveAspectCrop */
        && art->property("cache").toBool(),
        "the carried hero must share the library hero's crop and cache");
    require(!art->isVisible(), "the review shows no carried artwork before Play");

    primary->forceActiveFocus();
    key(*window, Qt::Key_Return);
    require(session.starts == 1, "Play did not start the session");
    require(preview->property("launchArtworkShown").toString() == "image://library-art/hero-1",
        "Play did not capture the details' ready hero");
    // The fixture's own start phase is not one the native session reports: no
    // carried artwork for a stage it does not know.
    require(!art->isVisible(), "an unknown phase showed the carried artwork");

    for (const char* phase : {"preparing", "launching", "connecting"}) {
        session.transition(phase, true, "Launching");
        settle();
        require(art->isVisible(), "a known launch stage hid the carried artwork");
        require(qFuzzyCompare(art->opacity(), 0.12) && qFuzzyCompare(art->scale(), 1.0),
            "under Reduce Motion the carried hero is still, faint and unscaled");
    }
    require(artwork->requests == libraryRequests, "the launch asked the provider again instead of reusing the library's pixmap");

    // Leaving a launch stage removes it on the same tick, with no fade over play.
    session.transition("active", true, "Native stream connected.");
    require(!art->isVisible(), "the carried artwork outlived the launch");
    require(preview->property("launchArtworkShown").toString().isEmpty(),
        "an active stream kept the carried artwork for a later resume");

    session.transition("stopping", true, "Ending the game…");
    session.transition("idle", false, "Ready");
    settle();
    require(!art->isVisible(), "the ended screen showed the carried artwork");
    require(artwork->requests == libraryRequests, "the launch fetched artwork");
    require(QMetaObject::invokeMethod(preview, "close"), "cannot close before observed Resume");
    settle();
    const int starts = session.starts;
    require(QMetaObject::invokeMethod(root.get(), "onePressResume"), "cannot request one-press Resume");
    QElapsedTimer resumeDeadline; resumeDeadline.start();
    while (session.starts == starts && resumeDeadline.elapsed() < 2000) settle();
    require(session.starts == starts + 1 && preview->property("attempted").toBool(), "one-press Resume required another Play press");
    std::cout << "Launch artwork shown only for known launch stages, from the cached pixmap\n";
    return 0;
}
