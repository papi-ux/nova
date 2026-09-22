#include "runtime/deck_pairing_controller.h"

#include <atomic>
#include <mutex>

namespace nova::deck::runtime {
using namespace identity;
namespace {
QVariantMap state(const QString& phase, const QString& copy, bool busy, const QString& pin = {}) {
    return {{"phase", phase}, {"copy", copy}, {"busy", busy}, {"pin", pin}};
}
}
struct DeckPairingController::Shared {
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    QVariantMap model;
    void publish(QVariantMap next) {
        const std::lock_guard lock(mutex);
        model = std::move(next);
    }
};

DeckPairingController::DeckPairingController(QString directory, PairTransportFactory factory,
    std::function<QString()> pinGenerator, QObject* parent)
    : QObject(parent), directory_(std::move(directory)), factory_(std::move(factory)), pinGenerator_(std::move(pinGenerator)),
      state_(runtime::state("idle", "Tap the address to type, then choose Trusted Pair or Pair with PIN.", false)) {
    timer_.setInterval(20);
    connect(&timer_, &QTimer::timeout, this, &DeckPairingController::poll);
    refreshHosts();
}
DeckPairingController::~DeckPairingController() {
    timer_.stop();
    if (worker_) {
        shared_->cancelled = true;
        worker_->wait();
        delete worker_;
    }
}

bool DeckPairingController::start(const QString& address, int httpPort) {
    return startPairing(address, httpPort, false);
}

bool DeckPairingController::startTrusted(const QString& address, int httpPort) {
    return startPairing(address, httpPort, true);
}

bool DeckPairingController::startPairing(const QString& address, int httpPort, bool trusted) {
    if (busy()) return false;
    removing_ = false;
    const auto endpoint = pairingEndpoint(address, httpPort);
    if (!endpoint) {
        state_ = runtime::state("failed", "Enter a PC name or IP address and a port from 1 to 65535. Leave out URLs and paths.", false);
        emit stateChanged();
        return false;
    }
    shared_ = std::make_shared<Shared>();
    state_ = runtime::state("preparing", trusted ? "Checking Trusted Pair on this PC…" : "Preparing Nova's pairing…", true);
    shared_->model = state_;
    worker_ = QThread::create([shared = shared_, endpoint = *endpoint, directory = directory_, factory = factory_, pinGenerator = pinGenerator_, trusted] {
        auto finish = [&](const QString& phase, const QString& copy) { shared->publish(runtime::state(phase, copy, false)); };
        try {
            auto identity = initializeNativeIdentity(directory);
            if (!identity.ok()) { finish("failed", pairingCopy(PairStatus::StoreFailed)); return; }
            const auto lease = lockNativePairing(directory);
            if (!lease) { finish("failed", "Another Nova pairing is still running. Wait for it to finish, then try again."); return; }
            identity = loadNativeIdentity(directory);
            if (!identity.ok()) { finish("failed", pairingCopy(PairStatus::StoreFailed)); return; }
            if (shared->cancelled) { finish("cancelled", pairingCopy(PairStatus::Cancelled)); return; }
            crypto::Credentials credentials{QByteArray::fromStdString(identity.identity->clientCertificatePem),
                QByteArray::fromStdString(identity.identity->clientPrivateKeyPemForBackendOnly)};
            const auto cancelled = [shared] { return shared->cancelled.load(); };
            const auto commit = [&](const DeckMoonlightHostRecord& host) { return saveNativeHost(directory, credentials, host).ok(); };
            PairResult result{PairStatus::TrustedUnavailable};
            if (trusted) {
                shared->publish(runtime::state("trusted", "Connecting with Trusted Pair…", true));
                result = pairHost(endpoint, credentials, "0000", identity.identity->hosts,
                    factory(credentials, cancelled), cancelled, commit, PairMode::Trusted);
            }
            // Only an absent capability can automatically select PIN pairing.
            // A failed handshake, changed pin, cancellation or uncertain cleanup
            // stays a failure and can never silently start another attempt.
            if (result.status == PairStatus::TrustedUnavailable) {
                if (cancelled()) { finish("cancelled", pairingCopy(PairStatus::Cancelled)); return; }
                const QString pin = pinGenerator();
                if (pin.isEmpty()) { finish("failed", "Nova could not create a pairing PIN."); return; }
                shared->publish(runtime::state("waiting", trusted
                    ? "Trusted Pair is unavailable here. Enter this PIN in your host's pairing screen."
                    : "Enter this PIN in your host's pairing screen.", true, pin));
                result = pairHost(endpoint, credentials, pin, identity.identity->hosts,
                    factory(credentials, cancelled), cancelled, commit);
            }
            QString copy = pairingCopy(result.status);
            if (result.authorizationMayRemain)
                copy += " Remove Nova Deck from the host's paired devices before retrying; cleanup could not be confirmed.";
            finish(result.status == PairStatus::Ok ? "paired" : result.status == PairStatus::Cancelled ? "cancelled" : "failed", copy);
        } catch (...) {
            finish("failed", "Pairing could not finish. Check the host's paired devices before retrying.");
        }
    });
    worker_->start();
    timer_.start();
    emit stateChanged();
    return true;
}

void DeckPairingController::cancel() {
    if (!worker_) return;
    shared_->cancelled = true;
    state_ = runtime::state("cancelling", removing_ ? "Waiting for the PC's response…" : "Cancelling pairing…", true);
    emit stateChanged();
}

void DeckPairingController::poll() {
    const bool finished = worker_->isFinished();
    QVariantMap next;
    {
        const std::lock_guard lock(shared_->mutex);
        next = shared_->model;
    }
    if (finished) {
        delete worker_;
        worker_ = nullptr;
        timer_.stop();
        next["busy"] = false;
        refreshHosts();
    } else {
        next["busy"] = true;
        if (shared_->cancelled) next = runtime::state("cancelling", removing_ ? "Waiting for the PC's response…" : "Cancelling pairing…", true);
    }
    if (state_ != next) { state_ = std::move(next); emit stateChanged(); }
}

void DeckPairingController::refreshHosts() {
    savedIdentity_ = loadNativeIdentity(directory_).identity;
    hosts_.clear();
    if (savedIdentity_) for (const auto& host : savedIdentity_->hosts)
        hosts_.push_back(QVariantMap{{"id", QString::fromStdString(host.uuid)},
            {"name", QString::fromStdString(host.hostname.empty() ? "Saved PC" : host.hostname)}});
    emit savedHostsChanged();
}

void DeckPairingController::reset() {
    if (busy()) return;
    refreshHosts();
    state_ = runtime::state("idle", "Enter the PC's address. Nova will give you a PIN to enter on the host.", false);
    emit stateChanged();
}

bool DeckPairingController::removeHost(const QString& hostId, bool localOnly) {
    if (busy() || !savedIdentity_) return false;
    const auto* selected = savedIdentity_->hostById(hostId.toStdString());
    if (!selected) return false;
    const auto expectedClient = savedIdentity_->clientCertificatePem;
    removing_ = true;
    shared_ = std::make_shared<Shared>();
    state_ = runtime::state("removing", localOnly ? "Forgetting this PC on the Deck…" : "Unpairing Nova from the PC…", true);
    shared_->model = state_;
    worker_ = QThread::create([shared = shared_, host = *selected, expectedClient, localOnly,
                              directory = directory_, factory = factory_] {
        const auto finish = [&](const QString& phase, const QString& copy) { shared->publish(runtime::state(phase, copy, false)); };
        try {
            const auto lease = lockNativePairing(directory);
            if (!lease) { finish("failed", "Another Nova pairing change is running. Try again when it finishes."); return; }
            const auto current = loadNativeIdentity(directory);
            const auto* saved = current.ok() ? current.identity->hostById(host.uuid) : nullptr;
            if (!saved || current.identity->clientCertificatePem != expectedClient ||
                saved->serverCertificatePem != host.serverCertificatePem || saved->preferredAddress() != host.preferredAddress() ||
                saved->preferredHttpPort() != host.preferredHttpPort() || saved->nativeHttpsPort != host.nativeHttpsPort) {
                finish("failed", "The saved pairing changed. Review the PC list before trying again."); return;
            }
            const auto cancelled = [shared] { return shared->cancelled.load(); };
            if (cancelled()) { finish("cancelled", "No pairing change was made."); return; }
            crypto::Credentials credentials{QByteArray::fromStdString(current.identity->clientCertificatePem),
                QByteArray::fromStdString(current.identity->clientPrivateKeyPemForBackendOnly)};
            if (!localOnly) {
                const auto result = unpairHost(host, factory(credentials, cancelled), cancelled);
                if (result.status != PairStatus::Ok) {
                    finish(result.status == PairStatus::Cancelled ? "cancelled" : "failed",
                        result.status == PairStatus::Cancelled && !result.requestMayHaveReachedHost
                        ? "Cancelled. This PC remains saved."
                        : result.requestMayHaveReachedHost
                        ? "The PC did not confirm unpairing. Its saved record is kept. Check the host's paired devices before retrying or forgetting locally."
                        : "Nova could not confirm this PC's pairing. Its saved record is kept. Check the PC, then retry or choose Forget on this Deck.");
                    return;
                }
            }
            // After the host acknowledges unpair, finish the local update even
            // if Cancel arrived late. Keeping an acknowledged success is honest.
            if (!forgetNativeHost(directory, credentials, host).ok()) {
                finish("failed", localOnly ? "The saved PC could not be removed. Try again."
                    : "The PC confirmed unpairing, but Nova could not remove its saved record. Use Forget on this Deck after checking the saved list.");
                return;
            }
            finish("removed", localOnly ? "Forgotten on this Deck. Remove Nova Deck from the PC's paired devices to revoke its access."
                : "The PC confirmed unpairing. Its saved record is removed; you can pair it again.");
        } catch (...) { finish("failed", "The change could not be confirmed. Check the host's paired devices and the saved list."); }
    });
    worker_->start();
    timer_.start();
    emit stateChanged();
    return true;
}
} // namespace nova::deck::runtime
