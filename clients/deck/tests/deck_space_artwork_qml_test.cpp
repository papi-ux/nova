#include "deck_game_tools_fixture.h"
#include "runtime/deck_play_settings.h"
#include <QGuiApplication>
#include <QQmlEngine>
#include <QQmlContext>
#include <QQmlComponent>
#include <QQuickWindow>
#include <QQuickItem>
#include <QQuickImageProvider>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <QPainter>
#include <QElapsedTimer>
#include <iostream>
using namespace nova::deck::runtime;
namespace {
void check(bool ok,const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
void settle() { QTest::qWait(130); }
void wait(const std::function<bool()>& done) { QElapsedTimer t; t.start(); while(!done() && t.elapsed()<6000)QTest::qWait(20);check(done(),"UI timed out");settle(); }
QQuickItem* find(QQuickItem* item,const QString& name) { if(item->objectName()==name && item->isVisible())return item;for(auto* child:item->childItems())if(auto* result=find(child,name))return result;return nullptr; }
class Art final : public QQuickImageProvider {
public: Art():QQuickImageProvider(Image) {}
QImage requestImage(const QString& id,QSize* size,const QSize&) override {
    QImage out(id=="logo"?QSize(600,180):QSize(800,450),QImage::Format_ARGB32);out.fill(id=="logo"?Qt::transparent:QColor("#17475d"));
    QPainter painter(&out);painter.setPen(QColor("#f3ddb5"));painter.setFont(QFont("Sans",32,QFont::Bold));
    if(id=="logo")painter.drawText(out.rect(),Qt::AlignCenter,"MOONLIT HARBOR");
    else { painter.fillRect(0,260,800,190,QColor("#0c2435"));painter.setBrush(QColor("#92bec9"));painter.drawEllipse(590,35,100,100); }
    if(size)*size=out.size();return out;
}};
}
int main(int argc,char** argv) {
    QTemporaryDir config;qputenv("XDG_CONFIG_HOME",config.path().toUtf8());QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");QCoreApplication::setApplicationName("SpaceArtwork");
    DeckPlaySettings settings(config.filePath("play.ini"));DeckGameTools tools;std::atomic<int> writes{0};
    tools.setTarget("host",[&]() -> std::optional<DeckGameToolsTarget> { return DeckGameToolsTarget{
        [&](const QString& game,const QString& action,const QVariantMap& values,const auto&) {
            check(game=="space.room.42" && action=="spaceRefresh" && values.isEmpty(),"Space UI requested a desktop action");++writes;
            return game_tools_fixture::ok(*nova::deck::polaris::gameToolReply(game,action,values,R"({"version":1,"revision":"two","assets":{},"resolution":{"status":"partial_failure","requested_kinds":["poster","hero"],"remaining_kinds":["hero"]}})"));
        },[](const auto&) { nova::deck::polaris::DeckPolarisGame game;game.id="space.room.42";game.spaceId="room";game.installed=true;return game_tools_fixture::ok(std::vector{game}); },[]{return true;}}; });
    QQmlEngine engine;engine.addImageProvider("art",new Art);engine.rootContext()->setContextProperty("settings",&settings);engine.rootContext()->setContextProperty("tools",&tools);
    bool warnings=false;QObject::connect(&engine,&QQmlEngine::warnings,[&](const QList<QQmlError>& errors){warnings=true;for(const auto& e:errors)std::cerr<<e.toString().toStdString()<<'\n';});
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \""+QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded()+"\"\n"+R"(
        ApplicationWindow {
            width:1280;height:800;visible:true;color:NovaTheme.window
            function large() { NovaTheme.setFontScale(1.3) }
            SpaceArtwork { id:studio;controller:tools;settingsProvider:settings;hostId:"host";game:({id:"space.room.42",title:"Moonlit Harbor",spaceName:"Game room",spaceId:"room",poster:"image://art/poster",hero:"image://art/hero",logo:"image://art/logo",icon:"image://art/icon"});Component.onCompleted:open() }
        }
    )",QUrl());
    auto root=std::unique_ptr<QObject>(component.create());if(!root)std::cerr<<component.errorString().toStdString();check(bool(root),"QML failed");
    auto* window=qobject_cast<QQuickWindow*>(root.get());check(window,"no window");
    const auto item=[&](const char* name){auto* p=find(window->contentItem(),name);check(p,name);return p;};
    const auto click=[&](const char* name){item(name)->forceActiveFocus();settle();QTest::keyClick(window,Qt::Key_Return);settle();};
    const auto capture=[&](const char* name){if(argc>1){QDir().mkpath(argv[1]);check(window->grabWindow().save(QString::fromLocal8Bit(argv[1])+"/"+name+".png"),"capture failed");}};
    wait([&]{return !tools.busy();});
    check(window->activeFocusItem()==item("space-artwork-kind-hero"),"Space preview initial focus missing");
    QTest::keyClick(window,Qt::Key_Down);QTest::keyClick(window,Qt::Key_Return);settle();
    check(window->activeFocusItem()==item("space-artwork-kind-logo"),"Space kind D-pad navigation failed");
    capture("space-artwork-1280");
    auto* button=item("space-artwork-refresh");auto* touch=QTest::createTouchDevice();auto point=button->mapToScene(QPointF(button->width()/2,button->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,point,window).commit();QTest::touchEvent(window,touch).release(0,point,window).commit();
    wait([&]{return writes==1 && !tools.busy();});
    check(tools.state()["copy"].toString().contains("backdrop") && window->activeFocusItem()==button,"partial result/focus missing");
    click("space-artwork-check");wait([&]{return !tools.busy();});check(writes==1,"Check again sent a POST");
    click("space-artwork-layout");click("logo-larger");click("logo-save");
    check(settings.logoPlacement("host","space.room.42")["scale"]==1.1 && settings.logoPlacement("host","space.other.42")["scale"]==1.0,"Space logo layout crossed destination identity");
    check(window->activeFocusItem()==item("space-artwork-layout") && writes==1,"Space layout wrote host or stranded focus");
    QMetaObject::invokeMethod(root.get(),"large");window->resize(960,600);settle();click("space-artwork-kind-icon");
    for(const char* name:{"space-artwork-refresh","space-artwork-check","space-artwork-layout"}){auto* p=item(name);auto pos=p->mapToScene({0,0});check(pos.y()>=0 && pos.y()+p->height()<=600,"Space footer offscreen");}
    capture("space-artwork-960-large");check(!find(window->contentItem(),"artwork-apply") && !find(window->contentItem(),"artwork-search"),"unsupported Space edits exposed");
    check(!warnings,"Space artwork emitted QML warnings");
    std::cout<<"Space artwork preview, touch refresh, partial result, GET check, scoped logo placement, D-pad focus and 130% footer passed.\n";
}
