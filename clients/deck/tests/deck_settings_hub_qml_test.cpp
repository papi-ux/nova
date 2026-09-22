#include "deck_game_tools_fixture.h"
#include "runtime/deck_host_settings.h"
#include <QGuiApplication>
#include <QQmlEngine>
#include <QQmlContext>
#include <QQmlComponent>
#include <QQuickWindow>
#include <QQuickItem>
#include <QTemporaryDir>
#include <QElapsedTimer>
#include <QTest>
#include <QDir>
#include <QSettings>
#include <iostream>
using namespace nova::deck::runtime;
namespace {
void check(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
void settle() { QTest::qWait(100); }
void wait(const std::function<bool()>& done) { QElapsedTimer t; t.start(); while (!done() && t.elapsed() < 6000) QTest::qWait(20); check(done(), "UI timed out"); settle(); }
QQuickItem* find(QQuickItem* p, const QString& name) {
    if (p->objectName() == name && p->isVisible()) return p;
    for (auto* child : p->childItems()) if (auto* r = find(child, name)) return r;
    return nullptr;
}
}
int main(int argc, char** argv) {
    QTemporaryDir config; qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("SettingsHub");
    DeckPlaySettings settings(config.filePath("play.ini"));
    QFile blocker(config.filePath("blocked")); check(blocker.open(QIODevice::WriteOnly), "failure fixture failed"); blocker.close();
    DeckPlaySettings failedSettings(config.filePath("blocked/play.ini"));
    check(settings.saveChoice("host", "game", {{"bitrateKbps", 35000}})
          && settings.saveChoice("host", "game", {{"faceButtonLayout", "positions"}}), "seed override failed");
    const auto override = settings.load("host", "game");
    auto current = *nova::deck::polaris::parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    std::atomic<int> reads{0}, writes{0};
    DeckHostSettingsController host; host.setPlaySettings(&settings); host.setWindowActive(true);
    host.setTarget("host", "Living Room PC", [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget target; target.identityValid = [] { return true; };
        target.capabilities = [] { nova::deck::polaris::DeckPolarisCapabilities c; c.clientSettings = true; return game_tools_fixture::ok(c); };
        target.read = [&](const auto&) { ++reads; return game_tools_fixture::ok(current); };
        target.idle = [](const auto&) { return game_tools_fixture::ok(true); };
        target.writeMode = [&](const auto&, const auto&) { ++writes; return game_tools_fixture::ok(current); };
        return target;
    });
    QQmlEngine engine; engine.rootContext()->setContextProperty("settings", &settings); engine.rootContext()->setContextProperty("host", &host);
    bool warnings = false;
    QObject::connect(&engine, &QQmlEngine::warnings, [&](const QList<QQmlError>& es) { warnings = true; for (const auto& e : es) std::cerr << e.toString().toStdString() << '\n'; });
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport QtCore\nimport \"" + QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded() + "\"\n" + R"(
        ApplicationWindow {
            width:1280; height:800; visible:true; color:NovaTheme.window
            property bool available:true
            property var provider:settings
            Settings { id:prefs; category:"Library"; property string layoutMode:"grid" }
            function large() { NovaTheme.setFontScale(1.3) }
            function contrast() { NovaTheme.setTheme("high_contrast") }
            function resetTheme() { NovaTheme.setTheme("polaris"); NovaTheme.setFontScale(1) }
            function controllerBack() { hub.back() }
            function syncPreferences() { NovaTheme.preferences.sync(); NovaHudPreferences.preferences.sync(); NovaStreamPreferences.preferences.sync(); prefs.sync() }
            NovaButton { id:open; objectName:"open-settings"; text:"Settings"; onClicked:hub.open() }
            SettingsHub { id:hub; settingsProvider:provider; hostController:host; libraryPreferences:prefs; hostAvailable:available; onClosed:open.forceActiveFocus() }
        }
    )", QUrl());
    auto root = std::unique_ptr<QObject>(component.create()); if (!root) std::cerr << component.errorString().toStdString(); check(bool(root), "QML failed");
    auto* window = qobject_cast<QQuickWindow*>(root.get()); check(window, "no window");
    const auto item = [&](const char* name) { auto* p = find(window->contentItem(), name); check(p, name); return p; };
    const auto click = [&](const char* name) { item(name)->forceActiveFocus(); settle(); QTest::keyClick(window, Qt::Key_Return); settle(); };
    const auto key = [&](Qt::Key k) { QTest::keyClick(window, k); settle(); };
    const auto controllerBack = [&] { QMetaObject::invokeMethod(root.get(), "controllerBack"); settle(); };
    const auto focused = [&](const char* name) { check(window->activeFocusItem() == item(name), name); };
    const auto capture = [&](const char* name) { if (argc > 1) { QDir().mkpath(argv[1]); check(window->grabWindow().save(QString::fromLocal8Bit(argv[1]) + "/" + name + ".png"), "capture failed"); } };
    const auto query = [&](const char* text) { auto* s = item("settings-search"); s->forceActiveFocus(); s->setProperty("text", text); settle(); };
    const auto within = [&](const char* name) { auto* p = item(name); const auto r = p->mapRectToScene(p->boundingRect()); check(r.top() >= 0 && r.bottom() <= window->height() && r.left() >= 0 && r.right() <= window->width(), "control outside window"); };
    settle(); click("open-settings"); focused("settings-category-all");
    check(reads == 0 && writes == 0, "opening hub touched host");
    capture("settings-all-1280");
    key(Qt::Key_Down); focused("settings-category-stream"); key(Qt::Key_Return); key(Qt::Key_Right); focused("settings-row-stream");
    key(Qt::Key_Down); focused("settings-row-scale"); click("settings-row-scale"); focused("video-scale-fit");
    key(Qt::Key_Down); controllerBack(); check(settings.videoScaleMode()=="fit","focus/Back changed scaling"); focused("settings-row-scale");
    click("settings-row-scale"); click("video-scale-fill");
    check(settings.videoScaleMode()=="fill" && DeckPlaySettings(config.filePath("play.ini")).videoScaleMode()=="fill","Fill did not persist");
    focused("video-scale-fill"); controllerBack(); focused("settings-row-scale");
    check(item("settings-row-scale")->property("text").toString().contains("Fill"),"Settings value did not refresh");
    click("settings-reset-scale"); check(settings.videoScaleMode()=="fit" && settings.load("host","game")==override,"scaling reset crossed game scope");
    check(reads==0 && writes==0,"scaling touched the host");
    query("frame pacing"); key(Qt::Key_Down); focused("settings-row-pacing"); key(Qt::Key_Return);
    focused("settings-choice-0"); key(Qt::Key_Down); controllerBack();
    check(settings.framePacingMode()=="latency", "pacing navigation saved a choice"); focused("settings-row-pacing");
    click("settings-row-pacing"); key(Qt::Key_Down); key(Qt::Key_Return);
    check(settings.framePacingMode()=="balanced" && DeckPlaySettings(config.filePath("play.ini")).framePacingMode()=="balanced", "Balanced did not persist");
    focused("settings-row-pacing"); click("settings-reset-pacing");
    check(settings.framePacingMode()=="latency" && settings.load("host","game")==override && reads==0 && writes==0, "pacing reset crossed scope");
    // Global search ignores the selected category; whitespace and multiple words work.
    query("  audio   channels  "); key(Qt::Key_Down); focused("settings-row-channels");
    click("settings-row-channels"); key(Qt::Key_Down); key(Qt::Key_Return);
    check(settings.audioSettings()["channels"] == 6, "audio choice did not save"); focused("settings-row-channels");
    query(""); click("settings-category-audio"); click("settings-row-hostAudio");
    check(settings.audioSettings()["playHostAudio"].toBool(), "host playback toggle failed");
    click("settings-reset-channels");
    check(settings.audioSettings()["channels"] == 2 && settings.audioSettings()["playHostAudio"].toBool(), "reset crossed audio setting scope");
    click("settings-row-channels"); key(Qt::Key_Down); controllerBack();
    check(settings.audioSettings()["channels"] == 2, "cancel saved a selection"); focused("settings-row-channels");
    query("no matching setting"); key(Qt::Key_Down); focused("settings-search-clear"); key(Qt::Key_Return); focused("settings-search");
    check(item("settings-search")->property("text").toString().isEmpty(), "empty results did not clear");
    // Touch uses the same on-screen keyboard as controller A, without changing the query until Done.
    auto* touch = QTest::createTouchDevice(); auto* search = item("settings-search");
    const auto point = search->mapToScene(QPointF(search->width()/2, search->height()/2)).toPoint();
    QTest::touchEvent(window, touch).press(0, point, window).commit(); QTest::touchEvent(window, touch).release(0, point, window).commit(); settle();
    item("endpoint-keyboard-entry")->setProperty("text", "rumble"); click("endpoint-keyboard-done"); focused("settings-search"); key(Qt::Key_Down); focused("settings-row-rumble");
    click("settings-search"); item("endpoint-keyboard-entry")->setProperty("text", "discarded"); controllerBack();
    check(item("settings-search")->property("text") == "rumble", "controller Back applied keyboard draft");
    click("settings-row-rumble"); check(!settings.rumbleEnabled(), "rumble toggle failed");
    click("settings-reset-rumble"); check(settings.rumbleEnabled(), "rumble reset failed");
    query("stick drift"); click("settings-row-deadzone"); focused("deadzone-minus");
    key(Qt::Key_Return); check(settings.stickDeadzonePercent()==5,"deadzone draft saved early");
    controllerBack(); focused("settings-row-deadzone");
    click("settings-row-deadzone");
    for (int i=0;i<10;++i) key(Qt::Key_Left);
    key(Qt::Key_Down); focused("deadzone-slider"); key(Qt::Key_Down); focused("deadzone-save"); key(Qt::Key_Return);
    check(settings.stickDeadzonePercent()==-5 && DeckPlaySettings(config.filePath("play.ini")).stickDeadzonePercent()==-5,"deadzone Save did not persist");
    focused("settings-row-deadzone"); capture("deadzone-saved-1280");
    click("settings-row-deadzone"); click("deadzone-default"); controllerBack();
    check(settings.stickDeadzonePercent()==-5,"cancelled default draft was saved");
    click("settings-reset-deadzone");
    check(settings.stickDeadzonePercent()==5 && settings.load("host","game")==override && reads==0 && writes==0,"deadzone reset crossed scope");
    query("face"); click("settings-row-face"); key(Qt::Key_Down); key(Qt::Key_Return);
    check(settings.defaultFaceButtonLayout() == "positions", "face choice failed");
    click("settings-reset-face"); check(settings.defaultFaceButtonLayout() == "labels" && settings.load("host", "game") == override, "face reset erased game override");
    // Persistence failure stays visible and must not appear to apply.
    root->setProperty("provider", QVariant::fromValue(static_cast<QObject*>(&failedSettings))); settle();
    query("deadzone"); click("settings-row-deadzone"); click("deadzone-minus"); click("deadzone-save");
    check(item("deadzone-error")->isVisible() && failedSettings.stickDeadzonePercent()==5,"failed deadzone save appeared applied");
    focused("deadzone-save"); controllerBack();
    query("pacing"); click("settings-row-pacing"); key(Qt::Key_Down); key(Qt::Key_Return);
    check(failedSettings.framePacingMode()=="latency" && !root->findChild<QObject*>("settings-hub")->property("error").toString().isEmpty(), "failed pacing save appeared applied");
    focused("settings-choice-1"); controllerBack();
    query("scaling"); click("settings-row-scale"); click("video-scale-fill");
    check(item("video-scale-error")->isVisible() && failedSettings.videoScaleMode()=="fit","failed scaling save appeared applied");
    controllerBack(); query("face");
    click("settings-row-face"); key(Qt::Key_Down); key(Qt::Key_Return);
    check(find(window->contentItem(), "settings-choice-back") && settings.defaultFaceButtonLayout() == "labels", "failed write dismissed picker or changed value");
    key(Qt::Key_Escape); root->setProperty("provider", QVariant::fromValue(static_cast<QObject*>(&settings))); settle();
    query(""); click("settings-category-appearance"); click("settings-row-theme"); click("settings-choice-1");
    click("settings-row-text"); click("settings-choice-2"); window->resize(960, 600); settle();
    focused("settings-row-text"); within("settings-row-text"); within("settings-back");
    capture("settings-appearance-960-large");
    query("scaling"); click("settings-row-scale");
    within("video-scale-back"); within("video-scale-reset"); capture("video-scaling-960-large");
    click("video-scale-stretch"); check(settings.videoScaleMode()=="stretch","Stretch did not save");
    click("video-scale-reset"); check(settings.videoScaleMode()=="fit","scaling reset failed");
    controllerBack(); query("frame pacing"); click("settings-row-pacing");
    within("settings-choice-back"); capture("frame-pacing-960-large");
    key(Qt::Key_Down); focused("settings-choice-1"); within("settings-choice-1");
    auto* balanced = item("settings-choice-1");
    const auto pacingPoint = balanced->mapToScene(QPointF(balanced->width()/2,balanced->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,pacingPoint,window).commit();
    QTest::touchEvent(window,touch).release(0,pacingPoint,window).commit(); settle();
    check(settings.framePacingMode()=="balanced", "touch pacing choice failed"); focused("settings-row-pacing");
    click("settings-reset-pacing"); query("deadzone"); click("settings-row-deadzone");
    within("deadzone-cancel"); within("deadzone-default"); within("deadzone-save"); capture("deadzone-960-large");
    auto* slider=item("deadzone-slider"); slider->forceActiveFocus(); settle(); within("deadzone-slider");
    const auto sliderPoint=slider->mapToScene(QPointF(slider->width()-2,slider->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,sliderPoint,window).commit();
    QTest::touchEvent(window,touch).release(0,sliderPoint,window).commit(); settle();
    check(item("deadzone-value")->property("text").toString()=="+20%" && settings.stickDeadzonePercent()==5,"touch slider saved early or missed its bound");
    click("deadzone-plus"); check(item("deadzone-value")->property("text").toString()=="+20%","deadzone exceeded upper bound");
    auto* handle=qvariant_cast<QQuickItem*>(slider->property("handle")); check(handle,"slider handle missing");
    const auto handlePoint=handle->mapToScene(QPointF(handle->width()/2,handle->height()/2)).toPoint();
    const auto leftPoint=slider->mapToScene(QPointF(2,slider->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,handlePoint,window).commit();
    QTest::touchEvent(window,touch).move(0,leftPoint,window).commit();
    QTest::touchEvent(window,touch).release(0,leftPoint,window).commit(); settle();
    check(item("deadzone-value")->property("text").toString()=="-20%" && settings.stickDeadzonePercent()==5,"touch drag did not preserve the unsaved lower bound");
    click("deadzone-minus"); check(item("deadzone-value")->property("text").toString()=="-20%","deadzone exceeded lower bound");
    within("deadzone-save"); capture("deadzone-negative-960-large");
    click("deadzone-save"); focused("settings-row-deadzone");
    check(settings.stickDeadzonePercent()==-20,"large-text deadzone did not save");
    click("settings-reset-deadzone"); query(""); click("settings-category-appearance");
    click("settings-row-layout"); click("settings-choice-2");
    click("settings-reset-theme");
    QMetaObject::invokeMethod(root.get(), "syncPreferences");
    QSettings persisted;
    wait([&] { persisted.sync(); return persisted.value("Library/layoutMode") == "stage"; });
    check(persisted.value("Appearance/themeId") == "polaris" && persisted.value("Appearance/textScale").toDouble() == 1.3
          && persisted.value("Library/layoutMode") == "stage", "theme reset crossed scope or preferences did not persist");
    click("settings-category-ingame"); click("settings-row-hud"); click("settings-row-hudMode"); click("settings-choice-2");
    click("settings-reset-hud");
    QMetaObject::invokeMethod(root.get(), "syncPreferences"); persisted.sync();
    wait([&] { persisted.sync(); return persisted.value("NovaHUD/mode") == "performance"; });
    check(!persisted.value("NovaHUD/enabled").toBool() && persisted.value("NovaHUD/mode") == "performance", "HUD reset crossed setting scope");
    click("settings-row-position"); click("settings-choice-2");
    click("settings-row-command"); click("settings-row-hint");
    QMetaObject::invokeMethod(root.get(), "contrast"); settle(); click("settings-row-opacity");
    within("settings-choice-back"); capture("settings-hud-choice-960-large"); key(Qt::Key_Escape);
    focused("settings-row-opacity"); within("settings-row-opacity");
    capture("settings-hud-960-large");
    for (const auto* name : {"settings-row-command", "settings-row-opacity"}) {
        auto* control = item(name);
        auto* content = qvariant_cast<QQuickItem*>(control->property("contentItem"));
        check(content && content->height() >= content->implicitHeight() - 1, "large-text row compressed its content");
    }
    check(reads == 0 && writes == 0, "device preferences touched host");
    // The same guarded host editor is reached without selecting a game.
    query("polaris sync"); click("settings-row-sync"); wait([&] { return reads > 0 && !host.busy(); });
    check(writes == 0, "review wrote host settings"); controllerBack(); focused("settings-row-sync");
    query("stream defaults"); click("settings-row-stream"); wait([&] { return host.state()["canEditDefaults"].toBool() && !host.busy(); });
    click("host-edit-defaults"); click("stream-profile-width");
    item("endpoint-keyboard-entry")->setProperty("text", "1920"); controllerBack();
    focused("stream-profile-width"); check(item("stream-profile-width")->property("text") == "1280", "controller Back saved number draft");
    item("stream-profile-width")->setProperty("text", "1920"); controllerBack();
    check(settings.streamDefaults()["width"] == 1280, "cancel from hub saved stream defaults");
    focused("host-edit-defaults"); controllerBack(); focused("settings-row-stream");
    query("polaris sync");
    root->setProperty("available", false); click("settings-row-sync"); focused("settings-row-sync");
    check(!find(window->contentItem(), "host-defaults-back"), "unavailable PC opened host editor");
    query("text size"); controllerBack(); check(item("settings-search")->property("text").toString().isEmpty(), "Back did not clear query");
    controllerBack(); focused("open-settings");
    check(settings.load("host", "game") == override && writes == 0, "hub changed game scope or host");
    DeckPlaySettings restarted(config.filePath("play.ini"));
    check(restarted.audioSettings()["playHostAudio"].toBool() && restarted.rumbleEnabled(), "device settings not persisted");
    check(!warnings, "Settings hub emitted QML warnings");
    std::cout << "Settings search, controller/touch, per-setting reset, scopes, failure, persistence, host routing and large text passed.\n";
}
