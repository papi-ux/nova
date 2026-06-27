#include "deck_layout.h"
#include "deck_gamepad.h"
#include "polaris_game_fixture.h"
#include "backend/deck_backend_interfaces.h"
#include "stream/deck_stream_media_adapters.h"

#include <QClipboard>
#include <QSocketNotifier>
#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <qqml.h>
#include <QObject>
#include <QString>
#include <QStringList>
#include <QTextStream>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <string>

#include <string_view>
#include <utility>

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


class QtBackendPreviewBridge final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap lastPreflightPreview READ lastPreflightPreview NOTIFY lastPreflightPreviewChanged)
    Q_PROPERTY(QVariantMap lastDiagnosticsPreview READ lastDiagnosticsPreview NOTIFY lastDiagnosticsPreviewChanged)
public:
    explicit QtBackendPreviewBridge(QObject* parent = nullptr)
        : QObject(parent), labGate_(nova::deck::backend::DeckLabGate::forMode(nova::deck::backend::DeckLabGateMode::Disabled)) {
        lastPreflightPreview_ = emptyPreflightModel();
        lastDiagnosticsPreview_ = emptyDiagnosticsModel();
    }

    void seedFixtureHost(std::string id, std::string displayName) {
        if (id.empty()) {
            return;
        }
        credentialStore_.upsertMetadata(nova::deck::backend::DeckCredentialMetadata{
            .hostId = id,
            .paired = false,
        });
        hostRepository_.upsertFixtureHost(std::move(id), std::move(displayName));
    }

    void seedReadOnlyHostSummary(nova::deck::backend::DeckHostSummary host) {
        if (host.id.empty()) {
            return;
        }
        credentialStore_.upsertMetadata(nova::deck::backend::DeckCredentialMetadata{
            .hostId = host.id,
            .paired = false,
        });
        hostRepository_.upsertSanitizedHostSummary(std::move(host));
    }

    [[nodiscard]] QVariantMap lastPreflightPreview() const {
        return lastPreflightPreview_;
    }

    [[nodiscard]] QVariantMap lastDiagnosticsPreview() const {
        return lastDiagnosticsPreview_;
    }

    Q_INVOKABLE QVariantMap requestBackendPreflightPreview(const QVariantMap& launchIntentPreview) {
        const auto preview = nova::deck::backend::requestDeckBackendPreflightPreview(
            hostRepository_,
            credentialStore_,
            preflightService_,
            coordinator_,
            labGate_,
            requestFrom(launchIntentPreview));
        lastPreflightPreview_ = toPreflightModel(preview);
        emit lastPreflightPreviewChanged();
        return lastPreflightPreview_;
    }

    Q_INVOKABLE QVariantMap requestBackendDiagnosticsPreview(const QVariantMap& launchIntentPreview) {
        const auto preview = nova::deck::backend::requestDeckBackendDiagnosticsPreview(
            hostRepository_,
            credentialStore_,
            preflightService_,
            coordinator_,
            diagnostics_,
            labGate_,
            requestFrom(launchIntentPreview));
        lastDiagnosticsPreview_ = toDiagnosticsModel(preview);
        emit lastDiagnosticsPreviewChanged();
        return lastDiagnosticsPreview_;
    }

signals:
    void lastPreflightPreviewChanged();
    void lastDiagnosticsPreviewChanged();

private:
    static nova::deck::backend::DeckPublicBackendPreviewRequest requestFrom(const QVariantMap& launchIntentPreview) {
        return nova::deck::backend::DeckPublicBackendPreviewRequest{
            .hostId = launchIntentPreview.value(QStringLiteral("hostId")).toString().toStdString(),
            .gameId = launchIntentPreview.value(QStringLiteral("gameId")).toString().toStdString(),
            .profileId = launchIntentPreview.value(QStringLiteral("streamProfileId")).toString().toStdString(),
        };
    }

    static QVariantMap emptyPreflightModel() {
        QVariantMap model;
        model.insert("statusCode", QStringLiteral("backend-preflight-not-requested"));
        model.insert("approved", false);
        model.insert("blockerCodes", QVariantList{});
        model.insert("launchDryRunAllowed", false);
        model.insert("streamAllowed", false);
        model.insert("backendPowerStarted", false);
        model.insert("publicCopy", QStringLiteral("Backend preflight preview has not been requested."));
        return model;
    }

    static QVariantMap emptyDiagnosticsModel() {
        QVariantMap model;
        model.insert("statusCode", QStringLiteral("backend-diagnostics-not-requested"));
        model.insert("privacyCode", QStringLiteral("redacted-public-dto"));
        model.insert("copyText", QStringLiteral("Backend diagnostics preview has not been requested."));
        return model;
    }

    static QVariantMap toPreflightModel(const nova::deck::backend::DeckPublicPreflightPreview& preview) {
        QVariantList blockerCodes;
        for (const auto& code : preview.blockerCodes) {
            blockerCodes.append(QString::fromStdString(code));
        }

        QVariantMap model;
        model.insert("statusCode", QString::fromStdString(preview.statusCode));
        model.insert("approved", preview.approved);
        model.insert("blockerCodes", blockerCodes);
        model.insert("launchDryRunAllowed", preview.launchDryRunAllowed);
        model.insert("streamAllowed", preview.streamAllowed);
        model.insert("backendPowerStarted", preview.backendPowerStarted);
        model.insert("publicCopy", QString::fromStdString(preview.publicCopy));
        return model;
    }

    static QVariantMap toDiagnosticsModel(const nova::deck::backend::DeckPublicDiagnosticsPreview& preview) {
        QVariantMap model;
        model.insert("statusCode", QString::fromStdString(preview.statusCode));
        model.insert("privacyCode", QString::fromStdString(preview.privacyCode));
        model.insert("copyText", QString::fromStdString(preview.copyText));
        return model;
    }

    nova::deck::backend::DeckFakeHostRepository hostRepository_;
    nova::deck::backend::DeckCredentialStore credentialStore_;
    nova::deck::backend::DeckLaunchPreflightService preflightService_;
    nova::deck::backend::DeckStreamSessionCoordinator coordinator_;
    nova::deck::backend::DeckDiagnosticsModel diagnostics_;
    nova::deck::backend::DeckLabGate labGate_;
    QVariantMap lastPreflightPreview_{};
    QVariantMap lastDiagnosticsPreview_{};
};

