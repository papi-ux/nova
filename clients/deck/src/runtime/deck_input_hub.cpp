#include "runtime/deck_input_hub.h"
#include "runtime/deck_input_device_policy.h"
#include "runtime/deck_native_session.h"
#include "runtime/deck_rumble.h"
#include "deck_gamepad.h"
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QSocketNotifier>
#include <QWindow>
#include <algorithm>
#include <Limelight.h>
#ifdef __linux__
#include <fcntl.h>
#include <linux/joystick.h>
#include <sys/ioctl.h>
#include <unistd.h>
#endif

namespace nova::deck::runtime {
namespace {
QString smallFile(const QString& path) {
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? QString::fromUtf8(file.read(256)).trimmed() : QString{};
}
bool isDeck() {
    const auto vendor = smallFile(QStringLiteral("/sys/class/dmi/id/sys_vendor"));
    const auto product = smallFile(QStringLiteral("/sys/class/dmi/id/product_name"));
    return vendor.contains(QStringLiteral("Valve"), Qt::CaseInsensitive) &&
        (product == "Jupiter" || product == "Galileo" || product.contains("Steam Deck", Qt::CaseInsensitive));
}
}

struct DeckInputHub::Device {
    QString path;
    std::string id;
    int fd = -1;
    QSocketNotifier* notifier = nullptr;
    stream::DeckControllerDecoder decoder;
    DeckRumbleController rumble;
    ~Device() {
        delete notifier;
        rumble.setDevice(-1);
#ifdef __linux__
        if (fd >= 0) ::close(fd);
#endif
    }
};

DeckInputHub::DeckInputHub(QObject* parent) : QObject(parent), assignments_(isDeck()) {
    connect(qApp, &QGuiApplication::focusWindowChanged, this, [this] { syncFocus(); });
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this] { syncFocus(); });
    connect(&scanTimer_, &QTimer::timeout, this, &DeckInputHub::scan);
    scanTimer_.start(1000);
    scan();
}
DeckInputHub::~DeckInputHub() = default;

bool DeckInputHub::neutral(stream::DeckControllerState state) const {
    return session_ ? session_->controllerNeutral(state)
        : stream::withStickDeadzone(state, stream::kDeckDefaultStickDeadzone).neutral();
}

QVariantList DeckInputHub::players() const {
    QVariantList result;
    for (const auto& device : assignments_.devices())
        result.push_back(QVariantMap{{"name", QString::fromStdString(device.name)},
            {"player", device.player}, {"builtIn", device.builtIn}, {"waiting", device.player < 0}});
    std::stable_sort(result.begin(), result.end(), [](const QVariant& a, const QVariant& b) {
        const int left = a.toMap().value("player").toInt(), right = b.toMap().value("player").toInt();
        return (left < 0 ? 16 : left) < (right < 0 ? 16 : right);
    });
    return result;
}

void DeckInputHub::setNativeSession(DeckNativeSessionController* session) {
    if (session_) disconnect(session_, nullptr, this, nullptr);
    for (const auto& device : devices_) device->rumble.submit({});
    session_ = session;
    if (session_) {
        connect(session_, &DeckNativeSessionController::playerRumbleRequested, this,
            [this](quint16 player, quint16 low, quint16 high) {
                for (const auto& device : devices_)
                    if (assignments_.player(device->id) == player) device->rumble.submit({low, high});
            });
        connect(session_, &DeckNativeSessionController::inputSessionStarted, this, [this](bool reusePlayers) {
            if (reusePlayers) return;
            const auto previous = assignments_.devices();
            for (const auto& device : devices_) {
                device->rumble.submit({});
                const auto player = assignments_.player(device->id);
                if (player >= 0) session_->updatePlayerController(player, {}, false);
            }
            assignments_ = stream::DeckPlayers(isDeck());
            for (const auto& device : previous) assignments_.connect(device.id, device.name, device.builtIn);
            for (const auto& device : devices_) {
                assignments_.input(device->id, device->decoder.ready() && neutral(device->decoder.state()), false);
                const auto player = assignments_.player(device->id);
                if (player >= 0) session_->updatePlayerController(player, device->decoder.state(), device->decoder.ready());
            }
            emit playersChanged();
        });
        for (const auto& device : devices_) {
            const auto player = assignments_.player(device->id);
            if (player >= 0) session_->updatePlayerController(player, device->decoder.state(), device->decoder.ready());
        }
    }
    syncFocus();
}

