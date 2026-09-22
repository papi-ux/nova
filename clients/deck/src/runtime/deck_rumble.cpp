#include "runtime/deck_rumble.h"

#include <chrono>
#include <condition_variable>
#include <fcntl.h>
#include <mutex>
#include <thread>
#include <unistd.h>

namespace nova::deck::runtime {
using namespace std::chrono_literals;
struct DeckRumbleController::Impl {
    struct Joystick {
        explicit Joystick(int fd) : fd(fd) {}
        ~Joystick() { if (fd >= 0) ::close(fd); }
        int fd;
    };
    Factory factory;
    std::mutex mutex;
    std::condition_variable wake;
    std::shared_ptr<Joystick> joystick;
    DeckRumble wanted;
    std::uint64_t deviceGeneration = 0, revision = 0;
    bool closed = false;
    std::thread worker;

    explicit Impl(Factory factory) : factory(std::move(factory)), worker([this] { run(); }) {}
    ~Impl() {
        { const std::lock_guard lock(mutex); closed = true; wake.notify_all(); }
        worker.join();
    }
    void run() {
        std::unique_ptr<DeckRumbleDevice> device;
        std::uint64_t generation = 0, applied = 0;
        auto refresh = std::chrono::steady_clock::time_point::max();
        for (;;) {
            std::unique_lock lock(mutex);
            wake.wait_until(lock, refresh, [&] { return closed || deviceGeneration != generation || revision != applied; });
            if (closed) break;
            if (deviceGeneration != generation) {
                generation = deviceGeneration;
                auto selected = joystick;
                refresh = std::chrono::steady_clock::time_point::max();
                lock.unlock();
                device.reset(); // destroys/stops the old endpoint before opening a new one
                try { if (selected) device = factory(selected->fd); }
                catch (...) { device.reset(); }
                lock.lock();
                // No command is sent if unplug/stop arrived while open blocked.
                if (closed || generation != deviceGeneration) continue;
            }
            const auto value = wanted;
            applied = revision;
            lock.unlock();
            if (device) {
                const auto current = [&] {
                    const std::lock_guard check(mutex);
                    return !closed && deviceGeneration == generation && revision == applied;
                };
                try { if (!device->play(value, current)) device.reset(); }
                catch (...) { device.reset(); }
            }
            refresh = value && device ? std::chrono::steady_clock::now() + 250ms
                : std::chrono::steady_clock::time_point::max();
        }
        // Endpoint destruction removes its effect, even when no zero command
        // could be delivered. The device-side lease also bounds a stalled writer.
    }
};

DeckRumbleController::DeckRumbleController(Factory factory) : impl_(std::make_unique<Impl>(std::move(factory))) {}
DeckRumbleController::~DeckRumbleController() = default;
void DeckRumbleController::setDevice(int joystickFd) {
    const int duplicate = joystickFd < 0 ? -1 : ::fcntl(joystickFd, F_DUPFD_CLOEXEC, 0);
    auto selected = duplicate < 0 ? std::shared_ptr<Impl::Joystick>{} : std::make_shared<Impl::Joystick>(duplicate);
    const std::lock_guard lock(impl_->mutex);
    impl_->joystick = std::move(selected);
    impl_->wanted = {};
    ++impl_->deviceGeneration;
    ++impl_->revision;
    impl_->wake.notify_all();
}
void DeckRumbleController::submit(DeckRumble value) {
    const std::lock_guard lock(impl_->mutex);
    if (!impl_->joystick) return;
    impl_->wanted = value;
    ++impl_->revision;
    impl_->wake.notify_all();
}
}
