#pragma once

#include "stream/deck_stream_media_adapters.h"
#include "runtime/deck_native_target.h"
#include "runtime/deck_play_settings.h"
#include "runtime/deck_frame_delivery.h"
#include "runtime/deck_reconnect_policy.h"
#include "runtime/deck_hud_metrics.h"
#include "stream/deck_controller_input.h"
#include "stream/deck_desktop_input.h"

#include <array>
#include <QObject>
#include <QElapsedTimer>
#include <QPointer>
#include <QThread>
#include <QTimer>
#include <QVariantMap>
#include <functional>
#include <memory>
#include <optional>

namespace nova::deck::runtime {

/// One worker owns launch, LiStart/StopConnection and host cleanup. UI methods
/// never wait for network work. Bounded frame delivery transfers retained VAAPI
/// frames to the GUI thread, which alone touches the QQuickItem. Pacing is frozen
/// per stream and preserved through recovery.
class DeckNativeSessionController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled CONSTANT)
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool controlsVisible READ controlsVisible NOTIFY controlsChanged)
    Q_PROPERTY(QString controllerHint READ controllerHint NOTIFY controlsChanged)
    Q_PROPERTY(QVariantMap hud READ hud NOTIFY hudChanged)
public:
    // GUI thread only; each running worker keeps its original resolver.
    bool setTargetResolver(DeckNativeTargetResolver resolver);
    DeckNativeSessionController(bool enabled, DeckNativeTargetResolver resolver,
        stream::DeckMoonlightConnectionDriver& driver = stream::defaultMoonlightConnectionDriver(),
        stream::DeckControllerSend sendController = stream::sendDeckControllerPacket,
        QObject* parent = nullptr,
        stream::DeckDesktopSend sendDesktop = stream::sendDeckDesktopPacket);
    ~DeckNativeSessionController() override;

    bool enabled() const { return enabled_; }
    QVariantMap hud() const { return hud_; }
    QVariantMap state() const { auto value = state_; value.insert("sleeping", sleeping_); return value; }
    bool busy() const { return worker_ != nullptr || reconnectPending_; }
    bool systemSleeping() const { return sleeping_; }
    void setSystemSleeping(bool sleeping);
    void setSurface(stream::DeckQtQuickRhiVaapiItem* surface);
    // The QObject bounds the borrowed sink lifetime. Replacing a live target is
    // refused; detachment is always allowed and cancels delivery to that target.
    bool setPresentationSink(QObject* lifetime, stream::DeckQtQuickRhiPresentationSink* sink,
        std::shared_ptr<const std::atomic<std::uint64_t>> composed = {});
    // GUI-thread binding; the reader itself must be safe on the session worker.
    bool setDisplayRateLimitReader(std::function<int()> reader);
    void updateController(stream::DeckControllerState state, bool connected);
    void updatePlayerController(unsigned player, stream::DeckControllerState state, bool connected);
    // Raw oriented input; membership and gameplay use the same stream snapshot.
    bool controllerNeutral(stream::DeckControllerState state) const {
        return stream::withStickDeadzone(state, stickDeadzonePercent_).neutral();
    }
    void setInputFocus(bool focused);
    bool capturesGamepad() const;
    bool capturesDesktopInput() const { return inputFocused_ && capturesGamepad(); }
    void sendDesktopInput(const stream::DeckDesktopPacket& packet);
    std::optional<stream::DeckDesktopPacket> desktopPosition(QPointF point, QSizeF viewport,
        stream::DeckVideoScaleMode scale, bool clamp = false) const;
    bool controlsVisible() const { return controlsVisible_; }
    QString controllerHint() const;
    Q_INVOKABLE bool setLiveTuningEnabled(bool enabled);
    Q_INVOKABLE bool setFixedBitrate(int bitrateKbps);
    Q_INVOKABLE bool setSyncProfile(const QString& hostId, const QString& display, int bitrateKbps, bool clear, const QVariantMap& reviewed);
    Q_INVOKABLE bool refreshDiagnostics();
    Q_INVOKABLE QVariantMap exportSupportReport();
    Q_INVOKABLE bool applyDoctorFix();
    Q_INVOKABLE bool undoDoctorFix();
    Q_INVOKABLE bool checkDoctorResult();
    Q_INVOKABLE void showControls();
    Q_INVOKABLE void resumeInput();
    Q_INVOKABLE bool start(const QString& hostId, const QString& gameId);
    Q_INVOKABLE bool startConfigured(const QString& hostId, const QString& gameId, const QVariantMap& configuration);
    Q_INVOKABLE void stop();
    Q_INVOKABLE bool disconnectFromHost();
    Q_INVOKABLE bool resumeDisconnected(const QString& hostId, const QString& gameId);
    Q_INVOKABLE bool reconnect(const QString& hostId, const QString& gameId);
    // Active ordinary streams detach; pending launches still get cancelled.
    Q_INVOKABLE void closeSession();