void DeckInputHub::syncFocus() {
    const auto* window = QGuiApplication::focusWindow();
    if ((!window || !window->isActive()) && primaryHeld()) { primaryOwner_.clear(); emit primaryHeldChanged(); }
    if (session_) session_->setInputFocus(window && window->isActive());
}
void DeckInputHub::activateFocusedItem() { navigationKey(Qt::Key_Return); }
void DeckInputHub::navigationKey(int key) {
    auto* window = QGuiApplication::focusWindow();
    if (!window || !window->isActive()) return;
    QKeyEvent press(QEvent::KeyPress, key, Qt::NoModifier), release(QEvent::KeyRelease, key, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &press);
    QCoreApplication::sendEvent(window, &release);
}

bool DeckInputHub::reassignPlayers() {
    const auto* window = QGuiApplication::focusWindow();
    if (!session_ || !window || !window->isActive() || session_->state().value("phase") != "active") return false;
    // Pause every player before dropping slots; already held presses and queued
    // feedback cannot become the next player's first action.
    session_->showControls();
    for (const auto& device : devices_) {
        device->rumble.submit({});
        const auto player = assignments_.player(device->id);
        if (player >= 0) session_->updatePlayerController(player, {}, false);
    }
    assignments_.reassign();
    for (const auto& device : devices_)
        assignments_.input(device->id, device->decoder.ready() && neutral(device->decoder.state()), false);
    emit playersChanged();
    session_->resumeInput();
    return true;
}

