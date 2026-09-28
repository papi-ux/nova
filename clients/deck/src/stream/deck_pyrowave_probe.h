#pragma once

#include "stream/deck_video_capabilities.h"
#include <QString>
#include <QStringList>
#include <functional>
#include <mutex>
#include <optional>

namespace nova::deck::stream {
struct DeckPyrowaveProbeResult {
    DeckDecodeLimits limits;
    QString reason;
};

// No Vulkan calls in the parent. Call on a worker, never the UI thread.
DeckPyrowaveProbeResult probePyrowaveInChild(const QString& program,
    const QStringList& arguments = {}, int timeoutMs = 5000);

// Session cache: no result survives an app restart or a changed device/driver
// fingerprint. Serializes concurrent review and launch requests into one child.
class DeckPyrowaveProbeCache {
public:
    DeckPyrowaveProbeResult get(const QString& key, const std::function<DeckPyrowaveProbeResult()>& probe);
private:
    std::mutex mutex_;
    QString key_;
    std::optional<DeckPyrowaveProbeResult> result_;
};
DeckPyrowaveProbeResult cachedPyrowaveDecodeSupport();
} // namespace nova::deck::stream
