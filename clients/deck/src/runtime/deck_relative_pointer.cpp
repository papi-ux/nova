#include "runtime/deck_relative_pointer.h"
#include <QAbstractNativeEventFilter>
#include <QCursor>
#include <QGuiApplication>
#include <QInputDevice>
#include <QPointer>
#include <QTimer>
#include <QtGui/qguiapplication_platform.h>
#include <QtGui/qpa/qplatformwindow_p.h>
#include <QtWaylandClient/QWaylandClientExtension>
#include <wayland-client.h>
#include <xcb/xinput.h>
#include "relative-pointer-client-protocol.h"
#include "pointer-constraints-client-protocol.h"
#include <algorithm>
#include <cstdlib>
#include <vector>
#include <cstring>

namespace nova::deck::runtime {
namespace {
// Qt owns registry dispatch and its GUI-thread event queue. Using its extension
// API avoids a second reader on Qt's shared Wayland display connection.
class RelativeManager final : public QWaylandClientExtension {
public:
    RelativeManager() : QWaylandClientExtension(1) { initialize(); }
    ~RelativeManager() override { if (object) zwp_relative_pointer_manager_v1_destroy(object); }
    const wl_interface* extensionInterface() const override { return &zwp_relative_pointer_manager_v1_interface; }
    void bind(wl_registry* registry, int id, int version) override {
        if (object) zwp_relative_pointer_manager_v1_destroy(object);
        object = static_cast<zwp_relative_pointer_manager_v1*>(wl_registry_bind(registry, id, extensionInterface(), std::min(version, 1)));
    }
    zwp_relative_pointer_manager_v1* object = nullptr;
};
class ConstraintsManager final : public QWaylandClientExtension {
public:
    ConstraintsManager() : QWaylandClientExtension(1) { initialize(); }
    ~ConstraintsManager() override { if (object) zwp_pointer_constraints_v1_destroy(object); }
    const wl_interface* extensionInterface() const override { return &zwp_pointer_constraints_v1_interface; }
    void bind(wl_registry* registry, int id, int version) override {
        if (object) zwp_pointer_constraints_v1_destroy(object);
        object = static_cast<zwp_pointer_constraints_v1*>(wl_registry_bind(registry, id, extensionInterface(), std::min(version, 1)));
    }
    zwp_pointer_constraints_v1* object = nullptr;
};
}

struct DeckRelativePointer::Impl final : QAbstractNativeEventFilter {
    DeckRelativePointer& owner;
    QPointer<QWindow> window;
    QCursor previousCursor;
    bool active = false, pending = false, x11 = false;
    QString error;
    QTimer deadline;
    QMetaObject::Connection destroyed, surfaceDestroyed;
    QList<QMetaObject::Connection> devices;
    xcb_connection_t* connection = nullptr;
    uint8_t inputOpcode = 0;
    struct Subscription { xcb_window_t root; uint16_t device; uint32_t added; };
    std::vector<Subscription> subscriptions;
    std::unique_ptr<RelativeManager> relative;
    std::unique_ptr<ConstraintsManager> constraints;
    zwp_relative_pointer_v1* pointer = nullptr;
    zwp_locked_pointer_v1* lock = nullptr;