signals:
    void stateChanged();
    void hudChanged();
    void inputSessionStarted(bool reusePlayers);
    void controlsChanged();
    void sleepPreparationFinished();
    void rumbleRequested(quint16 low, quint16 high);
    void playerRumbleRequested(quint16 player, quint16 low, quint16 high);

private:
    struct Shared;
    struct ResumeTicket;
    static void run(const std::shared_ptr<Shared>& shared,
        const DeckNativeTargetResolver& resolver, const QString& hostId,
        const QString& gameId, const std::optional<DeckPlayConfiguration>& configuration,
        const std::shared_ptr<const ResumeTicket>& resume,
        const DeckAudioConfiguration& audio,
        const std::function<int()>& displayRateLimit,
        stream::DeckMoonlightConnectionDriver& driver,
        const stream::DeckControllerSend& sendController, const stream::DeckDesktopSend& sendDesktop);
    void poll();
    void routeController(stream::DeckControllerRoute route, unsigned player = 0);
    void syncRumble();
    void releaseDesktopInput();
    bool requestStop(bool keepHostRunning);
    bool startRequest(const QString& hostId, const QString& gameId, std::optional<DeckPlayConfiguration> configuration,
        std::shared_ptr<const ResumeTicket> resume = {}, bool automatic = false);

    bool enabled_;
    DeckNativeTargetResolver resolver_;
    stream::DeckMoonlightConnectionDriver& driver_;
    stream::DeckControllerSend sendController_;
    stream::DeckDesktopSend sendDesktop_;
    QSizeF desktopVideo_;
    double desktopPixelAspect_ = 1;
    std::array<stream::DeckControllerRouter, 16> controllerRouters_;
    std::array<stream::DeckControllerState, 16> physicalControllers_;
    int stickDeadzonePercent_ = stream::kDeckDefaultStickDeadzone;
    std::uint16_t controllerMask_ = 0;
    QElapsedTimer inputClock_;
    bool controlsVisible_ = true, inputFocused_ = false;
    QVariantMap state_;
    QVariantMap hud_ = DeckHudMetrics::empty();
    DeckHudMetrics hudMetrics_;
    qint64 hudSampleMs_ = -1, hudReceivedMs_ = -1;
    QTimer timer_;
    QTimer reconnectTimer_;
    DeckReconnectPolicy reconnectPolicy_;
    bool reconnectPending_ = false, automaticSuppressed_ = false;
    bool sleeping_ = false;
    QThread* worker_ = nullptr;
    std::shared_ptr<Shared> shared_;
    std::shared_ptr<const ResumeTicket> resumeTicket_;
    QPointer<QObject> presentationOwner_;
    stream::DeckQtQuickRhiPresentationSink* presentationSink_ = nullptr;
    std::shared_ptr<const std::atomic<std::uint64_t>> presentationFrames_;
    std::unique_ptr<DeckFrameDelivery> frameDelivery_;
    std::function<int()> displayRateLimit_ = [] { return 60; };
};

} // namespace nova::deck::runtime
