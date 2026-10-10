#include "runtime/deck_working_baseline.h"
#include <QCryptographicHash>
#include <QDir>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLockFile>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <fcntl.h>
#include <initializer_list>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace nova::deck::runtime {
namespace {
constexpr auto kKind = "nova-deck-working-baseline-v1";
constexpr auto kBasis = "user_marked_working";
constexpr auto kScopeLabel = "nova-deck-working-baseline-scope-v1";
constexpr qint64 kFutureToleranceMs = 5 * 60 * 1000;

const QStringList kCodecs{"h264", "hevc", "auto", "pyrowave"};
const QStringList kProfiles{"auto", "quality", "high_fps", "stability"};
// "default" plus the exact canonical session modes accepted by
// polaris::isSessionLaunchMode(); aliases and headless_dongle are refused.
const QStringList kLaunchModes{"default", "headless_stream", "host_virtual_display", "desktop_display",
    "desktop_takeover", "windowed_stream", "gamescope_stream"};
const QStringList kObservedCodecs{"H.264", "HEVC", "HEVC10", "AV1", "AV1 Main10", "PyroWave", "unavailable"};
const QStringList kDecoderBackends{"VA-API", "V4L2", "PyroWave Vulkan", "unavailable"};
const QStringList kTransferPaths{"DMA-BUF", "CPU upload", "unavailable"};

const QStringList kTopKeys{"kind", "scope", "basis", "marked_at_utc", "sample_received_utc", "client", "requested", "observed", "readings"};
const QStringList kClientKeys{"app", "version"};
const QStringList kRequestedKeys{"width", "height", "fps", "bitrate_kbps", "video_codec", "profile_preference", "launch_mode"};
const QStringList kObservedKeys{"codec", "decoder_backend", "frame_transfer_path", "width", "height", "target_fps"};

struct Reading { const char* key; double max; bool integer; const char* label; const char* unit; const char* scope; };
const Reading kReadings[] = {
    {"incoming_fps", 1000, false, "Frames received", "fps", "frames received by this device"},
    {"decoded_fps", 1000, false, "Frames decoded", "fps", "frames decoded by this device"},
    {"composed_fps", 1000, false, "Frames drawn", "fps", "app draws, not panel presentation"},
    {"submitted_fps", 1000, false, "Frames submitted", "fps", "frames handed to the presenter"},
    {"decoder_callback_ms", 1e9, false, "Decoder callback", "ms", "includes waits and frame handoff; not GPU-only decode"},
    {"host_processing_ms", 1e9, false, "Host processing", "ms", "host processing time reported in the video stream"},
    {"video_payload_mbps", 1e9, false, "Video payload", "Mbps", "video payload received between samples"},
    {"client_media_loss_percent", 100, false, "Video frame gaps", "%", "video frame sequence gaps between samples; control retries are separate"},
    {"delivery_queue_depth", 2, true, "Frames waiting", "frames", "frames waiting for presentation at that moment"},
    {"rtt_ms", 1e9, false, "Control round trip", "ms", "control-channel round trip, not video latency"},
    {"rtt_variation_ms", 1e9, false, "Control round-trip variation", "ms", "control-channel round trip, not video latency"},
};

bool exactKeys(const QJsonObject& object, const QStringList& keys) {
    if (object.size() != keys.size()) return false;
    for (const auto& key : keys) if (!object.contains(key)) return false;
    return true;
}
bool nullableNumber(const QJsonValue& value, double min, double max, bool integer) {
    if (value.isNull()) return true;
    if (!value.isDouble()) return false;
    const double n = value.toDouble();
    if (!std::isfinite(n) || n < min || n > max) return false;
    // Finite, typed and bounded. No decimal-scale check: an IEEE double such as
    // 134698096.372 has no exact three-decimal representation.
    return !integer || std::floor(n) == n;
}
bool choiceValue(const QJsonValue& value, const QStringList& choices) {
    return value.isString() && choices.contains(value.toString());
}
QString utcText(const QDateTime& value) { return value.toUTC().toString(Qt::ISODateWithMs); }
std::optional<QDateTime> utcValue(const QJsonValue& value) {
    if (!value.isString() || value.toString().size() > 32) return {};
    const auto parsed = QDateTime::fromString(value.toString(), Qt::ISODateWithMs);
    if (!parsed.isValid() || parsed.offsetFromUtc() != 0 || utcText(parsed) != value.toString()) return {};
    return parsed;
}
QJsonValue copied(const QJsonObject& object, const char* key) {
    const auto value = object.value(key);
    return value.isUndefined() ? QJsonValue(QJsonValue::Null) : value;
}
QString version(const QJsonValue& value) {
    static const QRegularExpression pattern("\\A[0-9]{1,3}(?:\\.[0-9]{1,3}){2,3}\\z");
    return value.isString() && pattern.match(value.toString()).hasMatch() ? value.toString() : QStringLiteral("local-preview");
}
bool validVersion(const QJsonValue& value) {
    static const QRegularExpression pattern("\\A[0-9]{1,3}(?:\\.[0-9]{1,3}){2,3}\\z");
    return value.isString() && (value.toString() == "local-preview" || pattern.match(value.toString()).hasMatch());
}
// Sentinels are unknown, even when both sides carry the same sentinel. Explicit
// words such as a requested "auto" and measured zeros remain known.
bool sentinel(const QJsonValue& value) {
    return value.isNull() || value.isUndefined() ||
        (value.isString() && (value.toString() == "unavailable" || value.toString() == "local-preview"));
}
QString shown(const QJsonValue& value, const char* unit = "") {
    if (sentinel(value)) return "unknown";
    if (value.isString()) return value.toString();
    const double n = value.toDouble();
    QString text = std::floor(n) == n ? QString::number(static_cast<qint64>(n)) : QString::number(n, 'f', 3);
    if (text.contains('.')) { while (text.endsWith('0')) text.chop(1); if (text.endsWith('.')) text.chop(1); }
    return QString(unit).isEmpty() ? text : text + " " + unit;
}
bool known(const QJsonValue& value) { return !sentinel(value); }

enum class FileRead { Missing, Unsafe, Unreadable, Ok };
// O_NOFOLLOW open, then the opened file itself must be a regular, owned, private
// file no larger than `limit`, and exactly its fstat size must be read before EOF.
// Any short read, read error or growth is Unreadable, never a partial result.
FileRead readPrivate(const QString& path, qsizetype limit, QByteArray* bytes) {
    const int fd = ::open(QFile::encodeName(path).constData(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return errno == ENOENT ? FileRead::Missing : FileRead::Unsafe;
    struct stat st {};
    FileRead result = FileRead::Unsafe;
    if (::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == ::geteuid() && (st.st_mode & (S_IRWXG | S_IRWXO)) == 0) {
        result = FileRead::Unreadable;
        if (st.st_size >= 0 && st.st_size <= limit) {
            bytes->resize(static_cast<qsizetype>(st.st_size));
            qsizetype total = 0;
            bool failed = false;
            while (total < bytes->size()) {
                const auto got = ::read(fd, bytes->data() + total, static_cast<size_t>(bytes->size() - total));
                if (got < 0 && errno == EINTR) continue;
                if (got <= 0) { failed = true; break; }
                total += got;
            }
            char extra = 0;
            ssize_t tail = -1;
            if (!failed) do { tail = ::read(fd, &extra, 1); } while (tail < 0 && errno == EINTR);
            if (!failed && total == bytes->size() && tail == 0) result = FileRead::Ok;
        }
    }
    ::close(fd);
    if (result != FileRead::Ok) bytes->clear();
    return result;
}
}

bool validWorkingBaselineRequest(const DeckWorkingBaselineRequest& r) {
    // Mirrors supportedDeckResolution/ProfileRate/ProfileBitrate and the play
    // configuration's closed choices; a Linux test checks the two stay equal.
    return r.width >= 320 && r.width <= 4096 && r.width % 2 == 0 && r.height >= 240 && r.height <= 4096 && r.height % 2 == 0 &&
        r.fps >= 15 && r.fps <= 240 && r.bitrateKbps >= 1000 && r.bitrateKbps <= 500000 &&
        kCodecs.contains(r.videoCodec) && kProfiles.contains(r.profilePreference) && kLaunchModes.contains(r.launchMode);
}

QString workingBaselineScope(const QByteArray& host, const QByteArray& client, const QString& appUuid) {
    if (host.isEmpty() || client.isEmpty() || appUuid.isEmpty() || appUuid.size() > 256) return {};
    const QJsonArray parts{kScopeLabel, QString::fromLatin1(host.toHex()), QString::fromLatin1(client.toHex()), appUuid};
    return QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(parts).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256).toHex());
}
bool validWorkingBaselineScope(const QString& scope) {
    static const QRegularExpression pattern("\\A[a-f0-9]{64}\\z");
    return pattern.match(scope).hasMatch();
}

