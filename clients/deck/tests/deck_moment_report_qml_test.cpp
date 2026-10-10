#include "runtime/deck_play_settings.h"
#include "stream/deck_stream_media_adapters.h"
#include "deck_preview_fixture.h"
#include <QDir>
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QSet>
#include <QTemporaryDir>
#include <QTest>
#include <cstdlib>
#include <iostream>
#include <memory>
namespace {
void require(bool ok,const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
}
int main(int argc,char** argv) {
    QTemporaryDir directory; require(directory.isValid(),"isolated settings missing");
    qputenv("XDG_CONFIG_HOME",directory.path().toUtf8());
    QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("MomentReport");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream",0,1,"DeckVaapiPreviewSurface");
    PreviewSession session; PreviewPlayers players(session);
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264={4096,4096},.hevc={4096,4096}});
    session.transition("active",true,"Streaming fixture");
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("testSession",&session);
    engine.rootContext()->setContextProperty("testPlayers",&players);
    engine.rootContext()->setContextProperty("testSettings",&settings);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width:1280; height:800; visible:true
    NativeStreamPreview { id:preview; session:testSession; inputHub:testPlayers; settingsProvider:testSettings
        hostId:"fixture-host"; gameId:"fixture-game"; gameTitle:"Moonlit Harbor"; hostName:"Living Room PC" }
    function largeText() { NovaTheme.setFontScale(1.3) }
    Component.onCompleted: { preview.open(); preview.attempted=true }
})",QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY)+"/MomentReportTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root),qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window,"window missing"); QTest::qWait(150);
    const auto item = [&](const char* name) { auto* found=root->findChild<QQuickItem*>(name); require(found,name); return found; };
    auto* popup = root->findChild<QObject*>("moment-report-popup"); require(popup,"moment popup missing");
    auto* preview = root->findChild<QObject*>("native-stream-preview"); require(preview,"preview missing");
    const auto opened = [&] { return popup->property("opened").toBool(); };
    const auto focusName = [&] { return window->activeFocusItem() ? window->activeFocusItem()->objectName() : QString{}; };
    const auto press = [&](Qt::Key key) { QTest::keyClick(window,key); QTest::qWait(80); };
    const auto capture = [&](const char* name) {
        if (argc > 1) { require(QDir().mkpath(argv[1]),"capture directory failed"); require(window->grabWindow().save(QDir(argv[1]).filePath(name)),"PNG capture failed"); }
    };
    const auto untouched = [&] {
        return session.reportExports==0 && session.tuningWrites==0 && session.bitrateWrites==0 && session.syncWrites==0 &&
            session.diagnosticsRefreshes==0 && session.doctorApplies==0 && session.doctorUndos==0 && session.doctorChecks==0 &&
            session.stops==0 && session.disconnects==0 && session.starts==0 && session.resumes==0 && session.reconnects==0 &&
            session.baselineSaves==0 && session.baselineClears==0 && session.baselineCompares==0 && session.baselineStatusReads==0;
    };
    const auto openMoment = [&] {
        const int before = session.momentMarks;
        item("native-report-moment")->forceActiveFocus(); QTest::qWait(50); press(Qt::Key_Return);
        require(opened() && session.momentMarks==before+1 && session.momentPending && focusName()=="moment-report-keep-playing", "mark/open/safe focus failed");
    };
    const auto explicitSave = [&] {
        const int before = session.momentSaves; const auto token=session.momentToken;
        item("moment-report-save")->forceActiveFocus(); QTest::qWait(30); press(Qt::Key_Return);
        require(session.momentSaves==before+1 && session.momentSavedToken==token,"explicit Save did not carry the frozen token exactly once");
    };
    item("native-working-baseline")->forceActiveFocus(); QTest::qWait(30); press(Qt::Key_Down);
    require(focusName()=="native-report-moment","baseline Down lost moment row");
    press(Qt::Key_Up); require(focusName()=="native-working-baseline","moment Up lost baseline row");
    press(Qt::Key_Down); press(Qt::Key_Down); require(focusName()=="native-preview-action","moment Down lost Close");
    // A refused mark creates no pending report and must not claim frozen bytes.
    session.setMomentFocus(false);
    item("native-report-moment")->forceActiveFocus(); QTest::qWait(30); press(Qt::Key_Return);
    require(!opened() && !session.momentPending && item("native-moment-mark-error")->property("text").toString().contains("Nothing was marked") &&
        !item("native-moment-mark-error")->property("text").toString().contains("frozen"), "refused mark claims a frozen report");
    session.setMomentFocus(true);
    session.momentMarkResult="too_large";
    item("native-report-moment")->forceActiveFocus(); press(Qt::Key_Return);
    require(!opened() && !session.momentPending && item("native-moment-mark-error")->property("text").toString().contains("Nothing was marked"), "oversize mark opened/claimed a report");
    session.momentMarkResult="marked";
    openMoment(); capture("moment-current-1280.png");
    require(item("moment-report-storage-copy")->property("text").toString().contains("nothing is sent automatically") &&
        item("moment-report-window")->property("text").toString().contains("3 samples") &&
        item("moment-report-window")->property("text").toString().contains("4.2 s") &&
        item("moment-report-window")->property("text").toString().contains("sampled readings, not continuous"),"privacy/sample-window copy wrong");
    const int beforeCancel = session.momentSaves;
    press(Qt::Key_Escape);
    require(!opened() && !session.momentPending && session.momentSaves==beforeCancel && focusName()=="native-report-moment","Escape saved/failed to restore row");
    openMoment(); require(QMetaObject::invokeMethod(preview,"leave"),"controller Back dispatch failed"); QTest::qWait(80);
    require(!opened() && !session.momentPending && session.momentSaves==beforeCancel,"physical Back route saved/kept pending");
    openMoment(); QTest::mouseClick(window,Qt::LeftButton,Qt::NoModifier,QPoint(4,4)); QTest::qWait(80);
    require(!opened() && !session.momentPending && session.momentSaves==beforeCancel,"outside press saved/kept pending");
    openMoment(); press(Qt::Key_Return);
    require(!opened() && !session.momentPending && session.momentSaves==beforeCancel,"Keep playing saved/kept pending");
    require(session.momentDiscards==4,"cancel path did not discard once");
    openMoment(); press(Qt::Key_Down); require(focusName()=="moment-report-save","Save not reachable");
    const auto beforeFocusToken = session.momentToken;
    session.setMomentFocus(false); QTest::qWait(80);
    require(opened() && session.momentPending && session.momentToken==beforeFocusToken && !item("moment-report-save")->isEnabled() &&
        focusName()=="moment-report-keep-playing","focus loss closed/dropped pending or kept disabled Save focus");
    capture("moment-focus-lost-1280.png");
    session.setMomentFocus(true); QTest::qWait(80);
    require(item("moment-report-save")->isEnabled() && session.momentToken==beforeFocusToken,"focus restore lost frozen pending");
    emit session.controlsChanged(); QTest::qWait(80);
    require(opened() && focusName()=="moment-report-keep-playing","controller hint notification stole popup focus");
    explicitSave(); require(item("moment-report-done")->isVisible() && !item("moment-report-save")->isVisible() &&
        !session.momentPending && focusName()=="moment-report-done","saved state permits second Save or misses Done focus");
    capture("moment-saved-1280.png");
    const int beforeDone=session.momentSaves; press(Qt::Key_Return);
    require(!opened() && session.momentSaves==beforeDone,"Done saved another file");
    session.momentState="stale"; openMoment();
    require(item("moment-report-state")->property("text").toString().contains("snapshot contains no observed readings") && item("moment-report-save")->isEnabled(),
        "stale state invented current readings or blocked evidence save");
    capture("moment-stale-1280.png"); press(Qt::Key_Escape); session.momentState="current";
    QSet<QString> messages;
    for (const auto* status : {"saved","saved_durability_unknown","too_large","storage_unavailable","storage_unsafe","storage_busy",
            "storage_full","write_failed","inactive","stream_changed","no_pending"}) {
        session.momentSaveResult=status; openMoment(); explicitSave();
        const auto message=item("moment-report-message")->property("text").toString();
        require(!message.isEmpty() && !messages.contains(message),"save outcomes share missing/generic copy"); messages.insert(message);
        if (QString(status)=="saved_durability_unknown") {
            require(item("moment-report-done")->isVisible() && !item("moment-report-save")->isVisible() && !session.momentPending &&
                message.contains("report file was saved") && message.contains("No second copy"),"durability outcome suggests retry/no file");
            capture("moment-durability-1280.png");
        }
        require(untouched(),"moment UI invoked unrelated host/settings/baseline/lifecycle methods");
        press(Qt::Key_Escape);
    }
    session.momentSaveResult="saved";
    openMoment(); const int savedBeforeHide=session.momentSaves; session.resumeInput(); QTest::qWait(80);
    require(!opened() && !session.momentPending && session.momentSaves==savedBeforeHide,"hidden controls saved/kept popup pending"); session.showControls(); QTest::qWait(80);
    openMoment(); session.transition("stopping",true,"Fixture stopping"); QTest::qWait(80);
    require(!opened() && !session.momentPending && session.momentSaves==savedBeforeHide,"inactive run saved/kept popup pending");
    session.transition("active",true,"Streaming fixture"); QTest::qWait(80);
    require(QMetaObject::invokeMethod(root.get(),"largeText"),"large text setup failed");
    for (const auto& dimensions : QList<QSize>{{960,600},{360,640}}) {
        window->resize(dimensions); QTest::qWait(100); openMoment();
        for (const auto* name : {"moment-report-keep-playing","moment-report-save"}) {
            auto* button=item(name); button->forceActiveFocus(); QTest::qWait(100);
            const auto point=button->mapToScene(QPointF{});
            require(button->height()>=48 && button->width()>=48 && point.x()>=0 && point.y()>=0 &&
                point.x()+button->width()<=window->width() && point.y()+button->height()<=window->height(),"large-text focused target too small/outside viewport");
        }
        capture(dimensions.width()==960 ? "moment-large-960.png" : "moment-large-360.png");
        explicitSave();
        auto* done=item("moment-report-done"); const auto donePoint=done->mapToScene(QPointF{});
        require(done->isVisible() && focusName()=="moment-report-done" && done->height()>=48 && done->width()>=48 &&
            donePoint.x()>=0 && donePoint.y()>=0 && donePoint.x()+done->width()<=window->width() && donePoint.y()+done->height()<=window->height(),
            "large-text Done target outside viewport");
        press(Qt::Key_Escape);
    }
    require(untouched(),"moment UI acquired other authority");
    std::cout << "moment report QML flow passed; distinct outcomes=" << messages.size() << "; physical acceptance unrun\n";
    return 0;
}
