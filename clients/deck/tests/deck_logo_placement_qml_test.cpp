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
    QCoreApplication::setOrganizationName("NovaDeckTests");QCoreApplication::setApplicationName("LogoPlacement");
    DeckPlaySettings settings(config.filePath("play.ini"));game_tools_fixture::Host host;DeckGameTools tools;
    tools.setTarget("host",host.resolver());
    QQmlEngine engine;engine.addImageProvider("art",new Art);engine.rootContext()->setContextProperty("settings",&settings);engine.rootContext()->setContextProperty("tools",&tools);
    bool warnings=false;QObject::connect(&engine,&QQmlEngine::warnings,[&](const QList<QQmlError>& errors){warnings=true;for(const auto& e:errors)std::cerr<<e.toString().toStdString()<<'\n';});
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \""+QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded()+"\"\n"+R"(
        ApplicationWindow {
            width:1280;height:800;visible:true;color:NovaTheme.window
            function large() { NovaTheme.setFontScale(1.3) }
            function switchGame() { studio.game = {id:"other",title:"Another game",logo:"image://art/logo"} }
            ArtworkStudio { id:studio;controller:tools;settingsProvider:settings;hostId:"host";game:({id:"game",title:"Moonlit Harbor",hero:"image://art/hero",logo:"image://art/logo",logoTransform:{scale:1.4,x:0.2,y:0.7}});Component.onCompleted:open() }
        }
    )",QUrl());
    auto root=std::unique_ptr<QObject>(component.create());if(!root)std::cerr<<component.errorString().toStdString();check(bool(root),"QML failed");
    auto* window=qobject_cast<QQuickWindow*>(root.get());check(window,"no window");
    const auto item=[&](const char* name){auto* p=find(window->contentItem(),name);check(p,name);return p;};
    const auto click=[&](const char* name){item(name)->forceActiveFocus();settle();QTest::keyClick(window,Qt::Key_Return);settle();};
    const auto capture=[&](const char* name){if(argc>1){QDir().mkpath(argv[1]);check(window->grabWindow().save(QString::fromLocal8Bit(argv[1])+"/"+name+".png"),"capture failed");}};
    wait([&]{return !tools.busy();});click("artwork-logo-placement");
    const auto fallback=QVariantMap{{"scale",1.4},{"x",0.2},{"y",0.7}};
    auto* preview=item("logo-placement-preview");wait([&]{return preview->property("status").toInt()==1;});
    check(preview->property("placement").toMap()==fallback,"host logo defaults lost in editor");
    QTest::keyClick(window,Qt::Key_Right);QTest::keyClick(window,Qt::Key_Right);QTest::keyClick(window,Qt::Key_Return);settle();
    check(window->activeFocusItem()==item("logo-larger") && preview->property("placement").toMap()["scale"]==1.5,"D-pad larger action failed");
    auto* touch=QTest::createTouchDevice();auto* left=item("logo-left");const auto point=left->mapToScene(QPointF(left->width()/2,left->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,point,window).commit();QTest::touchEvent(window,touch).release(0,point,window).commit();settle();
    check(preview->property("placement").toMap()["x"]==0.15,"touch placement change failed");
    check(settings.logoPlacement("host","game",fallback)==fallback && host.writes==0,"preview saved early or changed host");
    capture("logo-placement-1280");click("logo-cancel");check(settings.logoPlacement("host","game",fallback)==fallback,"Cancel saved draft");
    check(window->activeFocusItem()==item("artwork-logo-placement"),"focus did not return to Studio");
    click("artwork-logo-placement");click("logo-larger");click("logo-right");click("logo-save");
    const auto saved=settings.logoPlacement("host","game",fallback);
    check(saved["scale"]==1.5 && saved["x"]==0.25 && DeckPlaySettings(config.filePath("play.ini")).logoPlacement("host","game",fallback)==saved,"save/restart lost placement");
    click("artwork-logo-placement");QMetaObject::invokeMethod(root.get(),"large");window->resize(960,600);settle();click("logo-down");
    for(const char* name:{"logo-save","logo-cancel"}){auto* p=item(name);auto pos=p->mapToScene({0,0});check(pos.y()>=0 && pos.y()+p->height()<=600,"footer offscreen at 130%");}
    capture("logo-placement-960-large");click("logo-reset");click("logo-save");
    check(settings.logoPlacement("host","game",fallback)==QVariantMap{{"scale",1.0},{"x",0.5},{"y",0.5}},"layout reset did not persist");
    click("artwork-logo-placement");click("logo-larger");QMetaObject::invokeMethod(root.get(),"switchGame");settle();
    check(!find(window->contentItem(),"logo-save") && settings.logoPlacement("host","game",fallback)["scale"]==1.0,"game change saved or kept stale editor");
    check(host.writes==0 && !warnings,"logo controls wrote host or emitted QML warnings");
    std::cout<<"Logo preview, touch/controller, Cancel/Save/reset, host/game scope, restart, focus and 130% footer passed.\n";
}