bool workingBaselineSampleFresh(bool hudFresh, qint64 receivedMs, qint64 nowMs) {
    return hudFresh && receivedMs >= 0 && nowMs >= receivedMs && nowMs - receivedMs <= kWorkingBaselineFreshMs;
}

std::optional<QJsonObject> workingBaselineRecord(const QString& scope, const QJsonObject& report,
    const std::optional<DeckWorkingBaselineRequest>& requested, const QDateTime& markedUtc, qint64 sampleAgeMs) {
    if (!validWorkingBaselineScope(scope) || !markedUtc.isValid() || sampleAgeMs < 0 || sampleAgeMs > kWorkingBaselineFreshMs) return {};
    if (requested && !validWorkingBaselineRequest(*requested)) return {};
    // The builder's own contract: only a report whose client readings were fresh.
    const auto availability = report.value("client_readings").toObject().value("available");
    if (!availability.isBool() || !availability.toBool()) return {};
    const auto marked = markedUtc.toUTC();
    QJsonObject request;
    const auto nullOr = [&](auto value) { return requested ? QJsonValue(value) : QJsonValue(QJsonValue::Null); };
    request["width"] = nullOr(requested ? requested->width : 0);
    request["height"] = nullOr(requested ? requested->height : 0);
    request["fps"] = nullOr(requested ? requested->fps : 0);
    request["bitrate_kbps"] = nullOr(requested ? requested->bitrateKbps : 0);
    request["video_codec"] = nullOr(requested ? requested->videoCodec : QString{});
    request["profile_preference"] = nullOr(requested ? requested->profilePreference : QString{});
    request["launch_mode"] = nullOr(requested ? requested->launchMode : QString{});
    const auto client = report.value("client_readings").toObject();
    const auto network = report.value("network").toObject();
    const auto host = report.value("host").toObject();
    QJsonObject observed{{"codec", copied(client, "codec")}, {"decoder_backend", copied(client, "decoder_backend")},
        {"frame_transfer_path", copied(client, "frame_transfer_path")}, {"width", copied(client, "width")},
        {"height", copied(client, "height")}, {"target_fps", copied(client, "requested_fps")}};
    QJsonObject readings{{"incoming_fps", copied(client, "incoming_fps")}, {"decoded_fps", copied(client, "decoded_fps")},
        {"composed_fps", copied(client, "composed_fps")}, {"submitted_fps", copied(client, "submitted_fps")},
        {"decoder_callback_ms", copied(client, "decoder_callback_ms")}, {"host_processing_ms", copied(host, "processing_ms")},
        {"video_payload_mbps", copied(network, "video_payload_mbps")},
        {"client_media_loss_percent", copied(network, "client_media_loss_percent")},
        {"delivery_queue_depth", copied(client, "delivery_queue_depth")},
        {"rtt_ms", copied(network, "rtt_ms")}, {"rtt_variation_ms", copied(network, "rtt_variation_ms")}};
    const QJsonObject record{{"kind", kKind}, {"scope", scope}, {"basis", kBasis},
        {"marked_at_utc", utcText(marked)}, {"sample_received_utc", utcText(marked.addMSecs(-sampleAgeMs))},
        {"client", QJsonObject{{"app", "Nova Linux"}, {"version", version(report.value("version"))}}},
        {"requested", request}, {"observed", observed}, {"readings", readings}};
    if (!validWorkingBaseline(record, scope, marked) || serializeWorkingBaseline(record).size() > kWorkingBaselineMaxBytes) return {};
    return record;
}

