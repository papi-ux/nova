#include "runtime/deck_rumble_linux.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QTemporaryDir>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <fcntl.h>
#include <iostream>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <vector>
#ifdef __linux__
#include <linux/input.h>
#include <linux/joystick.h>
#include <sys/sysmacros.h>
#endif

using namespace nova::deck::runtime;
using namespace std::chrono_literals;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
template<class Predicate> void until(Predicate predicate) {
    const auto deadline = std::chrono::steady_clock::now() + 3s;
    while (!predicate() && std::chrono::steady_clock::now() < deadline) std::this_thread::sleep_for(1ms);
    require(predicate(), "rumble fixture timed out");
}
struct State {
    std::mutex mutex;
    std::condition_variable wake;
    bool block = false, released = false;
    std::atomic<bool> entered{false}, fail{false};
    std::atomic<int> closed{0};
    std::vector<DeckRumble> values;
    std::thread::id openingThread;
    void opening(int fd) {
        require(::fcntl(fd, F_GETFD) >= 0, "worker lost retained joystick descriptor");
        std::unique_lock lock(mutex);
        openingThread = std::this_thread::get_id(); entered = true;
        if (block) require(wake.wait_for(lock, 3s, [&] { return released; }), "blocked rumble open timed out");
    }
    void release() { const std::lock_guard lock(mutex); released = true; wake.notify_all(); }
    std::vector<DeckRumble> snapshot() { const std::lock_guard lock(mutex); return values; }
};
class Device final : public DeckRumbleDevice {
public:
    explicit Device(std::shared_ptr<State> state) : state(std::move(state)) {}
    ~Device() override { ++state->closed; }
    bool play(DeckRumble value, const std::function<bool()>& current) override {
        if (!current()) return true;
        require(state->closed == 0, "retired endpoint received rumble");
        const std::lock_guard lock(state->mutex);
        state->values.push_back(value);
        return !state->fail;
    }
    std::shared_ptr<State> state;
};
void mailbox() {
    const int fd = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
    require(fd >= 0, "missing inert descriptor fixture");
    auto first = std::make_shared<State>(), second = std::make_shared<State>();
    first->block = true;
    std::atomic<int> opens{0};
    {
        DeckRumbleController output([&](int retained) {
            require(retained != fd, "joystick descriptor was borrowed without ownership");
            auto state = ++opens == 1 ? first : second;
            if (state == second) require(first->closed == 1, "replacement opened before old effect was retired");
            state->opening(retained);
            return std::make_unique<Device>(state);
        });
        output.setDevice(fd);
        until([&] { return first->entered.load(); });
        const auto before = std::chrono::steady_clock::now();
        for (int i = 0; i < 10000; ++i) output.submit({static_cast<uint16_t>(i), 42});
        output.submit({});
        require(std::chrono::steady_clock::now() - before < 100ms, "GUI rumble submission waited for device I/O");
        first->release();
        until([&] { return !first->snapshot().empty(); });
        require(first->snapshot() == std::vector<DeckRumble>{{}}, "stopped commands replayed after a blocked open");
        require(first->openingThread != std::this_thread::get_id(), "device work ran on the caller thread");
        output.submit({65535, 32768});
        until([&] { return first->snapshot().back() == DeckRumble{65535, 32768}; });
        const auto count = first->snapshot().size();
        until([&] { return first->snapshot().size() > count; }); // renew the short hardware lease
        output.submit({});
        until([&] { return !first->snapshot().back(); });
        const auto stopped = first->snapshot().size();
        std::this_thread::sleep_for(300ms);
        require(first->snapshot().size() == stopped, "heartbeat restarted a stopped effect");
        output.submit({1, 2});
        until([&] { return first->snapshot().back() == DeckRumble{1, 2}; });
        output.setDevice(fd);
        until([&] { return !second->snapshot().empty(); });
        require(second->snapshot() == std::vector<DeckRumble>{{}}, "old controller rumble crossed hotplug");
        second->fail = true;
        output.submit({3, 4});
        until([&] { return second->closed == 1; });
        output.submit({5, 6});
        std::this_thread::sleep_for(30ms);
        require(opens == 2 && second->snapshot().back() == DeckRumble{3, 4}, "failed output retried or selected another controller");
        output.setDevice(-1);
    }
    for (bool destroy : {false, true}) {
        auto late = std::make_shared<State>(); late->block = true;
        auto output = std::make_unique<DeckRumbleController>([&](int retained) {
            late->opening(retained); return std::make_unique<Device>(late);
        });
        output->setDevice(fd);
        until([&] { return late->entered.load(); });
        output->submit({500, 600});
        // Disconnect is synchronous at the mailbox, even if device open blocks.
        output->setDevice(-1);
        if (destroy) {
            std::thread closer([&] { output.reset(); });
            late->release(); closer.join();
        } else {
            late->release(); until([&] { return late->closed == 1; });
        }
        require(late->snapshot().empty(), "late endpoint activated after unplug/destruction");
    }
    ::close(fd);
}

