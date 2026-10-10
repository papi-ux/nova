#pragma once
#include "runtime/deck_working_baseline.h"
#include <QJsonValue>
#include <QList>
#include <cstdint>

namespace nova::deck::runtime {
inline constexpr int kMomentMaxEntries = 10;
inline constexpr qint64 kMomentLookbackMs = 10000;
inline constexpr qsizetype kMomentWindowMaxBytes = 24 * 1024;
inline constexpr qsizetype kMomentReportMaxBytes = 32 * 1024;
inline constexpr int kMomentMaxFiles = 32;

// Only sanitized, closed readings are retained. Clocks and generations stay local.
struct DeckMomentSample {
    qint64 receivedMs = -1;
    std::uint64_t generation = 0;
    QJsonValue readings = QJsonValue::Null;
};
struct DeckMomentEviction { qint64 receivedMs; std::uint64_t generation; };
QJsonObject selectMomentWindow(const QList<DeckMomentSample>& samples, qint64 markMs,
    std::uint64_t generation, std::optional<DeckMomentEviction> eviction = {},
    qsizetype maxBytes = kMomentWindowMaxBytes);
class DeckMomentHistory {
public:
    void append(const QVariantMap& hud, qint64 receivedMs, std::uint64_t generation);
    void clear() { samples_.clear(); eviction_.reset(); }
    QJsonObject window(qint64 markMs, std::uint64_t generation) const;
private:
    QList<DeckMomentSample> samples_;
    std::optional<DeckMomentEviction> eviction_;
};
// Freezes a bounded closed JSON report. Empty means the bounds/clock cannot hold.
// Every observed snapshot field is null together unless the sample is current.
QByteArray buildMomentReport(const QVariantMap& hud, qint64 receivedMs, qint64 markMs,
    std::uint64_t generation, const std::optional<DeckWorkingBaselineRequest>& requested,
    const DeckMomentHistory& history, const QDateTime& markedUtc);

class DeckMomentStore {
public:
    // Documents ancestors are user-chosen and followed. The reports leaf and all
    // operations below it are anchored to one descriptor and never follow links.
    explicit DeckMomentStore(QString documents) : documents_(std::move(documents)) {}
    static QString defaultDocumentsDirectory();
    static QString defaultDirectory();
    struct TestHooks {
        QString finalName;
        bool failPublishedTempCleanup = false, failDirectorySync = false, failParentSync = false;
    };
    // Explicit save only, no overwrite/deletion/automatic retry. After publication
    // cleanup or directory-sync failure means saved_durability_unknown, not failure.
    QVariantMap save(const QByteArray& frozen, const QDateTime& markedUtc, const TestHooks* hooks = nullptr) const;
private:
    QString documents_;
};
}
