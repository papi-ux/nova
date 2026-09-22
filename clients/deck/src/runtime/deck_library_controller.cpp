#include "runtime/deck_library_controller.h"

#include <algorithm>
#include <QTcpSocket>

namespace nova::deck::runtime {
namespace {
bool sameIdentity(const identity::DeckMoonlightIdentity& expected,
                  const std::optional<identity::DeckMoonlightIdentity>& current) {
    if (!current || !current->loaded || expected.clientCertificatePem != current->clientCertificatePem ||
        expected.clientPrivateKeyPemForBackendOnly != current->clientPrivateKeyPemForBackendOnly ||
        expected.hosts.size() != current->hosts.size()) return false;
    for (const auto& host : expected.hosts) {
        const auto* saved = current->hostById(host.stableId());
        if (!saved || saved->serverCertificatePem != host.serverCertificatePem ||
            saved->preferredAddress() != host.preferredAddress() ||
            saved->preferredHttpPort() != host.preferredHttpPort() ||
            saved->nativeHttpsPort != host.nativeHttpsPort) return false;
    }
    return true;
}

bool stopsAutomaticRefresh(polaris::DeckPolarisRequestStatus status) {
    using polaris::DeckPolarisRequestStatus;
    return status == DeckPolarisRequestStatus::Unauthorized || status == DeckPolarisRequestStatus::CertMismatch ||
           status == DeckPolarisRequestStatus::InvalidIdentity;
}

const backend::DeckLiveHostProbe* selectedProbe(const backend::DeckLiveHostLibrarySnapshot& snapshot) {
    const auto found = std::find_if(snapshot.probes.begin(), snapshot.probes.end(),
        [&](const auto& p) { return p.hostId == snapshot.selectedHostId; });
    return found == snapshot.probes.end() ? nullptr : &*found;
}
QString destinationId(const backend::DeckLiveHostLibrarySnapshot& snapshot) {
    const auto* probe = selectedProbe(snapshot);
    if (probe && probe->spacesSupported && (!probe->spaces || probe->spaces->enabled))
        return probe->spaces ? QString::fromStdString(probe->spaces->selectedId) : QString{};
    return "desktop";
}
polaris::DeckPolarisResult<polaris::DeckSpaces> selectSpaceOnHost(
    const identity::DeckMoonlightIdentity& identity, const identity::DeckMoonlightHostRecord& host, int port,
    const std::string& id, const std::string& previous, const std::function<bool()>& cancelled) {
    auto client = backend::polarisClientForHost(identity, host, port, std::chrono::milliseconds(4000));
    auto fresh = client.fetchSpaces(cancelled);
    if (!fresh.ok()) return fresh;
    if (fresh.value->selectedId != previous || !fresh.value->permitsSelection(id))
        return {polaris::DeckPolarisRequestStatus::HttpError, 409, "destination changed", {}};
    auto selected = client.selectSpace(id, previous, cancelled); // Exactly one POST, never replayed.
    if (selected.ok() && (!selected.value->available || selected.value->selectedId != id))
        return {polaris::DeckPolarisRequestStatus::MalformedBody, 200, "selection not confirmed", {}};
    return selected;
}
QString spaceStatus(const std::string& state) {
    if (state == "ready") return "Ready";
    if (state == "running") return "Running";
    if (state == "starting") return "Starting";
    if (state == "stopping") return "Stopping";
    if (state == "in_use") return "In use";
    return "Unavailable";
}
QString blockedCopy(const polaris::DeckSpaces& spaces) {
    if (spaces.available && spaces.canSwitch) {
        if (!spaces.desktopAllowed && spaces.spaces.size() == 1)
            return QString("This device can use %1. Desktop access is off.").arg(QString::fromStdString(spaces.spaces.front().name));
        return "Choose where to play on this PC.";
    }
    if (spaces.unavailableReason == "no_space_assigned") return "No Space is assigned to this device. Check device access in Polaris.";
    if (spaces.unavailableReason == "stopping") return "Spaces are stopping. Refresh to check again.";
    if (spaces.unavailableReason == "reconfiguring") return "Spaces are being configured. Refresh to check again.";
    if (spaces.switchBlockedReason == "your_stream") return "Disconnect your stream before changing where to play.";
    if (spaces.switchBlockedReason == "desktop_stream") return "The desktop is being streamed. Try again when that stream ends.";
    return "Spaces cannot be changed right now. Refresh to check again.";
}

QVariantMap state(bool busy, bool failed, const QString& copy, bool automatic = false) {
    return {{"busy", busy}, {"failed", failed}, {"copy", copy}, {"automatic", automatic}};
}
} // namespace

struct DeckLibraryController::Result {
    backend::DeckLiveHostLibrarySnapshot snapshot;
    QString copy = "The game list could not be refreshed. Try again.";
    bool failed = true;
    bool automatic = false, retryAllowed = true, changing = false;
};

DeckLibraryController::DeckLibraryController(std::optional<identity::DeckMoonlightIdentity> identity,
    backend::DeckLiveHostLibrarySnapshot snapshot, DeckLibraryIdentityLoader loader,
    DeckLibraryFetcherFactory fetcherFactory, QObject* parent, DeckSpaceSelector spaceSelector)
    : QObject(parent), identity_(std::move(identity)), snapshot_(std::move(snapshot)),
      loader_(std::move(loader)), fetcherFactory_(std::move(fetcherFactory)),
      spaceSelector_(spaceSelector ? std::move(spaceSelector) : selectSpaceOnHost),
      state_(runtime::state(false, false, {})) {
    timer_.setInterval(20);
    connect(&timer_, &QTimer::timeout, this, &DeckLibraryController::poll);
    automaticClock_.start();
    automaticTimer_.setSingleShot(true);
    automaticTimer_.setTimerType(Qt::PreciseTimer);
    connect(&automaticTimer_, &QTimer::timeout, this, [this] { request(selectedHost(), true); });
    for (const auto& probe : snapshot_.probes)
        if (probe.hostId == selectedHost().toStdString()) automaticBlocked_ = stopsAutomaticRefresh(probe.status);
    updateSpacesState();
}

DeckLibraryController::~DeckLibraryController() {
    *cancelled_ = true; ++*generation_;
    timer_.stop();
    automaticTimer_.stop();
    if (worker_) {
        // The normal window close waits asynchronously. The HTTP client has an
        // absolute deadline; no worker may outlive the app on forced shutdown.
        worker_->wait();
        delete worker_;
    }
}

DeckNativeTargetResolver DeckLibraryController::targetResolver() const {
    if (busy() || closing_ || state_.value("failed").toBool() || !identity_) return {};
    return [expected = *identity_, loader = loader_, resolver = nativeTargetResolver(identity_, snapshot_),
            generation = generation_, acceptedGeneration = generation_->load()]
        (const QString& host, const QString& game) -> std::optional<DeckNativeLaunchTarget> {
        // Executed on the native session worker, never the GUI thread. A saved
        // pairing changed since rendering cannot authorize a new connection.
        if (generation->load() != acceptedGeneration || !sameIdentity(expected, loader())) return std::nullopt;
        auto target = resolver(host, game);
        if (target && target->hostTelemetry) {
            target->hostTelemetry = [factory = target->hostTelemetry, expected, loader]() -> std::optional<DeckHudHostTarget> {
                if (!sameIdentity(expected, loader())) return {};
                auto observer = factory();
                if (observer) observer->identityValid = [expected, loader] { return sameIdentity(expected, loader()); };
                return observer;
            };
        }
        return target;
    };
}

DeckHostPowerResolver DeckLibraryController::hostPowerResolver() const {
    if (busy() || closing_ || state_.value("failed").toBool() || !identity_) return {};
    const auto* host = identity_->hostById(selectedHost().toStdString());
    if (!host || !host->hasServerCertificate() || !identity_->hasClientIdentity()) return {};
    const auto probe = std::find_if(snapshot_.probes.begin(), snapshot_.probes.end(),
        [&](const auto& item) { return item.hostId == host->stableId(); });
    if (probe == snapshot_.probes.end() || probe->standardHost || probe->status != polaris::DeckPolarisRequestStatus::Ok) return {};
    const int port = probe->resolvedHttpsPort > 0 ? probe->resolvedHttpsPort : host->nativeHttpsPort;
    if (port <= 0 || port > 65535) return {};
    return [expected = *identity_, saved = *host, port, loader = loader_]() -> std::optional<DeckHostPowerTarget> {
        if (!sameIdentity(expected, loader())) return {};
        auto client = std::make_shared<polaris::DeckPolarisClient>(
            backend::polarisClientForHost(expected, saved, port, std::chrono::milliseconds(4000)));
        DeckHostPowerTarget target;
        target.identityValid = [expected, loader] { return sameIdentity(expected, loader()); };
        target.capabilities = [client] { return client->fetchCapabilities(); };
        target.power = [client] { return client->fetchHostPower(); };
        target.sleep = [client](const std::function<bool()>& cancelled) { return client->requestHostSleep(cancelled); };
        target.reachable = [address = QString::fromStdString(saved.preferredAddress()), port] {
            QTcpSocket socket;
            socket.connectToHost(address, port);
            return socket.waitForConnected(1000);
        };
        return target;
    };
}

QString DeckLibraryController::selectedHost() const {
    const auto selected = snapshot_.selectedHostId.empty() && !snapshot_.hosts.empty()
        ? snapshot_.hosts.front().id : snapshot_.selectedHostId;
    return QString::fromStdString(selected);
}

DeckHostSettingsResolver DeckLibraryController::hostSettingsResolver() const {
    if (busy() || closing_ || state_.value("failed").toBool() || !identity_) return {};
    const auto* host = identity_->hostById(selectedHost().toStdString());
    if (!host || !host->hasServerCertificate() || !identity_->hasClientIdentity()) return {};
    const auto probe = std::find_if(snapshot_.probes.begin(), snapshot_.probes.end(),
        [&](const auto& item) { return item.hostId == host->stableId(); });
    if (probe == snapshot_.probes.end() || probe->standardHost || probe->status != polaris::DeckPolarisRequestStatus::Ok) return {};
    const int port = probe->resolvedHttpsPort > 0 ? probe->resolvedHttpsPort : host->nativeHttpsPort;
    if (port <= 0 || port > 65535) return {};
    return [expected = *identity_, saved = *host, port, loader = loader_, generation = generation_, accepted = generation_->load()]()
        -> std::optional<DeckHostSettingsTarget> {
        const auto valid = [expected, loader, generation, accepted] { return generation->load() == accepted && sameIdentity(expected, loader()); };
        if (!valid()) return {};
        auto client = std::make_shared<polaris::DeckPolarisClient>(
            backend::polarisClientForHost(expected, saved, port, std::chrono::milliseconds(4000)));
        DeckHostSettingsTarget target;
        target.identityValid = valid;
        target.capabilities = [client] { return client->fetchCapabilities(); };
        target.read = [client](const auto& cancelled) { return client->fetchHostSettings(cancelled); };
        target.idle = [client](const auto& cancelled) { return client->fetchHostSettingsIdle(cancelled); };
        target.writeResumeTimeout = [client](int seconds, const auto& cancelled) { return client->setHostResumeTimeout(seconds, cancelled); };
        target.writeMode = [client](const QString& mode, const auto& cancelled) { return client->setHostDefaultMode(mode, cancelled); };
        target.writeProfile = [client](const QString& display, int bitrate, bool clear, const auto& cancelled) {
            return client->setHostProfile(display, bitrate, clear, cancelled);
        };
        return target;
    };
}

DeckGameToolsResolver DeckLibraryController::gameToolsResolver() const {
    const auto destination = destinationId(snapshot_).toStdString();
    if (!hostSettingsResolver() || (destination != "desktop" && !polaris::validSpaceId(destination))) return {};
    const auto* host = identity_->hostById(selectedHost().toStdString());
    const auto* probe = selectedProbe(snapshot_);
    const int port = probe->resolvedHttpsPort > 0 ? probe->resolvedHttpsPort : host->nativeHttpsPort;
    return [expected = *identity_, saved = *host, port, destination, loader = loader_, generation = generation_, accepted = generation_->load()]()
        -> std::optional<DeckGameToolsTarget> {
        const auto valid = [expected, loader, generation, accepted] { return generation->load() == accepted && sameIdentity(expected, loader()); };
        if (!valid()) return {};
        auto client = std::make_shared<polaris::DeckPolarisClient>(backend::polarisClientForHost(expected, saved, port, std::chrono::milliseconds(6000)));
        DeckGameToolsTarget target;
        target.identityValid = valid;
        target.request = [client](const QString& game, const QString& action, const QVariantMap& values, const auto& cancelled) {
            if (action == "settings" || action == "plan") {
                const auto capabilities = client->fetchCapabilities();
                if (cancelled() || !capabilities.ok()) return polaris::DeckPolarisResult<QVariantMap>{capabilities.status, capabilities.httpStatus, {}, {}};
                if ((action == "settings" && !capabilities.value->clientSettings) || (action == "plan" && !capabilities.value->resolvedProfileProvenance))
                    return polaris::DeckPolarisResult<QVariantMap>{polaris::DeckPolarisRequestStatus::HttpError, 404, {}, {}};
            }
            return client->gameTool(game, action, values, cancelled);
        };
        target.games = [client, destination](const auto& cancelled) {
            const auto spaces = client->fetchSpaces(cancelled);
            if (destination != "desktop") {
                if (!spaces.ok() || !spaces.value->enabled || !spaces.value->permitsPlay(destination))
                    return polaris::DeckPolarisResult<std::vector<polaris::DeckPolarisGame>>{};
                auto games = client->fetchSpaceLibrary(destination, cancelled);
                if (!games.ok() || cancelled()) return games;
                const auto fresh = client->fetchSpaces(cancelled);
                if (!fresh.ok() || !fresh.value->enabled || !fresh.value->permitsPlay(destination))
                    return polaris::DeckPolarisResult<std::vector<polaris::DeckPolarisGame>>{};
                std::erase_if(*games.value, [&](const auto& game) {
                    const auto identity = polaris::spaceGameIdentity(game.id);
                    return !identity || identity->spaceId != destination || game.spaceId != destination;
                });
                return games;
            }
            if (spaces.ok() && spaces.value->enabled && !spaces.value->permitsPlay("desktop"))
                return polaris::DeckPolarisResult<std::vector<polaris::DeckPolarisGame>>{};
            if (!spaces.ok() && spaces.httpStatus != 404 && spaces.httpStatus != 501)
                return polaris::DeckPolarisResult<std::vector<polaris::DeckPolarisGame>>{};
            return client->fetchAllGames(100, cancelled, true);
        };
        return target;
    };
}

void DeckLibraryController::configureAutomaticRefresh(int intervalMs, int maxRetryMs) {
    intervalMs_ = std::max(0, intervalMs);
    retryMs_ = intervalMs_;
    maxRetryMs_ = std::max(intervalMs_, maxRetryMs);
    nextRefreshMs_ = automaticClock_.elapsed() + intervalMs_;
    scheduleAutomaticRefresh();
}

void DeckLibraryController::scheduleAutomaticRefresh() {
    automaticTimer_.stop();
    if (!intervalMs_ || busy() || sessionBusy_ || !windowActive_ || interactionPaused_ || automaticBlocked_ ||
        closing_ || !identity_ || selectedHost().isEmpty()) return;
    automaticTimer_.start(static_cast<int>(std::clamp<qint64>(nextRefreshMs_ - automaticClock_.elapsed(), 0, maxRetryMs_)));
}

void DeckLibraryController::setWindowActive(bool active) {
    if (windowActive_ == active) return;
    windowActive_ = active;
    if (active) {
        if (seenActive_ && !state_.value("failed").toBool()) nextRefreshMs_ = automaticClock_.elapsed();
        seenActive_ = true;
    }
    scheduleAutomaticRefresh();
}

void DeckLibraryController::setInteractionPaused(bool paused) {
    if (interactionPaused_ == paused) return;
    interactionPaused_ = paused;
    scheduleAutomaticRefresh();
}

void DeckLibraryController::setSessionBusy(bool busy) {
    if (sessionBusy_ == busy) return;
    sessionBusy_ = busy;
    if (!busy) nextRefreshMs_ = automaticClock_.elapsed();
    scheduleAutomaticRefresh();
}

void DeckLibraryController::suspendAutomaticRefresh() {
    closing_ = true;
    *cancelled_ = true; ++*generation_;
    automaticTimer_.stop();
}

bool DeckLibraryController::refresh() {
    return selectHost(selectedHost());
}

bool DeckLibraryController::selectHost(const QString& hostId) {
    return request(hostId, false);
}

bool DeckLibraryController::selectDestination(const QString& id) {
    const auto* probe = selectedProbe(snapshot_);
    if (state_.value("failed").toBool() || !probe || !probe->spaces ||
        id.toStdString() == probe->spaces->selectedId || !probe->spaces->permitsSelection(id.toStdString())) return false;
    return request(selectedHost(), false, id);
}

void DeckLibraryController::updateSpacesState() {
    const auto* probe = selectedProbe(snapshot_);
    const auto* spaces = probe && probe->spaces ? &*probe->spaces : nullptr;
    const bool known = spaces && probe->status == polaris::DeckPolarisRequestStatus::Ok && !state_.value("failed").toBool();
    const bool supported = probe && probe->spacesSupported;
    const bool changing = result_ && result_->changing;
    const auto id = destinationId(snapshot_);
    const auto* selected = spaces ? spaces->selected() : nullptr;
    const QString name = id == "desktop" ? "Desktop" : selected ? QString::fromStdString(selected->name) : "Spaces unavailable";
    QVariantList rows;
    rows.push_back(QVariantMap{{"id", "desktop"}, {"name", "Desktop"}, {"status", ""},
        {"caption", !known ? "Refresh to check Desktop access." : spaces->desktopAllowed ? (id == "desktop" ? "Current destination" : "Your PC's regular library")
            : "Desktop access is off for this device. Check device access in Polaris."},
        {"available", known && spaces->permitsSelection("desktop")}, {"selected", known && id == "desktop"}});
    if (spaces) for (const auto& space : spaces->spaces) {
        QString caption = !known ? "Refresh to check this destination." : space.selected ? "Current destination" : space.blockedReason == "at_capacity"
            ? "All Space slots are in use. You can still browse." : space.state == "in_use"
            ? "Another device is using this Space. You can still browse." : space.state == "unavailable"
            ? "This Space is unavailable. Refresh to check again." : space.state == "starting"
            ? "This Space is starting. You can still browse." : space.state == "stopping"
            ? "This Space is stopping. You can still browse." : "Browse this Space's games";
        rows.push_back(QVariantMap{{"id", QString::fromStdString(space.id)}, {"name", QString::fromStdString(space.name)},
            {"status", known ? spaceStatus(space.state) : QString("Status unknown")}, {"caption", caption}, {"selected", known && space.selected},
            {"available", known && spaces->permitsSelection(space.id)}});
    }
    state_["destinationId"] = id;
    state_["destinationName"] = name;
    state_["destinationPlayable"] = !supported || (known && spaces->permitsPlay(id.toStdString()));
    state_["destinationPlayLabel"] = !known ? "Check destination" : !selected ? "Unavailable"
        : selected->blockedReason == "at_capacity" ? "All slots in use"
        : selected->state == "ready" && !selected->openable() ? "Unavailable" : spaceStatus(selected->state);
    state_["spaces"] = QVariantMap{{"supported", supported}, {"known", known}, {"changing", changing},
        {"name", name}, {"selectedId", id}, {"rows", rows},
        {"caption", !known ? QString("Could not check destinations. Refresh before choosing.")
            : !spaces->enabled ? QString("Spaces are not enabled on this PC.") : blockedCopy(*spaces)}};
}

bool DeckLibraryController::request(const QString& hostId, bool automatic, const QString& destination) {
    if (busy() || sessionBusy_ || closing_ || !identity_ || !identity_->hostById(hostId.toStdString())) return false;
    if (automatic && (!windowActive_ || interactionPaused_ || automaticBlocked_ || !intervalMs_)) return false;
    automaticTimer_.stop();
    *cancelled_ = false;
    if (!destination.isEmpty() || hostId != selectedHost()) ++*generation_;
    result_ = std::make_shared<Result>();
    result_->automatic = automatic;
    result_->changing = !destination.isEmpty();
    result_->snapshot = snapshot_;
    result_->snapshot.selectedHostId = hostId.toStdString();
    result_->snapshot.library.games.clear();
    result_->snapshot.library.sourceLabel = "Game list unavailable";
    state_ = runtime::state(true, false, destination.isEmpty() ? "Checking the PC's game list…" : "Changing where to play…", automatic);
    const auto* previousProbe = selectedProbe(snapshot_);
    const int selectedPort = previousProbe ? previousProbe->resolvedHttpsPort : 0;
    const auto previousDestination = destinationId(snapshot_).toStdString();
    if (!destination.isEmpty()) snapshot_.library.games.clear();
    worker_ = QThread::create([result = result_, expected = *identity_, loader = loader_,
                              factory = fetcherFactory_, selected = hostId.toStdString(), selector = spaceSelector_,
                              desired = destination.toStdString(), previousDestination, selectedPort, cancelled = cancelled_] {
        try {
            auto current = loader();
            if (!sameIdentity(expected, current)) {
                result->retryAllowed = false;
                result->copy = "Saved PCs changed. Reopen Saved PCs before refreshing.";
                return;
            }
            if (!desired.empty()) {
                const auto stop = [cancelled, expected, loader] { return cancelled->load() || !sameIdentity(expected, loader()); };
                if (stop() || selectedPort <= 0) return;
                const auto choice = selector(*current, *current->hostById(selected), selectedPort, desired, previousDestination, stop);
                if (!choice.ok() || stop()) {
                    result->retryAllowed = false;
                    result->copy = choice.httpStatus == 409 ? "The destination changed or is in use. Refresh before choosing again."
                        : "The choice could not be confirmed. Refresh to see where this device is now. Nova will not repeat the choice.";
                    return;
                }
            }
            if (cancelled->load()) return;
            current->sourceLabel = "nova-native";
            auto selectedIdentity = *current;
            selectedIdentity.hosts = {*current->hostById(selected)};
            // Probe only the requested PC. The all-host startup chooser must
            // never substitute a different online PC after an explicit choice.
            auto next = backend::buildLiveSnapshot(selectedIdentity, factory(selectedIdentity));
            if (cancelled->load() || !sameIdentity(expected, loader())) {
                result->retryAllowed = false;
                result->copy = "Saved PCs changed during refresh. Reopen Saved PCs before continuing.";
                return;
            }
            next.selectedHostId = selected;
            result->failed = next.probes.empty() || next.probes.front().status != polaris::DeckPolarisRequestStatus::Ok;
            result->retryAllowed = next.probes.empty() || !stopsAutomaticRefresh(next.probes.front().status);
            if (!desired.empty() && destinationId(next).toStdString() != desired) {
                next.library.games.clear(); result->failed = true; result->retryAllowed = false;
            }
            result->copy = result->failed
                ? "The PC's game list is unavailable. Check its connection and pairing, then refresh."
                : next.library.games.empty() ? "This PC has no games to show." : "Game list updated.";
            // Other PC summaries remain selection choices, never library data.
            for (const auto& host : result->snapshot.hosts)
                if (host.id != selected) next.hosts.push_back(host);
            for (const auto& probe : result->snapshot.probes)
                if (probe.hostId != selected) next.probes.push_back(probe);
            result->snapshot = std::move(next);
        } catch (...) {
            // Keep the prebuilt empty result and a fixed player-facing error.
        }
    });
    worker_->start();
    timer_.start();
    updateSpacesState();
    emit stateChanged();
    if (!destination.isEmpty()) emit snapshotChanged();
    return true;
}

void DeckLibraryController::poll() {
    if (!worker_ || !worker_->wait(0)) return;
    delete worker_;
    worker_ = nullptr;
    timer_.stop();
    if (destinationId(snapshot_) != destinationId(result_->snapshot)) ++*generation_;
    snapshot_ = std::move(result_->snapshot);
    state_ = runtime::state(false, result_->failed, result_->copy, result_->automatic);
    automaticBlocked_ = !result_->retryAllowed;
    retryMs_ = result_->failed ? static_cast<int>(std::min<qint64>(maxRetryMs_, qint64(retryMs_) * 2)) : intervalMs_;
    nextRefreshMs_ = automaticClock_.elapsed() + retryMs_;
    const bool selected = result_->changing && !result_->failed;
    result_.reset();
    updateSpacesState();
    state_["selectionConfirmed"] = selected;
    emit snapshotChanged();
    emit stateChanged();
    scheduleAutomaticRefresh();
}

} // namespace nova::deck::runtime
