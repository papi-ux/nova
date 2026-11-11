#include "runtime/deck_play_settings.h"
#include <QGuiApplication>
#include <QQmlEngine>
#include <QQmlContext>
#include <QQmlComponent>
#include <QQuickWindow>
#include <QQuickItem>
#include <QTest>
#include <QTemporaryDir>
#include <functional>
#include <iostream>
namespace {
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::abort(); } }
QQuickItem* find(QQuickItem* item, const QString& name) {
    if (item->objectName() == name) return item;
    for (auto* child : item->childItems()) if (auto* found = find(child, name)) return found;
    return nullptr;
}
}
int main(int argc, char** argv) {
    QTemporaryDir config; qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("Resume");
    nova::deck::runtime::DeckPlaySettings settings(config.filePath("play.ini"));
    QQmlEngine engine; engine.rootContext()->setContextProperty("settings", &settings);
    bool warnings = false;
    QObject::connect(&engine, &QQmlEngine::warnings, [&](const auto& errors) { warnings = true; for (const auto& e : errors) std::cerr << e.toString().toStdString() << '\n'; });
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \"" + QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded() + "\"\n" + R"(
        ApplicationWindow {
            id: window; width: 1280; height: 800; visible: true
            property int resumes: 0
            property int ends: 0
            property bool plainPlay: false
            QtObject {
                id: resume
                property var state: ({visible:true, hostId:"host", gameId:"game-42", title:"Fixture game", canEnd:true, canResume:true, busy:false})
                function endSession() { window.ends++; return true }
            }
            LibraryBrowser {
                id: library; anchors.fill: parent; settingsProvider: settings; resumeController: resume
                host: ({id:"host", displayName:"Fixture PC"})
                refreshState: ({busy:false, automatic:false, failed:false, destinationPlayable:true})
                games: [{id:"game-42", title:"Fixture game", source:"steam"}]
                onResumeRequested: game => { if (game.id === "game-42") window.resumes++ }
                onPlayRequested: window.plainPlay = true
            }
            function expire() { resume.state = {visible:false, hostId:"host", gameId:"", canEnd:false, busy:false} }
            function paused() { resume.state = {visible:true, hostId:"host", gameId:"game-42", title:"Fixture game", canEnd:true, busy:false} }
            function noEnd() { resume.state = {visible:true, hostId:"host", gameId:"game-42", title:"Fixture game", canEnd:false, busy:false} }
            function details() { library.openDetails(0) }
            function back() { library.back() }
            function motion() { NovaTheme.setReduceMotion(true) }
            function theme() { return NovaTheme.themeId }
            function setupNotice() { library.visible = false; setup.visible = true }
            QtObject { id: tools; property var state: ({steam:{notice:"Steam may ask you to choose a launch option on your PC."}}) }
            PlaySetup {
                id: setup; anchors.fill: parent; visible: false; settingsProvider: settings; gameTools: tools
                hostId:"host"; gameId:"game-42"
            }
            QtObject {
                id: session
                property var hud: ({hostFresh:true, diagnosticsRefreshing:false, canRefreshDiagnostics:false,
                    doctor:{available:true, title:"Frames are arriving unevenly.", group:"HOST", advice:"Cap the game's frame rate.", confidence:"high", evidence:[]}})
            }
            Doctor { id: doctor; session: session }
            NowPlayingPill {
                id: hostPill; visible: false; width: 300; compact: true; namePrefix: "host-card"
                sessionState: resume.state; game: ({id:"game-42", title:"Fixture game"})
                onResumeRequested: window.resumes++
                onEndRequested: resume.endSession()
            }
            function hostCard() { library.visible = false; hostPill.visible = true }
            function diagnosis() { setup.visible = false; doctor.open() }
            function evidence() { doctor.selectPage(1) }
            function doctorHud(step, proposal) {
                session.hud = {hostFresh:true, diagnosticsRefreshing:false, canRefreshDiagnostics:false, doctorProposal:proposal,
                    doctor:{available:true, hasStep:step, title:"Host sentence.", group:"NET", advice:"Host explanation.", confidence:"high", evidence:[]}}
            }
            function steady() { doctorHud(false, "") }
            function stepOnly() { doctorHud(true, "") }
            function stepOffered() { doctorHud(true, "Lower bitrate toward 16.0 Mbps.") }
        }
    )", QUrl());
    auto root = std::unique_ptr<QObject>(component.create());
    if (!root) std::cerr << component.errorString().toStdString();
    require(bool(root), "actual library QML failed");
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "no window");
    QTest::qWait(100);
    const auto item = [&](const char* name) { auto* value = find(window->contentItem(), name); require(value, name); return value; };
    const auto invoke = [&](const char* method) { require(QMetaObject::invokeMethod(root.get(), method), method); QTest::qWait(20); };
    QVariant theme; QMetaObject::invokeMethod(root.get(), "theme", Q_RETURN_ARG(QVariant, theme));
    require(theme == "polaris", "Aurora default changed"); invoke("motion");
    auto* resume = item("library-resume"); auto* end = item("library-end-session");
    require(item("library-now-playing")->isVisible() && resume->property("text") == "Resume" && end->property("text") == "End Session", "paused labels absent");
    item("library-host-button")->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Down);
    require(resume->hasActiveFocus(), "D-pad cannot reach Resume");
    QTest::keyClick(window, Qt::Key_Return); require(root->property("resumes") == 1 && !root->property("plainPlay").toBool(), "Resume launched Play");
    QTest::keyClick(window, Qt::Key_Right); require(end->hasActiveFocus(), "D-pad cannot reach End");
    QTest::keyClick(window, Qt::Key_Left); require(resume->hasActiveFocus(), "D-pad cannot return from End");
    invoke("noEnd"); QTest::keyClick(window, Qt::Key_Right);
    require(item("library-host-button")->hasActiveFocus(), "unavailable End trapped focus");
    // End Session asks first, with the Command Center's question, and only End game sends it (#463).
    const auto asking = [&](const char* prefix) {
        QTest::qWait(60);
        auto* dialog = root->findChild<QObject*>(QString(prefix) + "-end-confirmation");
        return dialog && dialog->property("opened").toBool();
    };
    const auto focusName = [&] { return window->activeFocusItem() ? window->activeFocusItem()->objectName() : QString(); };
    invoke("paused"); resume->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Right);
    QTest::keyClick(window, Qt::Key_Return);
    require(root->property("ends") == 0, "one press of End Session ended the game without asking");
    require(asking("library") && focusName() == "library-end-stay", "End Session did not ask with Keep playing focused");
    auto* stay = root->findChild<QQuickItem*>("library-end-stay"); auto* confirm = root->findChild<QQuickItem*>("library-end-confirm");
    require(stay && confirm && stay->property("text") == "Keep playing" && confirm->property("text") == "End game", "End question is not the Command Center's");
    QTest::keyClick(window, Qt::Key_Return);
    require(!asking("library") && root->property("ends") == 0 && end->hasActiveFocus() && item("library-now-playing")->isVisible(),
            "Keep playing sent End, hid the session or lost focus");
    QTest::keyClick(window, Qt::Key_Return); require(asking("library"), "End Session did not ask a second time");
    invoke("back");
    require(!asking("library") && root->property("ends") == 0 && end->hasActiveFocus(), "Back did not leave the question without ending");
    QTest::keyClick(window, Qt::Key_Return); require(asking("library"), "End Session did not ask a third time");
    QTest::keyClick(window, Qt::Key_Down); require(focusName() == "library-end-confirm", "End game unreachable");
    QTest::keyClick(window, Qt::Key_Return);
    require(!asking("library") && root->property("ends") == 1, "End game did not send exactly one End");
    invoke("expire"); require(!item("library-now-playing")->isVisible(), "expired pill retained");
    require(window->activeFocusItem() && window->activeFocusItem()->objectName() == "game-42", "expired pill stranded controller focus");
    invoke("paused"); invoke("details");
    auto* play = item("game-detail-play"); require(play->property("text") == "Resume" && play->hasActiveFocus(), "game detail did not offer Resume");
    QTest::keyClick(window, Qt::Key_Return); require(root->property("resumes") == 2, "detail Resume not routed");
    QTest::keyClick(window, Qt::Key_Right); require(item("game-detail-end-session")->hasActiveFocus(), "detail End unreachable");
    QTest::keyClick(window, Qt::Key_Return);
    require(root->property("ends") == 1 && asking("game-detail") && focusName() == "game-detail-end-stay", "detail End Session did not ask first");
    QTest::keyClick(window, Qt::Key_Return);
    require(!asking("game-detail") && root->property("ends") == 1 && item("game-detail-end-session")->hasActiveFocus(), "detail Keep playing sent End or lost focus");
    QTest::keyClick(window, Qt::Key_Return); require(asking("game-detail"), "detail End Session did not ask again");
    QTest::keyClick(window, Qt::Key_Down); QTest::keyClick(window, Qt::Key_Return);
    require(!asking("game-detail") && root->property("ends") == 2 && item("game-detail-end-session")->hasActiveFocus(), "detail End game did not send exactly one End");
    invoke("expire"); require(play->property("text") == "Play" && play->hasActiveFocus(), "expired detail lost Play label or focus");
    invoke("paused"); invoke("hostCard");
    auto* hostResume = item("host-card-resume"); hostResume->forceActiveFocus();
    QTest::keyClick(window, Qt::Key_Return); require(root->property("resumes") == 3, "host-card Resume not routed");
    QTest::keyClick(window, Qt::Key_Right); require(item("host-card-end-session")->hasActiveFocus(), "host-card End unreachable");
    QTest::keyClick(window, Qt::Key_Return);
    require(root->property("ends") == 2 && asking("host-card") && focusName() == "host-card-end-stay", "host-card End Session did not ask first");
    QTest::keyClick(window, Qt::Key_Escape);
    require(!asking("host-card") && root->property("ends") == 2 && item("host-card-end-session")->hasActiveFocus(), "host-card Escape sent End or lost focus");
    QTest::keyClick(window, Qt::Key_Return); require(asking("host-card"), "host-card End Session did not ask again");
    QTest::keyClick(window, Qt::Key_Down); QTest::keyClick(window, Qt::Key_Return);
    require(!asking("host-card") && root->property("ends") == 3, "host-card End game did not send exactly one End");
    invoke("setupNotice"); require(item("play-setup-steam-notice")->property("text").toString().contains("launch option"), "Steam notice missing");
    invoke("diagnosis"); auto* confidence = item("doctor-confidence"); require(!confidence->isVisible(), "confidence remained inline");
    // #465: the host's own words, framed as a step only when the host has one.
    auto* tryFirst = item("doctor-try-first"); auto* advice = item("doctor-advice"); auto* proposal = item("doctor-proposal");
    const auto shown = [&](const char* text) {
        std::function<bool(QQuickItem*)> walk = [&](QQuickItem* node) {
            if (node->isVisible() && node->property("text").toString().contains(text)) return true;
            for (auto* child : node->childItems()) if (walk(child)) return true;
            return false;
        };
        return walk(item("doctor-scroll"));
    };
    invoke("steady");
    require(item("doctor-title")->property("text") == "Host sentence." && advice->isVisible() && advice->property("text") == "Host explanation.",
            "host sentence or explanation missing from a finding that needs no action");
    require(!tryFirst->isVisible() && !proposal->isVisible() && !shown("Try first") && !shown("No verified live fix"),
            "a finding that needs no action was framed as a step or told to review guidance");
    invoke("stepOnly");
    require(tryFirst->isVisible() && tryFirst->property("text") == "Try first" && advice->isVisible() && advice->property("text") == "Host explanation.",
            "the host's step lost its heading");
    require(!proposal->isVisible() && !shown("No verified live fix"), "the host's step was contradicted");
    invoke("stepOffered");
    require(tryFirst->isVisible() && proposal->isVisible() && proposal->property("text").toString().contains("16.0 Mbps"), "offered fix not shown under the host's step");
    invoke("evidence"); require(confidence->isVisible(), "confidence unavailable one level deeper");
    require(!warnings, "QML warnings occurred");
    std::cout << "Resume/End labels, End confirmation and backing out, controller focus, expiry, Steam notice, Doctor framing and depth passed\n";
}
