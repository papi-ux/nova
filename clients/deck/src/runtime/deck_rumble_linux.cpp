#include "runtime/deck_rumble_linux.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QRegularExpression>
#include <array>
#include <fcntl.h>
#include <unistd.h>
#ifdef __linux__
#include <linux/input.h>
#include <linux/joystick.h>
#include <sys/ioctl.h>
#include <sys/sysmacros.h>
#endif

namespace nova::deck::runtime {
#ifdef __linux__
namespace {
class EvdevRumble final : public DeckRumbleDevice {
public:
    EvdevRumble(int fd, DeckRumbleLinuxIo io) : fd_(fd), io_(std::move(io)) {}
    ~EvdevRumble() override {
        if (effect_ >= 0) {
            play({}, [] { return true; });
            io_.ioctl(fd_, EVIOCRMFF, static_cast<std::uintptr_t>(effect_));
        }
        io_.close(fd_);
    }
    bool play(DeckRumble value, const std::function<bool()>& current) override {
        if (!current()) return true;
        if (!value && effect_ < 0) return true;
        if (value && (effect_ < 0 || value != uploaded_)) {
            ff_effect effect{};
            effect.type = FF_RUMBLE;
            effect.id = effect_;
            effect.u.rumble.strong_magnitude = value.low;
            effect.u.rumble.weak_magnitude = value.high;
            effect.replay.length = 1000; // refreshed by the worker while still requested
            if (io_.ioctl(fd_, EVIOCSFF, reinterpret_cast<std::uintptr_t>(&effect)) < 0) return false;
            effect_ = effect.id;
            if (effect_ < 0) return false;
            uploaded_ = value;
        }
        if (!current()) return true;
        input_event event{};
        event.type = EV_FF;
        event.code = effect_;
        event.value = value ? 1 : 0;
        if (io_.write(fd_, &event, sizeof(event)) != sizeof(event)) return false;
        return true;
    }
private:
    int fd_, effect_ = -1;
    DeckRumbleLinuxIo io_;
    DeckRumble uploaded_;
};
template<std::size_t N> bool hasBit(const std::array<unsigned char, N>& bits, unsigned bit) {
    return bit / 8 < N && (bits[bit / 8] & (1u << (bit % 8)));
}
}
#endif

std::unique_ptr<DeckRumbleDevice> openDeckRumbleDevice(int joystickFd, DeckRumbleLinuxIo io) {
#ifdef __linux__
    struct stat joystick{};
    unsigned char axes = 0;
    if (io.stat(joystickFd, &joystick) < 0 || !S_ISCHR(joystick.st_mode) || major(joystick.st_rdev) != 13 ||
        io.ioctl(joystickFd, JSIOCGAXES, reinterpret_cast<std::uintptr_t>(&axes)) < 0) return {};
    const QString sysJoystick = io.sysRoot + QString("/dev/char/%1:%2").arg(major(joystick.st_rdev)).arg(minor(joystick.st_rdev));
    const auto name = QFileInfo(QFileInfo(sysJoystick).canonicalFilePath()).fileName();
    if (!QRegularExpression("^js[0-9]+$").match(name).hasMatch()) return {};
    const auto parent = QFileInfo(sysJoystick + "/device").canonicalFilePath();
    if (parent.isEmpty()) return {};
    const auto events = QDir(parent).entryList({"event*"}, QDir::Dirs | QDir::NoDotAndDotDot);
    if (events.size() != 1 || !QRegularExpression("^event[0-9]+$").match(events.front()).hasMatch()) return {};
    const auto eventSys = parent + "/" + events.front();
    QFile number(eventSys + "/dev");
    if (!number.open(QIODevice::ReadOnly)) return {};
    const auto parts = QString::fromLatin1(number.read(64)).trimmed().split(':');
    bool majorOk = false, minorOk = false;
    const auto expectedMajor = parts.size() == 2 ? parts[0].toUInt(&majorOk) : 0;
    const auto expectedMinor = parts.size() == 2 ? parts[1].toUInt(&minorOk) : 0;
    if (!majorOk || !minorOk || expectedMajor != 13) return {};
    const auto path = QFile::encodeName(io.inputRoot + "/" + events.front());
    const int fd = io.open(path.constData(), O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return {};
    auto output = std::make_unique<EvdevRumble>(fd, io);
    struct stat event{};
    std::array<unsigned char, (FF_CNT + 7) / 8> features{};
    // Validate after open: names/device numbers can be reused during hotplug.
    if (io.stat(fd, &event) < 0 || !S_ISCHR(event.st_mode) || major(event.st_rdev) != expectedMajor ||
        minor(event.st_rdev) != expectedMinor || QFileInfo(eventSys + "/device").canonicalFilePath() != parent ||
        QFileInfo(sysJoystick + "/device").canonicalFilePath() != parent ||
        io.ioctl(joystickFd, JSIOCGAXES, reinterpret_cast<std::uintptr_t>(&axes)) < 0 ||
        io.ioctl(fd, EVIOCGBIT(EV_FF, features.size()), reinterpret_cast<std::uintptr_t>(features.data())) < 0 ||
        !hasBit(features, FF_RUMBLE)) return {};
    return output;
#else
    (void)joystickFd; (void)io; return {};
#endif
}

std::unique_ptr<DeckRumbleDevice> openDeckRumbleDevice(int joystickFd) {
#ifdef __linux__
    return openDeckRumbleDevice(joystickFd, {"/sys", "/dev/input",
        [](int fd, struct stat* value) { return ::fstat(fd, value); },
        [](const char* path, int flags) { return ::open(path, flags); },
        [](int fd, unsigned long request, std::uintptr_t value) { return ::ioctl(fd, request, value); },
        [](int fd, const void* value, std::size_t size) { return ::write(fd, value, size); },
        [](int fd) { ::close(fd); }});
#else
    (void)joystickFd; return {};
#endif
}
}
