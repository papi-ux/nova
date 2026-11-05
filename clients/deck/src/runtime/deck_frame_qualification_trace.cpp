#include "runtime/deck_frame_qualification_trace.h"
#include "runtime/deck_support_report.h"
#include "runtime/deck_source_delivery_policy.h"
#include <QJsonObject>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QJsonDocument>
#include <QStandardPaths>
#include <QUuid>
#include <memory>

namespace nova::deck::runtime {
DeckFrameQualificationTrace::DeckFrameQualificationTrace(const QString& path, qint64 limit)
    : file_(path), limit_(limit) {
    if (limit <= 0 || !QDir::isAbsolutePath(path) || !file_.open(QIODevice::WriteOnly | QIODevice::NewOnly)) return;
    if (!file_.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner)) file_.close();
}
bool DeckFrameQualificationTrace::append(const QVariantMap& hud, qint64 sampleMs) {
    if (!file_.isOpen() || sampleMs < 0) return false;
    auto report = deckSupportReport(hud);
    report["sample_time_ms"] = sampleMs;
    const auto bounded = [&hud](const char* key, std::initializer_list<const char*> allowed) {
        const auto value = hud.value(key);
        if (value.metaType().id() == QMetaType::QString)
            for (const auto* name : allowed) if (value.toString() == name) return QString(name);
        return QString("unavailable");
    };
    report["source_time"] = QJsonObject{
        {"requested", bounded("sourceTimeRequested", {"off", "auto", "source_time"})},
        {"effective", bounded("sourceTimeEffective", {"legacy", "pending", "source_time_fixed", "source_time_vrr", "unavailable"})},
        {"fallback", bounded("sourceTimeFallback", {"none", "not_requested", "renderer_not_participating", "pacing_mode_incompatible",
            "source_timestamp_missing", "source_discontinuity", "timing_metadata_missing", "clock_mapping_unstable", "buffer_limit",
            "decoder_pressure", "hold_ceiling_exceeded", "stale_epoch", "stale_frame", "host_status_missing", "host_status_stale"})},
        {"reserve_maximum_us", double(sourceReserveMaximumUs)}, {"maximum_mapping_error_us", double(sourceMaximumMappingErrorUs)},
        {"hold_ceiling_us", double(sourceHoldCeilingUs)}, {"policy_version", 1}, {"offset_instability_revision", 2}};
    const auto bytes = QJsonDocument(report).toJson(QJsonDocument::Compact) + '\n';
    if (file_.size() > limit_ || bytes.size() > limit_ - file_.size()) { file_.close(); return false; }
    if (file_.write(bytes) != bytes.size() || !file_.flush()) { file_.close(); return false; }
    return true;
}
void appendDeckFrameQualificationTrace(const QVariantMap& hud, qint64 sampleMs) {
    if (qgetenv("NOVA_DECK_FRAME_V4L2") != "1" || qgetenv("NOVA_DECK_FRAME_TRACE") != "1") return;
    static const auto trace = []() -> std::unique_ptr<DeckFrameQualificationTrace> {
        const auto root = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
        if (root.isEmpty()) return {};
        const auto directory = root + "/frame-qualification";
        if (!QDir().mkpath(directory)) return {};
        if (QDir(directory).entryList({"*.jsonl"}, QDir::Files).size() >= 16) return {};
        const auto name = QDateTime::currentDateTimeUtc().toString("yyyyMMdd-HHmmss") + "-" +
            QUuid::createUuid().toString(QUuid::WithoutBraces) + ".jsonl";
        return std::make_unique<DeckFrameQualificationTrace>(directory + "/" + name);
    }();
    if (!trace || !trace->append(hud, sampleMs)) {
        static bool reported = false;
        if (!reported) qWarning("Frame qualification trace unavailable; saved readings will be incomplete.");
        reported = true;
    }
}
}
