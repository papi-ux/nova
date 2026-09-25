#pragma once
#include <QObject>
#include <QWindow>
#include <memory>

namespace nova::deck::runtime {
// Native relative motion is independent of cursor position and video scaling.
// A capture belongs to one presentation window and is never a global input tap.
class DeckRelativePointer final : public QObject {
    Q_OBJECT
public:
    explicit DeckRelativePointer(QObject* parent = nullptr);
    ~DeckRelativePointer() override;
    bool available() const;
    bool active() const;
    bool pending() const;
    QString error() const;
    bool start(QWindow* window);
    void stop();
signals:
    void changed();
    void motion(double x, double y);
    void lost();
private:
    struct Impl;
    std::unique_ptr<Impl> d;
};
}