bool validWorkingBaseline(const QJsonObject& record, const QString& expectedScope, const QDateTime& nowUtc) {
    if (!exactKeys(record, kTopKeys) || record.value("kind") != kKind || record.value("basis") != kBasis ||
        !validWorkingBaselineScope(expectedScope) || record.value("scope") != expectedScope) return false;
    const auto marked = utcValue(record.value("marked_at_utc"));
    const auto sample = utcValue(record.value("sample_received_utc"));
    if (!marked || !sample || !nowUtc.isValid()) return false;
    const auto age = sample->msecsTo(*marked);
    if (age < 0 || age > kWorkingBaselineFreshMs || nowUtc.toUTC().msecsTo(*marked) > kFutureToleranceMs) return false;
    const auto client = record.value("client"), requested = record.value("requested"), observed = record.value("observed"), readings = record.value("readings");
    if (!client.isObject() || !requested.isObject() || !observed.isObject() || !readings.isObject()) return false;
    const auto c = client.toObject();
    if (!exactKeys(c, kClientKeys) || c.value("app") != "Nova Linux" || !validVersion(c.value("version"))) return false;
    const auto r = requested.toObject();
    if (!exactKeys(r, kRequestedKeys)) return false;
    int nulls = 0;
    for (const auto& key : kRequestedKeys) if (r.value(key).isNull()) ++nulls;
    // The request is either wholly known (explicit configuration) or wholly unknown.
    if (nulls != 0 && nulls != kRequestedKeys.size()) return false;
    if (nulls == 0) {
        if (!r.value("width").isDouble() || !r.value("height").isDouble() || !r.value("fps").isDouble() || !r.value("bitrate_kbps").isDouble() ||
            !r.value("video_codec").isString() || !r.value("profile_preference").isString() || !r.value("launch_mode").isString()) return false;
        for (const auto* key : {"width", "height", "fps", "bitrate_kbps"})
            if (!nullableNumber(r.value(key), 0, 500000, true)) return false;
        const DeckWorkingBaselineRequest typed{r.value("width").toInt(), r.value("height").toInt(), r.value("fps").toInt(),
            r.value("bitrate_kbps").toInt(), r.value("video_codec").toString(), r.value("profile_preference").toString(), r.value("launch_mode").toString()};
        if (!validWorkingBaselineRequest(typed)) return false;
    }
    const auto o = observed.toObject();
    if (!exactKeys(o, kObservedKeys) || !choiceValue(o.value("codec"), kObservedCodecs) ||
        !choiceValue(o.value("decoder_backend"), kDecoderBackends) || !choiceValue(o.value("frame_transfer_path"), kTransferPaths) ||
        !nullableNumber(o.value("width"), 10, 9999, true) || !nullableNumber(o.value("height"), 10, 9999, true) ||
        o.value("width").isNull() != o.value("height").isNull() || !nullableNumber(o.value("target_fps"), 0, 240, false)) return false;
    const auto m = readings.toObject();
    if (m.size() != static_cast<qsizetype>(std::size(kReadings))) return false;
    for (const auto& reading : kReadings)
        if (!m.contains(reading.key) || !nullableNumber(m.value(reading.key), 0, reading.max, reading.integer)) return false;
    return true;
}