class QtPreviewLifecycleBridge final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap lastReport READ lastReport NOTIFY lastReportChanged)
    Q_PROPERTY(QVariantMap operatorAuthorization READ operatorAuthorization NOTIFY operatorAuthorizationChanged)
public:
    explicit QtPreviewLifecycleBridge(
        nova::deck::stream::DeckGuardedPreviewLifecycleGate& lifecycleGate,
        QObject* parent = nullptr)
        : QObject(parent), lifecycleGate_(lifecycleGate) {
        lastReportModel_ = toLifecycleReportModel(lifecycleGate_.lastReport());
    }

    [[nodiscard]] QVariantMap lastReport() const {
        return lastReportModel_;
    }

    [[nodiscard]] QVariantMap operatorAuthorization() const {
        return toOperatorAuthorizationModel(operatorPolicy_.snapshot());
    }

    Q_INVOKABLE QVariantMap authorizeOperatorDryRun() {
        operatorPolicy_.authorizeDryRun("deck-local-dry-run-operator-approved");
        emit operatorAuthorizationChanged();
        return operatorAuthorization();
    }

    Q_INVOKABLE QVariantMap authorizeOperatorStart() {
        operatorPolicy_.authorizeStart("deck-local-start-operator-approved");
        emit operatorAuthorizationChanged();
        return operatorAuthorization();
    }

    Q_INVOKABLE QVariantMap armNoNetworkPreview(const QVariantMap& launchIntentPreview) {
        const auto report = lifecycleGate_.armNoNetwork(nova::deck::stream::DeckStreamRequest{
            .hostId = launchIntentPreview.value(QStringLiteral("hostId")).toString().toStdString(),
            .gameId = launchIntentPreview.value(QStringLiteral("gameId")).toString().toStdString(),
            .width = 1280,
            .height = 800,
            .fps = 60,
            .bitrateKbps = 20000,
        });
        return updateLastReport(report, launchIntentPreview);
    }

    Q_INVOKABLE QVariantMap requestGuardedHostNetworkStart(const QVariantMap& launchIntentPreview) {
        return updateLastReport(lifecycleGate_.requestGuardedHostNetworkStart(), launchIntentPreview);
    }

    Q_INVOKABLE QVariantMap requestOperatorAuthorizedDryRun(const QVariantMap& launchIntentPreview) {
        return updateLastReport(
            lifecycleGate_.requestOperatorAuthorizedDryRun(operatorPolicy_.snapshot()),
            launchIntentPreview);
    }

    Q_INVOKABLE QVariantMap requestHostStartDryRunPreflight(const QVariantMap& launchIntentPreview) {
        const auto report = lifecycleGate_.requestHostStartDryRunPreflight(
            operatorPolicy_.snapshot(),
            nova::deck::stream::DeckStreamRequest{
                .hostId = launchIntentPreview.value(QStringLiteral("hostId")).toString().toStdString(),
                .gameId = launchIntentPreview.value(QStringLiteral("gameId")).toString().toStdString(),
                .width = 1280,
                .height = 800,
                .fps = 60,
                .bitrateKbps = 20000,
            });
        return updateLastReport(report, launchIntentPreview);
    }

    Q_INVOKABLE QVariantMap requestOperatorAuthorizedHostNetworkStart(const QVariantMap& launchIntentPreview) {
        return updateLastReport(
            lifecycleGate_.requestOperatorAuthorizedHostNetworkStart(operatorPolicy_.snapshot()),
            launchIntentPreview);
    }

    Q_INVOKABLE QVariantMap stopPreview() {
        return updateLastReport(lifecycleGate_.stop());
    }

signals:
    void lastReportChanged();
    void operatorAuthorizationChanged();