#ifdef __linux__
struct Kernel {
    QTemporaryDir root;
    int opens = 0, closes = 0, uploads = 0, erases = 0, joystickChecks = 0;
    bool supported = true, denied = false, reused = false, lostJoystick = false;
    bool uploadFails = false, writeFails = false;
    std::vector<ff_effect> effects;
    std::vector<input_event> commands;
    Kernel() {
        QDir dir(root.path());
        require(dir.mkpath("dev/char") && dir.mkpath("devices/input7/js0") && dir.mkpath("devices/input7/event8"), "cannot make sysfs fixture");
        require(QFile::link(root.filePath("devices/input7/js0"), root.filePath("dev/char/13:0")) &&
            QFile::link(root.filePath("devices/input7"), root.filePath("devices/input7/js0/device")) &&
            QFile::link(root.filePath("devices/input7"), root.filePath("devices/input7/event8/device")), "cannot link sysfs fixture");
        QFile number(root.filePath("devices/input7/event8/dev"));
        require(number.open(QIODevice::WriteOnly) && number.write("13:64\n") == 6, "cannot write event number");
    }
    DeckRumbleLinuxIo io() {
        return {root.path(), "/fixture/input",
            [&](int fd, struct stat* value) {
                *value = {}; value->st_mode = S_IFCHR;
                value->st_rdev = makedev(13, fd == 10 ? 0 : reused ? 65 : 64); return 0;
            },
            [&](const char* path, int flags) {
                require(QString::fromLatin1(path) == "/fixture/input/event8", "opened unrelated input device");
                require((flags & O_RDWR) && (flags & O_NONBLOCK) && (flags & O_CLOEXEC) && (flags & O_NOFOLLOW), "event flags lost safety properties");
                ++opens; return denied ? -1 : 20;
            },
            [&](int fd, unsigned long request, std::uintptr_t value) {
                if (request == JSIOCGAXES) { require(fd == 10, "queried wrong joystick"); ++joystickChecks; return lostJoystick && joystickChecks > 1 ? -1 : 0; }
                require(fd == 20, "queried wrong event endpoint");
                if (request == EVIOCGBIT(EV_FF, (FF_CNT + 7) / 8)) {
                    if (supported) reinterpret_cast<unsigned char*>(value)[FF_RUMBLE / 8] |= 1u << (FF_RUMBLE % 8);
                    return 0;
                }
                if (request == EVIOCSFF) {
                    ++uploads;
                    auto& effect = *reinterpret_cast<ff_effect*>(value);
                    effects.push_back(effect); effect.id = 4; return uploadFails ? -1 : 0;
                }
                require(request == EVIOCRMFF && value == 4, "removed another effect"); ++erases; return 0;
            },
            [&](int fd, const void* value, std::size_t size) -> ssize_t {
                require(fd == 20 && size == sizeof(input_event), "invalid event write");
                const auto event = *static_cast<const input_event*>(value);
                require(event.type == EV_FF && event.code == 4 && (event.value == 0 || event.value == 1), "unexpected input injection or global gain change");
                commands.push_back(event); return writeFails ? 0 : size;
            },
            [&](int fd) { require(fd == 20, "closed borrowed joystick"); ++closes; }};
    }
};
void kernelBoundary() {
    for (int scenario = 0; scenario < 6; ++scenario) {
        Kernel k;
        if (scenario == 0) k.denied = true;
        if (scenario == 1) k.supported = false;
        if (scenario == 2) k.reused = true;
        if (scenario == 3) k.lostJoystick = true;
        if (scenario == 4) {
            QFile::remove(k.root.filePath("devices/input7/event8/device"));
            QFile::link(k.root.path(), k.root.filePath("devices/input7/event8/device"));
        }
        if (scenario == 5) QDir(k.root.path()).mkpath("devices/input7/event9");
        require(!openDeckRumbleDevice(10, k.io()), "invalid, ambiguous or unsupported endpoint accepted");
        require(k.uploads == 0 && k.commands.empty() && k.closes == (k.opens && !k.denied ? 1 : 0), "rejected discovery changed a device or leaked a descriptor");
    }
    Kernel k;
    auto output = openDeckRumbleDevice(10, k.io());
    require(output && k.uploads == 0 && k.commands.empty(), "discovery activated an effect");
    require(output->play({65535, 32768}), "rumble upload failed");
    require(k.effects.front().id == -1 && k.effects.front().type == FF_RUMBLE &&
        k.effects.front().u.rumble.strong_magnitude == 65535 && k.effects.front().u.rumble.weak_magnitude == 32768 &&
        k.effects.front().replay.length == 1000, "motor mapping or short lease lost");
    require(output->play({65535, 32768}) && k.uploads == 1 && k.commands.size() == 2, "lease refresh allocated another effect");
    require(output->play({1, 2}) && k.effects.back().id == 4 && output->play({}) && k.commands.back().value == 0,
        "effect update/stop failed");
    const auto writes = k.commands.size();
    int checks = 0;
    require(output->play({9, 10}, [&] { return ++checks == 1; }) && k.commands.size() == writes,
        "cancelled upload started late vibration");
    require(output->play({1, 2}) && k.effects.back().u.rumble.strong_magnitude == 1,
        "cancelled upload left stale amplitudes in the next effect");
    k.writeFails = true;
    require(!output->play({3, 4}), "short output write reported success");
    output.reset();
    require(k.erases == 1 && k.closes == 1, "failed stop did not remove effect and close endpoint");
    Kernel failed; failed.uploadFails = true;
    output = openDeckRumbleDevice(10, failed.io());
    require(output && !output->play({1, 2}) && failed.commands.empty(), "failed upload played an invalid effect");
    output.reset();
    require(failed.closes == 1, "failed upload leaked endpoint");
    require(!openDeckRumbleDevice(-1), "invalid live descriptor resolved a controller");
}
#endif
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    mailbox();
#ifdef __linux__
    kernelBoundary();
#endif
    std::cout << "Rumble: matched-device identity, capabilities, motor mapping, lease, mailbox, hotplug and cleanup passed\n";
}
