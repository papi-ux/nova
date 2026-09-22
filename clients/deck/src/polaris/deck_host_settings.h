#pragma once
#include <QVariantMap>
#include <optional>
#include <string_view>

namespace nova::deck::polaris {
// Host defaults are distinct from a game's session override and from a resolved
// launch profile. Only the fields consumed by the Every Game view are retained.
struct DeckHostSettings {
    QString revision, desiredMode, effectiveMode;
    QString desiredDisplay, effectiveDisplay;
    int desiredBitrate = 0, effectiveBitrate = 0;
    bool relaunchRequired = false;
    bool resumeTimeoutControl = false;
    int desiredResumeTimeout = -1, effectiveResumeTimeout = -1;
    bool displayOverride = false, bitrateOverride = false;
    QVariantList modes;
    bool permits(const QString& mode) const;
    QString overrideDisplay() const { return desiredDisplay.isEmpty() ? effectiveDisplay : desiredDisplay; }
    int overrideBitrate() const { return desiredBitrate > 0 ? desiredBitrate : effectiveBitrate; }
    bool hasProfile() const { return !overrideDisplay().isEmpty() || overrideBitrate() > 0; }
    QVariantMap publicState() const;
    QVariantMap profileReview() const;
};
std::optional<DeckHostSettings> parseHostSettings(std::string_view json);
std::optional<bool> parseHostSettingsIdle(std::string_view json);
bool validHostProfile(const QString& display, int bitrate);
}