QByteArray serializeWorkingBaseline(const QJsonObject& record) { return QJsonDocument(record).toJson(QJsonDocument::Compact); }

std::optional<QJsonObject> parseWorkingBaseline(const QByteArray& bytes, const QString& expectedScope, const QDateTime& nowUtc) {
    if (bytes.isEmpty() || bytes.size() > kWorkingBaselineMaxBytes) return {};
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(bytes, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) return {};
    const auto record = document.object();
    if (!validWorkingBaseline(record, expectedScope, nowUtc)) return {};
    return record;
}

QVariantMap compareWorkingBaselines(const QJsonObject& baseline, const std::optional<QJsonObject>& current) {
    QVariantList context, readings;
    QStringList differentFields, unknownFields;
    int same = 0, different = 0, unknown = 0;
    const auto contextRow = [&](const QString& key, const QString& label, const QJsonValue& before, const QJsonValue& now) {
        QString state = "unknown";
        if (current && known(before) && known(now)) state = before == now ? "same" : "different";
        (state == "same" ? same : state == "different" ? different : unknown)++;
        if (state == "different") differentFields << label;
        if (state == "unknown") unknownFields << label;
        context.append(QVariantMap{{"key", key}, {"label", label}, {"baseline", shown(before)},
            {"now", current ? shown(now) : QString("unknown")}, {"state", state}});
    };
    const auto at = [](const std::optional<QJsonObject>& record, const char* group, const char* key) {
        return record ? record->value(group).toObject().value(key) : QJsonValue(QJsonValue::Null);
    };
    const std::optional<QJsonObject> before = baseline;
    for (const auto& [key, label] : std::initializer_list<std::pair<const char*, const char*>>{
            {"width", "Requested width"}, {"height", "Requested height"}, {"fps", "Requested frame rate"},
            {"bitrate_kbps", "Requested bitrate (kbps)"}, {"video_codec", "Requested codec"},
            {"profile_preference", "Requested profile"}, {"launch_mode", "Requested launch mode"}})
        contextRow(QString("requested.") + key, label, at(before, "requested", key), at(current, "requested", key));
    for (const auto& [key, label] : std::initializer_list<std::pair<const char*, const char*>>{
            {"codec", "Video codec"}, {"decoder_backend", "Decoder"}, {"frame_transfer_path", "Frame path"},
            {"width", "Video width"}, {"height", "Video height"}, {"target_fps", "Target frame rate"}})
        contextRow(QString("observed.") + key, label, at(before, "observed", key), at(current, "observed", key));
    contextRow("client.version", "Nova version", at(before, "client", "version"), at(current, "client", "version"));
    for (const auto& reading : kReadings) {
        const auto then = at(before, "readings", reading.key);
        const auto now = at(current, "readings", reading.key);
        readings.append(QVariantMap{{"key", QString(reading.key)}, {"label", reading.label}, {"scope", reading.scope},
            {"baseline", shown(then, reading.unit)}, {"now", current ? shown(now, reading.unit) : QString("unknown")},
            {"state", current && known(then) && known(now) ? "reading" : "unknown"}});
    }
    return {{"status", "ready"}, {"markedAtUtc", baseline.value("marked_at_utc").toString()},
        {"sampleReceivedUtc", baseline.value("sample_received_utc").toString()},
        {"currentFresh", current.has_value()},
        {"currentSampleReceivedUtc", current ? current->value("sample_received_utc").toString() : QString{}},
        {"contextState", different ? "different" : unknown ? "unknown" : "same"},
        {"differentFields", differentFields}, {"unknownFields", unknownFields},
        {"context", context}, {"readings", readings},
        {"notCompared", QStringList{"Game scene", "Host build and driver", "Host-side readings", "Network route", "Display panel timing"}}};
}