private:
    static QString lifecycleStateLabel(const nova::deck::stream::DeckStreamSessionState state) {
        using nova::deck::stream::DeckStreamSessionState;
        switch (state) {
        case DeckStreamSessionState::Idle:
            return QStringLiteral("idle");
        case DeckStreamSessionState::Preparing:
            return QStringLiteral("preparing");
        case DeckStreamSessionState::Starting:
            return QStringLiteral("starting");
        case DeckStreamSessionState::Active:
            return QStringLiteral("active");
        case DeckStreamSessionState::Stopping:
            return QStringLiteral("stopping");
        case DeckStreamSessionState::Stopped:
            return QStringLiteral("stopped");
        case DeckStreamSessionState::Cancelled:
            return QStringLiteral("cancelled");
        case DeckStreamSessionState::Failed:
            return QStringLiteral("failed");
        }
        return QStringLiteral("unknown");
    }

    static QString requestSummaryForReport(const nova::deck::stream::DeckGuardedPreviewLifecycleReport& report) {
        if (report.hostId.empty() && report.gameId.empty()) {
            return QStringLiteral("No selected request has been armed yet.");
        }
        return QStringLiteral("host=%1 · game=%2 · %3x%4@%5 · %6 kbps · no-network")
            .arg(QString::fromStdString(report.hostId), QString::fromStdString(report.gameId))
            .arg(report.width)
            .arg(report.height)
            .arg(report.fps)
            .arg(report.bitrateKbps);
    }

    static QString operatorAuthorizationStateLabel(
        const nova::deck::stream::DeckOperatorStartAuthorizationMode mode) {
        using nova::deck::stream::DeckOperatorStartAuthorizationMode;
        switch (mode) {
        case DeckOperatorStartAuthorizationMode::Blocked:
            return QStringLiteral("blocked");
        case DeckOperatorStartAuthorizationMode::DryRunAuthorized:
            return QStringLiteral("dry-run-authorized");
        case DeckOperatorStartAuthorizationMode::StartAuthorized:
            return QStringLiteral("start-authorized");
        }
        return QStringLiteral("blocked");
    }

    static QVariantMap toOperatorAuthorizationModel(
        const nova::deck::stream::DeckOperatorStartAuthorizationSnapshot& authorization) {
        QVariantMap model;
        model.insert("state", operatorAuthorizationStateLabel(authorization.mode));
        model.insert("statusCode", QString::fromStdString(authorization.statusCode));
        model.insert("reason", QString::fromStdString(authorization.reason));
        model.insert("dryRunAuthorized", authorization.dryRunAuthorized);
        model.insert("startAuthorized", authorization.startAuthorized);
        model.insert("tokenless", authorization.tokenless);
        model.insert("networkStarted", authorization.networkStarted);
        return model;
    }

    QString displayNameForReport(
        const QVariantMap& launchIntentPreview,
        const QString& reportId,
        const QString& displayNameKey,
        const QString& fallbackKey) const {
        if (!reportId.isEmpty() && launchIntentPreview.value(fallbackKey).toString() == reportId) {
            return launchIntentPreview.value(displayNameKey).toString();
        }
        return lastReportModel_.value(displayNameKey).toString();
    }

    QVariantMap toLifecycleReportModel(
        const nova::deck::stream::DeckGuardedPreviewLifecycleReport& report,
        const QVariantMap& launchIntentPreview = QVariantMap{}) const {
        const QString hostId = QString::fromStdString(report.hostId);
        const QString gameId = QString::fromStdString(report.gameId);
        QVariantMap model;
        model.insert("state", lifecycleStateLabel(report.state));
        model.insert("statusCode", QString::fromStdString(report.statusCode));
        model.insert("reason", QString::fromStdString(report.reason));
        model.insert("hostId", hostId);
        model.insert("hostDisplayName", displayNameForReport(
            launchIntentPreview,
            hostId,
            QStringLiteral("hostDisplayName"),
            QStringLiteral("hostId")));
        model.insert("gameId", gameId);
        model.insert("gameTitle", displayNameForReport(
            launchIntentPreview,
            gameId,
            QStringLiteral("gameTitle"),
            QStringLiteral("gameId")));
        model.insert("width", report.width);
        model.insert("height", report.height);
        model.insert("fps", report.fps);
        model.insert("bitrateKbps", report.bitrateKbps);
        model.insert("requestSummary", requestSummaryForReport(report));
        model.insert("prepared", report.prepared);
        model.insert("armed", report.armed);
        model.insert("dryRunPreflightRequested", report.dryRunPreflightRequested);
        model.insert("hostStartBoundaryExplicit", report.hostStartBoundaryExplicit);
        model.insert("hostStartContractAuthorized", report.hostStartContractAuthorized);
        model.insert("operatorAuthorizationState", QString::fromStdString(report.operatorAuthorizationState));
        model.insert("networkStartAllowed", report.networkStartAllowed);
        model.insert("networkStarted", report.networkStarted);
        model.insert("transitionCount", static_cast<qulonglong>(report.transitionCount));
        return model;
    }

    QVariantMap updateLastReport(
        const nova::deck::stream::DeckGuardedPreviewLifecycleReport& report,
        const QVariantMap& launchIntentPreview = QVariantMap{}) {
        lastReportModel_ = toLifecycleReportModel(report, launchIntentPreview);
        emit lastReportChanged();
        return lastReportModel_;
    }

    nova::deck::stream::DeckGuardedPreviewLifecycleGate& lifecycleGate_;
    nova::deck::stream::DeckOperatorStartAuthorizationPolicy operatorPolicy_{};
    QVariantMap lastReportModel_{};
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
        item.insert("subtitle", QStringLiteral("Read-only snapshot host — backend-owned sanitized summary."));
        item.insert("provenanceLabel", QStringLiteral("legacy-layout-adapter"));
        item.insert("initialFocus", host.initialFocus);
        model.append(item);
    }
    return model;
}

