#include "deck_layout.h"
#include "deck_gamepad.h"
#include "polaris_game_fixture.h"

#include <QClipboard>
#include <QSocketNotifier>
#include <QCoreApplication>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QObject>
#include <QString>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <cerrno>
#include <cstring>
#include <string>

#include <string_view>

#ifdef __linux__
#include <fcntl.h>
#include <linux/joystick.h>
#include <unistd.h>
#endif

namespace {
class QtDeckGamepadBridge final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool available READ available NOTIFY availabilityChanged)
public:
    explicit QtDeckGamepadBridge(QObject* parent = nullptr)
        : QObject(parent) {
        openDefaultDevice();
    }

    ~QtDeckGamepadBridge() override {
#ifdef __linux__
        if (gamepadFd_ >= 0) {
            ::close(gamepadFd_);
            gamepadFd_ = -1;
        }
#endif
    }

    [[nodiscard]] bool available() const {
#ifdef __linux__
        return gamepadFd_ >= 0;
#else
        return false;
#endif
    }

signals:
    void availabilityChanged();
    void primaryActionPressed(int activationCount);

private:
    void openDefaultDevice() {
#ifdef __linux__
        const QByteArray configuredDevice = qgetenv("NOVA_DECK_GAMEPAD_DEVICE");
        const QByteArray devicePath = configuredDevice.isEmpty() ? QByteArray("/dev/input/js0") : configuredDevice;
        gamepadFd_ = ::open(devicePath.constData(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (gamepadFd_ < 0) {
            return;
        }

        notifier_ = new QSocketNotifier(gamepadFd_, QSocketNotifier::Read, this);
        connect(notifier_, &QSocketNotifier::activated, this, [this]() {
            readPendingJoystickEvents();
        });
        emit availabilityChanged();
#endif
    }

#ifdef __linux__
    void readPendingJoystickEvents() {
        js_event rawEvent{};
        for (;;) {
            const ssize_t bytesRead = ::read(gamepadFd_, &rawEvent, sizeof(rawEvent));
            if (bytesRead == static_cast<ssize_t>(sizeof(rawEvent))) {
                const auto action = nova::deck::decodeGamepadAction(nova::deck::DeckGamepadEvent{
                    .timeMs = rawEvent.time,
                    .value = rawEvent.value,
                    .type = rawEvent.type,
                    .number = rawEvent.number,
                });
                if (action == nova::deck::DeckGamepadAction::PrimaryPressed) {
                    ++primaryActivationCount_;
                    emit primaryActionPressed(primaryActivationCount_);
                }
                continue;
            }

            if (bytesRead < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                return;
            }

            notifier_->setEnabled(false);
            ::close(gamepadFd_);
            gamepadFd_ = -1;
            emit availabilityChanged();
            return;
        }
    }

    int gamepadFd_ = -1;
    QSocketNotifier* notifier_ = nullptr;
#else
    void readPendingJoystickEvents() {}
#endif
    int primaryActivationCount_ = 0;
};

class QtLocalClipboardBridge final : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;

    Q_INVOKABLE bool copyPreviewText(const QString& text) {
        if (text.isEmpty()) {
            return false;
        }
        QClipboard* clipboard = QGuiApplication::clipboard();
        if (clipboard == nullptr) {
            return false;
        }
        clipboard->setText(text, QClipboard::Clipboard);
        return clipboard->text(QClipboard::Clipboard) == text;
    }
};

QString toQString(const std::string_view value) {
    return QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()));
}

QString toQString(const std::string& value) {
    return QString::fromStdString(value);
}

QVariantList toHostModel(const std::vector<nova::deck::DeckHostListItem>& hosts) {
    QVariantList model;
    for (const auto& host : hosts) {
        QVariantMap item;
        item.insert("id", toQString(host.id));
        item.insert("displayName", toQString(host.displayName));
        item.insert("statusLabel", toQString(host.statusLabel));
        item.insert("initialFocus", host.initialFocus);
        model.append(item);
    }
    return model;
}

QVariantMap toHostDetailModel(const nova::deck::DeckHostDetail& detail) {
    QVariantMap model;
    model.insert("id", toQString(detail.id));
    model.insert("displayName", toQString(detail.displayName));
    model.insert("statusLabel", toQString(detail.statusLabel));
    model.insert("subtitle", toQString(detail.subtitle));
    return model;
}

QVariantList toLibraryGameModel(const std::vector<nova::deck::DeckLibraryGameCard>& games) {
    QVariantList model;
    for (const auto& game : games) {
        QVariantMap item;
        item.insert("id", toQString(game.id));
        item.insert("title", toQString(game.title));
        item.insert("sourceRuntimeLabel", toQString(game.sourceRuntimeLabel));
        item.insert("launchModeLabel", toQString(game.launchModeLabel));
        item.insert("installedLabel", toQString(game.installedLabel));
        item.insert("initialFocus", game.initialFocus);
        model.append(item);
    }
    return model;
}

QVariantMap toLaunchCtaModel(const nova::deck::DeckLaunchCta& launchCta) {
    QVariantMap model;
    model.insert("id", toQString(launchCta.id));
    model.insert("label", toQString(launchCta.label));
    model.insert("helpText", toQString(launchCta.helpText));
    model.insert("previewStateLabel", toQString(launchCta.previewStateLabel));
    model.insert("previewText", toQString(launchCta.previewText));
    model.insert("enabled", launchCta.enabled);
    return model;
}

