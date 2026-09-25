#pragma once
#include <QPointer>
#include <QHash>
#include <QSettings>
#include <QTimer>
#include <QWindow>

namespace nova::deck::runtime {
// Coordinates the library and optional separate presentation window. Geometry
// is in Qt logical pixels; a removed monitor must never strand the next launch.
QRect deckWindowGeometry(QRect requested, QRect available);
class DeckWindowController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool fullscreen READ fullscreen NOTIFY changed)
public:
    explicit DeckWindowController(QObject* parent = nullptr);
    ~DeckWindowController() override;
    bool fullscreen() const { return fullscreen_; }
    void watchWindow(QWindow* window);
    Q_INVOKABLE void setFullscreen(bool value);
    Q_INVOKABLE void toggleFullscreen() { setFullscreen(!fullscreen_); }
signals:
    void changed();
    // Connected to the session before binding a window: held input is released
    // and Command Center stays open until the player explicitly resumes.
    void modeAboutToChange();
protected:
    bool eventFilter(QObject* watched, QEvent* event) override;
private:
    void rememberGeometry();
    void applyMode();
    void fitToScreen();
    void save();
    QSettings settings_;
    QPointer<QWindow> window_;
    QList<QMetaObject::Connection> connections_;
    struct WindowGeometry { QRect normal; bool maximized; };
    QHash<QWindow*, WindowGeometry> geometries_;
    QTimer geometryTimer_;
    QRect normalGeometry_;
    QString screenName_;
    bool fullscreen_ = false, maximized_ = false, applying_ = false, shortcutHeld_ = false;
};
}