namespace {
constexpr auto kIndexKind = "nova-deck-working-baseline-index-v1";
constexpr auto kIndexName = "index.json";
constexpr auto kLockName = ".working-baselines.lock";

enum class Directory { Missing, Safe, Unsafe };
// lstat: never follows a link. Safe is an owned directory with exactly owner access
// and no group or other bits. Loose permissions are refused, never changed.
Directory inspectDirectory(const QString& path) {
    struct stat st {};
    if (::lstat(QFile::encodeName(path).constData(), &st) != 0) return errno == ENOENT ? Directory::Missing : Directory::Unsafe;
    return S_ISDIR(st.st_mode) && st.st_uid == ::geteuid() && (st.st_mode & (S_IRWXG | S_IRWXO)) == 0 &&
        (st.st_mode & S_IRWXU) == S_IRWXU ? Directory::Safe : Directory::Unsafe;
}

struct Index { QString status; QJsonObject entries; };
// status: ok, missing, storage_unsafe or index_unreadable. The envelope must be
// exactly {kind, entries}, with at most 12 entries keyed by valid scopes.
Index readIndex(const QString& directory) {
    QByteArray bytes;
    const auto read = readPrivate(directory + "/" + kIndexName, kWorkingBaselineIndexMaxBytes, &bytes);
    if (read == FileRead::Missing) return {"missing", {}};
    if (read == FileRead::Unsafe) return {"storage_unsafe", {}};
    if (read != FileRead::Ok) return {"index_unreadable", {}};
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(bytes, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) return {"index_unreadable", {}};
    const auto root = document.object();
    if (root.size() != 2 || root.value("kind") != kIndexKind || !root.value("entries").isObject()) return {"index_unreadable", {}};
    const auto entries = root.value("entries").toObject();
    if (entries.size() > kWorkingBaselineMaxScopes) return {"index_unreadable", {}};
    for (auto it = entries.begin(); it != entries.end(); ++it)
        if (!validWorkingBaselineScope(it.key())) return {"index_unreadable", {}};
    return {"ok", entries};
}
// One record within a readable envelope; an invalid record affects only its scope.
std::optional<QJsonObject> entryRecord(const QJsonObject& entries, const QString& scope, const QDateTime& nowUtc) {
    const auto value = entries.value(scope);
    if (!value.isObject()) return {};
    const auto record = value.toObject();
    if (serializeWorkingBaseline(record).size() > kWorkingBaselineMaxBytes || !validWorkingBaseline(record, scope, nowUtc)) return {};
    return record;
}
QByteArray serializeIndex(const QJsonObject& entries) {
    return QJsonDocument(QJsonObject{{"kind", kIndexKind}, {"entries", entries}}).toJson(QJsonDocument::Compact);
}
// One checked atomic replacement: a failure leaves the previous index bytes as they were.
bool commitIndex(const QString& directory, const QByteArray& bytes) {
    QSaveFile file(directory + "/" + kIndexName);
    file.setDirectWriteFallback(false);
    return file.open(QIODevice::WriteOnly) && file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner) &&
        file.write(bytes) == bytes.size() && file.commit();
}
}