QString launchIntentBoundaryKindLabel(nova::deck::DeckLaunchIntentBoundaryKind kind) {
    switch (kind) {
    case nova::deck::DeckLaunchIntentBoundaryKind::PreviewOnly:
        return QStringLiteral("preview_only");
    }
    return QStringLiteral("unknown");
}

QVariantMap toLaunchIntentBoundaryModel(const nova::deck::DeckLaunchIntentBoundary& boundary) {
    QVariantMap model;
    model.insert("id", toQString(boundary.id));
    model.insert("kind", launchIntentBoundaryKindLabel(boundary.kind));
    model.insert("label", toQString(boundary.label));
    model.insert("reason", toQString(boundary.reason));
    model.insert("previewOnly", boundary.previewOnly);
    model.insert("allowsNetwork", boundary.allowsNetwork);
    model.insert("allowsProcessExecution", boundary.allowsProcessExecution);
    model.insert("allowsMoonlight", boundary.allowsMoonlight);
    model.insert("allowsHostMutation", boundary.allowsHostMutation);
    return model;
}

QVariantMap toPreviewCopyActionModel(const nova::deck::DeckLaunchPreviewCopyAction& copyAction) {
    QVariantMap model;
    model.insert("id", toQString(copyAction.id));
    model.insert("label", toQString(copyAction.label));
    model.insert("previewText", toQString(copyAction.previewText));
    model.insert("idleStatusLabel", toQString(copyAction.idleStatusLabel));
    model.insert("successToast", toQString(copyAction.successToast));
    model.insert("inertToast", toQString(copyAction.inertToast));
    model.insert("enabled", copyAction.enabled);
    model.insert("copyOnly", copyAction.copyOnly);
    model.insert("uiLocalClipboardOnly", copyAction.uiLocalClipboardOnly);
    model.insert("executable", copyAction.executable);
    return model;
}
} // namespace

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);

    const auto profile = nova::deck::defaultWindowProfile();
    const auto sampleLibrary = nova::deck::loadSamplePolarisGameLibraryFixture();
    const auto libraryGames = nova::deck::libraryGameCardsFor(sampleLibrary);
    const auto& selectedGame = sampleLibrary.games.front();
    const auto demoHosts = nova::deck::demoHostListState();
    const auto selectedHostDetail = nova::deck::resolveHostDetail(demoHosts, nova::deck::initialHostFocusTarget(demoHosts));
    const auto launchIntent = nova::deck::resolveLaunchIntent(selectedHostDetail, selectedGame);
    const auto launchPreview = nova::deck::fakeLaunchCommandPreviewFor(launchIntent);
    const auto launchCta = nova::deck::inertLaunchCtaFor(selectedHostDetail, selectedGame);
    const auto launchPreviewCopyAction = nova::deck::copyLaunchPreviewActionFor(launchPreview);

    QtLocalClipboardBridge localClipboard;
    QtDeckGamepadBridge gamepadBridge;

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("novaDeckShellName", toQString(profile.shellName));
    engine.rootContext()->setContextProperty("novaDeckWidth", profile.width);
    engine.rootContext()->setContextProperty("novaDeckHeight", profile.height);
    engine.rootContext()->setContextProperty("novaDeckFullscreenPreferred", profile.fullscreenPreferred);
    engine.rootContext()->setContextProperty("novaLibraryFixtureSource", toQString(sampleLibrary.sourceLabel));
    engine.rootContext()->setContextProperty("novaLibraryReadOnly", sampleLibrary.readOnly);
    engine.rootContext()->setContextProperty("novaLibraryGames", toLibraryGameModel(libraryGames));
    engine.rootContext()->setContextProperty("novaDemoHosts", toHostModel(demoHosts));
    engine.rootContext()->setContextProperty("novaSelectedHostDetail", toHostDetailModel(selectedHostDetail));
    engine.rootContext()->setContextProperty("novaHostLaunchCta", toLaunchCtaModel(launchCta));
    engine.rootContext()->setContextProperty("novaLaunchIntentBoundary", toLaunchIntentBoundaryModel(launchIntent.boundary));
    engine.rootContext()->setContextProperty("novaLaunchPreviewCopyAction", toPreviewCopyActionModel(launchPreviewCopyAction));
    engine.rootContext()->setContextProperty("novaLocalClipboard", &localClipboard);
    engine.rootContext()->setContextProperty("novaGamepad", &gamepadBridge);
    engine.rootContext()->setContextProperty("novaInitialHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(demoHosts)));
    engine.rootContext()->setContextProperty("novaEmptyHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(nova::deck::emptyHostListState())));

    const bool smokeExit = QCoreApplication::arguments().contains("--smoke-exit");

    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection
    );
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreated,
        &app,
        [smokeExit, &app](QObject *object) {
            if (smokeExit && object != nullptr) {
                QTimer::singleShot(0, &app, &QCoreApplication::quit);
            }
        },
        Qt::QueuedConnection
    );

    engine.loadFromModule("Nova.Deck", "Main");

    return app.exec();
}

#include "main.moc"