QVariantList toHostModel(const std::vector<nova::deck::backend::DeckPublicReadOnlyHostItem>& hosts) {
    QVariantList model;
    for (const auto& host : hosts) {
        QVariantMap item;
        item.insert("id", toQString(host.id));
        item.insert("displayName", toQString(host.displayName));
        item.insert("statusLabel", toQString(host.statusLabel));
        item.insert("subtitle", toQString(host.subtitle));
        item.insert("provenanceLabel", toQString(host.provenanceLabel));
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
    model.insert("provenanceLabel", QStringLiteral("legacy-layout-adapter"));
    return model;
}

QVariantMap toHostDetailModel(const nova::deck::backend::DeckPublicReadOnlyHostItem& detail) {
    QVariantMap model;
    model.insert("id", toQString(detail.id));
    model.insert("displayName", toQString(detail.displayName));
    model.insert("statusLabel", toQString(detail.statusLabel));
    model.insert("subtitle", toQString(detail.subtitle));
    model.insert("provenanceLabel", toQString(detail.provenanceLabel));
    return model;
}

QVariantMap toLibraryGameCardModel(const nova::deck::DeckLibraryGameCard& game) {
    QVariantMap item;
    item.insert("id", toQString(game.id));
    item.insert("title", toQString(game.title));
    item.insert("sourceRuntimeLabel", toQString(game.sourceRuntimeLabel));
    item.insert("launchModeLabel", toQString(game.launchModeLabel));
    item.insert("installedLabel", toQString(game.installedLabel));
    item.insert("initialFocus", game.initialFocus);
    return item;
}

QVariantList toLibraryGameModel(const std::vector<nova::deck::DeckLibraryGameCard>& games) {
    QVariantList model;
    for (const auto& game : games) {
        model.append(toLibraryGameCardModel(game));
    }
    return model;
}

QVariantList toLibraryGameModel(const std::vector<nova::deck::backend::DeckPublicReadOnlyGameItem>& games) {
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

QVariantMap toReadOnlyPreflightModel(const nova::deck::backend::DeckPublicReadOnlyPreflightState& preflight) {
    QVariantList blockerCodes;
    for (const auto& code : preflight.blockerCodes) {
        blockerCodes.append(toQString(code));
    }
    QVariantMap model;
    model.insert("statusCode", toQString(preflight.statusCode));
    model.insert("blockerCodes", blockerCodes);
    model.insert("launchDryRunAllowed", preflight.launchDryRunAllowed);
    model.insert("streamAllowed", preflight.streamAllowed);
    model.insert("backendPowerStarted", preflight.backendPowerStarted);
    model.insert("publicCopy", toQString(preflight.publicCopy));
    return model;
}

QVariantMap toReadOnlyDtoParityModel(const nova::deck::backend::DeckPublicReadOnlyDtoParity& dtoParity) {
    QVariantMap model;
    model.insert("contractId", toQString(dtoParity.contractId));
    model.insert("ownerCode", toQString(dtoParity.ownerCode));
    model.insert("privacyCode", toQString(dtoParity.privacyCode));
    model.insert("readinessCode", toQString(dtoParity.readinessCode));
    model.insert("collapsedSummary", toQString(dtoParity.collapsedSummary));
    model.insert("expandedDiagnostics", toQString(dtoParity.expandedDiagnostics));
    model.insert("artifactSummary", toQString(dtoParity.artifactSummary));
    return model;
}

QVariantMap toReadOnlyPlayerStateModel(const nova::deck::backend::DeckPublicReadOnlyPlayerState& playerState) {
    QVariantMap model;
    model.insert("title", toQString(playerState.title));
    model.insert("body", toQString(playerState.body));
    model.insert("actionLabel", toQString(playerState.actionLabel));
    model.insert("safetyLabel", toQString(playerState.safetyLabel));
    model.insert("provenanceLabel", toQString(playerState.provenanceLabel));
    model.insert("focusOrder", toQString(playerState.focusOrder));
    model.insert("focusOrderCopy", toQString(playerState.focusOrderCopy));
    return model;
}

QVariantMap toReadOnlyStateModel(const nova::deck::backend::DeckPublicReadOnlyHostLibraryState& state) {
    QVariantMap model;
    model.insert("scenarioId", toQString(state.scenarioId));
    model.insert("scenarioLabel", toQString(state.scenarioLabel));
    model.insert("sourceLabel", toQString(state.sourceLabel));
    model.insert("readOnly", state.readOnly);
    model.insert("hosts", toHostModel(state.hosts));
    model.insert("games", toLibraryGameModel(state.games));
    model.insert("preflight", toReadOnlyPreflightModel(state.preflight));
    model.insert("playerState", toReadOnlyPlayerStateModel(state.playerState));
    model.insert("dtoParity", toReadOnlyDtoParityModel(state.dtoParity));
    return model;
}

QVariantList toReadOnlyStateMatrixModel(const std::vector<nova::deck::backend::DeckPublicReadOnlyHostLibraryState>& matrix) {
    QVariantList model;
    for (const auto& state : matrix) {
        model.append(toReadOnlyStateModel(state));
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

QVariantMap toLaunchIntentPreviewModel(
    const nova::deck::DeckLaunchIntent& intent,
    const nova::deck::DeckStreamIntent& streamIntent) {
    QVariantMap model;
    model.insert("hostId", toQString(intent.host.id));
    model.insert("hostDisplayName", toQString(intent.host.displayName));
    model.insert("gameId", toQString(intent.game.libraryId));
    model.insert("gameTitle", toQString(intent.game.title));
    model.insert("streamProfileId", toQString(intent.streamProfile.id));
    model.insert("streamProfileLabel", toQString(intent.streamProfile.displayName));
    model.insert("preflightReason", toQString(intent.preflight.reason));
    model.insert("publicCopy", toQString(intent.publicPreviewCopy));
    model.insert("inertPreviewUri", toQString(intent.inertPreviewUri));
    model.insert("streamLifecycleCopy", toQString(streamIntent.publicCopy));
    model.insert("noopPreview", true);
    model.insert("notStarted", true);
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

QVariantMap toPresenterReadinessModel(const nova::deck::stream::DeckVaapiPresenterReadinessReport& report) {
    QVariantMap model;
    model.insert("statusCode", toQString(report.statusCode));
    model.insert("label", toQString(report.label));
    model.insert("detail", toQString(report.detail));
    model.insert("ready", report.ready);
    model.insert("hardwarePresenterPlanned", report.hardwarePresenterPlanned);
    model.insert("drmPrimeObjectCount", report.importPlan.drmPrimeObjectCount);
    model.insert("drmPrimeLayerCount", report.importPlan.drmPrimeLayerCount);
    return model;
}

int intArgumentAfter(const QStringList& arguments, const QString& flag, const int fallback) {
    const int index = arguments.indexOf(flag);
    if (index < 0 || index + 1 >= arguments.size()) {
        return fallback;
    }
    bool ok = false;
    const int value = arguments.at(index + 1).toInt(&ok);
    return ok ? value : fallback;
}

QString stringArgumentAfter(const QStringList& arguments, const QString& flag) {
    const int index = arguments.indexOf(flag);
    if (index < 0 || index + 1 >= arguments.size()) {
        return QString{};
    }
    return arguments.at(index + 1);
}

void captureFrontendSmokeFrame(QQuickWindow* window, const QString& frontendSmokeCapturePath) {
    if (window == nullptr || frontendSmokeCapturePath.isEmpty()) {
        return;
    }
    QFileInfo captureInfo(frontendSmokeCapturePath);
    if (!captureInfo.absoluteDir().exists()) {
        QDir().mkpath(captureInfo.absolutePath());
    }
    const QImage frame = window->grabWindow();
    if (!frame.isNull() && frame.save(frontendSmokeCapturePath)) {
        qInfo().noquote() << "Nova Deck frontend smoke capture"
                          << frontendSmokeCapturePath
                          << QStringLiteral("size=%1x%2").arg(frame.width()).arg(frame.height());
        return;
    }
    qWarning().noquote() << "Nova Deck frontend smoke capture failed" << frontendSmokeCapturePath;
}

QString boolLabel(const QVariant& value) {
    return value.toBool() ? QStringLiteral("true") : QStringLiteral("false");
}

void writeBackendDtoInteractionSmokeArtifact(QObject* rootObject, const QString& artifactPath) {
    if (rootObject == nullptr || artifactPath.isEmpty()) {
        return;
    }

    QVariant returned;
    const bool invoked = QMetaObject::invokeMethod(
        rootObject,
        "runBackendDtoPreviewInteractionSmoke",
        Q_RETURN_ARG(QVariant, returned));

    QFileInfo artifactInfo(artifactPath);
    if (!artifactInfo.absoluteDir().exists()) {
        QDir().mkpath(artifactInfo.absolutePath());
    }

    QFile artifact(artifactPath);
    if (!artifact.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        qWarning().noquote() << "Nova Deck backend-dto-interaction-smoke artifact failed" << artifactPath;
        return;
    }

    const QVariantMap report = returned.toMap();
    QTextStream stream(&artifact);
    stream << "Nova Deck backend DTO interaction smoke\n";
    stream << "invoked=" << (invoked ? "true" : "false") << "\n";
    stream << "preflight_button=" << report.value(QStringLiteral("preflightButton")).toString() << "\n";
    stream << "diagnostics_button=" << report.value(QStringLiteral("diagnosticsButton")).toString() << "\n";
    stream << "preflight_status=" << report.value(QStringLiteral("preflightStatus")).toString() << "\n";
    stream << "preflight_blockers=" << report.value(QStringLiteral("preflightBlockerCodes")).toString() << "\n";
    stream << "preflight_launch_dry_run_allowed=" << boolLabel(report.value(QStringLiteral("preflightLaunchDryRunAllowed"))) << "\n";
    stream << "preflight_stream_allowed=" << boolLabel(report.value(QStringLiteral("preflightStreamAllowed"))) << "\n";
    stream << "preflight_backend_power_started=" << boolLabel(report.value(QStringLiteral("preflightBackendPowerStarted"))) << "\n";
    stream << "preflight_public_copy=" << report.value(QStringLiteral("preflightPublicCopy")).toString() << "\n";
    stream << "dto_contract=" << report.value(QStringLiteral("dtoContractId")).toString() << "\n";
    stream << "dto_owner=" << report.value(QStringLiteral("dtoOwnerCode")).toString() << "\n";
    stream << "dto_privacy=" << report.value(QStringLiteral("dtoPrivacyCode")).toString() << "\n";
    stream << "dto_readiness=" << report.value(QStringLiteral("dtoReadinessCode")).toString() << "\n";
    stream << "dto_collapsed_summary=" << report.value(QStringLiteral("dtoCollapsedSummary")).toString() << "\n";
    stream << "dto_player_state_provenance=" << report.value(QStringLiteral("playerStateProvenance")).toString() << "\n";
    stream << "dto_player_state_focus_order=" << report.value(QStringLiteral("playerStateFocusOrder")).toString() << "\n";
    stream << "dto_player_state_focus_order_copy=" << report.value(QStringLiteral("playerStateFocusOrderCopy")).toString() << "\n";
    stream << "diagnostics_status=" << report.value(QStringLiteral("diagnosticsStatus")).toString() << "\n";
    stream << "diagnostics_privacy=" << report.value(QStringLiteral("diagnosticsPrivacyCode")).toString() << "\n";
    stream << "diagnostics_copy=" << report.value(QStringLiteral("diagnosticsCopyText")).toString() << "\n";
    artifact.close();

    qInfo().noquote() << "Nova Deck backend-dto-interaction-smoke artifact" << artifactPath;
}

void writeBackendReadOnlyStateMatrixSmokeArtifact(QObject* rootObject, const QString& artifactPath) {
    if (rootObject == nullptr || artifactPath.isEmpty()) {
        return;
    }

    QVariant returned;
    const bool invoked = QMetaObject::invokeMethod(
        rootObject,
        "runBackendReadOnlyStateMatrixSmoke",
        Q_RETURN_ARG(QVariant, returned));

    QFileInfo artifactInfo(artifactPath);
    if (!artifactInfo.absoluteDir().exists()) {
        QDir().mkpath(artifactInfo.absolutePath());
    }

    QFile artifact(artifactPath);
    if (!artifact.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        qWarning().noquote() << "Nova Deck backend-readonly-state-matrix-smoke artifact failed" << artifactPath;
        return;
    }

    QTextStream stream(&artifact);
    stream << "Nova Deck backend read-only state matrix smoke\n";
    stream << "invoked=" << (invoked ? "true" : "false") << "\n";
    const QVariantList states = returned.toList();
    for (const auto& row : states) {
        const QVariantMap state = row.toMap();
        stream << "matrix_scenario=" << state.value(QStringLiteral("scenarioId")).toString()
               << " label=" << state.value(QStringLiteral("scenarioLabel")).toString()
               << " hosts=" << state.value(QStringLiteral("hostCount")).toInt()
               << " games=" << state.value(QStringLiteral("gameCount")).toInt()
               << " status=" << state.value(QStringLiteral("preflightStatus")).toString()
               << " blockers=" << state.value(QStringLiteral("blockerCodes")).toString()
               << " backendPowerStarted=" << boolLabel(state.value(QStringLiteral("backendPowerStarted")))
               << " dtoContract=" << state.value(QStringLiteral("dtoContractId")).toString()
               << " dtoPrivacy=" << state.value(QStringLiteral("dtoPrivacyCode")).toString()
               << " dtoReadiness=" << state.value(QStringLiteral("dtoReadinessCode")).toString()
               << " primary=" << state.value(QStringLiteral("primaryBlockerCopy")).toString()
               << " stateHeadline=" << state.value(QStringLiteral("productStateHeadline")).toString()
               << " stateAction=" << state.value(QStringLiteral("productStateAction")).toString()
               << " stateSafety=" << state.value(QStringLiteral("productStateSafety")).toString()
               << " stateProvenance=" << state.value(QStringLiteral("productStateProvenance")).toString()
               << " stateFocusOrder=" << state.value(QStringLiteral("productStateFocusOrder")).toString()
               << " diagnostics=" << state.value(QStringLiteral("secondaryDiagnosticsCopy")).toString()
               << " dtoParity=" << state.value(QStringLiteral("dtoParityDiagnostics")).toString()
               << " collapsedFirstPaint=" << boolLabel(state.value(QStringLiteral("collapsedFirstPaint")))
               << " expansionToggle=" << state.value(QStringLiteral("expansionToggleObject")).toString()
               << " controllerReachable=" << boolLabel(state.value(QStringLiteral("expansionToggleControllerReachable")))
               << " expandedVisible=" << boolLabel(state.value(QStringLiteral("expandedDiagnosticsVisible")))
               << " expandedDiagnosticsCopy=" << state.value(QStringLiteral("expandedDiagnosticsCopy")).toString()
               << " expandedDtoParityCopy=" << state.value(QStringLiteral("expandedDtoParityCopy")).toString()
               << "\n";
    }
    artifact.close();

    qInfo().noquote() << "Nova Deck backend-readonly-state-matrix-smoke artifact" << artifactPath;
}

void writeExpandedDiagnosticsFrameSmokeArtifact(QObject* rootObject, const QString& artifactPath) {
    if (rootObject == nullptr || artifactPath.isEmpty()) {
        return;
    }

    QVariant returned;
    const bool invoked = QMetaObject::invokeMethod(
        rootObject,
        "runExpandedDiagnosticsFrameSmoke",
        Q_RETURN_ARG(QVariant, returned));

    QFileInfo artifactInfo(artifactPath);
    if (!artifactInfo.absoluteDir().exists()) {
        QDir().mkpath(artifactInfo.absolutePath());
    }

    QFile artifact(artifactPath);
    if (!artifact.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        qWarning().noquote() << "Nova Deck expanded-diagnostics-frame-smoke artifact failed" << artifactPath;
        return;
    }

    const QVariantMap report = returned.toMap();
    QTextStream stream(&artifact);
    stream << "Nova Deck expanded diagnostics frame smoke\n";
    stream << "invoked=" << (invoked ? "true" : "false") << "\n";
    stream << "liveExpandedBy=" << report.value(QStringLiteral("liveExpandedBy")).toString() << "\n";
    stream << "expandedFrameFocusTarget=" << report.value(QStringLiteral("expandedFrameFocusTarget")).toString() << "\n";
    stream << "expandedDiagnosticsLaneFocusTarget=" << report.value(QStringLiteral("expandedDiagnosticsLaneFocusTarget")).toString() << "\n";
    stream << "expandedDiagnosticsLaneReadable=" << boolLabel(report.value(QStringLiteral("expandedDiagnosticsLaneReadable"))) << "\n";
    stream << "expandedDensityRowsPaged=" << boolLabel(report.value(QStringLiteral("expandedDensityRowsPaged"))) << "\n";
    stream << "expandedDiagnosticsPageAffordanceVisible=" << boolLabel(report.value(QStringLiteral("expandedDiagnosticsPageAffordanceVisible"))) << "\n";
    stream << "expandedDiagnosticsPageAffordancePosition=" << report.value(QStringLiteral("expandedDiagnosticsPageAffordancePosition")).toString() << "\n";
    stream << "expandedDiagnosticsPageAffordanceText=" << report.value(QStringLiteral("expandedDiagnosticsPageAffordanceText")).toString() << "\n";
    stream << "expandedDiagnosticsScrollNavigationMoved=" << boolLabel(report.value(QStringLiteral("expandedDiagnosticsScrollNavigationMoved"))) << "\n";
    stream << "expandedDiagnosticsPostScrollCue=" << report.value(QStringLiteral("expandedDiagnosticsPostScrollCue")).toString() << "\n";
    stream << "expandedDiagnosticsPostScrollCueContrast=" << report.value(QStringLiteral("expandedDiagnosticsPostScrollCueContrast")).toString() << "\n";
    stream << "expandedDiagnosticsPostScrollCueSpacing=" << report.value(QStringLiteral("expandedDiagnosticsPostScrollCueSpacing")).toString() << "\n";
    stream << "expandedDiagnosticsPostScrollCueOverlapsBlocker=" << boolLabel(report.value(QStringLiteral("expandedDiagnosticsPostScrollCueOverlapsBlocker"))) << "\n";
    stream << "expandedDiagnosticsPostScrollTarget=" << report.value(QStringLiteral("expandedDiagnosticsPostScrollTarget")).toString() << "\n";
    stream << "expandedDiagnosticsFocusAffordance=" << report.value(QStringLiteral("expandedDiagnosticsFocusAffordance")).toString() << "\n";
    stream << "expandedDiagnosticsPage2Readable=" << boolLabel(report.value(QStringLiteral("expandedDiagnosticsPage2Readable"))) << "\n";
    stream << "expandedDiagnosticsLaneHeight=" << report.value(QStringLiteral("expandedDiagnosticsLaneHeight")).toInt() << "\n";
    stream << "expandedFrameReadable=" << boolLabel(report.value(QStringLiteral("expandedFrameReadable"))) << "\n";
    stream << "expandedFrameSanitized=" << boolLabel(report.value(QStringLiteral("expandedFrameSanitized"))) << "\n";
    stream << "expandedFrameFirstPaintCrowding=" << boolLabel(report.value(QStringLiteral("expandedFrameFirstPaintCrowding"))) << "\n";
    stream << "expandedDiagnosticsCopy=" << report.value(QStringLiteral("expandedDiagnosticsCopy")).toString() << "\n";
    stream << "expandedDtoParityCopy=" << report.value(QStringLiteral("expandedDtoParityCopy")).toString() << "\n";
    stream << "expandedPublicCopy=" << report.value(QStringLiteral("expandedPublicCopy")).toString() << "\n";
    stream << "expandedBlockersCopy=" << report.value(QStringLiteral("expandedBlockersCopy")).toString() << "\n";
    artifact.close();

    qInfo().noquote() << "Nova Deck expanded-diagnostics-frame-smoke artifact" << artifactPath;
}
} // namespace

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);

    const QStringList appArguments = QCoreApplication::arguments();
    const auto profile = nova::deck::defaultWindowProfile();
    const auto sampleLibrary = nova::deck::loadSamplePolarisGameLibraryFixture();
    const nova::deck::backend::DeckLaunchPreflightService readOnlyPreflightService;
    const nova::deck::backend::DeckFixtureReadOnlyStateProvider readOnlyStateProvider(
        sampleLibrary,
        readOnlyPreflightService);
    const auto backendReadOnlyStateMatrix = readOnlyStateProvider.stateMatrix();
    const QString selectedMatrixScenario = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-readonly-state"));
    const auto backendReadOnlyState = readOnlyStateProvider.stateForScenario(selectedMatrixScenario.toStdString());
    std::vector<nova::deck::DeckHostListItem> launchPreviewHosts;
    launchPreviewHosts.reserve(backendReadOnlyState.hosts.size());
    int launchPreviewHostRow = 0;
    for (const auto& host : backendReadOnlyState.hosts) {
        launchPreviewHosts.push_back(nova::deck::DeckHostListItem{
            .id = host.id,
            .displayName = host.displayName,
            .statusLabel = host.statusLabel,
            .row = launchPreviewHostRow,
            .initialFocus = host.initialFocus,
        });
        ++launchPreviewHostRow;
    }
    auto selectedLaunchLibrary = sampleLibrary;
    if (backendReadOnlyState.games.empty()) {
        selectedLaunchLibrary.games.clear();
    }
    const std::string initialGameId = selectedLaunchLibrary.games.empty() ? std::string{} : selectedLaunchLibrary.games.front().id;
    const auto selectedBinding = nova::deck::resolveLaunchPreviewBinding(
        launchPreviewHosts,
        selectedLaunchLibrary,
        nova::deck::initialHostFocusTarget(launchPreviewHosts),
        initialGameId);
    const auto& selectedHostDetail = selectedBinding.hostDetail;
    const auto selectedHostDetailModel = backendReadOnlyState.hosts.empty()
        ? toHostDetailModel(selectedHostDetail)
        : toHostDetailModel(backendReadOnlyState.hosts.front());
    const auto& launchIntent = selectedBinding.intent;
    const auto streamIntent = nova::deck::resolveStreamIntent(launchIntent);
    const auto& launchCta = selectedBinding.launchCta;
    const auto& launchPreviewCopyAction = selectedBinding.copyAction;

    QtLocalClipboardBridge localClipboard;
    QtDeckGamepadBridge gamepadBridge;
    QtBackendPreviewBridge backendPreview;
    for (const auto& host : sampleLibrary.hosts) {
        backendPreview.seedReadOnlyHostSummary(nova::deck::backend::DeckHostSummary{
            .id = host.id,
            .displayName = host.displayName,
            .state = nova::deck::backend::DeckHostState::Fixture,
            .endpointClass = nova::deck::backend::DeckEndpointClass::Unknown,
            .fixtureOnly = true,
            .hasEndpointCandidate = false,
            .polarisAvailable = true,
            .standardAppListAvailable = true,
            .publicStatusLabel = host.statusLabel.empty() ? "Backend-owned fixture summary · read-only" : host.statusLabel,
            .publicSubtitle = "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read.",
            .publicProvenanceLabel = "fixture/read-only/backend-owned",
        });
    }
    using nova::deck::stream::DeckGuardedPreviewLifecycleGate;
    nova::deck::stream::DeckProductPreviewPipeline productPreviewPipeline;
    nova::deck::stream::DeckGuardedStreamSessionPreviewProducer productPreviewProducer;
    DeckGuardedPreviewLifecycleGate productPreviewLifecycleGate(productPreviewProducer);
    productPreviewLifecycleGate.attachProductPreviewPipeline(productPreviewPipeline);
    QtPreviewLifecycleBridge previewLifecycle(productPreviewLifecycleGate);
    const auto mediaProbe = nova::deck::stream::DeckLinuxMediaProbe::detect();
    const auto presenterReadiness = nova::deck::stream::DeckVaapiEglImagePresenter::readinessReportForPlan(
        nova::deck::stream::DeckQrhiVaapiImportPlan{
            .status = mediaProbe.runtimeVaapiDeviceAvailable
                ? nova::deck::stream::DeckQrhiVaapiImportStatus::DeckTargetUnavailable
                : nova::deck::stream::DeckQrhiVaapiImportStatus::NotAttempted,
            .detail = mediaProbe.runtimeVaapiDeviceAvailable
                ? std::string("Deck shell loaded; EGLImage presenter waits for a VAAPI frame and Qt Quick render target before host streaming starts")
                : mediaProbe.runtimeStatus,
        });
    qInfo().noquote() << "Nova Deck VAAPI/EGL presenter readiness"
                      << QString::fromStdString(presenterReadiness.statusCode)
                      << QString::fromStdString(presenterReadiness.detail);

    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("novaDeckShellName", toQString(profile.shellName));
    engine.rootContext()->setContextProperty("novaDeckWidth", profile.width);
    engine.rootContext()->setContextProperty("novaDeckHeight", profile.height);
    engine.rootContext()->setContextProperty("novaDeckFullscreenPreferred", profile.fullscreenPreferred);
    engine.rootContext()->setContextProperty("novaBackendReadOnlyStateMatrix", toReadOnlyStateMatrixModel(backendReadOnlyStateMatrix));
    engine.rootContext()->setContextProperty("novaBackendReadOnlyState", toReadOnlyStateModel(backendReadOnlyState));
    engine.rootContext()->setContextProperty("novaLibraryFixtureSource", toQString(backendReadOnlyState.sourceLabel));
    engine.rootContext()->setContextProperty("novaLibraryReadOnly", backendReadOnlyState.readOnly);
    engine.rootContext()->setContextProperty("novaLibraryGames", toLibraryGameModel(backendReadOnlyState.games));
    engine.rootContext()->setContextProperty("novaLibraryHosts", toHostModel(backendReadOnlyState.hosts));
    engine.rootContext()->setContextProperty("novaSelectedHostDetail", selectedHostDetailModel);
    engine.rootContext()->setContextProperty("novaSelectedGameCard", toLibraryGameCardModel(selectedBinding.gameCard));
    engine.rootContext()->setContextProperty("novaSelectedLaunchPreviewText", toQString(selectedBinding.preview.text));
    engine.rootContext()->setContextProperty("novaHostLaunchCta", toLaunchCtaModel(launchCta));
    engine.rootContext()->setContextProperty("novaLaunchIntentBoundary", toLaunchIntentBoundaryModel(launchIntent.boundary));
    engine.rootContext()->setContextProperty("novaLaunchIntentPreview", toLaunchIntentPreviewModel(launchIntent, streamIntent));
    engine.rootContext()->setContextProperty("novaLaunchPreviewCopyAction", toPreviewCopyActionModel(launchPreviewCopyAction));
    engine.rootContext()->setContextProperty("novaPresenterReadiness", toPresenterReadinessModel(presenterReadiness));
    engine.rootContext()->setContextProperty("novaPreviewLifecycle", &previewLifecycle);
    engine.rootContext()->setContextProperty("novaBackendPreview", &backendPreview);
    engine.rootContext()->setContextProperty("novaLocalClipboard", &localClipboard);
    engine.rootContext()->setContextProperty("novaGamepad", &gamepadBridge);
    engine.rootContext()->setContextProperty("novaInitialHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(launchPreviewHosts)));
    engine.rootContext()->setContextProperty("novaEmptyHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(nova::deck::emptyHostListState())));

    const bool smokeExit = appArguments.contains("--smoke-exit");
    const int frontendSmokeExitAfterMs = intArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-exit-after-ms"), 0);
    const QString frontendSmokeCapturePath = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-capture"));
    const QString backendDtoInteractionSmokePath = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-backend-dto-interactions"));
    const QString backendReadOnlyStateMatrixSmokePath = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-readonly-state-matrix"));
    const QString expandedDiagnosticsFrameSmokePath = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-expanded-diagnostics-frame"));
    const QString expandedDiagnosticsCapturePath = stringArgumentAfter(appArguments, QStringLiteral("--frontend-smoke-expanded-diagnostics-capture"));

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
        [smokeExit, frontendSmokeExitAfterMs, frontendSmokeCapturePath, backendDtoInteractionSmokePath, backendReadOnlyStateMatrixSmokePath, expandedDiagnosticsFrameSmokePath, expandedDiagnosticsCapturePath, &app, &productPreviewPipeline](QObject *object) {
            if (object != nullptr) {
                QObject* previewSurfaceObject = object->findChild<QObject*>("nova-product-preview-surface");
                if (auto* previewSurface = dynamic_cast<nova::deck::stream::DeckQtQuickRhiVaapiItem*>(previewSurfaceObject)) {
                    productPreviewPipeline.attachBorrowedSink(previewSurface);
                    QObject::connect(previewSurface, &QObject::destroyed, &app, [&productPreviewPipeline]() {
                        productPreviewPipeline.attachBorrowedSink(nullptr);
                    });
                    qInfo().noquote() << "Nova Deck product preview fixture pump"
                                      << "decoded-frame-sink-attached"
                                      << "product preview surface is attached; waiting for a real decoded hardware frame from the Deck media adapter before reporting render readiness";
                } else {
                    qInfo().noquote() << "Nova Deck product preview fixture pump"
                                      << "deck-target-unavailable"
                                      << "product preview surface object was not created by QML";
                }
            }
            if (!frontendSmokeCapturePath.isEmpty() && object != nullptr) {
                if (auto* window = qobject_cast<QQuickWindow*>(object)) {
                    QTimer::singleShot(1000, &app, [window, frontendSmokeCapturePath]() {
                        captureFrontendSmokeFrame(window, frontendSmokeCapturePath);
                    });
                } else {
                    qWarning().noquote() << "Nova Deck frontend smoke capture failed: root object is not a QQuickWindow";
                }
            }
            if (!backendDtoInteractionSmokePath.isEmpty() && object != nullptr) {
                QTimer::singleShot(500, &app, [object, backendDtoInteractionSmokePath]() {
                    writeBackendDtoInteractionSmokeArtifact(object, backendDtoInteractionSmokePath);
                });
            }
            if (!backendReadOnlyStateMatrixSmokePath.isEmpty() && object != nullptr) {
                QTimer::singleShot(650, &app, [object, backendReadOnlyStateMatrixSmokePath]() {
                    writeBackendReadOnlyStateMatrixSmokeArtifact(object, backendReadOnlyStateMatrixSmokePath);
                });
            }
            if (!expandedDiagnosticsFrameSmokePath.isEmpty() && object != nullptr) {
                QTimer::singleShot(1300, &app, [object, expandedDiagnosticsFrameSmokePath]() {
                    writeExpandedDiagnosticsFrameSmokeArtifact(object, expandedDiagnosticsFrameSmokePath);
                });
            }
            if (!expandedDiagnosticsCapturePath.isEmpty() && object != nullptr) {
                if (auto* window = qobject_cast<QQuickWindow*>(object)) {
                    QTimer::singleShot(1700, &app, [window, expandedDiagnosticsCapturePath]() {
                        captureFrontendSmokeFrame(window, expandedDiagnosticsCapturePath);
                    });
                } else {
                    qWarning().noquote() << "Nova Deck expanded diagnostics capture failed: root object is not a QQuickWindow";
                }
            }
            if (frontendSmokeExitAfterMs > 0 && object != nullptr) {
                QTimer::singleShot(frontendSmokeExitAfterMs, &app, &QCoreApplication::quit);
                return;
            }
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
