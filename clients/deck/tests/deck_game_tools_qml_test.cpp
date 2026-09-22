#include "deck_game_tools_fixture.h"
#include "runtime/deck_play_settings.h"
#include "runtime/deck_game_shortcuts.h"
#include <QGuiApplication>
#include <QElapsedTimer>
#include <QQmlEngine>
#include <QQmlContext>
#include <QQmlComponent>
#include <QQuickWindow>
#include <QQuickItem>
#include <QQuickImageProvider>
#include <QTest>
#include <QTemporaryDir>
#include <QPainter>
#include <iostream>
using namespace nova::deck::runtime;
namespace {
void check(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
void settle() { QTest::qWait(150); }
void wait(const std::function<bool()>& done) { QElapsedTimer t; t.start(); while (!done() && t.elapsed() < 6000) QTest::qWait(20); check(done(),"UI wait timed out"); settle(); }
QQuickItem* find(QQuickItem* parent, const QString& name) { if (parent->objectName() == name) return parent; for (auto* child : parent->childItems()) if (auto* result = find(child,name)) return result; return nullptr; }
class Artwork final : public QQuickImageProvider {
public: Artwork(): QQuickImageProvider(Image) {}
QImage requestImage(const QString& id,QSize* size,const QSize&) override {
    QImage img(300,450,QImage::Format_RGB32); img.fill(QColor("#254459")); QPainter p(&img);
    p.setPen(QColor("#F3DDB5")); p.setFont(QFont("Sans",24)); p.drawText(img.rect(),Qt::AlignCenter, "MOONLIT\nHARBOR\n" + id);
    if(size)*size=img.size(); return img;
}};
}
int main(int argc,char** argv) {
    QTemporaryDir config; qputenv("XDG_CONFIG_HOME",config.path().toUtf8()); QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("GameTools");
    game_tools_fixture::Host host; DeckGameTools tools; DeckPlaySettings settings(config.filePath("play.ini")); DeckGameShortcuts shortcuts;
    settings.setVideoDecodeSupport({.h264={4096,4096},.hevc={1920,1200}});
    tools.setTarget("host",host.resolver());
    tools.setPreviewPublisher([](const auto&,const auto&,QVariantList items) { int index=0; for(auto& raw:items) {auto value=raw.toMap(); value.remove("previewPath"); value["preview"]="image://art/"+QString::number(index++);raw=value;}return items;});
    QQmlEngine engine; engine.addImageProvider("art",new Artwork);
    engine.rootContext()->setContextProperty("tools",&tools); engine.rootContext()->setContextProperty("settings",&settings); engine.rootContext()->setContextProperty("shortcuts",&shortcuts);
    bool warnings=false;
    QObject::connect(&engine,&QQmlEngine::warnings,[&](const QList<QQmlError>& errors){warnings=true;for(const auto& error:errors)std::cerr<<error.toString().toStdString()<<'\n';});
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \""+QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded()+"\"\n"+R"(
        ApplicationWindow {
            width:1280; height:800; visible:true; color:NovaTheme.window
            function large() { NovaTheme.setFontScale(1.3) }
            function art() { studio.open() }
            function steam() { steamSheet.open() }
            PlaySetup {
                id:setup; anchors.fill:parent; settingsProvider:settings; gameTools:tools
                hostId:"host"; gameId:"game"; hostName:"Living Room PC"; gameTitle:"Moonlit Harbor"
                displayCapabilities: ({known:true,refreshHz:90})
                Component.onCompleted: prepare()
            }
            ArtworkStudio { id:studio; controller:tools; settingsProvider:settings; hostId:"host"; game:({id:"game",title:"Moonlit Harbor",poster:"image://art/current",hero:"image://art/backdrop",logo:"",icon:""}) }
            GameShortcut { id:steamSheet; controller:shortcuts; game:({id:"game",title:"Moonlit Harbor"}) }
        }
    )",QUrl());
    auto root=std::unique_ptr<QObject>(component.create()); if(!root)std::cerr<<component.errorString().toStdString(); check(bool(root),"QML failed");
    auto* window=qobject_cast<QQuickWindow*>(root.get()); check(window,"no window");
    const auto item=[&](const char* name){auto* result=find(window->contentItem(),name);check(result,name);return result;};
    const auto capture=[&](const char* name){if(argc>1){QDir().mkpath(argv[1]);check(window->grabWindow().save(QString::fromLocal8Bit(argv[1])+"/"+name+".png"),"capture failed");}};
    wait([&]{return !tools.busy();});
    check(!warnings,"initial QML warnings");
    auto* encoder=item("play-setup-encoder"); encoder->forceActiveFocus(); settle(); QTest::keyClick(window,Qt::Key_Return);settle();
    auto* picker=root->findChild<QObject*>("play-setup-picker");check(picker && picker->property("opened").toBool(),"encoder picker not open");
    QTest::keyClick(window,Qt::Key_Down);QTest::keyClick(window,Qt::Key_Return);wait([&]{return !tools.busy();});
    check(settings.load("host","game")["configuration"].toMap()["encoderBackend"]=="vaapi","D-pad encoder not saved");
    check(window->activeFocusItem()==encoder,"host review stole encoder focus");
    item("play-setup-tuning")->forceActiveFocus();QTest::keyClick(window,Qt::Key_Return);settle();QTest::keyClick(window,Qt::Key_Down);QTest::keyClick(window,Qt::Key_Return);wait([&]{return !tools.busy();});
    check(settings.load("host","game")["configuration"].toMap()["profilePreference"]=="quality","preset not saved");
    auto* setup = root->findChild<QObject*>("play-setup");
    const auto effectiveFps = [&] { QVariant state; check(QMetaObject::invokeMethod(setup,"state",Q_RETURN_ARG(QVariant,state)),"setup state unavailable");
        return state.toMap()["streamPlan"].toMap()["configuration"].toMap()["fps"].toInt(); };
    check(settings.saveChoice("host","game",{{"profilePreference","high_fps"}}),"High FPS save failed");
    QMetaObject::invokeMethod(setup,"prepare"); wait([&]{return !tools.busy();}); check(effectiveFps()==60,"High FPS bypassed unknown host frame-rate support");
    setup->setProperty("streamCapabilities",QVariantMap{{"h264",true},{"maxFps",120}}); settle();
    check(effectiveFps()==90,"High FPS ignored available display and host rates");
    setup->setProperty("displayCapabilities",QVariantMap{{"known",true},{"refreshHz",75}}); settle();
    check(effectiveFps()==75,"High FPS ignored current 75 Hz mode");
    setup->setProperty("displayCapabilities",QVariantMap{{"known",true},{"refreshHz",90}}); settle();
    check(settings.saveChoice("host","game",{{"fps",30}}),"explicit FPS save failed");
    QMetaObject::invokeMethod(setup,"prepare"); wait([&]{return !tools.busy();}); check(effectiveFps()==30,"preset overrode explicit frame rate");
    check(settings.resetChoice("host","game","fps") && settings.saveChoice("host","game",{{"profilePreference","quality"}}),"fixture reset failed");
    QMetaObject::invokeMethod(setup,"prepare"); wait([&]{return !tools.busy();}); item("play-setup-tuning")->forceActiveFocus(); settle();
    capture("play-setup-1280");
    QMetaObject::invokeMethod(root.get(),"large");window->resize(960,600);settle();capture("play-setup-960-large");
    QMetaObject::invokeMethod(root.get(),"art");wait([&]{return !tools.busy();});
    auto* search=item("artwork-search");search->forceActiveFocus();QTest::keyClick(window,Qt::Key_Return);wait([&]{return !tools.busy();});
    QTest::keyClick(window,Qt::Key_Down);settle();check(window->activeFocusItem()==item("artwork-result-0"),"search to candidate focus lost");
    QTest::keyClick(window,Qt::Key_Return);wait([&]{return !tools.busy();});
    auto* choice=item("artwork-result-0");choice->forceActiveFocus();settle();
    auto* touch=QTest::createTouchDevice();const auto point=choice->mapToScene(QPointF(choice->width()/2,choice->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,point,window).commit();QTest::touchEvent(window,touch).release(0,point,window).commit();settle();
    check(tools.state()["canApply"].toBool() && host.writes==0,"touch preview saved before apply");
    capture("artwork-960-large");window->resize(1280,800);settle();capture("artwork-1280-large");
    item("artwork-apply")->forceActiveFocus();QTest::keyClick(window,Qt::Key_Return);wait([&]{return !tools.busy();});if(host.writes!=1) std::cerr << "writes=" << host.writes << " state=" << QJsonDocument::fromVariant(tools.state()).toJson().toStdString(); check(host.writes==1,"explicit apply not sent once");
    QTest::keyClick(window,Qt::Key_Escape);settle();
    QMetaObject::invokeMethod(root.get(),"steam");settle();capture("steam-entry-1280-large");
    QTest::keyClick(window,Qt::Key_Escape);settle();
    check(!warnings,"production screens emitted QML warnings");
    std::cout<<"Play Setup and Artwork Studio D-pad/touch, explicit apply, 130% text and Steam sheet passed.\n";
}
