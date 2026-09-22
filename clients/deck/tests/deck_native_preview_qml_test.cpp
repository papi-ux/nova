#include "runtime/deck_host_settings.h"
#include "deck_host_settings_fixture.h"
#include <atomic>
#include <QDateTime>
#include <QTemporaryDir>
#include "stream/deck_stream_media_adapters.h"
#include "runtime/deck_play_settings.h"
#include "runtime/deck_display_capabilities.h"
#include "polaris/deck_doctor.h"
#include "deck_doctor_fixture.h"
#include "runtime/deck_doctor_actions.h"
#include <QJsonDocument>
#include <QFile>

#include <QDir>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QThread>
#include <QTemporaryDir>
#include <QVariantMap>

#include <cstdlib>
#include <iostream>

#include "deck_preview_fixture.h"

namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
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
void focused(QQuickWindow& window, QObject* expected, const char* message) {
    require(window.activeFocusItem() == expected, message);
}
QQuickItem* visualChild(QQuickItem* item, const QString& name) {
    if (item->objectName() == name) return item;
    for (auto* child : item->childItems()) if (auto* found = visualChild(child, name)) return found;
    return nullptr;
}
} // namespace

int main(int argc, char** argv) {
    QTemporaryDir qmlSettings;
    qputenv("XDG_CONFIG_HOME", qmlSettings.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("NativePreview");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>(
        "Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    PreviewSession session;
    PreviewPlayers players(session);
    QTemporaryDir directory;
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264 = {4096, 4096}, .hevc = {1920, 1200}});
    double reportedRefresh = 60;
    nova::deck::runtime::DeckDisplayCapabilities display([&](QScreen*) { return reportedRefresh; });
    QQmlEngine engine;
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
            title: "Nova native controls test"
            function largeText() { NovaTheme.setFontScale(1.3) }
            Button { id: library; objectName: "library"; text: "Library"; focus: true }
            NativeStreamPreview {
                id: preview
                inputHub: testPlayers
                session: testSession
                settingsProvider: testSettings
                displayCapabilities: testDisplay.state
                hostId: "fixture-host"; gameId: "fixture-game"
                hostName: "Living Room PC"; gameTitle: "Moonlit Harbor"
                onClosed: library.forceActiveFocus()
            }
            Component.onCompleted: preview.open()
        }
    )", QUrl());
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create());
    require(root != nullptr, qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    require(window != nullptr, "missing preview window");
    display.watchWindow(window);
    window->requestActivate();
    settle();
    auto* preview = root->findChild<QObject*>("native-stream-preview");
    auto* primary = root->findChild<QQuickItem*>("native-preview-action");
    auto* end = root->findChild<QQuickItem*>("native-end-action");
    auto* disconnect = root->findChild<QQuickItem*>("native-disconnect-action");
    auto* resumeReturn = root->findChild<QQuickItem*>("native-resume-return");
    auto* confirmation = root->findChild<QObject*>("native-end-confirmation");
    auto* stay = root->findChild<QQuickItem*>("native-end-stay");
    auto* confirmEnd = root->findChild<QQuickItem*>("native-end-confirm");
    auto* controls = root->findChild<QQuickItem*>("native-show-controls");
    auto* library = root->findChild<QQuickItem*>("library");
    require(preview && primary && end && disconnect && resumeReturn && confirmation && stay && confirmEnd && controls && library, "missing production preview controls");
    const auto screenshot = [&](const char* name) {
        if (argc < 2) return;
        require(QDir().mkpath(QString::fromLocal8Bit(argv[1])), "cannot create capture directory");
        require(window->grabWindow().save(QDir(QString::fromLocal8Bit(argv[1])).filePath(name)),
                "cannot save preview capture");
    };
    focused(*window, primary, "review must focus Play");
    require(primary->property("text") == "Play" && session.starts == 0, "review launched automatically");
    screenshot("play-setup-defaults.png");
    // The legacy Space launcher can have the same game ID in two places.
    // Destination identity and readiness must independently invalidate review.
    auto* setup = root->findChild<QObject*>("play-setup");
    require(setup, "missing production Play Setup");
    preview->setProperty("destinationId", "arcade");
    preview->setProperty("destinationName", "Arcade");
    preview->setProperty("gameId", "706f6c61-7269-4373-8000-6d756c746973");
    settle();
    require(!primary->isEnabled(), "changed destination retained old review");
    QMetaObject::invokeMethod(preview, "close"); settle();
    QMetaObject::invokeMethod(preview, "open"); settle();
    require(primary->isEnabled() && setup->property("planHeadline") == "Play in Arcade"
        && setup->property("planIntro").toString().contains("in Arcade on Living Room PC"), "Space review lost its destination");
    key(*window, Qt::Key_Down); key(*window, Qt::Key_Return);
    preview->setProperty("destinationId", "lounge"); settle();
    require(!primary->isEnabled() && !root->findChild<QObject*>("play-setup-picker")->property("opened").toBool()
        && !settings.load("fixture-host", "706f6c61-7269-4373-8000-6d756c746973").value("custom").toBool(),
        "destination change accepted a copied choice or changed settings");
    focused(*window, root->findChild<QQuickItem*>("play-setup-back"), "destination change lost Back");
    QMetaObject::invokeMethod(preview, "close"); settle();
    preview->setProperty("destinationId", "desktop"); preview->setProperty("destinationName", "Desktop");
    preview->setProperty("gameId", "fixture-game"); preview->setProperty("destinationPlayable", false);
    QMetaObject::invokeMethod(preview, "open"); settle();
    require(!primary->isEnabled() && !preview->property("reviewValid").toBool(), "unready destination offered Play");
    QMetaObject::invokeMethod(preview, "close"); settle();
    preview->setProperty("destinationPlayable", true);
    QMetaObject::invokeMethod(preview, "open"); settle();
    require(primary->isEnabled() && session.starts == 0, "fresh ready review failed or launched automatically");
    key(*window, Qt::Key_Down);
    focused(*window, root->findChild<QQuickItem*>("play-setup-resolution"), "Play must reach settings");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    // Physical B calls leave() directly; it must close only the choice menu.
    require(QMetaObject::invokeMethod(preview, "leave"), "cannot route controller Back");
    settle();
    require(preview->property("opened").toBool() && session.starts == 0, "controller Back closed the setup or launched");
    focused(*window, root->findChild<QQuickItem*>("play-setup-resolution"), "choice Back lost its row");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Up);
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    screenshot("play-setup-bitrate-choice.png");
    key(*window, Qt::Key_Return);
    screenshot("play-setup-custom.png");
    key(*window, Qt::Key_Down);
    focused(*window, root->findChild<QQuickItem*>("play-setup-face-buttons"), "bitrate must reach face buttons");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    screenshot("play-setup-face-buttons.png");
    key(*window, Qt::Key_Return);
    require(session.starts == 0 && session.stops == 0, "editing settings mutated the host");
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    focused(*window, primary, "settings must return to Play");
    key(*window, Qt::Key_Return);
    require(session.starts == 1 && session.selectedHost == "fixture-host" && session.selectedGame == "fixture-game",
            "review action must launch the selected host/game exactly once");
    require(session.selectedConfiguration == nova::deck::runtime::DeckPlayConfiguration{1920, 1080, 30, 30000, "positions"}.toMap(),
        "Play did not pass the reviewed settings");
    require(primary->property("text") == "Cancel connection", "connecting must expose cancellation");

    session.transition("active", true, "Your game is connected.");
    settle();
    focused(*window, primary, "connected overlay must focus Continue game");
    require(primary->property("text") == "Close" && end->isVisible(), "missing active controls");
    require(qAbs(primary->width() - disconnect->width()) < 1 &&
            qAbs(primary->mapToScene(QPointF()).y() - disconnect->mapToScene(QPointF()).y()) < 1 &&
            disconnect->mapToScene(QPointF()).x() >= primary->mapToScene(QPointF()).x() + primary->width(),
            "Command Center header actions overlap or retained their old vertical anchors");
    screenshot("native-controls-continue.png");
    require(preview->property("players").toList().size() == 3, "waiting controller was omitted from Command Center");
    key(*window, Qt::Key_Right);
    focused(*window, disconnect, "header Right did not reach Disconnect");
    key(*window, Qt::Key_Right);
    focused(*window, end, "header Right did not reach End Session");
    key(*window, Qt::Key_Down);
    focused(*window, root->findChild<QQuickItem*>("native-players-reassign"), "Players/Reassign not reachable");
    key(*window, Qt::Key_Return);
    require(players.reassignments == 1 && !session.controlsVisible() && session.starts == 1 && session.stops == 0,
        "Reassign failed to close controls or restarted/ended the stream");
    key(*window, Qt::Key_Escape);
    focused(*window, primary, "return from Reassign lost Command Center focus");
    key(*window, Qt::Key_Down);
    focused(*window, disconnect, "D-pad down must select Disconnect");
    screenshot("native-controls-disconnect.png");
    key(*window, Qt::Key_Down);
    focused(*window, end, "D-pad down must select End game");
    screenshot("native-controls-end.png");
    key(*window, Qt::Key_Up);
    focused(*window, disconnect, "End game must return through Disconnect");
    key(*window, Qt::Key_Up);
    focused(*window, primary, "D-pad up must return to Continue game");
    key(*window, Qt::Key_Return);
    require(!session.controlsVisible() && !primary->isVisible() && !end->isVisible() && controls->isVisible(),
            "Continue must hide the action buttons");
    require(window->activeFocusItem() != primary && window->activeFocusItem() != end,
            "playing must not keep focus on hidden actions");
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    require(session.stops == 0 && session.starts == 1, "hidden controls must not receive play input");
    key(*window, Qt::Key_Escape);
    focused(*window, primary, "opening controls must recover visible focus");
    key(*window, Qt::Key_Escape);
    require(!session.controlsVisible(), "Escape from overlay must resume play");
    const auto point = controls->mapToScene(QPointF(controls->width() / 2, controls->height() / 2));
    QMouseEvent press(QEvent::MouseButtonPress, point, window->mapToGlobal(point),
                      Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
    QMouseEvent release(QEvent::MouseButtonRelease, point, window->mapToGlobal(point),
                        Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &press);
    QCoreApplication::sendEvent(window, &release);
    settle();
    require(session.controlsVisible(), "pointer Controls action must open the overlay");
    focused(*window, primary, "pointer-opened controls must receive navigation focus");
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    require(session.stops == 0 && confirmation->property("opened").toBool(), "End game bypassed confirmation");
    focused(*window, stay, "end confirmation must initially focus Keep playing");
    screenshot("native-end-confirmation.png");
    require(QMetaObject::invokeMethod(preview, "leave"), "cannot route controller B from confirmation");
    settle();
    require(!confirmation->property("opened").toBool() && session.stops == 0 && session.controlsVisible(),
        "controller B did not dismiss only the end confirmation");
    focused(*window, end, "confirmation Back must return to End game");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Escape);
    require(!confirmation->property("opened").toBool() && session.stops == 0, "Escape ended the game");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Return);
    require(!confirmation->property("opened").toBool() && session.stops == 0, "default confirmation action ended game");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    focused(*window, confirmEnd, "confirmation must reach explicit End game");
    QKeyEvent repeat(QEvent::KeyPress, Qt::Key_Return, Qt::NoModifier, QString(), true, 1);
    QCoreApplication::sendEvent(window, &repeat);
    settle();
    require(session.stops == 0, "held A/Enter confirmed game termination");
    key(*window, Qt::Key_Return);
    require(session.stops == 1, "confirmed End game must stop exactly once");
    focused(*window, primary, "stop must move focus off the disappearing End game action");
    session.transition("stopped", false, "Your game has ended.");
    settle();
    require(primary->property("text") == "Back to details", "completed stop must expose the return action");
    screenshot("native-controls-stopped.png");
    key(*window, Qt::Key_Return);
    require(!preview->property("opened").toBool(), "return action must close preview");
    focused(*window, library, "closing preview must restore library focus");

    require(QMetaObject::invokeMethod(preview, "open"), "cannot reopen preview");
    settle();
    require(primary->property("text") == "Play", "reopen must clear the prior attempt");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Escape);
    require(session.starts == 2 && session.stops == 2 && preview->property("opened").toBool(),
            "connecting Escape must cancel and stay open until cleanup completes");
    session.transition("failed", false, "The preview could not connect.");
    settle();
    focused(*window, primary, "failure must retain a visible return action");
    key(*window, Qt::Key_Escape);
    focused(*window, library, "failure Escape must return to the library");
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open selection-change review");
    settle();
    preview->setProperty("gameId", "other-game");
    settle();
    require(!primary->isEnabled() && !preview->property("reviewValid").toBool(), "changed selection kept Play enabled");
    focused(*window, root->findChild<QQuickItem*>("play-setup-back"), "stale review lost a way back");
    require(QMetaObject::invokeMethod(primary, "clicked"), "cannot check stale activation guard");
    require(session.starts == 2, "review redirected Play to a different game");
    preview->setProperty("gameId", "fixture-game");
    settle();
    require(!primary->isEnabled(), "stale review silently became live again");
    key(*window, Qt::Key_Escape);
    require(QMetaObject::invokeMethod(preview, "open"), "cannot review selection again");
    settle();
    require(primary->isEnabled() && preview->property("reviewValid").toBool(), "fresh review did not recover");
    key(*window, Qt::Key_Escape);
    require(settings.reset("fixture-host", "fixture-game"), "cannot reset stream-plan fixture");
    preview->setProperty("streamCapabilities", QVariantMap{{"h264", false}});
    require(QMetaObject::invokeMethod(preview, "open"), "cannot review unavailable codec");
    settle();
    require(!primary->isEnabled(), "unsupported codec left Play enabled");
    focused(*window, root->findChild<QQuickItem*>("play-setup-resolution"), "unsupported stream lost settings focus");
    require(QMetaObject::invokeMethod(primary, "clicked"), "cannot check unavailable stream guard");
    require(session.starts == 2, "unsupported stream reached session start");
    preview->setProperty("streamCapabilities", QVariantMap{{"h264", true}, {"maxFps", 30}});
    settle();
    key(*window, Qt::Key_Up);
    focused(*window, primary, "supported adjusted plan did not restore Play navigation");
    key(*window, Qt::Key_Return);
    require(session.starts == 3 && session.selectedConfiguration.value("fps") == 30,
        "Play used saved FPS instead of the displayed effective plan");
    require(settings.load("fixture-host", "fixture-game").value("configuration").toMap().value("fps") == 60,
        "effective stream plan overwrote the saved preference");
    session.transition("stopped", false, "Your game has ended.");
    settle();
    key(*window, Qt::Key_Escape);
    preview->setProperty("streamCapabilities", QVariantMap{{"h264", true}, {"maxFps", 120}});
    reportedRefresh = 90;
    display.refresh();
    require(QMetaObject::invokeMethod(preview, "open"), "cannot review fast display");
    settle();
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    auto* rate = root->findChild<QQuickItem*>("play-setup-rate");
    focused(*window, rate, "cannot reach rate for fast display");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    screenshot("display-90-rate-choice.png");
    require(window->activeFocusItem() && window->activeFocusItem()->objectName() == "play-setup-choice-2" &&
        window->activeFocusItem()->property("text").toString().startsWith("90 fps"), "90 FPS choice is missing or unfocusable");
    key(*window, Qt::Key_Return);
    require(rate->property("value") == "90 fps", "90 FPS choice was not applied to review");
    key(*window, Qt::Key_Up);
    key(*window, Qt::Key_Up);
    focused(*window, primary, "fast-display settings did not return to Play");
    screenshot("display-90-play-setup.png");
    key(*window, Qt::Key_Return);
    require(session.starts == 4 && session.selectedConfiguration.value("fps") == 90, "90 FPS review was not launched");
    session.transition("stopped", false, "Your game has ended.");
    settle();
    key(*window, Qt::Key_Escape);
    require(QMetaObject::invokeMethod(preview, "open"), "cannot reopen saved fast-display review");
    settle();
    reportedRefresh = 60;
    display.refresh();
    window->resize(960, 600);
    settle();
    require(rate->property("value") == "60 fps · Adjusted", "live display change did not update reviewed FPS");
    require(settings.load("fixture-host", "fixture-game").value("configuration").toMap().value("fps") == 90,
        "slower display erased saved 90 FPS preference");
    focused(*window, primary, "display adjustment stole Play focus");
    screenshot("display-60-fallback-960.png");
    reportedRefresh = 90;
    display.refresh();
    settle();
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    reportedRefresh = 60;
    display.refresh();
    settle();
    focused(*window, rate, "withdrawn rate left focus on an obsolete popup choice");
    require(!root->findChild<QObject*>("play-setup-picker")->property("opened").toBool(), "withdrawn rate left the picker open");
    key(*window, Qt::Key_Up);
    key(*window, Qt::Key_Up);
    key(*window, Qt::Key_Return);
    require(session.starts == 5 && session.selectedConfiguration.value("fps") == 60, "fallback Play ignored displayed effective rate");
    // Reopen a complete active preview for pointer disconnect and a stale
    // confirmation closed by the host. Neither flow may call End game.
    preview->setProperty("gameId", "fixture-game");
    session.allowDisconnect = true;
    require(QMetaObject::invokeMethod(preview, "close"), "cannot close prior fixture");
    require(QMetaObject::invokeMethod(preview, "open"), "cannot reopen exit fixture");
    settle();
    key(*window, Qt::Key_Return);
    session.transition("active", true, "Your game is connected.");
    settle();
    const int stopsBeforeDisconnect = session.stops;
    const auto tapItem = [&](QQuickItem* item) {
        const auto center = item->mapToScene(QPointF(item->width() / 2, item->height() / 2));
        QMouseEvent down(QEvent::MouseButtonPress, center, window->mapToGlobal(center), Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
        QMouseEvent up(QEvent::MouseButtonRelease, center, window->mapToGlobal(center), Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
        QCoreApplication::sendEvent(window, &down);
        QCoreApplication::sendEvent(window, &up);
        settle();
    };
    window->resize(960, 600);
    settle();
    tapItem(end);
    focused(*window, stay, "touch End game must open safe confirmation");
    screenshot("native-end-confirmation-960.png");
    session.transition("failed", false, "The host ended this stream.");
    settle();
    require(!confirmation->property("opened").toBool() && !end->isVisible(), "host termination left a stale destructive action");
    require(QMetaObject::invokeMethod(confirmEnd, "clicked"), "cannot probe stale confirmation");
    require(session.stops == stopsBeforeDisconnect, "stale confirmation sent a quit request");
    session.transition("active", true, "Your game is connected.");
    session.showControls();
    settle();
    require(qAbs(primary->height() - disconnect->height()) < 1, "Close height drifted after connection state changes");
    screenshot("native-controls-960.png");
    tapItem(disconnect);
    require(session.disconnects == 1 && session.stops == stopsBeforeDisconnect, "touch Disconnect called End game");
    session.transition("disconnected", true, "Finishing disconnect…");
    settle();
    require(!resumeReturn->isVisible() && primary->property("text") != "Resume game", "Resume raced unfinished teardown");
    session.transition("disconnected", false, "Disconnected without ending the game.");
    settle();
    focused(*window, primary, "disconnect completion lost the resume action");
    require(primary->property("text") == "Resume game" && resumeReturn->isVisible(), "completed disconnect has no Resume/Back choice");
    screenshot("native-resume-960.png");
    window->resize(1280, 800);
    settle();
    screenshot("native-resume-1280.png");
    key(*window, Qt::Key_Down);
    focused(*window, resumeReturn, "Resume must retain a D-pad return route");
    key(*window, Qt::Key_Up);
    const int startsBeforeResume = session.starts;
    const auto configurationBeforeResume = session.selectedConfiguration;
    QKeyEvent repeatResume(QEvent::KeyPress, Qt::Key_Return, Qt::NoModifier, QString(), true, 1);
    QCoreApplication::sendEvent(window, &repeatResume);
    settle();
    require(session.resumes == 0, "held activation resumed a session");
    tapItem(primary);
    require(session.resumes == 1 && session.starts == startsBeforeResume && session.selectedConfiguration == configurationBeforeResume,
        "Resume launched a new game or changed the reviewed configuration");
    require(!resumeReturn->isVisible(), "pending Resume left stale return controls");
    session.transition("active", true, "Game resumed.");
    settle();
    require(primary->property("text") == "Close", "resumed stream lost input focus handoff");
    tapItem(disconnect);
    session.transition("disconnected", false, "Disconnected without ending the game.");
    settle();
    session.rejectResume = true;
    tapItem(primary);
    require(session.resumes == 1 && !preview->property("resumeError").toString().isEmpty() && resumeReturn->isVisible(),
        "stale resume rejection lost recovery or launched another game");
    session.rejectResume = false;
    session.transition("interrupted", true, "Closing the stream without ending the game…");
    settle();
    require(primary->property("text") != "Reconnect" && !resumeReturn->isVisible(), "Reconnect raced pending teardown");
    const QString interruptedCopy = "The connection was interrupted. Nova did not ask the PC to end the game. Check your connection, then reconnect.";
    session.transition("interrupted", false, interruptedCopy);
    settle();
    focused(*window, primary, "interruption did not focus Reconnect");
    require(primary->property("text") == "Reconnect" && resumeReturn->isVisible() && !end->isVisible() && !disconnect->isVisible(),
        "interruption offered stale game controls");
    screenshot("native-reconnect-1280.png");
    window->resize(960, 600);
    settle();
    screenshot("native-reconnect-960.png");
    key(*window, Qt::Key_Down);
    focused(*window, resumeReturn, "Reconnect trapped D-pad focus");
    key(*window, Qt::Key_Up);
    QCoreApplication::sendEvent(window, &repeatResume);
    settle();
    require(session.reconnects == 0, "held activation started reconnect");
    tapItem(primary);
    require(session.reconnects == 1 && session.starts == startsBeforeResume && session.resumes == 1 &&
        session.selectedConfiguration == configurationBeforeResume && !resumeReturn->isVisible(),
        "Reconnect changed settings, launched a new game, or retained old controls");
    session.transition("interrupted", false, "Couldn't reconnect to the game. Nova did not ask the PC to end it. Check your connection and try again.");
    settle();
    require(primary->property("text") == "Reconnect", "transport failure lost the retry action");
    session.rejectResume = true;
    tapItem(primary);
    require(session.reconnects == 1 && !preview->property("resumeError").toString().isEmpty(), "stale recovery action was accepted");
    session.rejectResume = false;
    key(*window, Qt::Key_Return);
    require(session.reconnects == 2, "controller could not retry reconnect");
    session.transition("active", true, "Game resumed. Continue to send controls to the game.");
    settle();
    require(primary->property("text") == "Close" && session.controlsVisible(), "reconnected game skipped input handoff");
    session.allowResume = false;
    session.transition("interrupted", false, "The connection was interrupted. Return to the library and check this PC.");
    settle();
    require(primary->property("text") == "Back to details" && !resumeReturn->isVisible(), "unverified legacy session offered reconnect");
    session.allowResume = true;
    session.transition("interrupted", false, interruptedCopy);
    settle();
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Return);
    focused(*window, library, "interruption return did not restore library focus");
    require(session.stops == stopsBeforeDisconnect, "leaving recovery requested End game");
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open automatic recovery fixture");
    settle();
    key(*window, Qt::Key_Return);
    session.automaticAttempt = 2;
    session.transition("reconnecting", true, "Checking whether your game is still available…");
    settle();
    focused(*window, primary, "automatic recovery lost cancel focus");
    require(primary->property("text") == "Cancel reconnect" && !resumeReturn->isVisible() && !end->isVisible(),
        "automatic recovery offered stale actions");
    screenshot("native-auto-reconnect-960.png");
    window->resize(1280, 800);
    settle();
    screenshot("native-auto-reconnect-1280.png");
    QCoreApplication::sendEvent(window, &repeatResume);
    settle();
    require(session.stops == stopsBeforeDisconnect, "held activation cancelled automatic recovery");
    tapItem(primary);
    require(session.stops == stopsBeforeDisconnect + 1, "touch could not cancel automatic recovery");
    session.transition("interrupted", false, "Automatic reconnect cancelled. The game was not asked to quit.");
    settle();
    require(primary->property("text") == "Reconnect" && resumeReturn->isVisible(), "cancelled recovery lost manual fallback");
    session.transition("connecting", true, "Connecting video and audio…");
    settle();
    key(*window, Qt::Key_Escape);
    require(session.stops == stopsBeforeDisconnect + 2, "Escape could not cancel an in-flight automatic reconnect");
    session.automaticAttempt = 0;
    session.transition("interrupted", false, "Automatic reconnect stopped after four attempts. Check your connection, then reconnect when you're ready.");
    settle();
    require(primary->property("text") == "Reconnect", "exhausted budget lost manual recovery");
    key(*window, Qt::Key_Escape);
    focused(*window, library, "automatic recovery return lost library focus");
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open sleep fixture");
    settle();
    key(*window, Qt::Key_Return);
    session.transition("active", true, "Your game is connected.");
    session.showControls();
    settle();
    tapItem(end);
    require(confirmation->property("opened").toBool(), "sleep fixture lost end confirmation");
    session.sleeping = true;
    session.transition("disconnected", false, "Stream paused for sleep. Resume when you're ready.");
    settle();
    require(!confirmation->property("opened").toBool() && primary->property("text") == "Waiting for wake" &&
        !primary->isEnabled() && !resumeReturn->isVisible() && !end->isVisible() && !disconnect->isVisible(),
        "sleep retained live, resume, or destructive controls");
    screenshot("native-sleep-1280.png");
    window->resize(960, 600);
    settle();
    screenshot("native-sleep-960.png");
    const int startsBeforeSleep = session.starts, resumesBeforeSleep = session.resumes;
    require(QMetaObject::invokeMethod(primary, "clicked"), "cannot probe stale sleeping action");
    key(*window, Qt::Key_Return);
    QCoreApplication::sendEvent(window, &repeatResume);
    require(session.starts == startsBeforeSleep && session.resumes == resumesBeforeSleep, "sleep accepted stale activation");
    session.sleeping = false;
    session.transition("disconnected", false, "Stream paused for sleep. Resume when you're ready.");
    settle();
    focused(*window, primary, "wake did not focus Resume game");
    require(primary->property("text") == "Resume game" && primary->isEnabled() && resumeReturn->isVisible(),
        "wake lost manual resume and return choices");
    screenshot("native-wake-960.png");
    window->resize(1280, 800);
    settle();
    screenshot("native-wake-1280.png");
    key(*window, Qt::Key_Down);
    focused(*window, resumeReturn, "wake lost D-pad return route");
    key(*window, Qt::Key_Up);
    QCoreApplication::sendEvent(window, &repeatResume);
    settle();
    require(session.resumes == resumesBeforeSleep, "wake replayed held activation");
    tapItem(primary);
    require(session.resumes == resumesBeforeSleep + 1 && session.starts == startsBeforeSleep,
        "wake tap launched a new game instead of resuming");
    session.transition("active", true, "Game resumed. Continue to send controls to the game.");
    settle();
    require(primary->property("text") == "Close" && session.controlsVisible(), "wake skipped input handoff");
    session.transition("disconnected", false, "Disconnected without ending the game.");
    settle();
    key(*window, Qt::Key_Escape);
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open audio recovery fixture");
    settle();
    key(*window, Qt::Key_Return);
    session.transition("active", true, "Your game is connected.");
    session.resumeInput();
    settle();
    auto* audioNotice = root->findChild<QQuickItem*>("native-audio-notice");
    require(audioNotice && !audioNotice->isVisible(), "healthy audio showed a recovery notice");
    auto* gameFocus = window->activeFocusItem();
    session.audioCopy = "Reconnecting audio. Video and controls can continue.";
    session.transition("active", true, "Your game is connected.");
    settle();
    require(audioNotice->isVisible() && !session.controlsVisible() && window->activeFocusItem() == gameFocus,
        "audio recovery stole gameplay focus or remained hidden");
    screenshot("native-audio-recovery-1280.png");
    window->resize(960, 600);
    settle();
    screenshot("native-audio-recovery-960.png");
    session.audioCopy = "Audio is waiting for an output. Check your SteamOS audio output.";
    session.transition("active", true, "Native stream connected. Continue to send controls to the game.");
    session.showControls();
    settle();
    focused(*window, primary, "audio outage lost Continue game focus");
    require(primary->property("text") == "Close" && !audioNotice->isVisible(), "audio outage changed stream controls");
    screenshot("native-audio-waiting-960.png");
    session.audioCopy.clear();
    session.transition("active", true, "Your game is connected.");
    session.resumeInput();
    settle();
    require(!audioNotice->isVisible() && !session.controlsVisible(), "recovered audio left a stale notice or opened controls");
    session.transition("disconnected", false, "Disconnected without ending the game.");
    settle();
    key(*window, Qt::Key_Escape);
    require(QMetaObject::invokeMethod(preview, "open"), "cannot reopen Space fixture");
    settle();
    key(*window, Qt::Key_Return);
    session.allowDisconnect = false;
    session.transition("active", true, "Your Space is connected.");
    settle();
    require(!disconnect->isVisible(), "Space exposed ordinary keep-running disconnect");
    key(*window, Qt::Key_Down);
    focused(*window, end, "hidden Disconnect trapped D-pad focus");
    auto* scaleAction=root->findChild<QQuickItem*>("native-video-scale");
    auto* scalePopup=root->findChild<QObject*>("video-scale-popup");
    auto* scaleBack=scalePopup->findChild<QQuickItem*>("video-scale-back");
    auto* legacySurface=root->findChild<QQuickItem*>("nova-native-stream-surface");
    require(scaleAction && scalePopup && scaleBack && legacySurface,"scaling controls missing");
    const auto profileBeforeScaling=settings.load(preview->property("hostId").toString(),preview->property("gameId").toString());
    const int startsBeforeScaling=session.starts;
    auto* appearanceAction=root->findChild<QQuickItem*>("native-appearance");
    appearanceAction->forceActiveFocus(); key(*window,Qt::Key_Down); focused(*window,scaleAction,"scaling row unreachable");
    key(*window,Qt::Key_Return);
    auto* scaleFit=visualChild(window->contentItem(),"video-scale-fit");
    auto* scaleFill=visualChild(window->contentItem(),"video-scale-fill");
    auto* scaleStretch=visualChild(window->contentItem(),"video-scale-stretch");
    require(scaleFit && scaleFill && scaleStretch,"scaling choices missing");
    focused(*window,scaleFit,"saved scaling did not receive focus");
    key(*window,Qt::Key_Down); require(QMetaObject::invokeMethod(preview,"leave"),"scaling Back route missing"); settle();
    require(settings.videoScaleMode()=="fit" && session.controlsVisible(),"navigation saved scaling or resumed input");
    focused(*window,scaleAction,"scaling Back lost row focus");
    key(*window,Qt::Key_Return); key(*window,Qt::Key_Down); key(*window,Qt::Key_Return);
    require(settings.videoScaleMode()=="fill" && legacySurface->property("videoScaleMode")=="fill","scaling did not reach legacy presenter");
    focused(*window,scaleFill,"save moved scaling focus");
    screenshot("native-video-scaling-1280.png");
    window->resize(960,600); settle(); screenshot("native-video-scaling-960.png");
    key(*window,Qt::Key_Down); key(*window,Qt::Key_Return);
    require(settings.videoScaleMode()=="stretch" && legacySurface->property("videoScaleMode")=="stretch","Stretch did not update live preference");
    require(scaleBack->mapToScene(QPointF(0,scaleBack->height())).y()<=window->height(),"scaling footer clipped");
    session.resumeInput(); settle(); require(!scalePopup->property("opened").toBool(),"resume retained scaling editor");
    session.showControls(); settle(); scaleAction->forceActiveFocus(); key(*window,Qt::Key_Return);
    focused(*window,scaleStretch,"reopen lost saved scaling selection");
    QMetaObject::invokeMethod(preview,"leave"); settle();
    require(session.starts==startsBeforeScaling && settings.load(preview->property("hostId").toString(),preview->property("gameId").toString())==profileBeforeScaling,
        "scaling relaunched or changed the stream profile");
    require(settings.resetVideoScaleMode(),"scaling reset failed"); window->resize(1280,800); settle();
    auto* hudAction = root->findChild<QQuickItem*>("native-hud-settings");
    auto* hudPopup = root->findChild<QObject*>("hud-settings-popup");
    auto* hud = root->findChild<QQuickItem*>("nova-stream-hud");
    require(hudAction && hudPopup && hud && !hud->isVisible(), "HUD integration missing or not off by default");
    hudAction->forceActiveFocus(); settle(); key(*window, Qt::Key_Return);
    focused(*window, root->findChild<QQuickItem*>("hud-toggle"), "HUD picker did not receive focus");
    key(*window, Qt::Key_Return);
    require(QMetaObject::invokeMethod(preview, "leave"), "cannot route Back from HUD settings");
    settle();
    require(!hudPopup->property("opened").toBool() && session.controlsVisible(), "HUD Back resumed or closed the game");
    focused(*window, hudAction, "HUD Back lost its Command Center row");
    session.resumeInput(); settle();
    require(hud->isVisible() && root->findChild<QQuickItem*>("hud-fps")->property("text") == "59.8", "active game did not show live HUD");
    screenshot("native-hud-minimal-1280.png");
    QMetaObject::invokeMethod(hud, "commandCenterRequested"); settle();
    require(session.controlsVisible() && !hud->isVisible(), "HUD hold failed to open Command Center safely");
    focused(*window, primary, "HUD-opened Command Center lost initial focus");
    session.transition("disconnected", false, "Disconnected."); settle();
    require(!hud->isVisible(), "terminal stream kept HUD visible");

    session.transition("active", true, "Game is active."); session.showControls(); settle();
    auto* tuning = root->findChild<QQuickItem*>("native-live-tuning");
    require(tuning && !tuning->isEnabled(), "unavailable tuning control is missing or enabled");
    session.tuning = {{"canTune", true}, {"tuningKnown", true}, {"tuningEnabled", false}, {"tuningBusy", false},
        {"tuningCopy", "Changes save immediately. Turning tuning off holds the last confirmed bitrate."}};
    emit session.hudChanged(); settle();
    hudAction->forceActiveFocus(); settle(); key(*window, Qt::Key_Down);
    focused(*window, tuning, "Live Tuning not reachable from NovaHUD row");
    const auto readyTuning = session.tuning;
    session.tuning = {{"canTune", false}, {"hostRefreshing", true}, {"tuningCopy", "Refreshing host state…"}};
    emit session.hudChanged(); settle(); key(*window, Qt::Key_Return);
    focused(*window, tuning, "event refresh moved controller focus");
    require(session.tuningWrites == 0 && tuning->property("text").toString().contains("Refreshing"), "refresh retained stale tuning authority");
    window->resize(1280, 800); settle(); screenshot("session-events-refresh-1280.png");
    session.tuning = readyTuning; emit session.hudChanged(); settle();
    key(*window, Qt::Key_Return);
    require(session.tuningWrites == 1 && session.tuningRequested && session.controlsVisible(), "D-pad tuning switch did not save in Command Center");
    require(!session.tuning.value("tuningEnabled").toBool() && tuning->property("text").toString().contains("Saving"), "pending tuning optimistically changed confirmed preference");
    key(*window, Qt::Key_Return);
    require(session.tuningWrites == 1, "busy tuning switch sent another mutation");
    focused(*window, tuning, "pending tuning save lost focus");
    session.tuning["tuningBusy"] = false; session.tuning["canTune"] = true;
    session.tuning["tuningEnabled"] = true; session.tuning["tuningCopy"] = "Live Tuning turned on.";
    emit session.hudChanged(); window->resize(1280, 800); settle(); screenshot("native-live-tuning-on-1280.png");
    QMetaObject::invokeMethod(root.get(), "largeText"); window->resize(960, 600); settle();
    screenshot("native-live-tuning-on-large-960.png");
    require(tuning->mapToScene(QPointF(0, tuning->height())).y() <= window->height(), "tuning caption clipped below viewport");
    // Touch uses the same explicit toggle path and keeps the overlay open.
    const auto tuningPoint = tuning->mapToScene(QPointF(tuning->width() / 2, tuning->height() / 2));
    QMouseEvent down(QEvent::MouseButtonPress, tuningPoint, window->mapToGlobal(tuningPoint), Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
    QMouseEvent up(QEvent::MouseButtonRelease, tuningPoint, window->mapToGlobal(tuningPoint), Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &down); QCoreApplication::sendEvent(window, &up); settle();
    require(session.tuningWrites == 2 && !session.tuningRequested && session.controlsVisible(), "touch tuning Off did not use the same guarded route");
    session.tuning["tuningBusy"] = false; session.tuning["canTune"] = false;
    session.tuning["tuningCopy"] = "This session doesn't allow host tuning.";
    emit session.hudChanged(); settle(); screenshot("native-live-tuning-denied.png");
    require(!tuning->isEnabled() && session.tuningWrites == 2, "permission loss kept switch interactive");
    focused(*window, hudAction, "permission withdrawal left controller focus invisible");


    session.tuning = {{"canTune", true}, {"tuningKnown", true}, {"tuningEnabled", true}, {"tuningBusy", false},
        {"canSetBitrate", true}, {"appliedBitrateKbps", 20000}, {"bitrateCopy", "Choose a fixed bitrate. Applying it turns Live Tuning off."}};
    emit session.hudChanged(); settle();
    auto* bitrateAction = root->findChild<QQuickItem*>("native-live-bitrate");
    auto* bitratePopup = root->findChild<QObject*>("live-bitrate-popup");
    auto* bitratePlus = bitratePopup->findChild<QQuickItem*>("live-bitrate-plus");
    auto* bitrateMinus = bitratePopup->findChild<QQuickItem*>("live-bitrate-minus");
    auto* bitrateApply = bitratePopup->findChild<QQuickItem*>("live-bitrate-apply");
    auto* bitrateDone = bitratePopup->findChild<QQuickItem*>("live-bitrate-done");
    require(bitrateAction && bitratePopup && bitrateApply && bitratePlus && bitrateMinus, "live bitrate controls missing");
    tuning->forceActiveFocus(); settle(); key(*window, Qt::Key_Down);
    focused(*window, bitrateAction, "fixed bitrate row not reachable from tuning switch");
    const auto readyBitrate = session.tuning;
    session.tuning = {{"canSetBitrate", false}, {"hostRefreshing", true}, {"bitrateCopy", "Refreshing host state…"}};
    emit session.hudChanged(); settle(); key(*window, Qt::Key_Return);
    focused(*window, bitrateAction, "event refresh moved bitrate row focus");
    require(!bitratePopup->property("opened").toBool(), "event refresh opened a stale bitrate draft");
    session.tuning = readyBitrate; emit session.hudChanged(); settle();
    key(*window, Qt::Key_Return); focused(*window, bitrateMinus, "picker did not focus the decrement control");
    key(*window, Qt::Key_Right); key(*window, Qt::Key_Return);
    require(bitratePopup->property("draftKbps") == 21000 && session.bitrateWrites == 0, "draft adjustment mutated host");
    key(*window, Qt::Key_Down); focused(*window, bitrateApply, "Apply not reachable from increment");
    window->resize(1280, 800); settle(); screenshot("live-bitrate-review-1280.png");
    window->resize(960, 600); settle(); screenshot("live-bitrate-review-large-960.png");
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Return);
    require(session.bitrateWrites == 1 && session.requestedBitrate == 21000 && session.tuning.value("appliedBitrateKbps") == 20000,
        "Apply duplicated a mutation or optimistically painted requested bitrate");
    focused(*window, bitrateApply, "pending fixed bitrate lost controller focus");
    screenshot("live-bitrate-pending-960.png");
    require(bitrateApply->mapToScene(QPointF(0, bitrateApply->height())).y() <= window->height(), "pending bitrate feedback clipped below screen");
    session.tuning["tuningBusy"] = false; session.tuning["bitrateBusy"] = false; session.tuning["canSetBitrate"] = true;
    session.tuning["appliedBitrateKbps"] = 21000; session.tuning["tuningEnabled"] = false;
    session.tuning["bitrateCopy"] = "Applied 21 Mbps. Live Tuning is off.";
    emit session.hudChanged(); settle();
    require(QMetaObject::invokeMethod(preview, "leave"), "Back did not reach bitrate picker"); settle();
    require(!bitratePopup->property("opened").toBool() && session.controlsVisible(), "picker Back resumed game or kept modal open");
    focused(*window, bitrateAction, "picker Back lost Command Center row");
    key(*window, Qt::Key_Return);
    bitratePopup->setProperty("draftKbps", 300000); bitratePlus->forceActiveFocus(); settle(); key(*window, Qt::Key_Return);
    require(bitratePopup->property("draftKbps") == 300000, "picker exceeded upper bound");
    bitratePopup->setProperty("draftKbps", 1000); bitrateMinus->forceActiveFocus(); settle(); key(*window, Qt::Key_Return);
    require(bitratePopup->property("draftKbps") == 1000 && session.bitrateWrites == 1, "picker exceeded lower bound or wrote while editing");
    // A touch adjustment edits the draft without submitting it.
    bitratePopup->setProperty("draftKbps", 21000); bitratePlus->forceActiveFocus(); settle();
    const auto plusPoint = bitratePlus->mapToScene(QPointF(bitratePlus->width()/2, bitratePlus->height()/2));
    QMouseEvent plusDown(QEvent::MouseButtonPress, plusPoint, window->mapToGlobal(plusPoint), Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
    QMouseEvent plusUp(QEvent::MouseButtonRelease, plusPoint, window->mapToGlobal(plusPoint), Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &plusDown); QCoreApplication::sendEvent(window, &plusUp); settle();
    require(bitratePopup->property("draftKbps") == 22000 && session.bitrateWrites == 1, "touch adjustment sent a mutation or failed");
    session.tuning["canSetBitrate"] = false; session.tuning["bitrateCopy"] = "This session doesn't allow host tuning.";
    emit session.hudChanged(); bitrateApply->forceActiveFocus(); settle(); key(*window, Qt::Key_Return);
    require(session.bitrateWrites == 1 && bitrateApply->property("text") == "Unavailable", "permission withdrawal allowed fixed-rate save");
    key(*window, Qt::Key_Down); focused(*window, bitrateDone, "denied picker trapped controller focus");
    session.resumeInput(); settle(); require(!bitratePopup->property("opened").toBool(), "resuming gameplay left bitrate picker open");
    session.showControls(); session.tuning["canSetBitrate"] = true; emit session.hudChanged(); settle();
    bitrateAction->forceActiveFocus(); key(*window, Qt::Key_Return);
    session.transition("disconnected", false, "Disconnected."); settle();
    require(!bitratePopup->property("opened").toBool() && session.bitrateWrites == 1, "teardown retained picker or submitted draft");

    // Doctor shares the production parser/projection and modal. The first
    // fixture is observational and must never dispatch a mutation.
    QFile doctorFixture(QStringLiteral(NOVA_DECK_DOCTOR_FIXTURE));
    require(doctorFixture.open(QIODevice::ReadOnly), "Doctor fixture missing");
    const auto finding = nova::deck::polaris::doctorPresentation(QJsonDocument::fromJson(doctorFixture.readAll()).object());
    session.transition("active", true, "Game is active."); session.showControls();
    session.tuning = {{"hostFresh", true}, {"doctor", finding}, {"canRefreshDiagnostics", true},
        {"appliedBitrate", "20.0M"}, {"qualityLimit", "40.0M"}, {"host", "4.2ms"}, {"bitrate", "19.6M"},
        {"jitter", "2ms"}, {"incoming", "60.0"}, {"decoded", "59.9"}, {"resolution", "1280×800"}, {"codec", "H.264"}};
    emit session.hudChanged(); settle();
    auto* doctorAction = root->findChild<QQuickItem*>("native-doctor");
    auto* doctorPopup = root->findChild<QObject*>("doctor-popup");
    require(doctorAction && doctorPopup, "Doctor entry missing");
    hudAction->forceActiveFocus(); key(*window, Qt::Key_Down);
    focused(*window, doctorAction, "Doctor unreachable when host tuning is unavailable");
    key(*window, Qt::Key_Return);
    // Repeater delegates belong to the visual tree, not the QObject owner tree.
    auto* diagnosisTab = visualChild(window->contentItem(), "doctor-tab-0");
    auto* evidenceTab = visualChild(window->contentItem(), "doctor-tab-1");
    auto* sessionTab = visualChild(window->contentItem(), "doctor-tab-2");
    auto* reader = root->findChild<QQuickItem*>("doctor-reading-pane");
    auto* scroll = root->findChild<QQuickItem*>("doctor-scroll");
    auto* refresh = root->findChild<QQuickItem*>("doctor-refresh");
    auto* doctorBack = root->findChild<QQuickItem*>("doctor-back");
    require(doctorAction && doctorPopup && diagnosisTab && evidenceTab && sessionTab && reader && scroll && refresh && doctorBack,
        "Doctor controls missing after opening");
    focused(*window, diagnosisTab, "Doctor did not focus Diagnosis");
    require(doctorPopup->property("opened").toBool() && root->findChild<QObject*>("doctor-title")->property("text").toString().contains("display override"),
        "Doctor lost parsed display override finding");
    window->resize(1280, 800); settle(); screenshot("doctor-diagnosis-1280.png");
    window->resize(960, 600); settle(); screenshot("doctor-diagnosis-large-960.png");
    require(doctorBack->mapToScene(QPointF(0, doctorBack->height())).y() <= window->height(), "Doctor footer clipped with large text");
    key(*window, Qt::Key_Right); key(*window, Qt::Key_Return);
    require(doctorPopup->property("page") == 1, "Evidence tab did not open");
    key(*window, Qt::Key_Down); focused(*window, reader, "Doctor evidence cannot receive D-pad scrolling");
    key(*window, Qt::Key_Down);
    require(scroll->property("contentY").toReal() > 0, "D-pad did not scroll evidence");
    const auto beforeUpdate = scroll->property("contentY").toReal(); emit session.hudChanged(); settle();
    focused(*window, reader, "live Doctor update lost reading focus");
    require(scroll->property("contentY").toReal() == beforeUpdate, "unchanged readings reset scroll");
    screenshot("doctor-evidence-large-960.png");
    for (int i = 0; i < 50 && window->activeFocusItem() == reader; ++i) key(*window, Qt::Key_Down);
    focused(*window, refresh, "D-pad was trapped at the end of evidence");
    const int writesBefore = session.tuningWrites + session.bitrateWrites;
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Return);
    require(session.diagnosticsRefreshes == 1 && refresh->property("text") == "Refreshing…", "Doctor refresh not coalesced");
    focused(*window, refresh, "Doctor refresh lost focus");
    require(!doctorPopup->property("available").toBool(), "refresh retained stale Doctor findings");
    session.tuning["hostFresh"] = true; session.tuning["doctor"] = finding;
    session.tuning["diagnosticsRefreshing"] = false; session.tuning["canRefreshDiagnostics"] = true;
    emit session.hudChanged(); settle();
    focused(*window, refresh, "fresh Doctor result moved focus");
    sessionTab->forceActiveFocus(); key(*window, Qt::Key_Return);
    require(doctorPopup->property("page") == 2 && root->findChild<QObject*>("doctor-host-readings")->property("text").toString().contains("20.0Mbps"),
        "Session page lost separate applied bitrate");
    screenshot("doctor-session-large-960.png");
    // Touch uses the same tab/refresh routes.
    const auto tap = [&](QQuickItem* item) {
        const auto point = item->mapToScene(QPointF(item->width()/2, item->height()/2));
        QMouseEvent down(QEvent::MouseButtonPress, point, window->mapToGlobal(point), Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
        QMouseEvent up(QEvent::MouseButtonRelease, point, window->mapToGlobal(point), Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
        QCoreApplication::sendEvent(window, &down); QCoreApplication::sendEvent(window, &up); settle();
    };
    auto* reportTab = visualChild(window->contentItem(), "doctor-tab-3");
    require(reportTab, "Report tab missing");
    sessionTab->forceActiveFocus(); key(*window, Qt::Key_Right); focused(*window, reportTab, "D-pad cannot reach Report");
    key(*window, Qt::Key_Return); require(doctorPopup->property("page") == 3, "Report tab did not open");
    key(*window, Qt::Key_Down); focused(*window, reader, "Report reader unreachable");
    for (int i=0;i<30 && !refresh->hasActiveFocus();++i) key(*window, Qt::Key_Down);
    focused(*window, refresh, "D-pad cannot reach Save report");
    session.reportDirectory = ""; key(*window, Qt::Key_Return);
    require(!doctorPopup->property("reportSaved").toBool() && !doctorPopup->property("reportMessage").toString().isEmpty(), "save failure hidden");
    QTemporaryDir reportDirectory; session.reportDirectory=reportDirectory.path()+"/reports";
    tap(refresh); tap(refresh);
    require(session.reportExports == 2 && doctorPopup->property("reportSaved").toBool(), "report save duplicated or failed");
    require(QDir(session.reportDirectory).entryList({"*.json"},QDir::Files).size() == 1, "UI did not save actual report");
    focused(*window, refresh, "report result stole focus");
    require(doctorBack->mapToScene(QPointF(0,doctorBack->height())).y()<=window->height(), "report footer clipped");
    screenshot("doctor-report-large-960.png");
    window->resize(1280,800); settle(); screenshot("doctor-report-1280.png");
    window->resize(960,600); settle();
    tap(diagnosisTab); require(doctorPopup->property("page") == 0, "touch Doctor tab failed");
    tap(refresh); require(session.diagnosticsRefreshes == 2, "touch refresh did not use same backend");
    require(QMetaObject::invokeMethod(preview, "leave"), "Doctor Back route missing"); settle();
    require(!doctorPopup->property("opened").toBool() && session.controlsVisible(), "Doctor Back resumed the game");
    focused(*window, doctorAction, "Doctor Back lost Command Center row");
    session.tuning = {}; emit session.hudChanged(); key(*window, Qt::Key_Return);
    require(!doctorPopup->property("available").toBool(), "unavailable session painted healthy Doctor");
    refresh->forceActiveFocus(); key(*window, Qt::Key_Return);
    require(session.diagnosticsRefreshes == 2, "unavailable refresh dispatched");
    screenshot("doctor-unavailable-960.png");
    // Production action projection drives the actual touch/controller controls.
    using namespace nova::deck::polaris;
    nova::deck::runtime::DeckDoctorActions doctorFlow;
    auto doctorSample = *parseHostTelemetry(QJsonDocument(doctor_fixture::envelope()).toJson().toStdString());
    doctorFlow.observe(&doctorSample, true, 1000);
    const auto publishDoctor = [&](qint64 now) {
        session.tuning = doctorFlow.view(now); session.tuning["hostFresh"] = true;
        session.tuning["doctor"] = doctorSample.doctor; session.tuning["canRefreshDiagnostics"] = true;
        emit session.hudChanged(); settle();
    };
    publishDoctor(1000);
    auto* fix = root->findChild<QQuickItem*>("doctor-fix");
    auto* undo = root->findChild<QQuickItem*>("doctor-undo");
    require(fix && undo, "Doctor action controls missing");
    refresh->forceActiveFocus(); key(*window, Qt::Key_Up); focused(*window, fix, "D-pad cannot reach Auto Fix");
    require(fix->property("text").toString().contains("16.0 Mbps"), "Auto Fix hid the proposed target below the scroll fold");
    window->resize(1280, 800); settle(); screenshot("doctor-fix-1280.png");
    window->resize(960, 600); settle();
    screenshot("doctor-fix-large-960.png");
    require(root->findChild<QObject*>("doctor-proposal")->property("text").toString().contains("16.0 Mbps"), "reviewed target missing");
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Return);
    require(session.doctorApplies == 1 && fix->property("text") == "Working…", "Apply duplicated or missing pending state");
    require(doctorFlow.apply(1000), "action projection fixture rejected Apply");
    ++doctorSample.live->sequence; doctorFlow.observe(&doctorSample, true, 1010);
    const auto applyRequest = doctorFlow.next(1010); require(applyRequest.has_value(), "fixture preflight failed");
    doctorFlow.complete(parseDoctorReceipt(doctor_fixture::receipt(*applyRequest), 200, *applyRequest), 1020);
    publishDoctor(1020); focused(*window, fix, "receipt update lost Apply focus");
    require(fix->property("text") == "Check result", "Apply receipt painted success or kept Apply");
    require(root->findChild<QObject*>("doctor-action-result")->property("text").toString().contains("Waiting for the encoder"), "unverified receipt claimed success");
    tap(fix); require(session.doctorChecks == 1, "touch Check result did not route");
    require(doctorFlow.check(1030), "fixture check failed");
    const auto verifyRequest = doctorFlow.next(1030); require(verifyRequest.has_value(), "fixture verify missing");
    doctorFlow.complete(parseDoctorReceipt(doctor_fixture::receipt(*verifyRequest), 200, *verifyRequest), 1040);
    auto savedDoctor = doctorFlow.checkpoint();
    nova::deck::runtime::DeckDoctorActions restartedDoctor;
    require(restartedDoctor.restore(savedDoctor,*doctorSample.live,QDateTime::currentMSecsSinceEpoch()) == nova::deck::runtime::DeckDoctorActions::Recovery::Restored, "UI recovery fixture failed");
    restartedDoctor.observe(&doctorSample,true,1040);
    session.tuning = restartedDoctor.view(1040); session.tuning["hostFresh"]=true; session.tuning["doctor"]=doctorSample.doctor;
    emit session.hudChanged(); settle();
    require(!session.tuning.value("doctorCanUndo").toBool(), "cached receipt enabled Undo");
    tap(undo); require(session.doctorUndos==0, "recovered Undo dispatched before Check");
    screenshot("doctor-recovered-large-960.png");
    publishDoctor(1040); fix->forceActiveFocus(); key(*window, Qt::Key_Right); focused(*window, undo, "D-pad cannot reach Undo");
    require(doctorBack->mapToScene(QPointF(0, doctorBack->height())).y() <= window->height() && reader->height() >= 100,
        "Doctor action receipt clipped footer or reading pane");
    screenshot("doctor-verified-undo-large-960.png");
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Return); require(session.doctorUndos == 1, "Undo duplicated");
    require(doctorFlow.undo(1050), "fixture Undo failed"); const auto undoRequest = doctorFlow.next(1050);
    require(undoRequest.has_value(), "fixture Undo missing");
    doctorFlow.complete(parseDoctorReceipt(doctor_fixture::receipt(*undoRequest), 200, *undoRequest), 1060);
    publishDoctor(1060); focused(*window, undo, "Undo receipt lost focus");
    tap(undo); require(session.doctorUndos == 1, "terminal Undo dispatched");
    screenshot("doctor-undone-large-960.png");
    doctorFlow.observe(nullptr, false, 1070); publishDoctor(1070);
    tap(fix); tap(undo); require(session.doctorApplies == 1 && session.doctorUndos == 1, "stale Doctor actions dispatched");
    session.resumeInput(); settle(); require(!doctorPopup->property("opened").toBool(), "resume retained Doctor modal");
    session.showControls(); settle(); doctorAction->forceActiveFocus(); key(*window, Qt::Key_Return);
    session.transition("disconnected", false, "Disconnected."); settle();
    require(!doctorPopup->property("opened").toBool() && session.tuningWrites + session.bitrateWrites == writesBefore,
        "Doctor teardown retained modal or modified host settings");

    auto syncSettings = *nova::deck::polaris::parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    syncSettings.desiredDisplay = "1920x1080x60";
    std::atomic<int> syncReads{0}, syncWrites{0};
    nova::deck::runtime::DeckHostSettingsController syncController;
    syncController.setPlaySettings(&settings); syncController.setWindowActive(true); syncController.setSessionActive(true);
    syncController.setTarget("fixture-host","Living Room PC",[&]() -> std::optional<nova::deck::runtime::DeckHostSettingsTarget> {
        nova::deck::runtime::DeckHostSettingsTarget t;
        t.identityValid=[] { return true; };
        t.capabilities=[] { nova::deck::polaris::DeckPolarisCapabilities c; c.clientSettings=true; return nova::deck::polaris::DeckPolarisResult<nova::deck::polaris::DeckPolarisCapabilities>{nova::deck::polaris::DeckPolarisRequestStatus::Ok,200,{},c}; };
        t.read=[&](const auto&) { ++syncReads; return nova::deck::polaris::DeckPolarisResult<nova::deck::polaris::DeckHostSettings>{nova::deck::polaris::DeckPolarisRequestStatus::Ok,200,{},syncSettings}; };
        t.idle=[](const auto&) { return nova::deck::polaris::DeckPolarisResult<bool>{nova::deck::polaris::DeckPolarisRequestStatus::Ok,200,{},false}; };
        t.writeMode=[&](const auto&,const auto&) { ++syncWrites; return nova::deck::polaris::DeckPolarisResult<nova::deck::polaris::DeckHostSettings>{}; };
        return t;
    });
    preview->setProperty("hostSettingsController",QVariant::fromValue(static_cast<QObject*>(&syncController)));
    session.transition("active",true,"Game is active."); session.showControls(); settle();
    auto* syncAction=root->findChild<QQuickItem*>("native-polaris-sync");
    auto* syncPopup=root->findChild<QObject*>("native-polaris-sync-view");
    require(syncAction && syncPopup,"Command Center Sync is missing");
    doctorAction->forceActiveFocus(); key(*window,Qt::Key_Down); focused(*window,syncAction,"D-pad cannot reach Polaris Sync");
    key(*window,Qt::Key_Return);
    for(int i=0;i<100 && syncController.busy();++i) settle();
    require(syncPopup->property("opened").toBool() && syncController.state().value("readOnly").toBool() && syncReads>0,"in-game Sync did not load");
    auto* syncBack=syncPopup->findChild<QQuickItem*>("host-defaults-back");
    auto* syncRefresh=syncPopup->findChild<QQuickItem*>("host-defaults-refresh");
    auto* syncFacts=syncPopup->findChild<QQuickItem*>("host-defaults-plan");
    require(syncBack && syncRefresh && syncFacts,"Sync navigation missing");
    require(syncBack->mapToScene(QPointF(0,syncBack->height())).y()<=window->height(),"Sync footer clips at large text");
    const auto readySync = [&] {
        session.tuning = {{"canSyncProfile",true},{"canTune",true},{"canSetBitrate",true},{"hostFresh",true},
            {"tuningKnown",true},{"tuningEnabled",true},{"appliedBitrateKbps",20000},
            {"tuningCopy","Changes save immediately. Turning tuning off holds the last confirmed bitrate."},
            {"syncVersion",session.tuning.value("syncVersion").toInt()+1}};
        emit session.hudChanged(); settle();
        for (int i=0;i<100 && syncController.busy();++i) settle();
    };
    readySync();
    auto* syncTuning=syncPopup->findChild<QQuickItem*>("sync-live-tuning");
    auto* syncBitrate=syncPopup->findChild<QQuickItem*>("sync-live-bitrate");
    auto* syncMatch=syncPopup->findChild<QQuickItem*>("sync-profile-match");
    auto* syncSend=syncPopup->findChild<QQuickItem*>("sync-profile-send");
    auto* syncClear=syncPopup->findChild<QQuickItem*>("sync-profile-clear");
    auto* syncResult=syncPopup->findChild<QQuickItem*>("sync-result");
    auto* syncBitratePopup=syncPopup->findChild<QObject*>("sync-live-bitrate-picker");
    require(syncTuning && syncBitrate && syncMatch && syncSend && syncClear && syncResult && syncBitratePopup,"session Sync controls missing");
    syncBack->forceActiveFocus(); key(*window,Qt::Key_Up); focused(*window,syncTuning,"Sync actions not controller accessible");
    auto* syncTitle=syncPopup->findChild<QQuickItem*>("host-settings-title");
    require(syncTitle && syncTitle->mapToScene(QPointF(0,0)).y() >= 0 &&
        syncTitle->height() >= syncTitle->implicitHeight(),"Sync title clips at large text");
    // Qt 6.10's offscreen software renderer can retain damage from the previous
    // popup frame. Request and settle a full frame before reviewing the capture.
    window->update(); settle();
    screenshot("polaris-sync-session-960-large.png");
    window->resize(1280,800); settle(); screenshot("polaris-sync-session-1280.png");
    window->resize(960,600); settle();
    key(*window,Qt::Key_Left); focused(*window,syncFacts,"Sync readings not controller accessible");
    require(syncController.refresh(),"Sync manual refresh refused");
    for(int i=0;i<100 && syncController.busy();++i) settle();
    focused(*window,syncFacts,"Sync refresh moved reading focus");
    key(*window,Qt::Key_Right); focused(*window,syncTuning,"Sync actions unreachable from readings");
    key(*window,Qt::Key_Down); focused(*window,syncBitrate,"live bitrate unreachable");
    key(*window,Qt::Key_Return); require(syncBitratePopup->property("opened").toBool(),"nested bitrate picker did not open");
    require(QMetaObject::invokeMethod(preview,"leave"),"nested Sync Back route missing"); settle();
    require(!syncBitratePopup->property("opened").toBool() && syncPopup->property("opened").toBool(),"Back skipped the nested editor");
    focused(*window,syncBitrate,"nested bitrate Back lost focus");
    key(*window,Qt::Key_Down); focused(*window,syncMatch,"Match Nova unreachable");
    key(*window,Qt::Key_Down); focused(*window,syncSend,"Send Nova unreachable");
    screenshot("polaris-sync-send-review-960-large.png");
    key(*window,Qt::Key_Return);
    require(session.syncWrites==1 && session.syncHost=="fixture-host" && !session.syncClear &&
        session.syncReview==syncSettings.profileReview() && session.syncDisplay==syncController.state().value("novaDisplay").toString() &&
        session.syncBitrate==syncController.state().value("novaBitrate").toInt(),"Send Nova lost reviewed profile or device defaults");
    key(*window,Qt::Key_Return); require(session.syncWrites==1,"busy Sync sent duplicate request");
    focused(*window,syncSend,"pending Sync moved focus");
    session.tuning["syncBusy"]=false; session.tuning["tuningBusy"]=false; session.tuning["syncPhase"]="unconfirmed";
    session.tuning["syncVersion"]=session.tuning.value("syncVersion").toInt()+1;
    session.tuning["syncCopy"]="Profile saved for the next stream. The live encoder change wasn't confirmed; check the current bitrate before trying again.";
    emit session.hudChanged(); settle(); for(int i=0;i<100 && syncController.busy();++i) settle();
    focused(*window,syncSend,"Sync readback moved focus");
    require(syncResult->property("text").toString().contains("wasn't confirmed") &&
        syncBack->mapToScene(QPointF(0,syncBack->height())).y()<=window->height(),"uncertain result or footer clipped");
    screenshot("polaris-sync-unconfirmed-960-large.png");
    readySync(); key(*window,Qt::Key_Down); focused(*window,syncClear,"Clear profile unreachable");
    tap(syncClear); require(session.syncWrites==2 && session.syncClear && session.syncDisplay.isEmpty() && session.syncBitrate==0,"touch Clear sent live settings");
    readySync(); key(*window,Qt::Key_Up); key(*window,Qt::Key_Up); focused(*window,syncMatch,"Match Nova return route failed");
    key(*window,Qt::Key_Return); require(session.syncWrites==3 && !session.syncClear,"Match Nova did not submit the explicit profile");
    readySync(); key(*window,Qt::Key_Down); key(*window,Qt::Key_Down);
    session.tuning["canSyncProfile"]=false; emit session.hudChanged(); settle();
    key(*window,Qt::Key_Return); require(session.syncWrites==3,"revoked authority allowed profile mutation");
    screenshot("polaris-sync-unavailable-960-large.png");
    key(*window,Qt::Key_Down); focused(*window,syncBack,"Sync footer unreachable");
    key(*window,Qt::Key_Right); focused(*window,syncRefresh,"Sync refresh unreachable");
    tap(syncRefresh); for(int i=0;i<100 && syncController.busy();++i) settle();
    require(syncReads>=3 && syncWrites==0,"session Sync used idle host mutation route");
    require(QMetaObject::invokeMethod(preview,"leave"),"Sync Back route missing"); settle();
    require(!syncPopup->property("opened").toBool() && session.controlsVisible(),"Sync Back resumed the game");
    focused(*window,syncAction,"Sync Back lost menu focus");
    tap(syncAction); settle(); session.resumeInput(); settle();
    require(!syncPopup->property("opened").toBool(),"resume retained Sync modal");
    session.showControls(); settle(); tap(syncAction); settle(); session.transition("disconnected",false,"Disconnected."); settle();
    require(!syncPopup->property("opened").toBool() && syncWrites==0,"disconnect retained Sync or changed host");
    preview->setProperty("hostSettingsController",QVariant::fromValue(static_cast<QObject*>(nullptr)));

    const int beforeCodecEdits = session.starts;
    require(QMetaObject::invokeMethod(preview, "close"), "cannot close before codec review");
    session.transition("stopped", false, "Your game has ended.");
    preview->setProperty("hostId", "codec-pc"); preview->setProperty("gameId", "codec-game");
    preview->setProperty("destinationId", "desktop");
    preview->setProperty("streamCapabilities", QVariantMap{{"h264", true}, {"hevc", true}});
    require(QMetaObject::invokeMethod(preview, "open"), "cannot open codec review");
    settle();
    auto* codec = root->findChild<QQuickItem*>("play-setup-codec");
    auto* mode = root->findChild<QQuickItem*>("play-setup-launch-mode");
    require(codec && mode, "codec setting missing");
    mode->forceActiveFocus(); key(*window, Qt::Key_Down);
    focused(*window, codec, "D-pad did not reach codec setting");
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Down); key(*window, Qt::Key_Return);
    require(setup->property("plan").toMap().value("videoLabel") == "HEVC · SDR" && primary->isEnabled(), "HEVC review missing");
    focused(*window, codec, "codec choice lost return focus");
    window->resize(1280, 800); settle(); screenshot("hevc-review-1280.png");
    window->resize(960, 600); settle(); screenshot("hevc-review-large-960.png");
    tap(codec);
    auto* autoChoice = visualChild(window->contentItem(), "play-setup-choice-0");
    require(autoChoice, "Auto codec choice missing from visual tree");
    tap(autoChoice);
    require(settings.load("codec-pc", "codec-game").value("configuration").toMap().value("videoCodec") == "auto" &&
        setup->property("plan").toMap().value("videoLabel") == "HEVC · SDR", "touch Auto lost stored/effective distinction");
    codec->forceActiveFocus(); key(*window, Qt::Key_Return);
    auto* explanation = visualChild(window->contentItem(), "play-setup-choice-description");
    require(explanation && !explanation->property("truncated").toBool() && explanation->property("lineCount").toInt() > 1,
        "codec explanation clipped at large text");
    screenshot("codec-picker-large-960.png");
    preview->setProperty("streamCapabilities", QVariantMap{{"h264", true}, {"hevc", false}}); settle();
    require(!root->findChild<QObject*>("play-setup-picker")->property("opened").toBool(), "withdrawn codec left stale choices open");
    focused(*window, codec, "withdrawn codec lost return focus");
    require(setup->property("plan").toMap().value("videoLabel") == "H.264 · SDR" &&
        setup->property("plan").toMap().value("codecDetail").toString().contains("Auto selected H.264"), "Auto fallback was not disclosed");
    key(*window, Qt::Key_Return); key(*window, Qt::Key_Down); key(*window, Qt::Key_Down); key(*window, Qt::Key_Return);
    require(!primary->isEnabled() && !setup->property("plan").toMap().value("reason").toString().isEmpty(), "unavailable explicit HEVC remained playable");
    screenshot("codec-unavailable-large-960.png");
    require(session.starts == beforeCodecEdits &&
        nova::deck::runtime::DeckPlaySettings(directory.filePath("play.ini")).load("codec-pc", "codec-game")
            .value("configuration").toMap().value("videoCodec") == "hevc", "codec review launched host or lost persisted preference");

    require(settings.saveChoice("codec-pc", "codec-game", {{"launchMode", "headless_stream"}}), "cannot prepare retired mode");
    preview->setProperty("launchPolicy", QVariantMap{{"known", true}, {"hostDefault", "desktop_display"}, {"allowed", QVariantList{"desktop_display"}}});
    require(QMetaObject::invokeMethod(preview, "close") && QMetaObject::invokeMethod(preview, "open"), "cannot reopen retired mode review");
    settle();
    const auto retired = settings.load("codec-pc", "codec-game").value("configuration").toMap();
    require(retired.value("launchMode") == "default" && retired.value("videoCodec") == "hevc" &&
        !setup->property("notice").toString().isEmpty() && setup->property("error").toString().isEmpty(),
        "retiring a launch mode reset the codec or failed to save");

    // Hiding the two decorations must not hide menu access, diagnostics or
    // controller recovery. Exercise the actual Appearance controls at 130%/960.
    preview->setProperty("attempted", true);
    session.transition("active", true, "Your game is connected.");
    session.showControls(); settle();
    auto* appearance = root->findChild<QObject*>("appearance-settings-popup");
    require(appearance && QMetaObject::invokeMethod(appearance, "open"), "cannot open stream appearance");
    settle();
    auto* buttonToggle = root->findChild<QQuickItem*>("appearance-command-center-button");
    auto* hintToggle = root->findChild<QQuickItem*>("appearance-shortcut-hint");
    auto* hint = root->findChild<QQuickItem*>("native-controller-hint");
    auto* shortcut = root->findChild<QQuickItem*>("native-controller-shortcut");
    require(buttonToggle && hintToggle && hint && shortcut, "missing stream overlay preferences");
    buttonToggle->forceActiveFocus(); settle();
    screenshot("stream-appearance-large-960.png");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Down);
    focused(*window, hintToggle, "button toggle lost D-pad path to shortcut toggle");
    key(*window, Qt::Key_Escape);
    session.resumeInput(); settle();
    require(!controls->isVisible() && hint->isVisible() && shortcut->isVisible(), "button/hint cannot be hidden independently");
    screenshot("stream-shortcut-glyphs-large-960.png");
    key(*window, Qt::Key_Escape);
    require(session.controlsVisible(), "hidden touch button blocked menu access");
    QMetaObject::invokeMethod(appearance, "open"); settle();
    hintToggle->forceActiveFocus(); key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Up);
    focused(*window, buttonToggle, "shortcut toggle lost D-pad return path");
    key(*window, Qt::Key_Escape);
    session.resumeInput(); settle();
    require(!controls->isVisible() && !hint->isVisible(), "hidden decorations remained over the game");
    screenshot("stream-overlays-hidden-large-960.png");
    QQmlEngine freshEngine;
    QQmlComponent saved(&freshEngine);
    saved.setData(imports + R"(QtObject {
        property bool menu: NovaStreamPreferences.commandCenterButton
        property bool hint: NovaStreamPreferences.shortcutHint
    })", QUrl());
    std::unique_ptr<QObject> savedState(saved.create());
    require(savedState && !savedState->property("menu").toBool() && !savedState->property("hint").toBool(),
        "hidden preferences did not survive a new engine");
    session.inputNotice = "Release the buttons, sticks and triggers to continue.";
    emit session.controlsChanged(); settle();
    require(hint->isVisible() && !shortcut->isVisible(), "hidden shortcut also hid the held-input notice");
    session.inputNotice.clear(); emit session.controlsChanged();
    players.connected = false; emit players.playersChanged(); settle();
    require(controls->isVisible() && !hint->isVisible(), "controller removal lost the touch escape route");
    tapItem(controls);
    require(session.controlsVisible(), "touch escape no longer opens Command Center");
    players.connected = true; emit players.playersChanged();
    QMetaObject::invokeMethod(appearance, "open"); settle();
    buttonToggle->forceActiveFocus(); settle(); tapItem(buttonToggle);
    hintToggle->forceActiveFocus(); settle(); tapItem(hintToggle);
    key(*window, Qt::Key_Escape);
    session.resumeInput(); settle();
    require(controls->isVisible() && hint->isVisible(), "touch toggles did not restore decorations");
    screenshot("stream-deck-glyphs-large-960.png");
    window->resize(1280, 800); settle();
    screenshot("stream-deck-glyphs-large-1280.png");
    std::cout << "Native QML passed: stream review, lifecycle, hideable overlays, Deck glyphs, preference persistence and menu recovery\n";
}