QString DeckWorkingBaselineStore::defaultDirectory() {
    const auto root = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    return root.isEmpty() ? QString{} : root + "/working-baselines";
}

DeckWorkingBaselineStore::Read DeckWorkingBaselineStore::load(const QString& scope, const QDateTime& nowUtc) const {
    // Read-only: no directory, file, lock or key is created, changed or removed.
    if (directory_.isEmpty() || !QDir::isAbsolutePath(directory_) || !validWorkingBaselineScope(scope)) return {"storage_unavailable", {}};
    const auto directory = inspectDirectory(directory_);
    if (directory == Directory::Missing) return {"no_baseline", {}};
    if (directory == Directory::Unsafe) return {"storage_unsafe", {}};
    const auto index = readIndex(directory_);
    if (index.status == "missing") return {"no_baseline", {}};
    if (index.status != "ok") return {index.status, {}};
    if (!index.entries.contains(scope)) return {"no_baseline", {}};
    auto record = entryRecord(index.entries, scope, nowUtc);
    if (!record) return {"baseline_unreadable", {}};
    return {"ready", std::move(record)};
}

QString DeckWorkingBaselineStore::save(const QString& scope, const QJsonObject& record, const QDateTime& nowUtc) {
    // Prepare and fully check the new bytes before touching storage.
    if (!validWorkingBaselineScope(scope) || !validWorkingBaseline(record, scope, nowUtc)) return "invalid_snapshot";
    const auto bytes = serializeWorkingBaseline(record);
    if (bytes.size() > kWorkingBaselineMaxBytes) return "too_large";
    if (parseWorkingBaseline(bytes, scope, nowUtc) != record) return "invalid_snapshot";
    if (directory_.isEmpty() || !QDir::isAbsolutePath(directory_)) return "storage_unavailable";
    auto directory = inspectDirectory(directory_);
    if (directory == Directory::Missing) {
        if (!QDir().mkpath(QFileInfo(directory_).absolutePath())) return "storage_unavailable";
        if (::mkdir(QFile::encodeName(directory_).constData(), S_IRWXU) != 0 && errno != EEXIST) return "storage_unavailable";
        directory = inspectDirectory(directory_);
    }
    if (directory != Directory::Safe) return "storage_unsafe";
    QLockFile lock(directory_ + "/" + kLockName);
    lock.setStaleLockTime(0);
    if (!lock.tryLock(0)) return "storage_busy";
    const auto index = readIndex(directory_);
    // An unsafe or unreadable index is refused, never replaced: it may hold other baselines.
    if (index.status != "ok" && index.status != "missing") return index.status;
    auto entries = index.entries;
    entries.insert(scope, record);
    // Deterministic retention, inside the same single commit: other scopes' malformed
    // entries first (by scope), then the oldest valid mark (ties by scope), until the
    // index holds at most 12 entries and fits the total byte cap.
    QStringList order;
    for (auto it = entries.begin(); it != entries.end(); ++it) if (it.key() != scope) order << it.key();
    std::sort(order.begin(), order.end(), [&](const QString& a, const QString& b) {
        const auto left = entryRecord(entries, a, nowUtc), right = entryRecord(entries, b, nowUtc);
        if (left.has_value() != right.has_value()) return !left.has_value();
        if (left) {
            const auto l = left->value("marked_at_utc").toString(), r = right->value("marked_at_utc").toString();
            if (l != r) return l < r; // canonical UTC text orders chronologically
        }
        return a < b;
    });
    auto next = serializeIndex(entries);
    for (const auto& victim : order) {
        if (entries.size() <= kWorkingBaselineMaxScopes && next.size() <= kWorkingBaselineIndexMaxBytes) break;
        entries.remove(victim);
        next = serializeIndex(entries);
    }
    if (entries.size() > kWorkingBaselineMaxScopes || next.size() > kWorkingBaselineIndexMaxBytes) return "storage_full";
    return commitIndex(directory_, next) ? "saved" : "write_failed";
}

QString DeckWorkingBaselineStore::clear(const QString& scope) {
    if (directory_.isEmpty() || !QDir::isAbsolutePath(directory_) || !validWorkingBaselineScope(scope)) return "storage_unavailable";
    const auto directory = inspectDirectory(directory_);
    if (directory == Directory::Missing) return "no_baseline";
    if (directory == Directory::Unsafe) return "storage_unsafe";
    QLockFile lock(directory_ + "/" + kLockName);
    lock.setStaleLockTime(0);
    if (!lock.tryLock(0)) return "storage_busy";
    const auto index = readIndex(directory_);
    if (index.status == "missing") return "no_baseline";
    if (index.status != "ok") return index.status;
    if (!index.entries.contains(scope)) return "no_baseline";
    // Explicit maintenance: removes this scope's entry even when it is malformed.
    auto entries = index.entries;
    entries.remove(scope);
    return commitIndex(directory_, serializeIndex(entries)) ? "cleared" : "write_failed";
}
}
