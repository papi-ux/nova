#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <iostream>
class Handoff : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    QVariantMap state() const { return {{"available", available}, {"armed", armed}, {"running", running}}; }
    Q_INVOKABLE void activate(const QString& host, const QString& game, const QString&) {
        ++activations;
        if (armed && host == reviewedHost && game == reviewedGame) { ++launches; armed = false; running = true; }
        else { reviewedHost = host; reviewedGame = game; armed = true; }
        emit stateChanged();
    }
    Q_INVOKABLE void cancel() { ++cancels; armed = false; emit stateChanged(); }
    void expire() { armed = false; emit stateChanged(); }
    bool available = true, armed = false, running = false;
    int activations = 0, launches = 0, cancels = 0;
    QString reviewedHost, reviewedGame;
signals:
    void stateChanged();
};
void require(bool ok, const char* copy) { if (!ok) { std::cerr << copy << '\n'; std::exit(1); } }
QQuickItem* find(QQuickItem* item, const QString& name) { if (item->objectName() == name && item->isVisible()) return item; for (auto* child : item->childItems()) if (auto* found = find(child, name)) return found; return nullptr; }
int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8()); QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("HandoffReview");
    Handoff backend; QQmlEngine engine; engine.rootContext()->setContextProperty("handoffBackend", &backend);
    bool warnings = false; QObject::connect(&engine, &QQmlEngine::warnings, [&](const QList<QQmlError>& errors) { warnings = true; for (const auto& error : errors) std::cerr << error.toString().toStdString() << '\n'; });
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width:960; height:600; visible:true; color:NovaTheme.window
    property string selectedGame:"game"
    property bool sheetOpen:sheet.opened
    MoonlightHandoffReview { id:sheet; controller:handoffBackend; hostId:"host"; gameId:selectedGame; gameTitle:"Moonlit Harbor" }
    function review() { sheet.review() }
    Component.onCompleted: NovaTheme.setFontScale(1.3)
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/HandoffTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "missing handoff review window");
    const auto item = [&](const char* name) { auto* found = find(window->contentItem(), name); require(found, name); return found; };
    const auto review = [&] { QMetaObject::invokeMethod(root.get(), "review"); QTest::qWait(80); };
    const auto click = [&](const char* name) { item(name)->forceActiveFocus(); QTest::qWait(50); QTest::keyClick(window, Qt::Key_Return); QTest::qWait(80); };
    QTest::qWait(80); require(backend.activations == 0, "loading review launched or armed Moonlight");
    review(); require(root->property("sheetOpen").toBool() && backend.activations == 1 && backend.launches == 0, "opening review skipped the first-arm boundary");
    const auto copy = item("moonlight-review-limits")->property("text").toString();
    for (const auto* word : {"NovaHUD", "Command Center", "Doctor", "Live Tuning", "Live Bitrate", "PyroWave"}) require(copy.contains(word), "handoff omitted a player-visible limitation");
    click("moonlight-review-back"); require(backend.cancels == 1 && !backend.armed && backend.launches == 0 && !root->property("sheetOpen").toBool(), "Back launched Moonlight or retained an armed request");
    review(); root->setProperty("selectedGame", "other"); QTest::qWait(80);
    require(!root->property("sheetOpen").toBool() && !backend.armed && backend.launches == 0, "game change retained old launch authority");
    review(); backend.expire(); QTest::qWait(80);
    require(!item("moonlight-review-continue")->isEnabled() && window->activeFocusItem() == item("moonlight-review-back"), "expired review retained confirm authority or lost Back focus");
    click("moonlight-review-back"); review();
    for (const auto* name : {"moonlight-review-continue", "moonlight-review-back"}) { auto* action = item(name); action->forceActiveFocus(); QTest::qWait(80); const auto rect = action->mapRectToScene(action->boundingRect()); require(rect.top() >= 0 && rect.bottom() <= window->height() && action->height() >= 48, "large-text handoff actions escaped viewport or touch target shrank"); }
    if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QString::fromLocal8Bit(argv[1]) + "/moonlight-review-960-large.png"), "handoff capture failed"); }
    click("moonlight-review-continue"); require(backend.launches == 1 && !root->property("sheetOpen").toBool(), "explicit second action did not launch exactly once");
    backend.running = false; backend.available = false; emit backend.stateChanged(); const int before = backend.activations; review();
    require(backend.activations == before && !root->property("sheetOpen").toBool(), "unavailable handoff opened or armed");
    require(!warnings, "handoff review emitted QML warnings");
    std::cout << "Moonlight limits before launch, cancellation, selection/expiry/unavailable gates and large-text focus passed\n";
}
#include "deck_handoff_review_qml_test.moc"