void DeckInputHub::scan() {
#ifdef __linux__
    struct Candidate { QString path, name; bool builtIn, virtualSteam; };
    std::vector<Candidate> candidates;
    const auto configured = qEnvironmentVariable("NOVA_DECK_GAMEPAD_DEVICE");
    QStringList paths;
    if (!configured.isEmpty()) paths.push_back(configured);
    else for (const auto& name : QDir("/dev/input").entryList({"js*"}, QDir::System | QDir::Files, QDir::Name))
        paths.push_back("/dev/input/" + name);
    const bool deck = isDeck();
    for (const auto& path : paths.mid(0, 64)) {
        const auto node = QFileInfo(path).fileName();
        const auto base = "/sys/class/input/" + node + "/device/";
        auto name = smallFile(base + "name");
        const auto kind = classifyDeckInputDevice(smallFile(base + "id/vendor").toUShort(nullptr, 16),
            smallFile(base + "id/product").toUShort(nullptr, 16), name.toStdString(), deck);
        // An inaccessible/stale virtual node must not hide usable physical pads.
        const auto probe = ::open(path.toLocal8Bit().constData(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (probe < 0) continue;
        const bool valid = stream::readDeckControllerMapping(probe).valid;
        ::close(probe);
        if (valid) candidates.push_back({path, name, kind.builtIn, kind.steamVirtual});
    }
    const bool steamOwnsInput = configured.isEmpty() && std::any_of(candidates.begin(), candidates.end(),
        [](const auto& item) { return item.virtualSteam; });
    // Steam Input's virtual set is authoritative while present. Reading its raw
    // physical devices as well delivers each press twice and invents players.
    std::erase_if(candidates, [steamOwnsInput](const auto& item) { return steamOwnsInput && !item.virtualSteam; });
    for (auto it = devices_.begin(); it != devices_.end();) {
        if (std::none_of(candidates.begin(), candidates.end(), [&](const auto& item) { return item.path == (*it)->path; })) {
            remove(**it);
            it = devices_.erase(it);
            emit availabilityChanged();
        } else ++it;
    }
    for (const auto& candidate : candidates) {
        if (devices_.size() >= 32) break;
        if (std::any_of(devices_.begin(), devices_.end(), [&](const auto& device) { return device->path == candidate.path; })) continue;
        const auto fd = ::open(candidate.path.toLocal8Bit().constData(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        const auto mapping = stream::readDeckControllerMapping(fd);
        if (!mapping.valid) { ::close(fd); continue; }
        auto device = std::make_unique<Device>();
        device->fd = fd;
        device->path = candidate.path;
        device->id = "controller-" + std::to_string(++serial_);
        device->decoder = stream::DeckControllerDecoder(mapping);
        char name[128]{};
        ::ioctl(fd, JSIOCGNAME(sizeof(name)), name);
        name[sizeof(name) - 1] = 0;
        const auto label = QString::fromUtf8(name).simplified().left(80);
        assignments_.connect(device->id, (label.isEmpty() ? QStringLiteral("Controller") : label).toStdString(), candidate.builtIn);
        device->rumble.setDevice(fd);
        device->notifier = new QSocketNotifier(fd, QSocketNotifier::Read, this);
        connect(device->notifier, &QSocketNotifier::activated, this, [this, current = device.get()] { read(*current); });
        devices_.push_back(std::move(device));
        emit availabilityChanged();
        emit playersChanged();
    }
#endif
}

void DeckInputHub::remove(Device& device) {
    if (primaryOwner_ == device.id) { primaryOwner_.clear(); emit primaryHeldChanged(); }
    device.rumble.submit({});
    const auto player = assignments_.player(device.id);
    if (session_ && player >= 0) session_->updatePlayerController(player, {}, false);
    assignments_.disconnect(device.id);
    emit playersChanged();
}

void DeckInputHub::read(Device& device) {
#ifdef __linux__
    js_event raw{};
    for (;;) {
        const auto bytes = ::read(device.fd, &raw, sizeof(raw));
        if (bytes == sizeof(raw)) {
            const DeckGamepadEvent event{raw.time, raw.value, raw.type, raw.number};
            const auto before = device.decoder.state();
            device.decoder.consume(event);
            const auto state = device.decoder.state();
            if (!(state.buttons & A_FLAG) && primaryOwner_ == device.id) { primaryOwner_.clear(); emit primaryHeldChanged(); }
            assignments_.input(device.id, device.decoder.ready() && neutral(state), false);
            const auto* window = QGuiApplication::focusWindow();
            if (session_) {
                const bool captured = session_->capturesGamepad();
                session_->setInputFocus(window && window->isActive());
                if (captured && window && window->isActive() && device.decoder.ready()) {
                    if (assignments_.input(device.id, neutral(state), !(raw.type & JS_EVENT_INIT) && state.buttons != 0)) emit playersChanged();
                }
                const int player = assignments_.player(device.id);
                if (player >= 0) session_->updatePlayerController(player, state, device.decoder.ready());
                if (captured || session_->capturesGamepad()) continue;
            }
            if (!window || !window->isActive()) continue;
            const auto pressed = (raw.type & JS_EVENT_INIT) ? 0u : state.buttons & ~before.buttons;
            // The decoder normalizes both hat axes and BTN_DPAD_* devices.
            // Reading hats separately would leave button-based D-pads inert in
            // the UI even though the same controller worked during gameplay.
            if (pressed & LEFT_FLAG) navigationKey(Qt::Key_Left);
            if (pressed & RIGHT_FLAG) navigationKey(Qt::Key_Right);
            if (pressed & UP_FLAG) navigationKey(Qt::Key_Up);
            if (pressed & DOWN_FLAG) navigationKey(Qt::Key_Down);
            if (pressed & A_FLAG) {
                if (primaryHeld()) { primaryOwner_.clear(); emit primaryHeldChanged(); }
                primaryOwner_ = device.id;
                emit primaryHeldChanged();
                emit primaryActionPressed(++primaryCount_);
            }
            else if (pressed & B_FLAG) emit secondaryActionPressed(++secondaryCount_);
            continue;
        }
        if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return;
        // Destroy after this callback, so notifier activation never uses a dead
        // Device. A new fd/path is admitted as a new physical generation.
        device.notifier->setEnabled(false);
        const auto id = device.id;
        remove(device);
        QTimer::singleShot(0, this, [this, id] {
            std::erase_if(devices_, [&](const auto& item) { return item->id == id; });
            emit availabilityChanged();
        });
        return;
    }
#endif
}
} // namespace nova::deck::runtime
