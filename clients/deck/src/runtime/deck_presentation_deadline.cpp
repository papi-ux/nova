#include "runtime/deck_presentation_deadline.h"
#ifdef __linux__
#include <sys/timerfd.h>
#include <unistd.h>
#endif
namespace nova::deck::runtime {
DeckPresentationDeadline::DeckPresentationDeadline(std::function<void()> callback, QObject* parent)
    : QObject(parent), callback_(std::move(callback)) {
#ifdef __linux__
    fd_ = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (fd_ < 0) return;
    notifier_ = std::make_unique<QSocketNotifier>(fd_, QSocketNotifier::Read, this);
    connect(notifier_.get(), &QSocketNotifier::activated, this, [this] {
        std::uint64_t expirations = 0;
        if (read(fd_, &expirations, sizeof(expirations)) == sizeof(expirations) && expirations) callback_();
    });
#endif
}
DeckPresentationDeadline::~DeckPresentationDeadline() {
    cancel(); notifier_.reset();
#ifdef __linux__
    if (fd_ >= 0) close(fd_);
#endif
}
bool DeckPresentationDeadline::arm(std::int64_t ns) {
#ifdef __linux__
    if (fd_ < 0 || ns <= 0) return false;
    itimerspec timer{}; timer.it_value.tv_sec = ns / 1'000'000'000; timer.it_value.tv_nsec = ns % 1'000'000'000;
    return timerfd_settime(fd_, TFD_TIMER_ABSTIME, &timer, nullptr) == 0;
#else
    (void)ns; return false;
#endif
}
void DeckPresentationDeadline::cancel() {
#ifdef __linux__
    if (fd_ >= 0) { itimerspec timer{}; timerfd_settime(fd_, 0, &timer, nullptr); }
#endif
}
}
