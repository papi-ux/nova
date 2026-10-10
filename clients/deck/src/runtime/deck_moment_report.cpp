#include "runtime/deck_moment_report.h"
#include "runtime/deck_support_report.h"
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSet>
#include <QStandardPaths>
#include <QUuid>
#include <cmath>
#include <cerrno>
#include <dirent.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace nova::deck::runtime {
namespace {
bool flag(const QVariantMap& hud, const char* key) {
    const auto v = hud.value(key);
    return v.metaType().id() == QMetaType::Bool && v.toBool();
}
QJsonObject readings(const QVariantMap& hud) {
    // Called only for a current sample; support-report strings are selected again
    // into this closed schema. Doctor and free-form fields never enter the ring.
    const auto report = deckSupportReport(hud);
    const auto oldClient = report.value("client_readings").toObject();
    const auto oldHost = report.value("host").toObject();
    QJsonObject client;
    for (const auto* key : {"codec", "decoder_backend", "frame_transfer_path"}) {
        const auto value = oldClient.value(key);
        client[key] = value == "unavailable" ? QJsonValue(QJsonValue::Null) : value;
    }
    for (const auto* key : {"width", "height", "incoming_fps", "decoded_fps", "composed_fps", "submitted_fps",
            "decoder_callback_ms", "delivery_queue_depth", "delivery_dropped_frames", "decoder_refused_frames"})
        client[key] = oldClient.contains(key) ? oldClient.value(key) : QJsonValue(QJsonValue::Null);
    client["target_fps"] = oldClient.value("requested_fps");
    QJsonObject host{{"available", oldHost.value("available")}};
    for (const auto* key : {"encoder_applied_mbps", "requested_mbps", "quality_ceiling_mbps"}) host[key] = oldHost.value(key);
    return {{"client", client}, {"network", report.value("network")},
        {"stream_reported", QJsonObject{{"host_processing_ms", oldHost.value("processing_ms")}}}, {"host_telemetry", host}};
}
bool keys(const QJsonObject& object, std::initializer_list<const char*> expected) {
    QSet<QString> wanted, actual;
    for (const auto* key : expected) wanted.insert(key);
    for (const auto& key : object.keys()) actual.insert(key);
    return actual == wanted;
}
bool numeric(const QJsonValue& value, double maximum, bool integral = false) {
    return value.isNull() || (value.isDouble() && std::isfinite(value.toDouble()) && value.toDouble() >= 0 &&
        value.toDouble() <= maximum && (!integral || std::floor(value.toDouble()) == value.toDouble()));
}
bool choice(const QJsonValue& value, std::initializer_list<const char*> allowed) {
    if (value.isNull()) return true;
    if (!value.isString()) return false;
    for (const auto* text : allowed) if (value.toString() == text) return true;
    return false;
}
bool validReadings(const QJsonValue& value) {
    if (value.isNull()) return true;
    if (!value.isObject()) return false;
    const auto r = value.toObject();
    if (!keys(r, {"client", "network", "stream_reported", "host_telemetry"})) return false;
    for (const auto* k : {"client", "network", "stream_reported", "host_telemetry"}) if (!r[k].isObject()) return false;
    const auto c = r["client"].toObject(), n = r["network"].toObject(), s = r["stream_reported"].toObject(), h = r["host_telemetry"].toObject();
    if (!keys(c, {"codec", "decoder_backend", "frame_transfer_path", "width", "height", "target_fps", "incoming_fps",
            "decoded_fps", "composed_fps", "submitted_fps", "decoder_callback_ms", "delivery_queue_depth",
            "delivery_dropped_frames", "decoder_refused_frames"}) ||
        !keys(n, {"rtt_ms", "rtt_variation_ms", "video_payload_mbps", "client_media_loss_percent"}) ||
        !keys(s, {"host_processing_ms"}) || !keys(h, {"available", "encoder_applied_mbps", "requested_mbps", "quality_ceiling_mbps"})) return false;
    if (!choice(c["codec"], {"H.264", "HEVC", "HEVC10", "AV1", "AV1 Main10", "PyroWave"}) ||
        !choice(c["decoder_backend"], {"VA-API", "V4L2", "PyroWave Vulkan"}) ||
        !choice(c["frame_transfer_path"], {"DMA-BUF", "CPU upload"})) return false;
    for (const auto* k : {"width", "height"}) if (!numeric(c[k], 9999, true)) return false;
    for (const auto* k : {"incoming_fps", "decoded_fps", "composed_fps", "submitted_fps"}) if (!numeric(c[k], 1000)) return false;
    if (!numeric(c["target_fps"], 240) || !numeric(c["delivery_queue_depth"], 2, true) ||
        !numeric(c["delivery_dropped_frames"], 1e9, true) || !numeric(c["decoder_refused_frames"], 1e9, true) ||
        !numeric(c["decoder_callback_ms"], 1e9) || !numeric(s["host_processing_ms"], 1e9)) return false;
    for (const auto* k : {"rtt_ms", "rtt_variation_ms", "video_payload_mbps"}) if (!numeric(n[k], 1e9)) return false;
    if (!numeric(n["client_media_loss_percent"], 100) || !h["available"].isBool()) return false;
    for (const auto* k : {"encoder_applied_mbps", "requested_mbps", "quality_ceiling_mbps"})
        if (!numeric(h[k], 300) || (!h["available"].toBool() && !h[k].isNull())) return false;
    return true;
}
struct Fd {
    int value = -1;
    explicit Fd(int fd) : value(fd) {}
    ~Fd() { if (value >= 0) ::close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};
bool ownedRegular0600(int fd) {
    struct stat st{};
    return ::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == ::geteuid() && (st.st_mode & 07777) == 0600;
}
QVariantMap result(const char* status, const QString& name = {}) {
    QVariantMap out{{"status", status}};
    if (!name.isEmpty()) out["fileName"] = name;
    return out;
}
}

QJsonObject selectMomentWindow(const QList<DeckMomentSample>& samples, qint64 markMs, std::uint64_t generation,
        std::optional<DeckMomentEviction> eviction, qsizetype maxBytes) {
    if (samples.size() > kMomentMaxEntries || markMs < 0 || maxBytes < 2 || maxBytes > kMomentWindowMaxBytes) return {};
    QJsonObject omitted{{"other_run", 0}, {"future", 0}, {"invalid", 0}, {"too_old", 0}, {"out_of_order", 0}, {"bytes", 0}};
    const auto count = [&](const char* key) { omitted[key] = omitted[key].toInt() + 1; };
    QJsonArray entries;
    qint64 lastKept = -1;
    for (const auto& sample : samples) {
        if (sample.generation != generation) { count("other_run"); continue; }
        if (sample.receivedMs < 0 || !validReadings(sample.readings)) { count("invalid"); continue; }
        if (sample.receivedMs > markMs) { count("future"); continue; }
        if (markMs - sample.receivedMs > kMomentLookbackMs) { count("too_old"); continue; }
        if (sample.receivedMs <= lastKept) { count("out_of_order"); continue; }
        lastKept = sample.receivedMs;
        entries.append(QJsonObject{{"received_age_ms", markMs - sample.receivedMs},
            {"state_at_receipt", sample.readings.isNull() ? "unavailable" : "current"},
            {"provenance", "client_hud_sample"}, {"readings", sample.readings}});
    }
    while (QJsonDocument(entries).toJson(QJsonDocument::Compact).size() > maxBytes && !entries.isEmpty()) {
        entries.removeAt(0); count("bytes");
    }
    QJsonValue span = QJsonValue::Null;
    if (entries.size() >= 2) span = entries.first().toObject()["received_age_ms"].toDouble() - entries.last().toObject()["received_age_ms"].toDouble();
    const bool evicted = eviction && eviction->generation == generation && eviction->receivedMs >= 0 &&
        eviction->receivedMs <= markMs && markMs - eviction->receivedMs <= kMomentLookbackMs;
    return {{"max_entries", kMomentMaxEntries}, {"max_lookback_ms", kMomentLookbackMs}, {"sampled", true},
        {"continuous", false}, {"span_ms", span}, {"entries", entries}, {"omitted", omitted}, {"older_samples_not_retained", evicted}};
}
void DeckMomentHistory::append(const QVariantMap& hud, qint64 receivedMs, std::uint64_t generation) {
    samples_.append({receivedMs, generation, flag(hud, "fresh") ? QJsonValue(readings(hud)) : QJsonValue(QJsonValue::Null)});
    if (samples_.size() > kMomentMaxEntries) {
        const auto old = samples_.takeFirst(); eviction_ = DeckMomentEviction{old.receivedMs, old.generation};
    }
}
QJsonObject DeckMomentHistory::window(qint64 markMs, std::uint64_t generation) const {
    return selectMomentWindow(samples_, markMs, generation, eviction_);
}
QByteArray buildMomentReport(const QVariantMap& hud, qint64 receivedMs, qint64 markMs, std::uint64_t generation,
        const std::optional<DeckWorkingBaselineRequest>& requested, const DeckMomentHistory& history, const QDateTime& markedUtc) {
    if (markMs < 0 || !markedUtc.isValid()) return {};
    const bool available = receivedMs >= 0 && receivedMs <= markMs;
    const bool current = available && workingBaselineSampleFresh(flag(hud, "fresh"), receivedMs, markMs);
    QJsonValue request = QJsonValue::Null;
    if (requested && validWorkingBaselineRequest(*requested)) request = QJsonObject{{"width", requested->width}, {"height", requested->height},
        {"fps", requested->fps}, {"bitrate_kbps", requested->bitrateKbps}, {"video_codec", requested->videoCodec},
        {"profile_preference", requested->profilePreference}, {"launch_mode", requested->launchMode}};
    const auto version = QCoreApplication::applicationVersion();
    const auto window = history.window(markMs, generation);
    if (window.isEmpty()) return {};
    const QJsonValue snapshotReadings = current ? QJsonValue(readings(hud)) : QJsonValue(QJsonValue::Null);
    if (!validReadings(snapshotReadings)) return {};
    QJsonObject report{{"kind", "nova-deck-moment-report-v1"}, {"marked_at_utc", markedUtc.toUTC().toString(Qt::ISODateWithMs)},
        {"client", QJsonObject{{"app", "Nova Linux"}, {"version", QRegularExpression("\\A[0-9]{1,3}(?:\\.[0-9]{1,3}){2,3}\\z").match(version).hasMatch() ? version : "local-preview"}}},
        {"requested", request}, {"snapshot", QJsonObject{{"state", !available ? "unavailable" : current ? "current" : "stale"},
            {"sample_age_ms", available ? QJsonValue(markMs - receivedMs) : QJsonValue(QJsonValue::Null)},
            {"readings", snapshotReadings}}},
        {"window", window}, {"limitations", QJsonArray{"client_observed_readings", "host_processing_reported_in_stream",
            "sampled_not_continuous_window", "no_logs", "no_host_record", "no_identity", "doctor_not_included", "picture_audio_controls_not_judged"}}};
    const auto bytes = QJsonDocument(report).toJson(QJsonDocument::Compact);
    return bytes.size() <= kMomentReportMaxBytes ? bytes : QByteArray{};
}
QString DeckMomentStore::defaultDocumentsDirectory() { return QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation); }
QString DeckMomentStore::defaultDirectory() {
    const auto docs = defaultDocumentsDirectory(); return docs.isEmpty() ? QString{} : docs + "/Nova reports";
}
QVariantMap DeckMomentStore::save(const QByteArray& frozen, const QDateTime& markedUtc, const TestHooks* hooks) const {
    // This check precedes every filesystem operation.
    if (frozen.size() > kMomentReportMaxBytes) return result("too_large");
    if (frozen.isEmpty() || !markedUtc.isValid()) return result("write_failed");
    if (documents_.isEmpty() || !QDir::isAbsolutePath(documents_)) return result("storage_unavailable");
    Fd docs(::open(QFile::encodeName(documents_).constData(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (docs.value < 0) return result("storage_unavailable");
    const bool createdLeaf = ::mkdirat(docs.value, "Nova reports", 0700) == 0;
    if (!createdLeaf && errno != EEXIST) return result("storage_unavailable");
    Fd dir(::openat(docs.value, "Nova reports", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (dir.value < 0) return result("storage_unsafe");
    struct stat st{};
    if (::fstat(dir.value, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != ::geteuid() || (st.st_mode & 077) != 0)
        return result("storage_unsafe");
    Fd lock(::openat(dir.value, ".nova-moments.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0600));
    if (lock.value < 0 || !ownedRegular0600(lock.value)) return result("storage_unsafe");
    if (::flock(lock.value, LOCK_EX | LOCK_NB) != 0) return result(errno == EWOULDBLOCK || errno == EAGAIN ? "storage_busy" : "storage_unavailable");
    const int copied = ::fcntl(dir.value, F_DUPFD_CLOEXEC, 0);
    if (copied < 0) return result("storage_unavailable");
    DIR* listing = ::fdopendir(copied);
    if (!listing) { ::close(copied); return result("storage_unavailable"); }
    int files = 0;
    int scanError = 0;
    for (;;) {
        errno = 0;
        const auto* entry = ::readdir(listing);
        if (!entry) { scanError = errno; break; }
        const QByteArray name(entry->d_name);
        if (name.startsWith("nova-moment-") && name.endsWith(".json")) ++files;
    }
    ::closedir(listing);
    if (scanError) return result("storage_unavailable");
    if (files >= kMomentMaxFiles) return result("storage_full");
    const auto uuid = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const auto temporary = (".nova-moment-" + uuid + ".tmp").toUtf8();
    const QString finalName = hooks && !hooks->finalName.isEmpty() ? hooks->finalName :
        "nova-moment-" + markedUtc.toUTC().toString("yyyyMMdd-HHmmsszzz") + "-" + uuid + ".json";
    if (!QRegularExpression("\\Anova-moment-[A-Za-z0-9-]{1,100}\\.json\\z").match(finalName).hasMatch()) return result("write_failed");
    const auto finalBytes = finalName.toUtf8();
    Fd temp(::openat(dir.value, temporary.constData(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (temp.value < 0) return result("write_failed");
    const auto failure = [&] { ::unlinkat(dir.value, temporary.constData(), 0); return result("write_failed"); };
    qsizetype written = 0;
    while (written < frozen.size()) {
        const auto count = ::write(temp.value, frozen.constData() + written, static_cast<size_t>(frozen.size() - written));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return failure();
        written += count;
    }
    if (!ownedRegular0600(temp.value) || ::fsync(temp.value) != 0) return failure();
    const int fd = temp.value; temp.value = -1;
    if (::close(fd) != 0) return failure();
    if (::linkat(dir.value, temporary.constData(), dir.value, finalBytes.constData(), 0) != 0) return failure();
    // Publication is committed. Never delete the final name or report "not saved".
    const bool cleaned = !(hooks && hooks->failPublishedTempCleanup) && ::unlinkat(dir.value, temporary.constData(), 0) == 0;
    const bool durable = !(hooks && hooks->failDirectorySync) && ::fsync(dir.value) == 0;
    // Also covers a leaf created by an earlier durability-unknown save.
    const bool parentDurable = !(hooks && hooks->failParentSync) && ::fsync(docs.value) == 0;
    return result(cleaned && durable && parentDurable ? "saved" : "saved_durability_unknown", finalName);
}
}
