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
#include <QRegularExpression>
#include <QTemporaryDir>
#include <QTest>
#include <cstdlib>
#include <iostream>
#include <memory>
namespace {
void require(bool ok,const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
// Repeater delegates belong to the Quick visual tree, which need not match
// QObject ownership. Use the same childItems traversal as the existing HUD test.
QQuickItem* visual(QQuickItem* parent, const QString& name) {
    if (parent->objectName() == name) return parent;
    for (auto* child : parent->childItems()) if (auto* found = visual(child, name)) return found;
    return nullptr;
}
// Clearly synthetic readings, shaped as the controller freezes them.
QVariantMap recap(const QString& generation, const QString& end = "end_game", const QString& host = "fixture-host") {
    return {{"generation",generation},{"hostId",host},{"gameId","fixture-game"},{"end",end},{"measuredForMs",723400},{"restarted",false},
        {"framesReceived",43210},{"refusedFrames",3},
        {"hostProcessing",QVariantMap{{"meanMs",4.21},{"frames",43100}}},{"videoWork",QVariantMap{{"meanMs",1.27},{"frames",43210}}},
        {"rates",QVariantMap{{"spanMs",722000},{"receivedFrames",43176},{"receivedFps",59.8},{"decodedFrames",43032},{"decodedFps",59.6},{"receivedVideoMbps",18.43}}},
        {"video",QVariantMap{{"codec","HEVC"},{"width",1280},{"height",800},{"decoder","VA-API"}}}};
}
}
int main(int argc,char** argv) {
    QTemporaryDir directory; require(directory.isValid(),"isolated settings missing");
    qputenv("XDG_CONFIG_HOME",directory.path().toUtf8());
    QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("StreamRecap");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream",0,1,"DeckVaapiPreviewSurface");
    PreviewSession session; PreviewPlayers players(session);
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264={4096,4096},.hevc={4096,4096}});
    // The session holds the reviewed PC and game, so a later Resume can match them.
    session.startConfigured("fixture-host","fixture-game",{});
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
    function reduceMotion(on) { NovaTheme.setReduceMotion(on) }
    function changeFont(value) { NovaTheme.setFontScale(value) }
    Component.onCompleted: { preview.open(); preview.attempted=true }
})",QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY)+"/StreamRecapTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root),qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window,"window missing"); QTest::qWait(150);
    const auto item = [&](const char* name) { auto* found=visual(window->contentItem(),QString::fromUtf8(name)); require(found,name); return found; };
    const auto text = [&](const char* name) { return item(name)->property("text").toString(); };
    auto* preview = root->findChild<QObject*>("native-stream-preview"); require(preview,"preview missing");
    auto* card = item("stream-recap"); auto* primary = item("native-preview-action");
    auto* scroll = item("stream-recap-scroll");
    const auto revealed = [&](QQuickItem* reading) {
        const auto top = reading->mapToItem(scroll, QPointF(0,0)).y();
        return top >= -1 && top + reading->height() <= scroll->height() + 1;
    };
    const auto focusName = [&] { return window->activeFocusItem() ? window->activeFocusItem()->objectName() : QString{}; };
    const auto press = [&](Qt::Key key) { QTest::keyClick(window,key); QTest::qWait(80); };
    const auto settle = [] { QTest::qWait(80); };
    const auto capture = [&](const char* name) {
        if (argc > 1) { require(QDir().mkpath(argv[1]),"capture directory failed"); require(window->grabWindow().save(QDir(argv[1]).filePath(name)),"PNG capture failed"); }
    };
    const auto lifecycleUntouched = [&](int starts, int resumes) {
        return session.starts==starts && session.resumes==resumes && session.reconnects==0 && session.disconnects==0 &&
            session.momentMarks==0 && session.reportExports==0 && session.baselineSaves==0 && session.tuningWrites==0;
    };
    require(QMetaObject::invokeMethod(root.get(),"reduceMotion",Q_ARG(QVariant,true)),"reduce motion setup failed");
    session.setStreamRecap(recap("1")); settle();
    require(!card->isVisible(),"recap covered active gameplay");
    session.stop(); settle();
    require(!card->isVisible(),"recap shown while the stream was still finishing");
    session.transition("stopped",false,"Stream ended."); settle();
    require(card->isVisible() && card->opacity()==1 && focusName()=="native-preview-action",
        "ended recap missing, animated under Reduce Motion, or stole focus");
    require(text("stream-recap-summary").contains("asked the PC to close the game") && text("stream-recap-summary").contains("at least 12 min 3 s"),
        "summary lost its end choice or lower-bound duration");
    require(text("stream-recap-received-value")=="59.8 fps" && text("stream-recap-received-detail").contains("over 12 min 2 s") &&
        text("stream-recap-payload-value")=="18.4 Mbps" && text("stream-recap-payload-detail").contains("not encoder bitrate"),
        "rates lost their span or were presented as encoder bitrate");
    require(text("stream-recap-missing-value")=="Unknown" && text("stream-recap-missing-detail")=="No current frame count",
        "missing frame-sequence counts became a number");
    require(text("stream-recap-host-value")=="4.2 ms avg" && text("stream-recap-host-detail").remove(QRegularExpression("[^0-9]"))=="43100" &&
        text("stream-recap-work-detail").contains("3 refused") && text("stream-recap-video").contains("HEVC · 1280×800 · VA-API"),
        "per-frame means lost their own coverage");
    capture("stream-recap-ended-1280.png");
    // Nothing here said it lost nothing: an unknown count earns no calm line and no badge (#423, #424).
    require(!item("stream-recap-nothing-missing")->isVisible() && !item("stream-recap-clear-skies")->isVisible() &&
        !item("stream-recap-fps-line")->isVisible(),"an unknown run claimed a clean count or drew a line");
    press(Qt::Key_Up); require(focusName()=="stream-recap-dismiss","Up from Back did not reach Dismiss");
    press(Qt::Key_Down); require(focusName()=="stream-recap-received","Down from Dismiss did not reach the readings");
    press(Qt::Key_Up); require(focusName()=="stream-recap-dismiss","Up from the first reading did not reach Dismiss");
    press(Qt::Key_Return);
    require(session.recapDismissals==1 && session.recapDismissedGeneration=="1" && !card->isVisible() && focusName()=="native-preview-action",
        "Dismiss lost its generation, kept the card or dropped focus");
    require(lifecycleUntouched(1,0),"recap invoked lifecycle, report or tuning actions");

    // A clean run says so calmly and shows its line; an hour of it earns the badge, and focus stays put.
    {
        auto clean = recap("2");
        clean["missingFrames"] = QVariantMap{{"percent",0.0},{"lost",0},{"expected",43176},{"partial",false}};
        clean["fpsTrail"] = QVariantList{58.0,60.0,59.0};
        session.setStreamRecap(clean); settle();
        require(card->isVisible() && item("stream-recap-nothing-missing")->isVisible() && text("stream-recap-nothing-missing")=="0.00% frames missing",
            "a clean run lost its calm line");
        require(!item("stream-recap-clear-skies")->isVisible(),"twelve minutes earned the Clear skies badge");
        require(item("stream-recap-fps-line")->isVisible() && text("stream-recap-fps-words").contains("58.0 to 60.0") && text("stream-recap-fps-words").contains("line averages"),
            "the FPS line or its words went missing");
        auto hour = clean; auto rates = hour["rates"].toMap(); rates["spanMs"] = 3600000; hour["rates"] = rates; hour["generation"] = "3";
        session.setStreamRecap(hour); settle();
        require(item("stream-recap-clear-skies")->isVisible(),"a clean hour lost its badge");
        auto lost = hour; lost["missingFrames"] = QVariantMap{{"percent",0.1},{"lost",1},{"expected",43176},{"partial",false}}; lost["generation"] = "4";
        session.setStreamRecap(lost); settle();
        require(!item("stream-recap-clear-skies")->isVisible() && !item("stream-recap-nothing-missing")->isVisible(),"a lost frame kept a clean claim");
        auto restarted = hour; restarted["missingFrames"] = QVariantMap{{"percent",0.0},{"lost",0},{"expected",100},{"partial",true}}; restarted["generation"] = "5";
        session.setStreamRecap(restarted); settle();
        require(!item("stream-recap-nothing-missing")->isVisible(),"a count of only part of the run claimed the whole");
        require(focusName()=="native-preview-action","the at-a-glance block took focus");
        session.setStreamRecap(recap("1")); settle();
    }

    // Scope: another PC's recap, a changed selection or sleep stays silent.
    session.setStreamRecap(recap("1","end_game","other-host")); settle();
    require(!card->isVisible(),"another PC's recap was shown");
    session.setStreamRecap(recap("1")); settle(); require(card->isVisible(),"matching recap was not shown");
    item("stream-recap-dismiss")->forceActiveFocus(); settle();
    preview->setProperty("gameId","other-game"); settle();
    require(!card->isVisible() && focusName()=="native-preview-action","changed selection kept the card or dropped focus");
    preview->setProperty("gameId","fixture-game"); settle();
    primary->forceActiveFocus(); press(Qt::Key_Up); press(Qt::Key_Down);
    require(focusName()=="stream-recap-received","reading focus setup failed");
    preview->setProperty("gameId","other-game"); settle();
    require(!card->isVisible() && focusName()=="native-preview-action","hiding a focused reading dropped focus");
    preview->setProperty("gameId","fixture-game"); settle();
    session.sleeping=true; session.transition("stopped",false,"Stream ended."); settle();
    require(!card->isVisible(),"recap shown while sleeping");
    session.sleeping=false; session.transition("stopped",false,"Stream ended."); settle();
    require(card->isVisible(),"recap did not return after wake on the same ended run");

    // Disconnect: the recap sits beside Resume; a fresh resume clears it.
    session.transition("disconnected",false,"Disconnected without ending the game.");
    session.setStreamRecap(recap("2","disconnect")); settle();
    require(card->isVisible() && primary->property("text")=="Resume game" && item("native-resume-return")->isVisible() &&
        text("stream-recap-summary").contains("without asking the PC to quit"), "disconnect recap or recovery actions wrong");
    capture("stream-recap-disconnected-1280.png");
    // A short screen with large text really overflows. Every controller focus
    // target must be revealed, including both video and scope text at the end.
    window->resize(960,600);
    require(QMetaObject::invokeMethod(root.get(),"changeFont",Q_ARG(QVariant,1.3)),"large text setup failed");
    QTest::qWait(150);
    require(scroll->property("contentHeight").toDouble() > scroll->height(),"short recap fixture did not overflow");
    primary->forceActiveFocus(); settle(); press(Qt::Key_Up); require(focusName()=="stream-recap-dismiss","Dismiss unreachable beside Resume");
    for (const char* key : {"received","payload","missing","host","work"}) {
        press(Qt::Key_Down);
        const auto name = QStringLiteral("stream-recap-") + key;
        require(focusName()==name && revealed(item(qPrintable(name))),"controller did not focus and reveal a recap metric");
    }
    press(Qt::Key_Down);
    require(focusName()=="stream-recap-footer" && revealed(item("stream-recap-video")) && revealed(item("stream-recap-scope")) &&
        scroll->property("contentY").toDouble() > 0,"controller did not reveal the footer and scope");
    press(Qt::Key_Up); require(focusName()=="stream-recap-work","footer Up did not return to the last reading");
    press(Qt::Key_Down); press(Qt::Key_Down);
    require(focusName()=="native-preview-action","footer Down did not return to Resume");
    item("stream-recap-footer")->forceActiveFocus(); settle();
    session.setStreamRecap({}); settle();
    require(!card->isVisible() && focusName()=="native-preview-action","clearing a focused footer dropped focus");
    session.setStreamRecap(recap("2","disconnect")); settle();
    require(card->isVisible() && focusName()=="native-preview-action","returning recap stole focus");
    require(lifecycleUntouched(1,0),"reading traversal invoked a lifecycle or report action");
    require(QMetaObject::invokeMethod(root.get(),"changeFont",Q_ARG(QVariant,0.8)),"text reset failed");
    window->resize(1280,800); QTest::qWait(150);
    press(Qt::Key_Return);
    require(session.resumes==1 && session.streamRecap().isEmpty() && !card->isVisible(),"fresh resume kept the previous run's recap");
    require(lifecycleUntouched(1,1),"recap changed lifecycle outside the explicit Resume");

    // With motion, the card fades in and settles opaque; it never stays hidden.
    require(QMetaObject::invokeMethod(root.get(),"reduceMotion",Q_ARG(QVariant,false)),"motion setup failed");
    session.transition("stopped",false,"Stream ended."); session.setStreamRecap(recap("3")); QTest::qWait(400);
    require(card->isVisible() && card->opacity()==1,"animated recap did not settle visible");
    for (const auto& size : QList<QSize>{{960,600},{1280,800}}) {
        window->resize(size); QTest::qWait(150);
        require(card->y() + card->height() <= primary->y(),"recap overlapped the return action");
    }
    std::cout << "stream recap fence, focus, unknowns, motion and layout checks passed\n";
}
