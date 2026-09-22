#pragma once

#include <QObject>
#include <QVariantMap>
#include <optional>
#include "stream/deck_video_capabilities.h"

namespace nova::deck::runtime {

// Device-wide Android audio preferences. A stream snapshots these on start;
// reconnect keeps that snapshot rather than reading newer defaults.
struct DeckAudioConfiguration {
    int channels = 2;
    bool playHostAudio = false;
    QVariantMap toMap() const;
    static std::optional<DeckAudioConfiguration> fromMap(const QVariantMap& values);
};

// Only client stream/input preferences, never host permissions, transport or identity.
struct DeckPlayConfiguration {
    int width = 1280;
    int height = 800;
    int fps = 60;
    int bitrateKbps = 20000;
    QString faceButtonLayout = "default";
    QString launchMode = "default";
    QString videoCodec = "h264"; // Preserve the existing device default.
    QString profilePreference = "auto";
    QString encoderBackend;
    QVariantMap toMap() const;
    static std::optional<DeckPlayConfiguration> fromMap(const QVariantMap& values);
};

class DeckPlaySettings final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString defaultFaceButtonLayout READ defaultFaceButtonLayout NOTIFY defaultFaceButtonLayoutChanged)
    Q_PROPERTY(QVariantMap audioSettings READ audioSettings NOTIFY audioSettingsChanged)
    Q_PROPERTY(bool rumbleEnabled READ rumbleEnabled NOTIFY rumbleEnabledChanged)
    Q_PROPERTY(QString videoScaleMode READ videoScaleMode NOTIFY videoScaleModeChanged)
    Q_PROPERTY(QString framePacingMode READ framePacingMode NOTIFY framePacingModeChanged)
    Q_PROPERTY(int stickDeadzonePercent READ stickDeadzonePercent NOTIFY stickDeadzonePercentChanged)
    Q_PROPERTY(QVariantMap streamDefaults READ streamDefaults NOTIFY streamDefaultsChanged)
public:
    explicit DeckPlaySettings(QString fileName = {}, QObject* parent = nullptr);
    Q_INVOKABLE QVariantMap load(const QString& hostId, const QString& gameId) const;
    Q_INVOKABLE bool save(const QString& hostId, const QString& gameId, const QVariantMap& configuration);
    Q_INVOKABLE bool saveChoice(const QString& hostId, const QString& gameId, const QVariantMap& choice);
    Q_INVOKABLE bool resetChoice(const QString& hostId, const QString& gameId, const QString& field);
    Q_INVOKABLE bool reset(const QString& hostId, const QString& gameId);
    Q_INVOKABLE QVariantMap streamPlan(const QVariantMap& configuration,
        const QVariantMap& capabilities, const QVariantMap& planner, const QVariantMap& display = {}, bool spaceSession = false) const;
    Q_INVOKABLE int displayRateLimit(double refreshHz) const;
    // Startup snapshot for review. The session worker probes again before launch.
    void setVideoDecodeSupport(stream::DeckVideoDecodeSupport support) { videoSupport_ = support; }
    QString defaultFaceButtonLayout() const;
    Q_INVOKABLE bool setDefaultFaceButtonLayout(const QString& layout);
    DeckAudioConfiguration audioConfiguration() const;
    QVariantMap audioSettings() const { return audioConfiguration().toMap(); }
    Q_INVOKABLE bool saveAudioSettings(const QVariantMap& values);
    Q_INVOKABLE bool resetAudioSettings();
    bool rumbleEnabled() const;
    Q_INVOKABLE bool setRumbleEnabled(const QVariant& value);
    Q_INVOKABLE bool resetRumble();
    QString videoScaleMode() const;
    Q_INVOKABLE bool setVideoScaleMode(const QString& mode);
    Q_INVOKABLE bool resetVideoScaleMode();
    QString framePacingMode() const;
    Q_INVOKABLE bool setFramePacingMode(const QString& mode);
    Q_INVOKABLE bool resetFramePacingMode();
    int stickDeadzonePercent() const;
    Q_INVOKABLE bool setStickDeadzonePercent(const QVariant& percent);
    Q_INVOKABLE bool resetStickDeadzonePercent();
    QVariantMap streamDefaults() const;
    bool saveStreamDefaults(const QVariantMap& values);
    Q_INVOKABLE bool resetStreamDefaults();
    std::optional<QVariantMap> defaultsFromHost(const QString& display, int bitrate) const;
    QString keepInStep(const QString& hostId) const;
    bool saveKeepInStep(const QString& hostId, const QString& state);
    Q_INVOKABLE QVariantMap logoPlacement(const QString& hostId, const QString& gameId, const QVariantMap& fallback = {}) const;
    Q_INVOKABLE bool saveLogoPlacement(const QString& hostId, const QString& gameId, const QVariantMap& values, const QVariantMap& expected, const QVariantMap& fallback = {});
signals:
    void logoPlacementChanged();
    void streamDefaultsChanged();
    void defaultFaceButtonLayoutChanged();
    void audioSettingsChanged();
    void rumbleEnabledChanged();
    void videoScaleModeChanged();
    void framePacingModeChanged();
    void stickDeadzonePercentChanged();
private:
    QString fileName_;
    stream::DeckVideoDecodeSupport videoSupport_;
};

} // namespace nova::deck::runtime