    explicit Impl(DeckRelativePointer& o) : owner(o) {
        const auto platform = QGuiApplication::platformName();
        if (platform == "xcb") {
            if (auto* native = qApp->nativeInterface<QNativeInterface::QX11Application>()) {
                connection = native->connection();
                const auto* extension = xcb_get_extension_data(connection, &xcb_input_id);
                if (extension && extension->present) {
                    auto* version = xcb_input_xi_query_version_reply(connection, xcb_input_xi_query_version(connection, 2, 4), nullptr);
                    x11 = version && (version->major_version > 2 || (version->major_version == 2 && version->minor_version >= 1));
                    std::free(version);
                    inputOpcode = extension->major_opcode;
                }
            }
            qApp->installNativeEventFilter(this);
        } else if (platform.startsWith("wayland")) {
            relative = std::make_unique<RelativeManager>();
            constraints = std::make_unique<ConstraintsManager>();
            const auto update = [this] {
                if (!available() && (active || pending)) fail("Relative mouse capture is no longer available. Choose Direct Pointer or reconnect your mouse.");
                emit owner.changed();
            };
            QObject::connect(relative.get(), &QWaylandClientExtension::activeChanged, &owner, update);
            QObject::connect(constraints.get(), &QWaylandClientExtension::activeChanged, &owner, update);
        }
        deadline.setSingleShot(true); deadline.setInterval(1500);
        QObject::connect(&deadline, &QTimer::timeout, &owner, [this] {
            fail("Mouse capture was not granted. Move the pointer into the game window and resume, or choose Direct Pointer.");
        });
    }
    ~Impl() override { stop(); qApp->removeNativeEventFilter(this); }
    bool available() const { return x11 || (relative && relative->isActive() && constraints && constraints->isActive()); }
    void fail(QString why) { stop(); error = std::move(why); emit owner.changed(); emit owner.lost(); }
    bool changeMask(xcb_window_t root, uint16_t device, uint32_t bits, bool add) {
        auto* reply = xcb_input_xi_get_selected_events_reply(connection,
            xcb_input_xi_get_selected_events(connection, root), nullptr);
        if (!reply) return false;
        std::vector<uint32_t> words(1, 0);
        for (auto it = xcb_input_xi_get_selected_events_masks_iterator(reply); it.rem; xcb_input_event_mask_next(&it)) {
            if (it.data->deviceid != device) continue;
            const auto* mask = xcb_input_event_mask_mask(it.data);
            words.assign(mask, mask + it.data->mask_len);
            if (words.empty()) words.push_back(0);
            break;
        }
        std::free(reply);
        const auto added = bits & ~words[0];
        words[0] = add ? words[0] | bits : words[0] & ~bits;
        // Qt shares this connection and subscribes to device/hierarchy events.
        // Preserve every existing mask word, removing only bits we added.
        std::vector<uint32_t> packed(words.size() + 1);
        const xcb_input_event_mask_t header{device, static_cast<uint16_t>(words.size())};
        std::memcpy(packed.data(), &header, sizeof(header));
        std::copy(words.begin(), words.end(), packed.begin() + 1);
        auto* error = xcb_request_check(connection, xcb_input_xi_select_events_checked(connection, root, 1,
            reinterpret_cast<const xcb_input_event_mask_t*>(packed.data())));
        const bool ok = !error;
        std::free(error);
        if (ok && add && added) subscriptions.push_back({root, device, added});
        return ok;
    }
    bool selectMotion() {
        for (auto screens = xcb_setup_roots_iterator(xcb_get_setup(connection)); screens.rem; xcb_screen_next(&screens)) {
            if (!changeMask(screens.data->root, XCB_INPUT_DEVICE_ALL_MASTER, XCB_INPUT_XI_EVENT_MASK_RAW_MOTION, true) ||
                !changeMask(screens.data->root, XCB_INPUT_DEVICE_ALL, XCB_INPUT_XI_EVENT_MASK_HIERARCHY, true)) return false;
        }
        return true;
    }
    void releaseMotion() {
        for (const auto& subscription : subscriptions)
            changeMask(subscription.root, subscription.device, subscription.added, false);
        subscriptions.clear();
    }
    void stop() {
        deadline.stop();
        QObject::disconnect(destroyed); QObject::disconnect(surfaceDestroyed);
        for (const auto& connection : devices) QObject::disconnect(connection);
        devices.clear();
        if (lock) { zwp_locked_pointer_v1_destroy(lock); lock = nullptr; }
        if (pointer) { zwp_relative_pointer_v1_destroy(pointer); pointer = nullptr; }
        if (x11 && (active || pending)) { releaseMotion(); xcb_ungrab_pointer(connection, XCB_CURRENT_TIME); xcb_flush(connection); }
        if (window) window->setCursor(previousCursor);
        window = nullptr;
        const bool changed = active || pending;
        active = pending = false;
        if (changed) emit owner.changed();
    }
    bool start(QWindow* target) {
        if (window == target && (active || pending)) return true;
        stop(); error.clear();
        if (!target || !target->isActive() || !available()) {
            error = "Relative mouse capture is unavailable here. Choose Direct Pointer to continue.";
            emit owner.changed(); return false;
        }
        window = target; previousCursor = target->cursor();
        destroyed = QObject::connect(target, &QObject::destroyed, &owner, [this] { fail("The mouse capture window closed."); });
        for (const auto* device : QInputDevice::devices())
            if (device->type() == QInputDevice::DeviceType::Mouse || device->type() == QInputDevice::DeviceType::TouchPad)
                devices << QObject::connect(device, &QObject::destroyed, &owner, [this] { fail("A pointing device disconnected. Resume to capture the mouse again."); });
        if (x11) {
            const auto mask = XCB_EVENT_MASK_BUTTON_PRESS | XCB_EVENT_MASK_BUTTON_RELEASE | XCB_EVENT_MASK_POINTER_MOTION;
            auto* reply = xcb_grab_pointer_reply(connection, xcb_grab_pointer(connection, 0, target->winId(), mask,
                XCB_GRAB_MODE_ASYNC, XCB_GRAB_MODE_ASYNC, target->winId(), XCB_NONE, XCB_CURRENT_TIME), nullptr);
            const bool grabbed = reply && reply->status == XCB_GRAB_STATUS_SUCCESS;
            std::free(reply);
            if (!grabbed) { fail("Another window has the mouse. Return to Nova and resume to try again."); return false; }
            active = true;
            if (!selectMotion()) { fail("Relative mouse input could not be enabled. Choose Direct Pointer to continue."); return false; }
        } else {
            auto* application = qApp->nativeInterface<QNativeInterface::QWaylandApplication>();
            auto* native = target->nativeInterface<QNativeInterface::Private::QWaylandWindow>();
            if (!application || !application->pointer() || !native || !native->surface()) {
                fail("Connect a mouse and move its pointer into the game window, then resume."); return false;
            }
            surfaceDestroyed = QObject::connect(native, &QNativeInterface::Private::QWaylandWindow::surfaceDestroyed,
                &owner, [this] { fail("The streaming window changed. Resume to capture the mouse again."); });
            pointer = zwp_relative_pointer_manager_v1_get_relative_pointer(relative->object, application->pointer());
            static const zwp_relative_pointer_v1_listener motionListener{[](void* data, zwp_relative_pointer_v1*, uint32_t, uint32_t,
                wl_fixed_t, wl_fixed_t, wl_fixed_t dx, wl_fixed_t dy) {
                auto& d = *static_cast<Impl*>(data);
                if (d.active && d.window && d.window->isActive()) emit d.owner.motion(wl_fixed_to_double(dx), wl_fixed_to_double(dy));
            }};
            zwp_relative_pointer_v1_add_listener(pointer, &motionListener, this);
            lock = zwp_pointer_constraints_v1_lock_pointer(constraints->object, native->surface(), application->pointer(), nullptr,
                ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT);
            static const zwp_locked_pointer_v1_listener lockListener{
                [](void* data, zwp_locked_pointer_v1*) { auto& d = *static_cast<Impl*>(data); d.deadline.stop(); d.pending = false; d.active = true; emit d.owner.changed(); },
                [](void* data, zwp_locked_pointer_v1*) { static_cast<Impl*>(data)->fail("Mouse capture was released. Resume to capture it again."); }};
            zwp_locked_pointer_v1_add_listener(lock, &lockListener, this);
            pending = true; deadline.start();
            wl_display_flush(application->display());
        }
        target->setCursor(QCursor(Qt::BlankCursor));
        emit owner.changed(); return true;
    }
    bool nativeEventFilter(const QByteArray& type, void* message, qintptr*) override {
        if (!active || !x11 || !window || !window->isActive() || type != "xcb_generic_event_t") return false;
        const auto* event = static_cast<xcb_generic_event_t*>(message);
        if ((event->response_type & 0x7f) != XCB_GE_GENERIC) return false;
        auto* raw = static_cast<xcb_input_raw_motion_event_t*>(message);
        if (raw->extension != inputOpcode) return false;
        if (raw->event_type == XCB_INPUT_HIERARCHY) {
            const auto flags = static_cast<xcb_input_hierarchy_event_t*>(message)->flags;
            if (flags & (XCB_INPUT_HIERARCHY_MASK_MASTER_REMOVED | XCB_INPUT_HIERARCHY_MASK_SLAVE_REMOVED | XCB_INPUT_HIERARCHY_MASK_DEVICE_DISABLED))
                fail("An input device disconnected. Resume to capture the mouse again.");
            return false;
        }
        if (raw->event_type != XCB_INPUT_RAW_MOTION) return false;
        const auto* mask = xcb_input_raw_button_press_valuator_mask(raw);
        const auto* values = xcb_input_raw_button_press_axisvalues_raw(raw);
        double x = 0, y = 0;
        int index = 0;
        for (int axis = 0; axis < raw->valuators_len * 32; ++axis) if (mask[axis / 32] & (uint32_t{1} << (axis % 32))) {
            const double value = values[index].integral + values[index].frac / 4294967296.0;
            if (axis == 0) x = value;
            else if (axis == 1) y = value;
            ++index;
        }
        if (x || y) emit owner.motion(x, y);
        return false;
    }
};

DeckRelativePointer::DeckRelativePointer(QObject* parent) : QObject(parent), d(std::make_unique<Impl>(*this)) {}
DeckRelativePointer::~DeckRelativePointer() = default;
bool DeckRelativePointer::available() const { return d->available(); }
bool DeckRelativePointer::active() const { return d->active; }
bool DeckRelativePointer::pending() const { return d->pending; }
QString DeckRelativePointer::error() const { return d->error; }
bool DeckRelativePointer::start(QWindow* window) { return d->start(window); }
void DeckRelativePointer::stop() { d->stop(); }
}
