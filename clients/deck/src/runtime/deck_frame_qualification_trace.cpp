#include "runtime/deck_frame_qualification_trace.h"
#include "runtime/deck_support_report.h"
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
