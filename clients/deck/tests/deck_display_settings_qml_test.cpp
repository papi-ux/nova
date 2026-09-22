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
#include <iostream>
using namespace nova::deck::runtime;
namespace {
void check(bool ok,const char* message) { if(!ok){std::cerr<<message<<'\n';std::abort();} }
void settle() { QTest::qWait(130); }
void wait(const std::function<bool()>& done) { QElapsedTimer t;t.start();while(!done()&&t.elapsed()<6000)QTest::qWait(20);check(done(),"UI timed out");settle(); }
QQuickItem* find(QQuickItem* parent,const QString& name) { if(parent->objectName()==name&&parent->isVisible())return parent;for(auto* child:parent->childItems())if(auto* result=find(child,name))return result;return nullptr; }
}
int main(int argc,char** argv) {
    QTemporaryDir config;qputenv("XDG_CONFIG_HOME",config.path().toUtf8());QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");QCoreApplication::setApplicationName("DisplaySettings");
    DeckPlaySettings settings(config.filePath("play.ini"));settings.setVideoDecodeSupport({.h264={4096,4096},.hevc={1920,1200}});
    auto current=*nova::deck::polaris::parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    std::atomic<int> writes{0};DeckHostSettingsController host;host.setPlaySettings(&settings);
    host.setTarget("host","Living Room PC",[&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget target;
        target.identityValid=[] {return true;};
        target.capabilities=[] {nova::deck::polaris::DeckPolarisCapabilities c;c.clientSettings=true;return game_tools_fixture::ok(c);};
        target.read=[&](const auto&){return game_tools_fixture::ok(current);};
        target.idle=[](const auto&){return game_tools_fixture::ok(true);};
        target.writeMode=[&](const auto&,const auto&){check(false,"unexpected topology change");return game_tools_fixture::ok(current);};
        target.writeResumeTimeout=[&](int seconds,const auto&){++writes;current.desiredResumeTimeout=seconds;current.effectiveResumeTimeout=seconds;current.revision="changed";return game_tools_fixture::ok(current);};
        return target;
    });host.setWindowActive(true);
    QQmlEngine engine;engine.rootContext()->setContextProperty("settings",&settings);engine.rootContext()->setContextProperty("host",&host);
    bool warnings=false;QObject::connect(&engine,&QQmlEngine::warnings,[&](const QList<QQmlError>& errors){warnings=true;for(const auto& e:errors)std::cerr<<e.toString().toStdString()<<'\n';});
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \""+QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded()+"\"\n"+R"(
        ApplicationWindow {
            width:1280;height:800;visible:true;color:NovaTheme.window
            function large() { NovaTheme.setFontScale(1.3) }
            PlaySetup { id:setup;anchors.fill:parent;settingsProvider:settings;hostSettingsController:host;hostId:"host";gameId:"game";gameTitle:"Moonlit Harbor";hostName:"Living Room PC";displayCapabilities:({known:true,refreshHz:90});streamCapabilities:({h264:true,maxFps:120});Component.onCompleted:prepare() }
        }
    )",QUrl());
    auto root=std::unique_ptr<QObject>(component.create());if(!root)std::cerr<<component.errorString().toStdString();check(bool(root),"QML failed");
    auto* window=qobject_cast<QQuickWindow*>(root.get());check(window,"no window");
    const auto item=[&](const char* name){auto* result=find(window->contentItem(),name);check(result,name);return result;};
    const auto click=[&](const char* name){item(name)->forceActiveFocus();settle();QTest::keyClick(window,Qt::Key_Return);settle();};
    const auto capture=[&](const char* name){if(argc>1){QDir().mkpath(argv[1]);check(window->grabWindow().save(QString::fromLocal8Bit(argv[1])+"/"+name+".png"),"capture failed");}};
    settle();check(!warnings,"initial QML warnings");
    click("play-setup-resolution");click("play-setup-choice-4");
    item("stream-profile-width")->setProperty("text","2561");item("stream-profile-height")->setProperty("text","1440");click("stream-profile-save");
    check(!item("stream-profile-error")->property("text").toString().isEmpty()&&!settings.load("host","game")["custom"].toBool(),"invalid custom size saved");
    item("stream-profile-width")->setProperty("text","2560");click("stream-profile-save");
    check(settings.load("host","game")["configuration"].toMap()["width"]==2560 && window->activeFocusItem()==item("play-setup-resolution"),"custom resolution/focus lost");
    click("play-setup-bitrate");click("play-setup-choice-4");
    auto* field=item("stream-profile-bitrate");field->forceActiveFocus();settle();
    auto* touch=QTest::createTouchDevice();const auto point=field->mapToScene(QPointF(field->width()/2,field->height()/2)).toPoint();
    QTest::touchEvent(window,touch).press(0,point,window).commit();QTest::touchEvent(window,touch).release(0,point,window).commit();settle();
    auto* entry=item("endpoint-keyboard-entry");entry->forceActiveFocus();QTest::keyClick(window,Qt::Key_A,Qt::ControlModifier);
    for(const auto key:{Qt::Key_2,Qt::Key_7,Qt::Key_Period,Qt::Key_5})QTest::keyClick(window,key);
    check(entry->property("text")=="27.5","numeric pad rejected decimal bitrate");
    click("endpoint-keyboard-done");capture("custom-bitrate-1280");click("stream-profile-save");
    check(settings.load("host","game")["configuration"].toMap()["bitrateKbps"]==27500&&writes==0,"local bitrate touched host");
    click("play-setup-rate");click("play-setup-choice-3");item("stream-profile-fps")->setProperty("text","45");click("stream-profile-save");
    check(settings.load("host","game")["configuration"].toMap()["fps"]==45,"custom frame rate not saved");
    click("play-setup-every-game");wait([&]{return !host.busy();});click("host-edit-defaults");
    item("stream-profile-width")->setProperty("text","1920");item("stream-profile-height")->setProperty("text","1200");item("stream-profile-fps")->setProperty("text","50");item("stream-profile-bitrate")->setProperty("text","22.5");
    QMetaObject::invokeMethod(root.get(),"large");window->resize(960,600);settle();item("stream-profile-fps")->forceActiveFocus();settle();capture("device-defaults-960-large");
    click("stream-profile-save");check(settings.streamDefaults()["fps"]==50&&settings.streamDefaults()["bitrateKbps"]==22500&&writes==0,"device defaults incorrectly saved");
    check(settings.load("host","game")["configuration"].toMap()["fps"]==45,"device defaults erased game override");
    click("host-resume-timeout");capture("resume-timeout-960-large");click("host-resume-timeout-600");wait([&]{return !host.busy();});
    check(writes==1&&current.desiredResumeTimeout==600,"timeout not sent once");
    window->resize(1280,800);settle();capture("every-game-1280-large");
    click("host-defaults-back");click("play-setup-rate");click("play-setup-choice-reset");
    check(settings.load("host","game")["configuration"].toMap()["fps"]==50
        && settings.load("host","game")["configuration"].toMap()["bitrateKbps"]==27500,"individual reset ignored new defaults or erased bitrate");
    click("play-setup-bitrate");click("play-setup-choice-5");item("stream-profile-bitrate")->setProperty("text","99");click("stream-profile-back");
    check(DeckPlaySettings(config.filePath("play.ini")).load("host","game")["configuration"].toMap()["bitrateKbps"]==27500,"cancel mutated saved choice");
    check(!warnings,"display/settings screens emitted warnings");
    std::cout<<"Custom stream/device scopes, numeric touch entry, controller focus, reset, timeout and large text passed.\n";
}
