#pragma once

#include <QObject>
#include <QPointer>
#include <QScreen>
#include <QVariantMap>
#include <QWindow>
#include <atomic>
#include <functional>
#include <memory>
#include <vector>

namespace nova::deck::runtime {

// Reports the window's current compositor mode, not the panel's maximum mode
// or proof of presented frames. No monitor identity is exposed to QML.
class DeckDisplayCapabilities final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    using RefreshReader = std::function<double(QScreen*)>;
    explicit DeckDisplayCapabilities(RefreshReader reader = {}, QObject* parent = nullptr);
    ~DeckDisplayCapabilities() override;
    void watchWindow(QWindow* window);
    void refresh();
    QVariantMap state() const { return state_; }
    // Safe to retain on the session worker; destruction returns to SDR60.
    std::function<int()> rateLimitReader() const;
signals:
    void stateChanged();
private:
    void watchScreen();
    void applyRate(double rate);
    RefreshReader reader_;
    QPointer<QWindow> window_;
    QPointer<QScreen> screen_;
    std::vector<QMetaObject::Connection> windowConnections_, screenConnections_;
    std::shared_ptr<std::atomic<int>> rateLimit_ = std::make_shared<std::atomic<int>>(60);
    QVariantMap state_;
};

} // namespace nova::deck::runtime
