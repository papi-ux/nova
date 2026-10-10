#include "runtime/deck_play_settings.h"
#include "runtime/deck_host_settings.h"
#include "stream/deck_stream_media_adapters.h"
#include "deck_preview_fixture.h"
#include <QDir>
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>
#include <cstdlib>
#include <iostream>
#include <memory>

// Working baseline Command Center flow (#282 Linux), offscreen with the software
// backend. Each step drives the real QML and checks the session calls it caused.
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
}

int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("WorkingBaseline");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    PreviewSession session; PreviewPlayers players(session);
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264 = {4096, 4096}, .hevc = {4096, 4096}});
    // The real controller supplies the enabled-row contract without a target,
    // resolver or open request. This fixture never performs host operations.
    nova::deck::runtime::DeckHostSettingsController syncController;
    session.startConfigured("fixture-host", "fixture-game", {});
    session.transition("active", true, "Your game is connected.");
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("testSession", &session);
    engine.rootContext()->setContextProperty("testPlayers", &players);
    engine.rootContext()->setContextProperty("testSettings", &settings);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width:1280; height:800; visible:true
    NativeStreamPreview { id:preview; session:testSession; inputHub:testPlayers; settingsProvider:testSettings
        hostId:"fixture-host"; gameId:"fixture-game"; gameTitle:"Moonlit Harbor"; hostName:"Living Room PC" }
    function largeText() { NovaTheme.setFontScale(1.3) }
    Component.onCompleted: { preview.open(); preview.attempted=true }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/WorkingBaselineTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "missing window"); QTest::qWait(150);
    const auto item = [&](const char* name) { auto* found = root->findChild<QQuickItem*>(name); require(found, name); return found; };
    const auto popup = [&](const char* name) { auto* found = root->findChild<QObject*>(name); require(found, name); return found; };
    const auto opened = [&](const char* name) { return popup(name)->property("opened").toBool(); };
    const auto focusName = [&] { return window->activeFocusItem() ? window->activeFocusItem()->objectName() : QString{}; };
    const auto press = [&](Qt::Key key) { QTest::keyClick(window, key); QTest::qWait(80); };
    const auto capture = [&](const char* name) { if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QDir(argv[1]).filePath(name)), "capture failed"); } };
    const auto untouched = [&] {
        return session.reportExports == 0 && session.tuningWrites == 0 && session.bitrateWrites == 0 && session.syncWrites == 0 &&
            session.diagnosticsRefreshes == 0 && session.doctorApplies == 0 && session.doctorUndos == 0 && session.doctorChecks == 0 &&
            session.stops == 0 && session.disconnects == 0;
    };
    const auto openBaseline = [&] {
        auto* row = item("native-working-baseline"); row->forceActiveFocus(); QTest::qWait(50);
        require(focusName() == "native-working-baseline", "Command Center row is not reachable");
        press(Qt::Key_Return); require(opened("working-baseline-popup"), "baseline popup did not open");
    };

    auto* preview = root->findChild<QObject*>("native-stream-preview"); require(preview, "preview missing");
    // Without a host controller, navigation skips the disabled Sync row in
    // both directions. Never force focus onto a disabled item to test routing.
    require(!item("native-polaris-sync")->isEnabled(), "Sync should be disabled without a host controller");
    item("native-doctor")->forceActiveFocus(); QTest::qWait(50); press(Qt::Key_Down);
    require(focusName() == "native-working-baseline", "D-pad did not skip unavailable Sync to reach baseline");
    press(Qt::Key_Up); require(focusName() == "native-doctor", "baseline Up did not skip unavailable Sync");

    // With a controller, preserve Doctor → Sync and verify Sync ↔ baseline.
    require(preview->setProperty("hostSettingsController", QVariant::fromValue(static_cast<QObject*>(&syncController))),
        "cannot attach host controller fixture");
    QTest::qWait(50); require(item("native-polaris-sync")->isEnabled(), "Sync did not enable with its controller");
    press(Qt::Key_Down); require(focusName() == "native-polaris-sync", "Doctor Down no longer reaches available Sync");
    press(Qt::Key_Down); require(focusName() == "native-working-baseline", "Sync Down cannot reach the baseline row");
    press(Qt::Key_Up); require(focusName() == "native-polaris-sync", "baseline Up cannot reach available Sync");
    press(Qt::Key_Down); require(focusName() == "native-working-baseline", "Sync Down lost baseline focus");
    require(!opened("native-polaris-sync-view"), "navigation opened host operations");

    openBaseline();
    require(focusName() == "working-baseline-save", "Save was not the first focused action");
    require(item("working-baseline-storage-copy")->property("text").toString().contains("Stored locally on this device") &&
        !item("working-baseline-storage-copy")->property("text").toString().contains("backup"), "storage copy is missing or claims backup exclusion");
    capture("working-baseline-1280.png");

    // Confirmation opens on the safe choice; Escape and an outside press cancel without saving.
    press(Qt::Key_Return);
    require(opened("working-baseline-save-confirmation") && focusName() == "working-baseline-keep-playing", "confirmation did not focus Keep playing");
    capture("working-baseline-confirmation-1280.png");
    press(Qt::Key_Escape);
    require(!opened("working-baseline-save-confirmation") && opened("working-baseline-popup") && session.baselineSaves == 0, "Escape saved or closed the baseline page");
    press(Qt::Key_Return); require(opened("working-baseline-save-confirmation"), "confirmation did not reopen");
    QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, QPoint(4, 4)); QTest::qWait(80);
    require(!opened("working-baseline-save-confirmation") && session.baselineSaves == 0, "outside press saved");
    require(opened("working-baseline-popup"), "outside press on the confirmation also closed the baseline page");
    // Physical B routes through leave(): it cancels only the confirmation.
    item("working-baseline-save")->forceActiveFocus(); press(Qt::Key_Return);
    require(QMetaObject::invokeMethod(preview, "leave"), "cannot route controller Back"); QTest::qWait(80);
    require(!opened("working-baseline-save-confirmation") && opened("working-baseline-popup") && session.baselineSaves == 0,
        "controller Back saved or closed the baseline page");
    item("working-baseline-save")->forceActiveFocus(); press(Qt::Key_Return);
    press(Qt::Key_Return); // Keep playing
    require(!opened("working-baseline-save-confirmation") && session.baselineSaves == 0, "Keep playing saved");

    // The generation read at the confirmation's opening goes back with the Save press.
    item("working-baseline-save")->forceActiveFocus(); press(Qt::Key_Return);
    const auto generation = QString::number(session.baselineGeneration);
    press(Qt::Key_Down); require(focusName() == "working-baseline-confirm-save", "Save as working unreachable");
    press(Qt::Key_Return);
    require(session.baselineSaves == 1 && session.baselineSavedGeneration == generation && session.baselineStored, "explicit save did not carry its generation");
    require(item("working-baseline-message")->property("text") == "Saved on this device.", "save result not shown");
    require(untouched(), "baseline save called a Doctor, tuning, sync, report or lifecycle method");

    // A run change between opening and confirming is refused by the session.
    item("working-baseline-save")->forceActiveFocus(); press(Qt::Key_Return);
    ++session.baselineGeneration; // the run was replaced while the confirmation was open
    press(Qt::Key_Down); press(Qt::Key_Return);
    require(session.baselineSaves == 2 && session.baselineSavedGeneration != QString::number(session.baselineGeneration) &&
        item("working-baseline-message")->property("text").toString().startsWith("The stream changed"), "stale confirmation was accepted");

    // Compare is read-only: one compare call, no saves, clears or other authority.
    const int saves = session.baselineSaves, clears = session.baselineClears;
    item("working-baseline-compare")->forceActiveFocus(); press(Qt::Key_Return);
    require(session.baselineCompares == 1 && session.baselineSaves == saves && session.baselineClears == clears && untouched(), "Compare wrote or called another authority");
    require(item("working-baseline-comparison")->isVisible() && item("working-baseline-context-different")->isVisible() &&
        item("working-baseline-context-unknown")->isVisible(), "comparison or its specific context notes are hidden");
    const auto differentText = item("working-baseline-context-different")->property("text").toString();
    const auto unknownText = item("working-baseline-context-unknown")->property("text").toString();
    require(differentText.contains("Video codec") && unknownText.contains("Nova version") &&
        !differentText.contains("like for like") && !unknownText.contains("like for like"), "context notes are not specific or carry a verdict");
    capture("working-baseline-compare-1280.png");

    // Clear also opens on the safe choice and cancels without effect.
    item("working-baseline-clear")->forceActiveFocus(); press(Qt::Key_Return);
    require(opened("working-baseline-clear-confirmation") && focusName() == "working-baseline-keep", "clear confirmation did not focus Keep baseline");
    press(Qt::Key_Escape); require(session.baselineClears == clears, "Escape cleared the baseline");
    item("working-baseline-clear")->forceActiveFocus(); press(Qt::Key_Return); press(Qt::Key_Down); press(Qt::Key_Return);
    require(session.baselineClears == clears + 1 && !session.baselineStored, "explicit clear failed");

    // Back returns focus to the row while playing with controls shown.
    item("working-baseline-back")->forceActiveFocus(); press(Qt::Key_Return); QTest::qWait(80);
    require(!opened("working-baseline-popup") && focusName() == "native-working-baseline", "Back did not restore the row focus");

    // Stale current readings: Save is disabled and focus starts elsewhere.
    session.baselineFresh = false;
    openBaseline();
    require(!item("working-baseline-save")->isEnabled() && focusName() != "working-baseline-save", "stale readings left Save enabled");
    press(Qt::Key_Escape); QTest::qWait(80);
    session.baselineFresh = true;

    // An open page follows the HUD: opened stale with a saved baseline, Save enables
    // when a fresh sample arrives and disables again when it goes stale, without
    // reopening; Clear stays available and nothing is written.
    {
        const int saves = session.baselineSaves, clears = session.baselineClears, compares = session.baselineCompares;
        session.baselineStored = true; session.baselineFresh = false;
        openBaseline();
        require(!item("working-baseline-save")->isEnabled() && item("working-baseline-clear")->isEnabled() &&
            item("working-baseline-waiting")->isVisible(), "stale page did not show the waiting reason");
        const int reads = session.baselineStatusReads;
        session.baselineFresh = true; emit session.hudChanged(); QTest::qWait(80);
        require(opened("working-baseline-popup") && item("working-baseline-save")->isEnabled() && !item("working-baseline-waiting")->isVisible() &&
            session.baselineStatusReads > reads, "fresh HUD did not enable Save on the open page");
        item("working-baseline-save")->forceActiveFocus(); QTest::qWait(50);
        session.baselineFresh = false; emit session.hudChanged(); QTest::qWait(80);
        require(!item("working-baseline-save")->isEnabled() && item("working-baseline-clear")->isEnabled() &&
            item("working-baseline-waiting")->isVisible() && focusName() == "working-baseline-compare", "stale HUD left Save enabled or focus on a disabled Save");
        require(session.baselineSaves == saves && session.baselineClears == clears && session.baselineCompares == compares && untouched(),
            "live status refresh wrote or called another authority");
        press(Qt::Key_Escape); QTest::qWait(80);
        session.baselineStored = false; session.baselineFresh = true;
    }

    // Known storage refusals disable Save and explain storage, rather than suggesting
    // that fresh readings could fix an unreadable or unsafe index. A malformed entry
    // in an otherwise readable index still offers explicit Clear without freshness.
    {
        const int saves = session.baselineSaves, clears = session.baselineClears, compares = session.baselineCompares;
        for (const char* reason : {"index_unreadable", "storage_unsafe", "storage_unavailable"}) {
            session.baselineReason = reason;
            openBaseline();
            require(!item("working-baseline-save")->isEnabled() && !item("working-baseline-compare")->isEnabled() &&
                !item("working-baseline-clear")->isEnabled() && !item("working-baseline-waiting")->isVisible() &&
                !item("working-baseline-status")->property("text").toString().isEmpty() && focusName() == "working-baseline-back",
                "known storage refusal offered Save or a misleading readings wait");
            emit session.hudChanged(); QTest::qWait(80);
            require(!item("working-baseline-save")->isEnabled(), "fresh HUD enabled Save on refused storage");
            session.baselineFresh = false; emit session.hudChanged(); QTest::qWait(80);
            require(!item("working-baseline-save")->isEnabled() && !item("working-baseline-waiting")->isVisible(),
                "refused storage suggested that waiting for readings could fix it");
            press(Qt::Key_Escape);
            session.baselineFresh = true;
        }
        session.baselineReason = "baseline_unreadable"; session.baselineFresh = false;
        openBaseline();
        require(!item("working-baseline-save")->isEnabled() && !item("working-baseline-compare")->isEnabled() &&
            item("working-baseline-clear")->isEnabled(), "malformed entry could not be cleared without fresh readings");
        item("working-baseline-clear")->forceActiveFocus(); press(Qt::Key_Return);
        require(opened("working-baseline-clear-confirmation") && focusName() == "working-baseline-keep",
            "malformed entry clear lost safe confirmation focus");
        press(Qt::Key_Escape); press(Qt::Key_Escape);
        require(session.baselineSaves == saves && session.baselineClears == clears && session.baselineCompares == compares && untouched(),
            "storage status or cancelled maintenance wrote or called another authority");
        session.baselineReason.clear(); session.baselineFresh = true;
    }

    // Hidden controls and a stopped stream close every baseline popup.
    openBaseline(); press(Qt::Key_Return); require(opened("working-baseline-save-confirmation"), "confirmation fixture failed");
    session.resumeInput(); QTest::qWait(120);
    require(!opened("working-baseline-popup") && !opened("working-baseline-save-confirmation") && session.baselineSaves == 2,
        "hidden controls left a baseline popup open or saved");
    session.showControls(); QTest::qWait(80);
    openBaseline(); press(Qt::Key_Return);
    session.transition("stopping", true, "Ending the game…"); QTest::qWait(120);
    require(!opened("working-baseline-popup") && !opened("working-baseline-save-confirmation") && session.baselineSaves == 2,
        "stream stop left a baseline popup open or saved");
    session.transition("active", true, "Your game is connected."); session.showControls(); QTest::qWait(80);

    // No stable identity: Save and Compare are unavailable, with an honest reason.
    session.baselineAvailable = false;
    openBaseline();
    require(!item("working-baseline-save")->isEnabled() && !item("working-baseline-compare")->isEnabled() &&
        item("working-baseline-status")->property("text").toString().contains("no stable game identity"), "unscoped stream offered a baseline");
    press(Qt::Key_Escape); QTest::qWait(80);
    session.baselineAvailable = true;

    // Large text keeps 48 px targets and the focused control in view.
    QMetaObject::invokeMethod(root.get(), "largeText");
    for (const QSize size : {QSize(960, 600), QSize(360, 640)}) {
        window->resize(size); QTest::qWait(150);
        auto* row = item("native-working-baseline"); row->forceActiveFocus(); QTest::qWait(80);
        const auto rowRect = row->mapRectToScene(row->boundingRect());
        require(focusName() == "native-working-baseline" && rowRect.top() >= 0 && rowRect.bottom() <= window->height() && row->height() >= 48,
            "large-text baseline row escaped the viewport or shrank");
        press(Qt::Key_Return);
        for (const char* name : {"working-baseline-save", "working-baseline-compare", "working-baseline-clear", "working-baseline-back"}) {
            auto* target = item(name);
            if (!target->isEnabled()) continue;
            target->forceActiveFocus(); QTest::qWait(80);
            const auto rect = target->mapRectToScene(target->boundingRect());
            require(target->height() >= 48 && rect.top() >= 0 && rect.bottom() <= window->height(), "large-text baseline action escaped the viewport");
        }
        capture(size.width() == 960 ? "working-baseline-large-960.png" : "working-baseline-large-360.png");
        press(Qt::Key_Escape); QTest::qWait(80);
    }
    require(session.starts == 1 && session.stops == 0 && session.disconnects == 0, "baseline UI changed the stream lifecycle");
    std::cout << "working baseline QML flow passed\n";
    return 0;
}
