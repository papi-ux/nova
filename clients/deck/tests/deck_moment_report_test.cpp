#include "runtime/deck_moment_report.h"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <cstdlib>
#include <iostream>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
const QDateTime kUtc = QDateTime::fromString("2026-10-10T15:00:00.123Z", Qt::ISODateWithMs);
const DeckWorkingBaselineRequest kRequest{1280, 800, 60, 20000, "hevc", "quality", "host_virtual_display"};
QVariantMap hud() {
    return {{"fresh", true}, {"hostFresh", true}, {"appliedBitrate", "123.456M"}, {"requestedBitrate", "234.567M"}, {"qualityLimit", "287.654M"},
        {"fps", "59.8"}, {"incoming", "60.0"}, {"decoded", "59.9"}, {"submitted", "59.7"}, {"cpuUploadCompositions", "12.0"},
        {"videoWork", "1.25ms"}, {"host", "4.321ms"}, {"rtt", "17ms"}, {"jitter", "3ms"}, {"bitrate", "19.6M"},
        {"refused", "2"}, {"deliveryDrops", "4"}, {"deliveryQueueDepth", "1"}, {"requestedFps", "60"},
        {"codec", "HEVC"}, {"decoderBackend", "VA-API"}, {"frameTransferPath", "DMA-BUF"}, {"resolution", "1280×800"},
        {"mediaLoss", "0.5%"}, {"mediaLossFresh", true}, {"mediaLossSource", "Video frame sequence"},
        {"hostName", "private-PC 192.0.2.22 /home/private token=private-token"},
        {"doctor", QVariantMap{{"available", true}, {"confidence", "high"}, {"title", "private-token"}}}};
}
QJsonObject report(const QVariantMap& h = hud(), qint64 received = 9000, qint64 mark = 10000,
        const DeckMomentHistory& history = {}, std::optional<DeckWorkingBaselineRequest> request = kRequest) {
    const auto bytes = buildMomentReport(h, received, mark, 1, request, history, kUtc);
    require(!bytes.isEmpty(), "valid report refused");
    QJsonParseError error;
    const auto doc = QJsonDocument::fromJson(bytes, &error);
    require(error.error == QJsonParseError::NoError && doc.isObject(), "report is not JSON");
    return doc.object();
}
bool hasKey(const QJsonValue& value, const QString& key) {
    if (value.isObject()) {
        const auto o = value.toObject(); if (o.contains(key)) return true;
        for (auto it = o.begin(); it != o.end(); ++it) if (hasKey(it.value(), key)) return true;
    } else if (value.isArray()) for (const auto& entry : value.toArray()) if (hasKey(entry, key)) return true;
    return false;
}
void keyset(const QJsonObject& object, QStringList expected) {
    auto actual = object.keys(); actual.sort(); expected.sort(); require(actual == expected, "schema has missing or additional keys");
}
int mode(const QString& path) { struct stat st{}; return ::lstat(QFile::encodeName(path).constData(), &st) == 0 ? st.st_mode & 07777 : -1; }
QByteArray bytesOf(const QString& path) { QFile f(path); require(f.open(QIODevice::ReadOnly), "readback open failed"); return f.readAll(); }
void write(const QString& path, const QByteArray& bytes) {
    QFile f(path); require(f.open(QIODevice::WriteOnly) && f.write(bytes) == bytes.size(), "fixture write failed"); f.close();
    require(::chmod(QFile::encodeName(path).constData(), 0600) == 0, "fixture permissions failed");
}
QStringList listing(const QString& path) { return QDir(path).entryList(QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot, QDir::Name); }
QStringList files(const QString& path) { return QDir(path).entryList({"nova-moment-*.json"}, QDir::AllEntries | QDir::System, QDir::Name); }
QByteArray tree(const QString& path) {
    QByteArray out;
    for (const auto& name : listing(path)) {
        const auto entry = QDir(path).filePath(name); struct stat st{};
        require(::lstat(QFile::encodeName(entry).constData(), &st) == 0, "fixture tree stat failed");
        out += name.toUtf8() + "|" + QByteArray::number(st.st_mode) + "|" + QByteArray::number(st.st_size) + "|";
        // Never open a FIFO or follow a symlink to compare unchanged storage.
        if (S_ISREG(st.st_mode)) out += QCryptographicHash::hash(bytesOf(entry), QCryptographicHash::Sha256).toHex();
        out += '\n';
    }
    return out;
}
void snapshotAndPrivacy() {
    const auto current = report();
    keyset(current, {"kind", "marked_at_utc", "client", "requested", "snapshot", "window", "limitations"});
    require(current["kind"] == "nova-deck-moment-report-v1" && current["marked_at_utc"] == "2026-10-10T15:00:00.123Z", "report identity/time wrong");
    require(current["snapshot"].toObject()["state"] == "current", "fresh sample unavailable");
    const auto r = current["snapshot"].toObject()["readings"].toObject();
    require(r["network"].toObject()["rtt_ms"] == 17 && r["stream_reported"].toObject()["host_processing_ms"] == 4.321 &&
        r["client"].toObject()["codec"] == "HEVC", "current readings changed provenance/value");
    for (const auto* key : {"doctor", "cpu_upload_composed_fps", "generation", "token", "receivedMs", "hostName", "path"})
        require(!hasKey(current, key), "private or excluded key escaped selection");
    const auto encoded = QJsonDocument(current).toJson(QJsonDocument::Compact);
    for (const auto* text : {"private-PC", "192.0.2", "private-token", "/home/private"}) require(!encoded.contains(text), "identity leaked");
    require(report(hud(), 7500)["snapshot"].toObject()["state"] == "current", "2500 ms boundary refused");
    auto h = hud();
    for (const auto& scenario : QList<std::pair<qint64, QString>>{{7499, "stale"}, {-1, "unavailable"}, {10001, "unavailable"}}) {
        const auto s = report(h, scenario.first)["snapshot"].toObject();
        require(s["state"] == scenario.second && s["readings"].isNull(), "stale/future sample leaked observed fields");
        require(s["sample_age_ms"].isNull() == (scenario.second == "unavailable"), "unavailable age not null");
    }
    h["fresh"] = false; // hostFresh remains true: it cannot independently expose a snapshot.
    const auto stale = report(h); const auto s = stale["snapshot"].toObject();
    keyset(s, {"state", "sample_age_ms", "readings"}); require(s["state"] == "stale" && s["readings"].isNull(), "hostFresh exposed stale readings");
    const auto staleBytes = QJsonDocument(stale).toJson(QJsonDocument::Compact);
    for (const auto* text : {"123.456", "234.567", "287.654", "4.321", "HEVC", "VA-API", "DMA-BUF"}) require(!staleBytes.contains(text), "stale observed string leaked");
    // The requested configuration is separately labelled, so use no request for resolution leak checks.
    const auto noRequestBytes = QJsonDocument(report(h, 9000, 10000, {}, {})).toJson(QJsonDocument::Compact);
    require(!noRequestBytes.contains("1280") && !noRequestBytes.contains("HEVC"), "stale snapshot fell back to HUD context");
    for (const auto* key : {"\"encoder_applied_mbps\"", "\"codec\"", "\"width\""}) require(!noRequestBytes.contains(key), "stale snapshot exported observed key");
    h = hud(); h["hostFresh"] = false;
    const auto host = report(h)["snapshot"].toObject()["readings"].toObject()["host_telemetry"].toObject();
    require(host["available"] == false && host["encoder_applied_mbps"].isNull() && host["requested_mbps"].isNull() && host["quality_ceiling_mbps"].isNull(), "unavailable host telemetry leaked");
    auto invalid = kRequest; invalid.fps = 0;
    require(report(hud(), 9000, 10000, {}, invalid)["requested"].isNull() && report(hud(), 9000, 10000, {}, {})["requested"].isNull(), "invalid/absent request invented defaults");
    require(current["requested"].toObject()["video_codec"] == "hevc" && current["requested"].toObject()["fps"] == 60, "reviewed request not retained");
    std::cout << "snapshot/schema/privacy boundaries passed\n";
}
void historyAndBounds() {
    const auto r = report()["snapshot"].toObject()["readings"];
    const QList<DeckMomentSample> candidates{{500, 1, r}, {7000, 1, r}, {8000, 1, QJsonValue::Null},
        {8000, 1, r}, {6000, 1, r}, {10001, 1, r}, {-1, 1, r}, {0, 2, r}, {0, 1, r}};
    const auto w = selectMomentWindow(candidates, 10000, 1);
    keyset(w, {"max_entries", "max_lookback_ms", "sampled", "continuous", "span_ms", "entries", "omitted", "older_samples_not_retained"});
    const auto entries = w["entries"].toArray();
    const auto omitted = w["omitted"].toObject();
    require(entries.size() == 3 && entries[0].toObject()["received_age_ms"] == 9500 && entries[1].toObject()["received_age_ms"] == 3000 &&
        entries[2].toObject()["state_at_receipt"] == "unavailable" && entries[2].toObject()["readings"].isNull() && w["span_ms"] == 7500,
        "arrival order/own age/state changed");
    require(omitted["out_of_order"] == 3 && omitted["future"] == 1 && omitted["invalid"] == 1 && omitted["other_run"] == 1,
        "omitted classifications wrong");
    const auto boundary = selectMomentWindow({{0, 1, r}, {1, 1, r}}, 10001, 1);
    require(boundary["entries"].toArray().size() == 1 && boundary["omitted"].toObject()["too_old"] == 1 && boundary["span_ms"].isNull(), "10000 ms lookback boundary wrong");
    auto injected = r.toObject(); injected["identity"] = "private-token";
    require(selectMomentWindow({{100, 1, injected}}, 1000, 1)["omitted"].toObject()["invalid"] == 1, "unclosed supplied history exported");
    for (const auto& e : entries) {
        keyset(e.toObject(), {"received_age_ms", "state_at_receipt", "provenance", "readings"});
        require(e.toObject()["received_age_ms"].toDouble() >= 0 && e.toObject()["received_age_ms"].toDouble() <= 10000 &&
            e.toObject()["provenance"] == "client_hud_sample", "entry age/provenance wrong");
    }
    require(w["sampled"] == true && w["continuous"] == false && !omitted.contains("count"), "continuous/count claim invented");
    DeckMomentHistory preserved; preserved.append(hud(), 2500, 1); preserved.append(hud(), 7000, 1);
    auto staleHud = hud(); staleHud["fresh"] = false; preserved.append(staleHud, 8000, 1);
    const auto stale = report(staleHud, 8000, 12000, preserved);
    require(stale["snapshot"].toObject()["readings"].isNull() && stale["window"].toObject()["entries"].toArray().size() == 3,
        "stale mark lost valid lookback history");
    const auto kept = stale["window"].toObject()["entries"].toArray();
    require(kept[0].toObject()["readings"].toObject()["network"].toObject()["rtt_ms"] == 17 &&
        kept[1].toObject()["readings"].toObject()["network"].toObject()["rtt_ms"] == 17 && kept[2].toObject()["readings"].isNull(),
        "history receipt state was recomputed at stale mark");
    DeckMomentHistory ring; for (int i = 0; i < 11; ++i) ring.append(hud(), 1000 + i * 100, 1);
    require(ring.window(3000, 1)["entries"].toArray().size() == 10 && ring.window(3000, 1)["older_samples_not_retained"] == true,
        "ring eviction not accounted separately");
    require(ring.window(12001, 1)["older_samples_not_retained"] == false && ring.window(3000, 2)["older_samples_not_retained"] == false,
        "old/other-generation eviction counted");
    ring.clear(); require(ring.window(3000, 1)["entries"].toArray().isEmpty() && ring.window(3000, 1)["older_samples_not_retained"] == false, "clear retained eviction");
    require(kMomentWindowMaxBytes == 24576 && kMomentReportMaxBytes == 32768 && kMomentMaxFiles == 32 && kMomentMaxEntries == 10 && kMomentLookbackMs == 10000, "fixed caps changed");
    auto maximum = hud(); maximum["resolution"] = "9999×9999"; maximum["requestedFps"] = "240";
    maximum["codec"] = "AV1 Main10"; maximum["decoderBackend"] = "PyroWave Vulkan"; maximum["frameTransferPath"] = "CPU upload";
    for (const auto* key : {"fps", "incoming", "decoded", "submitted"}) maximum[key] = "999.999";
    for (const auto* key : {"rtt", "jitter", "videoWork", "host"}) maximum[key] = "999999999.999ms";
    maximum["bitrate"] = "999999999.999M"; maximum["mediaLoss"] = "99.999%";
    maximum["deliveryDrops"] = "1000000000"; maximum["refused"] = "1000000000"; maximum["deliveryQueueDepth"] = "2";
    for (const auto* key : {"appliedBitrate", "requestedBitrate", "qualityLimit"}) maximum[key] = "299.999M";
    QCoreApplication::setApplicationVersion("999.999.999.999");
    DeckMomentHistory maxHistory; QList<DeckMomentSample> maxCandidates;
    const auto maxReading = report(maximum)["snapshot"].toObject()["readings"];
    for (int i = 0; i < 10; ++i) { const qint64 at = 2000 + i * 1000; maxHistory.append(maximum, at, 1); maxCandidates.append({at, 1, maxReading}); }
    const auto trimmed = selectMomentWindow(maxCandidates, 12000, 1, {}, 2400); const auto trimmedEntries = trimmed["entries"].toArray();
    require(!trimmedEntries.isEmpty() && trimmedEntries.size() < 10 && QJsonDocument(trimmedEntries).toJson(QJsonDocument::Compact).size() <= 2400 &&
        trimmed["omitted"].toObject()["bytes"].toInt() == 10 - trimmedEntries.size(), "reduced-budget trim incorrect");
    auto oneMore = trimmedEntries; oneMore.prepend(selectMomentWindow(maxCandidates, 12000, 1)["entries"].toArray()[9 - trimmedEntries.size()]);
    require(QJsonDocument(oneMore).toJson(QJsonDocument::Compact).size() > 2400, "trim omitted a fitting newest entry");
    const auto full = buildMomentReport(maximum, 11000, 12000, 1, DeckWorkingBaselineRequest{4096,4096,240,500000,"pyrowave","stability","host_virtual_display"}, maxHistory, kUtc);
    require(!full.isEmpty() && full.size() <= 32768 && QJsonDocument::fromJson(full).object()["window"].toObject()["entries"].toArray().size() == 10, "maximum valid fixture exceeded report cap");
    require(QJsonDocument::fromJson(full).object()["requested"].isObject(), "maximum fixture lost its request");
    std::cout << "history/bounds passed; Qt maximum fixture bytes=" << full.size() << ", reduced-budget kept=" << trimmedEntries.size() << '\n';
}
void storeBoundaries() {
    const auto frozen = buildMomentReport(hud(), 9000, 10000, 1, kRequest, {}, kUtc);
    QTemporaryDir root; require(root.isValid(), "temporary Documents missing");
    const auto leaf = root.filePath("Nova reports"); DeckMomentStore store(root.path());
    require(store.save(QByteArray(32769, 'x'), kUtc)["status"] == "too_large" && !QFileInfo::exists(leaf), "oversize touched storage");
    const auto saved = store.save(frozen, kUtc); require(saved["status"] == "saved", "explicit Save failed");
    const auto path = QDir(leaf).filePath(saved["fileName"].toString());
    require(mode(leaf) == 0700 && mode(path) == 0600 && !QFileInfo(path).isSymLink() && bytesOf(path) == frozen && files(leaf).size() == 1,
        "saved file mode/type/bytes incorrect");
    require(QDir(leaf).entryList({"*.tmp"}, QDir::Files | QDir::Hidden).isEmpty(), "successful save left temp");
    DeckMomentStore::TestHooks collision; collision.finalName = saved["fileName"].toString(); const auto unchanged = tree(leaf);
    require(store.save("different bytes", kUtc, &collision)["status"] == "write_failed" && tree(leaf) == unchanged, "existing final overwritten or temp retained");
    const auto lockPath = QFile::encodeName(QDir(leaf).filePath(".nova-moments.lock")); const int lock = ::open(lockPath.constData(), O_RDWR | O_CLOEXEC);
    require(lock >= 0 && ::flock(lock, LOCK_EX | LOCK_NB) == 0, "test lock unavailable");
    require(store.save(frozen, kUtc)["status"] == "storage_busy" && tree(leaf) == unchanged, "held lock allowed Save"); ::close(lock);
    require(::chmod(lockPath.constData(), 0644) == 0, "test lock chmod failed");
    require(store.save(frozen,kUtc)["status"] == "storage_unsafe" && mode(QString::fromUtf8(lockPath)) == 0644, "unsafe lock repaired/used");
    require(::chmod(lockPath.constData(),0600) == 0, "restore test lock failed");
    DeckMomentStore::TestHooks cleanup; cleanup.failPublishedTempCleanup = true;
    const auto partial = store.save(frozen, kUtc, &cleanup);
    require(partial["status"] == "saved_durability_unknown" && bytesOf(QDir(leaf).filePath(partial["fileName"].toString())) == frozen &&
        QDir(leaf).entryList({"*.tmp"}, QDir::Files | QDir::Hidden).size() == 1, "post-publication cleanup misreported/lost final");
    DeckMomentStore::TestHooks sync; sync.failDirectorySync = true;
    const auto unsynced = store.save(frozen,kUtc,&sync);
    require(unsynced["status"] == "saved_durability_unknown" && bytesOf(QDir(leaf).filePath(unsynced["fileName"].toString())) == frozen,
        "post-publication directory sync misreported/lost final");
    QTemporaryDir firstSync; require(firstSync.isValid(), "new leaf sync fixture missing");
    DeckMomentStore::TestHooks parentSync; parentSync.failParentSync = true;
    const auto parentUnknown = DeckMomentStore(firstSync.path()).save(frozen,kUtc,&parentSync);
    require(parentUnknown["status"] == "saved_durability_unknown" &&
        bytesOf(firstSync.filePath("Nova reports/" + parentUnknown["fileName"].toString())) == frozen, "new leaf parent sync misreported/lost final");
    // A later explicit save leaves the earlier owned-but-unremoved temporary name alone.
    require(store.save(frozen,kUtc)["status"] == "saved" && QDir(leaf).entryList({"*.tmp"},QDir::Files|QDir::Hidden).size() == 1,
        "later save deleted a leftover temporary report");
    QTemporaryDir unsafe; require(unsafe.isValid(), "unsafe fixture missing"); const auto unsafeLeaf = unsafe.filePath("Nova reports");
    require(QDir().mkpath(unsafeLeaf) && ::chmod(QFile::encodeName(unsafeLeaf).constData(),0755) == 0, "unsafe directory fixture failed");
    require(DeckMomentStore(unsafe.path()).save(frozen,kUtc)["status"] == "storage_unsafe" && mode(unsafeLeaf) == 0755, "loose leaf repaired/used");
    QTemporaryDir linked; require(linked.isValid(), "linked fixture missing"); const auto linkedLeaf = linked.filePath("Nova reports");
    require(::symlink(QFile::encodeName(leaf).constData(),QFile::encodeName(linkedLeaf).constData()) == 0, "linked fixture failed");
    const auto linkedBefore = tree(leaf);
    require(DeckMomentStore(linked.path()).save(frozen,kUtc)["status"] == "storage_unsafe" && tree(leaf) == linkedBefore, "linked leaf followed");
    QTemporaryDir capped; require(capped.isValid(), "cap fixture missing"); const auto capLeaf = capped.filePath("Nova reports");
    require(QDir().mkpath(capLeaf) && ::chmod(QFile::encodeName(capLeaf).constData(),0700) == 0, "cap fixture directory failed");
    write(QDir(capLeaf).filePath(".nova-moments.lock"),{});
    for (int i = 0; i < 30; ++i) write(QDir(capLeaf).filePath("nova-moment-" + QString::number(i) + ".json"),"keep " + QByteArray::number(i));
    require(::symlink("missing",QFile::encodeName(QDir(capLeaf).filePath("nova-moment-link.json")).constData()) == 0 &&
        ::mkfifo(QFile::encodeName(QDir(capLeaf).filePath("nova-moment-fifo.json")).constData(),0600) == 0, "cap link/FIFO fixture failed");
    const auto before = tree(capLeaf);
    require(DeckMomentStore(capped.path()).save(frozen,kUtc)["status"] == "storage_full" && tree(capLeaf) == before && files(capLeaf).size() == 32,
        "cap followed/excluded nonregular entries or deleted files");
    require(DeckMomentStore(root.filePath("missing")).save(frozen,kUtc)["status"] == "storage_unavailable", "missing Documents not refused");
    std::cout << "storage/no-overwrite/exact frozen readback passed; bytes=" << frozen.size() << ", sha256="
        << QCryptographicHash::hash(bytesOf(path),QCryptographicHash::Sha256).toHex().constData() << '\n';
}
}
int main(int argc,char** argv) {
    QCoreApplication app(argc,argv); snapshotAndPrivacy(); historyAndBounds(); storeBoundaries();
    std::cout << "moment report pure checks passed\n"; return 0;
}
