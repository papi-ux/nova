#pragma once
#include <QByteArray>
#include <QDateTime>
#include <QJsonObject>
#include <QString>
#include <QVariantMap>
#include <optional>

namespace nova::deck::runtime {
// A user-marked working baseline (Nova #282 Linux parity). One closed record per
// pseudonymous local scope, kept on this device. The scope groups streams by
// paired PC certificate, this install's client certificate and the stable Polaris
// app UUID. It is not proof of the observed game, scene, host build or route, and
// anyone who can read Nova's local files can recompute it for a known host and app.
inline constexpr qsizetype kWorkingBaselineMaxBytes = 2048;
inline constexpr int kWorkingBaselineMaxScopes = 12;
// One index file holds every baseline: <=12 entries of <=2048 compact bytes each,
// plus keys and envelope, under a hard total cap.
inline constexpr qsizetype kWorkingBaselineIndexMaxBytes = 26 * 1024;
// The interface drops HUD readings after 2.5 s without a new sample.
inline constexpr qint64 kWorkingBaselineFreshMs = 2500;

// The client's original stream request, as reviewed before launch. Absent when the
// stream started without an explicit configuration; never filled from defaults.
struct DeckWorkingBaselineRequest {
    int width = 0, height = 0, fps = 0, bitrateKbps = 0;
    QString videoCodec, profilePreference, launchMode;
};
bool validWorkingBaselineRequest(const DeckWorkingBaselineRequest& request);

// SHA-256 over a versioned label and the inputs; empty when any input is empty.
QString workingBaselineScope(const QByteArray& hostCertificateDer, const QByteArray& clientCertificateDer, const QString& appUuid);
bool validWorkingBaselineScope(const QString& scope);

// The HUD's own flag and the monotonic age of the last received sample must both
// hold at the press; the flag alone can lag the poll timer.
bool workingBaselineSampleFresh(bool hudFresh, qint64 receivedMs, qint64 nowMs);

// Select the closed baseline fields from a deckSupportReport() snapshot. Returns
// nullopt unless client_readings.available is the JSON bool true, or when the scope
// or request is invalid or the result breaks the schema.
std::optional<QJsonObject> workingBaselineRecord(const QString& scope, const QJsonObject& supportReport,
    const std::optional<DeckWorkingBaselineRequest>& requested, const QDateTime& markedUtc, qint64 sampleAgeMs);
// Exact key set, types, closed words and bounds; the scope must match and the
// marked time may not be more than 5 minutes after nowUtc.
bool validWorkingBaseline(const QJsonObject& record, const QString& expectedScope, const QDateTime& nowUtc);
std::optional<QJsonObject> parseWorkingBaseline(const QByteArray& bytes, const QString& expectedScope, const QDateTime& nowUtc);
QByteArray serializeWorkingBaseline(const QJsonObject& record);

// Descriptive comparison only: context rows are same/different/unknown, reading
// rows show both values or unknown. `current` is nullopt when the current sample
// is not fresh; nothing is inferred from older readings.
QVariantMap compareWorkingBaselines(const QJsonObject& baseline, const std::optional<QJsonObject>& current);

// One owner-only index file (index.json) in an owner-only directory. load() never
// creates, locks, chmods or deletes anything. save() and clear() are the only
// maintenance: under an exclusive lock they read the index strictly, apply the
// change and deterministic retention, and replace the whole index with one checked
// QSaveFile commit, so readers only ever see the complete old or new index. An
// unsafe or unreadable index is refused, never overwritten. Results are closed words.
class DeckWorkingBaselineStore {
public:
    explicit DeckWorkingBaselineStore(QString directory) : directory_(std::move(directory)) {}
    static QString defaultDirectory();
    struct Read { QString status; std::optional<QJsonObject> baseline; };
    // status: ready, no_baseline, baseline_unreadable, index_unreadable, storage_unavailable, storage_unsafe
    Read load(const QString& scope, const QDateTime& nowUtc) const;
    // saved, too_large, invalid_snapshot, storage_unavailable, storage_unsafe, storage_busy, index_unreadable, storage_full, write_failed
    QString save(const QString& scope, const QJsonObject& record, const QDateTime& nowUtc);
    // cleared, no_baseline, storage_unavailable, storage_unsafe, storage_busy, index_unreadable, write_failed
    QString clear(const QString& scope);
private:
    QString directory_;
};
}
