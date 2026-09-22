#pragma once

#include "runtime/deck_native_target.h"
#include "runtime/deck_host_power.h"
#include "runtime/deck_host_settings.h"
#include "runtime/deck_game_tools.h"

#include <QObject>
#include <atomic>
#include <QElapsedTimer>
#include <QThread>
#include <QTimer>
#include <QVariantMap>

namespace nova::deck::runtime {

using DeckLibraryIdentityLoader = std::function<std::optional<identity::DeckMoonlightIdentity>()>;
using DeckLibraryFetcherFactory = std::function<backend::DeckLivePolarisFetcher(const identity::DeckMoonlightIdentity&)>;

using DeckSpaceSelector = std::function<polaris::DeckPolarisResult<polaris::DeckSpaces>(
    const identity::DeckMoonlightIdentity&, const identity::DeckMoonlightHostRecord&, int,
    const std::string&, const std::string&, const std::function<bool()>&)>;

// Standalone library requests have one owner and one immutable result. A failed
// selection never borrows another PC's games or retains old launch authority.
class DeckLibraryController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    DeckLibraryController(std::optional<identity::DeckMoonlightIdentity> identity,
        backend::DeckLiveHostLibrarySnapshot snapshot, DeckLibraryIdentityLoader loader,
        DeckLibraryFetcherFactory fetcherFactory, QObject* parent = nullptr, DeckSpaceSelector spaceSelector = {});
    ~DeckLibraryController() override;
    QVariantMap state() const { return state_; }
    bool busy() const { return worker_ != nullptr; }
    const backend::DeckLiveHostLibrarySnapshot& snapshot() const { return snapshot_; }
    DeckNativeTargetResolver targetResolver() const;
    DeckHostPowerResolver hostPowerResolver() const;
    DeckHostSettingsResolver hostSettingsResolver() const;
    DeckGameToolsResolver gameToolsResolver() const;
    void setSessionBusy(bool busy);
    void configureAutomaticRefresh(int intervalMs = 30000, int maxRetryMs = 120000);
    Q_INVOKABLE void setWindowActive(bool active);
    Q_INVOKABLE void setInteractionPaused(bool paused);
    Q_INVOKABLE void suspendAutomaticRefresh();
    Q_INVOKABLE bool selectHost(const QString& hostId);
    Q_INVOKABLE bool refresh();
    Q_INVOKABLE bool selectDestination(const QString& id);
signals:
    void stateChanged();
    void snapshotChanged();
private:
    struct Result;
    void poll();
    bool request(const QString& hostId, bool automatic, const QString& destination = {});
    void updateSpacesState();
    QString selectedHost() const;
    void scheduleAutomaticRefresh();
    std::optional<identity::DeckMoonlightIdentity> identity_;
    backend::DeckLiveHostLibrarySnapshot snapshot_;
    DeckLibraryIdentityLoader loader_;
    DeckLibraryFetcherFactory fetcherFactory_;
    DeckSpaceSelector spaceSelector_;
    std::shared_ptr<std::atomic<unsigned long long>> generation_ = std::make_shared<std::atomic<unsigned long long>>(0);
    std::shared_ptr<std::atomic<bool>> cancelled_ = std::make_shared<std::atomic<bool>>(false);
    QVariantMap state_;
    bool sessionBusy_ = false;
    QThread* worker_ = nullptr;
    QTimer timer_;
    QTimer automaticTimer_;
    QElapsedTimer automaticClock_;
    qint64 nextRefreshMs_ = 0;
    int intervalMs_ = 0, retryMs_ = 0, maxRetryMs_ = 0;
    bool windowActive_ = false, seenActive_ = false, interactionPaused_ = true;
    bool automaticBlocked_ = false, closing_ = false;
    std::shared_ptr<Result> result_;
};

} // namespace nova::deck::runtime
