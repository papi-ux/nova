#include <QColor>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QFont>
#include <QGuiApplication>
#include <QImage>
#include <QJSValue>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QSettings>
#include <QTemporaryDir>
#include <QTest>
#include <cmath>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <memory>

namespace {
void require(bool value, const char* message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}
bool near(double a, double b) { return std::abs(a - b) < 0.0001; }
void settle() { QTest::qWait(90); }
void wait(const std::function<bool()>& ready) {
    QElapsedTimer clock; clock.start();
    while (!ready() && clock.elapsed() < 3000) QTest::qWait(10);
    require(ready(), "appearance fixture timed out"); settle();
}
QVariantMap saved() {
    QSettings settings; settings.sync(); QVariantMap result;
    for (const auto& key : settings.allKeys()) result[key] = settings.value(key);
    return result;
}
void seed(const QString& config, const QString& name, int percent) {
    // Each application name has a new native INI store. Write raw bytes before
    // any QSettings/QQmlEngine read, so cold numeric strings cannot be hidden by
    // a same-process typed setter cache.
    const auto filename = QDir(config).filePath("NovaDeckTests/" + name + ".conf");
    require(QDir().mkpath(QFileInfo(filename).absolutePath()), "raw appearance store directory failed");
    QFile file(filename); require(file.open(QIODevice::WriteOnly), "raw appearance store open failed");
    const auto ini = QString("[Appearance]\ntextScale=%1\n\n[InGameControls]\ncommandCenterButton=false\nshortcutHint=false\nmenuOpacityPercent=64\n\n[Unrelated]\nsentinel=preserve-me\n")
        .arg(QString::number(percent / 100., 'g', 4)).toUtf8();
    require(file.write(ini) == ini.size(), "raw appearance store write failed"); file.close();
    QSettings settings;
    require(QFileInfo(settings.fileName()).absoluteFilePath() == QFileInfo(filename).absoluteFilePath(), "raw INI did not address the actual native store");
}
QQuickItem* visual(QQuickItem* root, const QString& name) {
    if (!root->isVisible()) return nullptr;
    if (root->objectName() == name) return root;
    for (auto* child : root->childItems()) if (auto* found = visual(child, name)) return found;
    return nullptr;
}
struct Scene {
    std::unique_ptr<QQmlEngine> engine = std::make_unique<QQmlEngine>();
    std::unique_ptr<QQmlComponent> component = std::make_unique<QQmlComponent>(engine.get());
    std::unique_ptr<QObject> root;
    QQuickWindow* window = nullptr;
    bool warnings = false;
    QString captures;
    explicit Scene(QString directory = {}) : captures(std::move(directory)) {
        QObject::connect(engine.get(), &QQmlEngine::warnings, [&](const QList<QQmlError>& errors) {
            warnings = true; for (const auto& error : errors) std::cerr << error.toString().toStdString() << '\n';
        });
        // Every named type below exists on staging522. Missing new controls are
        // asserted after creation, so the unchanged-source RED is not a QML
        // compile error caused by importing a future component.
        component->setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    id: root
    width: 1280; height: 800; visible: true; color: NovaTheme.window
    property int taps: 0
    NovaButton { objectName: "appearance-probe-button"; x:32; y:32; text:"Controls"; unit:1; onClicked:root.taps++ }
    AppearanceSettings { id: appearance; unit:1 }
    function openAppearance() { appearance.open() }
    function changeFont(value) { NovaTheme.setFontScale(value) }
    function changeControl(value) { NovaTheme.setControlSize(value) }
    function state() {
        return {font:NovaTheme.fontScale, theme:NovaTheme.themeId, label:NovaTheme.label,
            size:typeof NovaTheme.controlSize === "undefined" ? "missing" : NovaTheme.controlSize,
            scale:typeof NovaTheme.controlScale === "undefined" ? -1 : NovaTheme.controlScale,
            opacity:typeof NovaStreamPreferences.menuOpacityPercent === "undefined" ? -1 : NovaStreamPreferences.menuOpacityPercent,
            window:String(NovaTheme.window), text:String(NovaTheme.text), focus:String(NovaTheme.focus), focusText:String(NovaTheme.focusText),
            commandButton:NovaStreamPreferences.commandCenterButton, shortcut:NovaStreamPreferences.shortcutHint}
    }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/AppearanceFixture.qml"));
        require(component->isReady(), qPrintable(component->errorString()));
        root.reset(component->create()); require(bool(root), qPrintable(component->errorString()));
        window = qobject_cast<QQuickWindow*>(root.get()); require(window, "appearance window missing"); settle();
    }
    QVariantMap state() {
        QVariant value; require(QMetaObject::invokeMethod(root.get(), "state", Q_RETURN_ARG(QVariant, value)), "appearance state callback missing");
        return value.metaType() == QMetaType::fromType<QJSValue>() ? value.value<QJSValue>().toVariant().toMap() : value.toMap();
    }
    void call(const char* method) { require(QMetaObject::invokeMethod(root.get(), method), method); settle(); }
    void call(const char* method, const QVariant& value) { require(QMetaObject::invokeMethod(root.get(), method, Q_ARG(QVariant, value)), method); settle(); }
    QObject* popup(const char* name) { auto* value = root->findChild<QObject*>(name); require(value, name); return value; }
    QQuickItem* item(const QString& name) { auto* value = visual(window->contentItem(), name); require(value, qPrintable(name)); return value; }
    QRectF bounds(QQuickItem* value) { return value->mapRectToScene(value->boundingRect()); }
    void contained(QQuickItem* value) {
        const auto rect = bounds(value);
        require(rect.width() >= 47.99 && rect.height() >= 47.99, "appearance control lost its 48px target");
        require(QRectF(0, 0, window->width(), window->height()).adjusted(-1, -1, 1, 1).contains(rect), "appearance control escaped the viewport");
        for (auto* ancestor = value->parentItem(); ancestor; ancestor = ancestor->parentItem())
            if (ancestor->clip()) require(bounds(ancestor).adjusted(-1, -1, 1, 1).contains(rect), "appearance control was clipped by its scrolling parent");
        auto* label = qobject_cast<QQuickItem*>(value->property("contentItem").value<QObject*>());
        if (label && label->property("paintedHeight").isValid()) {
            require(label->property("paintedHeight").toReal() <= label->height() + 1 &&
                    label->property("paintedWidth").toReal() <= label->width() + 1 && !label->property("truncated").toBool(),
                "appearance control clipped its rendered label");
        }
    }
    void focus(const QString& name) { item(name)->forceActiveFocus(); settle(); require(window->activeFocusItem() == item(name), "appearance control refused controller focus"); }
    void activate(const QString& name, bool touch = false, bool edge = false) {
        focus(name); auto* target = item(name); contained(target);
        if (touch) {
            static auto* device = QTest::createTouchDevice();
            const auto point = target->mapToScene(QPointF(target->width()/2, target->height()/2 - (edge ? 23 : 0))).toPoint();
            QTest::touchEvent(window, device).press(0, point, window).commit();
            QTest::touchEvent(window, device).release(0, point, window).commit();
        } else QTest::keyClick(window, Qt::Key_Return);
        settle();
    }
    void open(const QString& button, QObject* sheet) {
        activate(button); wait([&] { return sheet->property("opened").toBool(); });
    }
    void edit(const QString& field, const QString& text, bool keyboard = false) {
        focus(field);
        if (keyboard) {
            QTest::keyClick(window, Qt::Key_A, Qt::ControlModifier); QTest::keyClick(window, Qt::Key_Backspace);
            for (const auto digit : text) {
                require(digit.isDigit(), "numeric keyboard fixture requires digits");
                QTest::keyClick(window, static_cast<Qt::Key>(Qt::Key_0 + digit.digitValue()));
            }
        } else require(item(field)->setProperty("text", text), "numeric draft setter missing");
        settle(); require(item(field)->property("text").toString() == text, "numeric draft did not receive exact input");
    }
    void capture(const QString& name) {
        if (captures.isEmpty()) return;
        require(QDir().mkpath(captures), "appearance capture directory failed");
        const auto image = window->grabWindow(); require(!image.isNull() && image.save(captures + "/" + name + ".png"), "appearance frame capture failed");
    }
    void clean() { require(!warnings, "appearance production QML emitted warnings"); }
};
void fresh(const QString& captures) {
    Scene ui(captures); const auto state = ui.state();
    require(near(state["font"].toDouble(), .8), "fresh Linux appearance did not default to 80% text");
    require(state["size"] == "standard" && near(state["scale"].toDouble(), .88), "fresh Linux appearance lost Standard density");
    require(state["opacity"].toInt() == 64, "fresh menu opacity did not default to 64%");
    ui.contained(ui.item("appearance-probe-button")); ui.capture("appearance-fresh-80"); ui.clean();
}
void legacy(const QString& config) {
    for (int percent : {100, 115, 130}) {
        const auto name = QString("Appearance247-legacy-%1").arg(percent); QCoreApplication::setApplicationName(name);
        seed(config, name, percent); const auto before = saved();
        { Scene ui; const auto state = ui.state();
          require(near(state["font"].toDouble(), percent / 100.), "restart migration changed saved 100/115/130% text");
          require(state["size"] == "standard" && near(state["scale"].toDouble(), .88), "legacy partial record lost Standard density");
          require(!state["commandButton"].toBool() && !state["shortcut"].toBool(), "appearance startup rewrote unrelated in-game choices");
          require(state["opacity"].toInt() == 64, "cold INI opacity did not load its exact saved percentage"); ui.clean(); }
        require(before == saved(), "appearance startup changed partial or unrelated persisted values");
    }
}
void controls(const QString& captures) {
    Scene ui(captures); ui.call("openAppearance");
    auto* appearance = ui.popup("appearance-settings-popup"); wait([&] { return appearance->property("opened").toBool(); });
    ui.focus("appearance-theme-high_contrast"); QTest::keyClick(ui.window, Qt::Key_Down); settle();
    require(ui.window->activeFocusItem() == ui.item("appearance-control-size-compact"), "D-pad did not reach interface sizes from themes");
    const int font = ui.item("appearance-probe-button")->property("font").value<QFont>().pixelSize();
    qreal widths[3]; int i = 0;
    for (const auto& choice : {QString("compact"), QString("standard"), QString("large")}) {
        ui.activate("appearance-control-size-" + choice, choice == "large");
        const auto state = ui.state(); require(state["size"] == choice && near(state["font"].toDouble(), .8), "density action changed text size or selected the wrong density");
        auto* probe = ui.item("appearance-probe-button"); widths[i++] = probe->width();
        require(probe->property("font").value<QFont>().pixelSize() == font && probe->height() >= 48, "density scaled text or reduced minimum touch height");
    }
    require(widths[0] < widths[1] && widths[1] < widths[2], "shared button geometry ignored independent density");
    ui.activate("appearance-theme-director"); const auto director = ui.state();
    require(director["theme"] == "director" && director["label"] == "< Congratulations, Director >", "Director selector or literal label missing");
    require(QColor(director["window"].toString()) == QColor("#6E0B17") && QColor(director["text"].toString()) == QColor("#FFF5E8") &&
            QColor(director["focus"].toString()) == QColor("#FFF5E8") && QColor(director["focusText"].toString()) == QColor("#12090B"), "Director semantic palette did not reach production UI");
    QTest::keyClick(ui.window, Qt::Key_Escape); settle();
    ui.activate("appearance-probe-button", true, true); ui.activate("appearance-probe-button");
    require(ui.root->property("taps").toInt() == 2, "touch/controller shared button callback fired incorrectly");
    ui.call("changeFont", 1.3); require(ui.state()["size"] == "large" && near(ui.state()["scale"].toDouble(), 1.15) &&
        ui.item("appearance-probe-button")->property("font").value<QFont>().pixelSize() > font, "large text changed density or did not grow text");
    ui.call("changeFont", .81); require(near(ui.state()["font"].toDouble(), .81), "text setter did not accept one-percent values");
    ui.capture("appearance-director-independent-controls"); ui.clean();
    require(saved()["Appearance/controlSize"] == "large" && saved()["Appearance/themeId"] == "director", "density or Director choices were not persisted");
}
void popups(const QString& captures) {
    Scene ui(captures); auto* text = ui.popup("text-size-settings-popup"); auto* menu = ui.popup("menu-opacity-settings-popup");
    ui.call("openAppearance"); wait([&] { return ui.popup("appearance-settings-popup")->property("opened").toBool(); });
    const auto base = QString("text-size-settings-popup"); ui.open("appearance-text-size", text);
    require(ui.window->activeFocusItem() == ui.item(base + "-increase") && !ui.item(base + "-decrease")->isEnabled(), "80% text editor did not focus its enabled increase action");
    ui.activate(base + "-increase"); require(ui.item(base + "-value")->property("text") == "81" && near(ui.state()["font"].toDouble(), .8), "one-percent text draft applied before Save");
    ui.activate(base + "-decrease", true); require(ui.item(base + "-value")->property("text") == "80" &&
        ui.window->activeFocusItem() == ui.item(base + "-value"), "minimum step did not preserve focus on the value field");
    QTest::keyClick(ui.window, Qt::Key_Down); settle();
    require(ui.window->activeFocusItem() == ui.item(base + "-reset"), "minimum value could not reach Reset with D-pad");
    QTest::keyClick(ui.window, Qt::Key_Down); settle();
    require(ui.window->activeFocusItem() == ui.item(base + "-save"), "minimum value could not reach Save with D-pad");
    QTest::keyClick(ui.window, Qt::Key_Return); wait([&] { return !text->property("opened").toBool(); });
    require(near(ui.state()["font"].toDouble(), .8), "minimum endpoint Save changed the value");
    ui.open("appearance-text-size", text); const auto untouched = saved();
    for (const auto& invalid : {QString(""), QString("79"), QString("131"), QString("NaN")}) {
        ui.edit(base + "-value", invalid); require(!ui.item(base + "-save")->isEnabled(), "invalid text draft enabled Save");
        ui.focus(base + "-value"); QTest::keyClick(ui.window, Qt::Key_Return); settle();
        require(text->property("opened").toBool() && untouched == saved(), "invalid text draft saved or closed the editor");
    }
    ui.edit(base + "-value", "82", true); ui.activate(base + "-save", true); wait([&] { return !text->property("opened").toBool(); });
    require(near(ui.state()["font"].toDouble(), .82) && near(saved()["Appearance/textScale"].toDouble(), .82), "touch Save did not apply/persist exact text draft");
    ui.open("appearance-text-size", text); ui.edit(base + "-value", "129", true); ui.activate(base + "-increase");
    require(ui.item(base + "-value")->property("text") == "130" && !ui.item(base + "-increase")->isEnabled() &&
        ui.window->activeFocusItem() == ui.item(base + "-value"), "maximum step lost its exact value or endpoint focus");
    QTest::keyClick(ui.window, Qt::Key_Down); settle();
    require(ui.window->activeFocusItem() == ui.item(base + "-reset"), "maximum value could not reach Reset with D-pad");
    QTest::keyClick(ui.window, Qt::Key_Down); settle();
    require(ui.window->activeFocusItem() == ui.item(base + "-save"), "maximum value could not reach Save with D-pad");
    QTest::keyClick(ui.window, Qt::Key_Return); wait([&] { return !text->property("opened").toBool(); });
    require(near(ui.state()["font"].toDouble(), 1.3), "maximum endpoint Save did not apply130%");
    ui.open("appearance-text-size", text); const auto beforeCancel = saved(); ui.activate(base + "-reset");
    require(ui.item(base + "-value")->property("text") == "80" && near(ui.state()["font"].toDouble(), 1.3), "text reset applied before Save");
    ui.capture("appearance-text-130-reset-draft"); ui.activate(base + "-cancel", true);
    require(beforeCancel == saved() && near(ui.state()["font"].toDouble(), 1.3), "Cancel saved the reset text draft");
    const auto opacity = QString("menu-opacity-settings-popup"); ui.open("appearance-menu-opacity", menu);
    require(ui.window->activeFocusItem() == ui.item(opacity + "-decrease"), "menu editor lost initial enabled focus");
    ui.activate(opacity + "-increase"); require(ui.item(opacity + "-value")->property("text") == "65" && ui.state()["opacity"].toInt() == 64, "menu step applied before Save or was not one percent");
    const auto original = saved();
    for (const auto& invalid : {QString("-1"), QString("101"), QString("64.5")}) {
        ui.edit(opacity + "-value", invalid); require(!ui.item(opacity + "-save")->isEnabled() && original == saved(), "invalid menu draft changed saved opacity");
    }
    for (const auto& limit : {QString("0"), QString("100")}) {
        ui.edit(opacity + "-value", limit, true);
        require(!ui.item(opacity + (limit == "0" ? "-decrease" : "-increase"))->isEnabled(), "menu step did not respect its endpoint");
        ui.activate(opacity + "-save"); require(ui.state()["opacity"].toInt() == limit.toInt(), "menu endpoint Save was lost");
        ui.open("appearance-menu-opacity", menu);
    }
    const auto beforeMenuCancel = saved(); ui.activate(opacity + "-reset");
    require(ui.item(opacity + "-value")->property("text") == "64" && ui.state()["opacity"].toInt() == 100, "menu reset applied before Save");
    QTest::keyClick(ui.window, Qt::Key_Escape); settle(); require(beforeMenuCancel == saved() && !menu->property("opened").toBool(), "Back saved menu reset draft");
    ui.open("appearance-menu-opacity", menu); ui.activate(opacity + "-reset", true); ui.activate(opacity + "-save");
    require(ui.state()["opacity"].toInt() == 64 && saved()["InGameControls/menuOpacityPercent"] == 64, "menu reset Save did not persist64%");
    ui.capture("appearance-menu-reset-saved"); ui.clean();
}
void portrait(const QString& captures) {
    Scene ui(captures); ui.window->resize(400, 640); ui.call("changeFont", 1.3); ui.call("changeControl", QStringLiteral("large"));
    ui.call("openAppearance"); wait([&] { return ui.popup("appearance-settings-popup")->property("opened").toBool(); });
    for (const auto& name : {QString("appearance-theme-director"), QString("appearance-control-size-large"), QString("appearance-text-size"), QString("appearance-menu-opacity"), QString("appearance-done")}) {
        ui.focus(name); ui.contained(ui.item(name));
    }
    ui.capture("appearance-400-130-large");
    for (const auto& kind : {QString("text-size-settings-popup"), QString("menu-opacity-settings-popup")}) {
        auto* sheet = ui.popup(kind.toUtf8().constData()); ui.open(kind.startsWith("text") ? "appearance-text-size" : "appearance-menu-opacity", sheet);
        for (const auto& suffix : {QString("-value"), QString("-reset"), QString("-cancel"), QString("-save")}) {
            ui.focus(kind + suffix); ui.contained(ui.item(kind + suffix));
        }
        ui.capture(kind + "-400-130-large"); ui.activate(kind + "-cancel");
    }
    ui.clean();
}
}
int main(int argc, char** argv) {
    QTemporaryDir config; require(config.isValid(), "missing isolated appearance settings"); qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) qputenv("QT_QPA_PLATFORM", "offscreen"); qputenv("QT_QUICK_BACKEND", "software");
    QGuiApplication app(argc, argv); QCoreApplication::setOrganizationName("NovaDeckTests");
    const auto arguments = app.arguments(); QString selected = "all", captures;
    for (int i = 1; i < arguments.size(); ++i) {
        if (arguments[i] == "--case" && i + 1 < arguments.size()) selected = arguments[++i];
        else if (arguments[i] == "--capture-dir" && i + 1 < arguments.size()) captures = arguments[++i];
        else require(false, "unknown appearance fixture argument");
    }
    require(QStringList{"all", "fresh", "legacy", "controls", "popups", "portrait"}.contains(selected), "unknown appearance fixture case");
    for (const auto& name : {QString("fresh"), QString("legacy"), QString("controls"), QString("popups"), QString("portrait")}) {
        if (selected != "all" && selected != name) continue;
        QCoreApplication::setApplicationName("Appearance247-" + name);
        if (name == "fresh") fresh(captures);
        else if (name == "legacy") legacy(config.path());
        else if (name == "controls") controls(captures);
        else if (name == "popups") popups(captures);
        else portrait(captures);
        require(!saved().contains("Unrelated/sentinel") || saved()["Unrelated/sentinel"] == "preserve-me", "appearance callbacks corrupted unrelated preferences");
        std::cout << "Appearance247 " << name.toStdString() << " passed\n";
    }
}
