#pragma once
#include <QObject>
#include <QSocketNotifier>
#include <functional>
#include <memory>
#include <cstdint>
namespace nova::deck::runtime {
// Absolute CLOCK_MONOTONIC Linux deadline. No GUI sleeps or millisecond rounding.
class DeckPresentationDeadline final : public QObject {
public:
    explicit DeckPresentationDeadline(std::function<void()> callback, QObject* parent = nullptr);
    ~DeckPresentationDeadline() override;
    bool available() const { return fd_ >= 0; }
    bool arm(std::int64_t monotonicNs);
    void cancel();
private:
    int fd_ = -1;
    std::unique_ptr<QSocketNotifier> notifier_;
    std::function<void()> callback_;
};
}
