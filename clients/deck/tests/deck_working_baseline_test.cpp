#include "runtime/deck_support_report.h"
#include "runtime/deck_working_baseline.h"
#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLockFile>
#include <QTemporaryDir>
#include <cstdlib>
#include <iostream>
#include <sys/stat.h>
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
const QString kSecret = "private-PC 192.168.22.45 token=private-token /home/private";
QVariantMap freshHud() {
    return {{"fresh", true}, {"hostFresh", true}, {"appliedBitrate", "20.0M"}, {"requestedBitrate", "150.0M"},
        {"fps", "59.8"}, {"incoming", "60.0"}, {"decoded", "59.9"}, {"submitted", "59.7"}, {"cpuUploadCompositions", "12.0"},
        {"videoWork", "1.25ms"}, {"host", "4.2ms"}, {"rtt", "17ms"}, {"jitter", "3ms"}, {"bitrate", "19.6M"},
        {"refused", "2"}, {"deliveryDrops", "4"}, {"deliveryQueueDepth", "1"}, {"requestedFps", "60"},
        {"codec", "HEVC"}, {"decoderBackend", "VA-API"}, {"frameTransferPath", "DMA-BUF"}, {"resolution", "1280×800"},
        {"mediaLoss", "0.5%"}, {"mediaLossFresh", true}, {"mediaLossSource", "Video frame sequence"},
        {"hostName", kSecret}, {"doctor", QVariantMap{{"available", true}, {"title", kSecret}}}};
}
const DeckWorkingBaselineRequest kRequest{1280, 800, 60, 20000, "hevc", "quality", "host_virtual_display"};
const QDateTime kNow = QDateTime::fromString("2026-10-10T12:00:00.000Z", Qt::ISODateWithMs);
QString scopeFor(int index) { return workingBaselineScope(QByteArray("host-der-") + QByteArray::number(index), "client-der", "app-uuid"); }
QJsonObject record(const QString& scope, const QDateTime& marked = kNow, std::optional<DeckWorkingBaselineRequest> request = kRequest) {
    const auto value = workingBaselineRecord(scope, deckSupportReport(freshHud()), request, marked, 400);
    require(value.has_value(), "valid baseline record was refused");
    return *value;
}
// Names, sizes, permissions and modification times: proves a read changed nothing.
QStringList tree(const QString& root) {
    QStringList out;
    QDirIterator it(root, QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        it.next(); const auto info = it.fileInfo();
        out << info.filePath() + "|" + QString::number(info.size()) + "|" + QString::number(int(info.permissions())) + "|" +
            QString::number(info.lastModified().toMSecsSinceEpoch()) + "|" + (info.isSymLink() ? "link" : "");
    }
    out.sort();
    return out;
}
int mode(const QString& path) { struct stat st {}; return ::lstat(QFile::encodeName(path).constData(), &st) == 0 ? int(st.st_mode & 07777) : -1; }
QByteArray bytesOf(const QString& path) { QFile f(path); return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray{}; }
void write(const QString& path, const QByteArray& bytes, QFileDevice::Permissions permissions = QFileDevice::ReadOwner | QFileDevice::WriteOwner) {
    QFile f(path); require(f.open(QIODevice::WriteOnly) && f.write(bytes) == bytes.size(), "fixture write failed"); f.close();
    require(QFile::setPermissions(path, permissions), "fixture chmod failed");
}
QString privateDirectory(const QTemporaryDir& root, const QString& name = "working-baselines") {
    const auto path = root.filePath(name);
    require(QDir().mkpath(path) && QFile::setPermissions(path, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner), "fixture directory failed");
    return path;
}

void testScope() {
    const auto base = workingBaselineScope("host", "client", "app");
    require(validWorkingBaselineScope(base), "scope is not 64 lowercase hex");
    require(base == workingBaselineScope("host", "client", "app"), "scope is not deterministic");
    require(base != workingBaselineScope("host2", "client", "app") && base != workingBaselineScope("host", "client2", "app") &&
        base != workingBaselineScope("host", "client", "app2"), "a changed identity kept the same scope");
    require(workingBaselineScope({}, "client", "app").isEmpty() && workingBaselineScope("host", {}, "app").isEmpty() &&
        workingBaselineScope("host", "client", {}).isEmpty(), "empty certificate or app identity produced a fallback scope");
    require(!base.contains("host") && !base.contains("app"), "scope exposed an identity");
}

void testFreshness() {
    require(workingBaselineSampleFresh(true, 1000, 3500), "fresh sample at the bound refused");
    require(!workingBaselineSampleFresh(true, 1000, 3501), "sample older than 2.5 s accepted although the HUD flag was still set");
    require(!workingBaselineSampleFresh(false, 1000, 1001), "HUD reset accepted as fresh");
    require(!workingBaselineSampleFresh(true, -1, 10) && !workingBaselineSampleFresh(true, 50, 40), "missing or future receipt accepted");
}

void testRecordSelection() {
    const auto scope = scopeFor(0);
    const auto value = record(scope);
    const auto bytes = serializeWorkingBaseline(value);
    require(bytes.size() <= kWorkingBaselineMaxBytes, "record exceeds 2 KiB");
    for (const auto* leaked : {"private", "192.168", "refused", "dropped", "cpu_upload", "doctor", "encoder_applied", "requested_mbps", "token"})
        require(!bytes.contains(leaked), "cumulative, host-only, Doctor or identity data was stored");
    const auto readings = value["readings"].toObject(), observed = value["observed"].toObject(), requested = value["requested"].toObject();
    require(readings["composed_fps"] == 59.8 && readings["incoming_fps"] == 60 && readings["submitted_fps"] == 59.7 &&
        readings["decoder_callback_ms"] == 1.25 && readings["host_processing_ms"] == 4.2 && readings["video_payload_mbps"] == 19.6 &&
        readings["client_media_loss_percent"] == 0.5 && readings["delivery_queue_depth"] == 1 && readings["rtt_ms"] == 17 &&
        readings["rtt_variation_ms"] == 3, "selected readings differ from deckSupportReport");
    require(observed["codec"] == "HEVC" && observed["decoder_backend"] == "VA-API" && observed["frame_transfer_path"] == "DMA-BUF" &&
        observed["width"] == 1280 && observed["height"] == 800 && observed["target_fps"] == 60, "observed context differs");
    require(requested["width"] == 1280 && requested["video_codec"] == "hevc" && requested["launch_mode"] == "host_virtual_display",
        "original request was not kept");
    require(value["marked_at_utc"] == "2026-10-10T12:00:00.000Z" && value["sample_received_utc"] == "2026-10-10T11:59:59.600Z",
        "marked or sample receipt time is wrong");
    // No configuration means unknown, never the saved defaults.
    const auto unknown = record(scope, kNow, std::nullopt)["requested"].toObject();
    for (const auto& key : unknown.keys()) require(unknown[key].isNull(), "missing request was filled from defaults");
    // A stale HUD makes the report unavailable, and the builder refuses it outright.
    auto stale = freshHud(); stale["fresh"] = false; stale["mediaLossFresh"] = false;
    require(!workingBaselineRecord(scope, deckSupportReport(stale), kRequest, kNow, 0), "stale readings were stored");
    // Closed request words and bounds.
    for (auto bad : {DeckWorkingBaselineRequest{1281, 800, 60, 20000, "hevc", "auto", "default"},
                     DeckWorkingBaselineRequest{1280, 800, 14, 20000, "hevc", "auto", "default"},
                     DeckWorkingBaselineRequest{1280, 800, 60, 999, "hevc", "auto", "default"},
                     DeckWorkingBaselineRequest{1280, 800, 60, 20000, "av1", "auto", "default"},
                     DeckWorkingBaselineRequest{1280, 800, 60, 20000, "hevc", "fast", "default"},
                     DeckWorkingBaselineRequest{1280, 800, 60, 20000, "hevc", "auto", "headless"},
                     DeckWorkingBaselineRequest{1280, 800, 60, 20000, "hevc", "auto", "headless_dongle"}})
        require(!workingBaselineRecord(scope, deckSupportReport(freshHud()), bad, kNow, 0), "out-of-set request stored");
    require(!workingBaselineRecord(scope, deckSupportReport(freshHud()), kRequest, kNow, 2501) &&
        !workingBaselineRecord("not-a-scope", deckSupportReport(freshHud()), kRequest, kNow, 0), "stale age or bad scope stored");
}

void testValidation() {
    const auto scope = scopeFor(0);
    const auto good = record(scope);
    require(parseWorkingBaseline(serializeWorkingBaseline(good), scope, kNow) == good, "valid record did not round-trip");
    require(!parseWorkingBaseline(serializeWorkingBaseline(good), scopeFor(1), kNow), "record accepted under another scope");
    const auto mutate = [&](auto change) { auto copy = good; change(copy); return parseWorkingBaseline(serializeWorkingBaseline(copy), scope, kNow).has_value(); };
    require(!mutate([](QJsonObject& r) { r["extra"] = 1; }), "extra top-level key accepted");
    require(!mutate([](QJsonObject& r) { r.remove("observed"); }), "missing key accepted");
    require(!mutate([](QJsonObject& r) { r["kind"] = "nova-deck-working-baseline-v2"; }), "wrong kind accepted");
    require(!mutate([](QJsonObject& r) { r["basis"] = "connected"; }), "non-user basis accepted");
    require(!mutate([](QJsonObject& r) { r["marked_at_utc"] = "2026-10-10T12:06:00.000Z"; r["sample_received_utc"] = "2026-10-10T12:05:59.000Z"; }), "future mark accepted");
    require(!mutate([](QJsonObject& r) { r["sample_received_utc"] = "2026-10-10T11:59:57.000Z"; }), "sample older than 2.5 s before mark accepted");
    require(!mutate([](QJsonObject& r) { r["sample_received_utc"] = "2026-10-10T12:00:00.500Z"; }), "sample after mark accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["observed"].toObject(); o["codec"] = "VP9"; r["observed"] = o; }), "unknown codec accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["readings"].toObject(); o["client_media_loss_percent"] = 101; r["readings"] = o; }), "loss over 100% accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["readings"].toObject(); o["delivery_queue_depth"] = 1.5; r["readings"] = o; }), "fractional depth accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["readings"].toObject(); o["rtt_ms"] = "17"; r["readings"] = o; }), "string reading accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["requested"].toObject(); o["fps"] = QJsonValue::Null; r["requested"] = o; }), "partly unknown request accepted");
    require(!mutate([](QJsonObject& r) { auto o = r["client"].toObject(); o["version"] = kSecret; r["client"] = o; }), "free-form version accepted");
    require(!parseWorkingBaseline(QByteArray(2049, ' '), scope, kNow) && !parseWorkingBaseline("[]", scope, kNow), "oversize or non-object accepted");
}

void testCompare() {
    const auto scope = scopeFor(0);
    const auto baseline = record(scope);
    const auto noCurrent = compareWorkingBaselines(baseline, std::nullopt);
    require(noCurrent["currentFresh"] == false && noCurrent["contextState"] == "unknown", "stale current sample was compared");
    for (const auto& row : noCurrent["readings"].toList()) require(row.toMap()["now"] == "unknown" && row.toMap()["state"] == "unknown", "stale current reading shown");
    require(noCurrent["readings"].toList().first().toMap()["baseline"] == "60 fps", "saved reading hidden when current is stale");
    auto changedHud = freshHud(); changedHud["codec"] = "H.264";
    const auto changed = workingBaselineRecord(scope, deckSupportReport(changedHud), kRequest, kNow.addSecs(60), 0);
    const auto result = compareWorkingBaselines(baseline, changed);
    require(result["contextState"] == "different", "changed codec not marked different");
    const auto same = compareWorkingBaselines(baseline, record(scope, kNow.addSecs(60)));
    // Every known context field matches; the local-preview version stays unknown, never "same".
    require(same["differentFields"].toStringList().isEmpty() && same["unknownFields"].toStringList() == QStringList{"Nova version"},
        "identical known context not marked same, or the version sentinel was matched");
    const auto unknownRequest = compareWorkingBaselines(baseline, record(scope, kNow.addSecs(60), std::nullopt));
    require(unknownRequest["contextState"] == "unknown", "unknown request treated as matching");
    const auto text = QJsonDocument::fromVariant(result).toJson().toLower();
    for (const auto* verdict : {"regress", "better", "worse", "healthy", "score", "pass", "fail"})
        require(!text.contains(verdict), "comparison contains a verdict");
}

QString indexPath(const QString& directory) { return directory + "/index.json"; }
QJsonObject indexEntries(const QString& directory) {
    return QJsonDocument::fromJson(bytesOf(indexPath(directory))).object().value("entries").toObject();
}
QByteArray envelope(const QJsonObject& entries) {
    return QJsonDocument(QJsonObject{{"kind", "nova-deck-working-baseline-index-v1"}, {"entries", entries}}).toJson(QJsonDocument::Compact);
}

void testBuilderContract() {
    const auto scope = scopeFor(0);
    // The builder requires client_readings.available to be the JSON bool true.
    auto report = deckSupportReport(freshHud());
    require(report["client_readings"].toObject()["available"] == true, "fixture report not available");
    for (const QJsonValue& bad : {QJsonValue(false), QJsonValue("true"), QJsonValue(1), QJsonValue(QJsonValue::Null), QJsonValue(QJsonValue::Undefined)}) {
        auto changed = report; auto client = changed["client_readings"].toObject();
        if (bad.isUndefined()) client.remove("available"); else client["available"] = bad;
        changed["client_readings"] = client;
        require(!workingBaselineRecord(scope, changed, kRequest, kNow, 0), "builder accepted unavailable client readings");
    }
    // A real producer number with no exact three-decimal double survives the round trip.
    auto large = freshHud(); large["bitrate"] = "134698096.372M";
    const auto value = workingBaselineRecord(scope, deckSupportReport(large), kRequest, kNow, 0);
    require(value && (*value)["readings"].toObject()["video_payload_mbps"] == 134698096.372, "valid producer number rejected");
    const auto parsed = parseWorkingBaseline(serializeWorkingBaseline(*value), scope, kNow);
    require(parsed && (*parsed)["readings"].toObject()["video_payload_mbps"].toDouble() == 134698096.372, "valid producer number lost in serialize/parse");
    QTemporaryDir root; DeckWorkingBaselineStore store(root.filePath("working-baselines"));
    require(store.save(scope, *value, kNow) == "saved" && store.load(scope, kNow).baseline == value, "valid producer number lost in storage");
}

void testCompareSentinels() {
    const auto scope = scopeFor(0);
    auto hud = freshHud(); hud["codec"] = "unknown-codec"; hud["decoderBackend"] = "--";
    const auto before = workingBaselineRecord(scope, deckSupportReport(hud), DeckWorkingBaselineRequest{1280, 800, 60, 20000, "auto", "auto", "default"}, kNow, 0);
    const auto after = workingBaselineRecord(scope, deckSupportReport(hud), DeckWorkingBaselineRequest{1280, 800, 60, 20000, "auto", "auto", "default"}, kNow.addSecs(5), 0);
    require(before && after && (*before)["observed"].toObject()["codec"] == "unavailable" && (*before)["client"].toObject()["version"] == "local-preview",
        "fixture did not produce sentinels");
    const auto result = compareWorkingBaselines(*before, after);
    QMap<QString, QString> states;
    for (const auto& row : result["context"].toList()) states[row.toMap()["key"].toString()] = row.toMap()["state"].toString();
    require(states["observed.codec"] == "unknown" && states["observed.decoder_backend"] == "unknown" && states["client.version"] == "unknown",
        "equal sentinels were treated as matching");
    require(states["requested.video_codec"] == "same" && states["requested.profile_preference"] == "same", "explicit auto was treated as unknown");
    require(result["unknownFields"].toStringList().contains("Video codec") && result["unknownFields"].toStringList().contains("Nova version") &&
        result["differentFields"].toStringList().isEmpty(), "unknown fields not listed by name");
    auto zero = freshHud(); zero["mediaLoss"] = "0.0%"; zero["deliveryQueueDepth"] = "0";
    const auto zeroRecord = workingBaselineRecord(scope, deckSupportReport(zero), kRequest, kNow, 0);
    const auto zeroCompare = compareWorkingBaselines(*zeroRecord, zeroRecord);
    for (const auto& row : zeroCompare["readings"].toList())
        if (row.toMap()["key"] == "client_media_loss_percent" || row.toMap()["key"] == "delivery_queue_depth")
            require(row.toMap()["state"] == "reading" && row.toMap()["now"] != "unknown", "measured zero treated as unknown");
}

void testReadsAreReadOnly() {
    QTemporaryDir root;
    const auto missing = root.filePath("app/working-baselines");
    DeckWorkingBaselineStore store(missing);
    const auto before = tree(root.path());
    require(store.load(scopeFor(0), kNow).status == "no_baseline", "missing directory not reported as no baseline");
    require(tree(root.path()) == before && !QFileInfo::exists(missing), "a read created storage");
    const auto directory = privateDirectory(root);
    DeckWorkingBaselineStore present(directory);
    const auto empty = tree(root.path());
    require(present.load(scopeFor(0), kNow).status == "no_baseline" && tree(root.path()) == empty, "missing index misreported or created");
    // A readable envelope with one good, one malformed and one foreign-scope record.
    QJsonObject entries;
    entries[scopeFor(1)] = record(scopeFor(1));
    entries[scopeFor(2)] = QJsonObject{{"kind", "broken"}};
    entries[scopeFor(3)] = record(scopeFor(4)); // record for another scope under this key
    write(indexPath(directory), envelope(entries));
    const auto snapshot = tree(root.path());
    require(present.load(scopeFor(1), kNow).status == "ready", "valid entry beside malformed ones unreadable");
    require(present.load(scopeFor(2), kNow).status == "baseline_unreadable" && present.load(scopeFor(3), kNow).status == "baseline_unreadable",
        "malformed or mismatched entry was read");
    require(present.load(scopeFor(6), kNow).status == "no_baseline", "absent entry misreported");
    require(tree(root.path()) == snapshot, "a read changed, created or removed an index, lock or key");
    // Global envelope faults make every read unavailable without touching the bytes.
    for (const QByteArray& bad : {QByteArray("{corrupt"), QByteArray("[]"), envelope(entries).replace("index-v1", "index-v2"),
            QByteArray(kWorkingBaselineIndexMaxBytes + 1, ' ')}) {
        write(indexPath(directory), bad);
        require(present.load(scopeFor(1), kNow).status == "index_unreadable" && bytesOf(indexPath(directory)) == bad, "unreadable index was trusted or changed");
    }
    write(indexPath(directory), envelope(entries), QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ReadOther);
    require(present.load(scopeFor(1), kNow).status == "storage_unsafe", "loose index file was read");
    QFile::remove(indexPath(directory));
    write(root.filePath("elsewhere.json"), envelope(entries));
    require(QFile::link(root.filePath("elsewhere.json"), indexPath(directory)), "link fixture failed");
    require(present.load(scopeFor(1), kNow).status == "storage_unsafe", "linked index was followed");
    QFile::remove(indexPath(directory));
    require(QFile::setPermissions(directory, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner | QFileDevice::ReadGroup | QFileDevice::ExeGroup), "chmod fixture failed");
    require(present.load(scopeFor(1), kNow).status == "storage_unsafe", "loose directory was trusted");
    const auto loose = tree(root.path());
    require(present.save(scopeFor(7), record(scopeFor(7)), kNow) == "storage_unsafe" && tree(root.path()) == loose,
        "loose directory permissions were changed or written into");
    QTemporaryDir linkRoot;
    const auto real = privateDirectory(linkRoot, "real");
    require(QFile::link(real, linkRoot.filePath("working-baselines")), "directory link fixture failed");
    DeckWorkingBaselineStore linked(linkRoot.filePath("working-baselines"));
    require(linked.load(scopeFor(0), kNow).status == "storage_unsafe" &&
        linked.save(scopeFor(0), record(scopeFor(0)), kNow) == "storage_unsafe" && QDir(real).isEmpty(), "linked directory was followed");
}

void testSaveReplaceClear() {
    QTemporaryDir root;
    const auto directory = root.filePath("app/working-baselines");
    DeckWorkingBaselineStore store(directory);
    const auto scope = scopeFor(0);
    require(store.save(scope, record(scope), kNow) == "saved", "first save failed");
    require(mode(directory) == 0700 && mode(indexPath(directory)) == 0600, "storage is not owner-only");
    require(store.load(scope, kNow).baseline == record(scope) && indexEntries(directory).value(scope).toObject() == record(scope), "saved baseline did not read back");
    require(QDir(directory).entryList(QDir::Files | QDir::Hidden).size() == 1, "save left extra files (lock or temp)");
    const auto first = bytesOf(indexPath(directory));
    // Every refused save keeps the previous index bytes.
    auto broken = record(scope); broken["basis"] = "connected";
    require(store.save(scope, broken, kNow) == "invalid_snapshot" && bytesOf(indexPath(directory)) == first, "invalid replacement touched the index");
    {
        QLockFile other(directory + "/.working-baselines.lock"); other.setStaleLockTime(0);
        require(other.tryLock(0), "lock fixture failed");
        require(store.save(scope, record(scope, kNow.addSecs(10)), kNow.addSecs(10)) == "storage_busy" && bytesOf(indexPath(directory)) == first,
            "save ignored another holder's lock");
        require(store.clear(scope) == "storage_busy" && bytesOf(indexPath(directory)) == first, "clear ignored another holder's lock");
    }
    require(store.save(scope, record(scope, kNow.addSecs(10)), kNow.addSecs(10)) == "saved" && bytesOf(indexPath(directory)) != first, "replace failed");
    require(indexEntries(directory).size() == 1, "replace duplicated the scope");
    require(!QFileInfo::exists(directory + "/.working-baselines.lock"), "maintenance lock outlived the operation");
    // An unreadable global envelope is refused by Save and Clear, never overwritten.
    const QByteArray corrupt("{corrupt");
    write(indexPath(directory), corrupt);
    require(store.save(scopeFor(1), record(scopeFor(1)), kNow) == "index_unreadable" && store.clear(scope) == "index_unreadable" &&
        bytesOf(indexPath(directory)) == corrupt, "unreadable index was replaced");
    // A malformed individual record: Clear is its explicit maintenance; other scopes survive.
    QJsonObject entries; entries[scope] = QJsonObject{{"kind", "broken"}}; entries[scopeFor(1)] = record(scopeFor(1));
    write(indexPath(directory), envelope(entries));
    require(store.clear(scope) == "cleared" && !indexEntries(directory).contains(scope) && indexEntries(directory).contains(scopeFor(1)),
        "clear of a malformed entry failed or removed another scope");
    require(store.clear(scope) == "no_baseline", "clear of an absent entry misreported");
    // A directory without owner write is refused before any lock or commit, and the index
    // is unchanged. (A forced QSaveFile commit failure is not inducible without fault injection.)
    const auto stable = bytesOf(indexPath(directory));
    require(QFile::setPermissions(directory, QFileDevice::ReadOwner | QFileDevice::ExeOwner), "chmod fixture failed");
    const auto blocked = store.save(scopeFor(2), record(scopeFor(2)), kNow);
    require(QFile::setPermissions(directory, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner), "chmod restore failed");
    require(blocked == "storage_unsafe" && bytesOf(indexPath(directory)) == stable, "refused save changed the index");
}

void testCapAndRetention() {
    QTemporaryDir root;
    const auto directory = privateDirectory(root);
    DeckWorkingBaselineStore store(directory);
    for (int index = 0; index < 12; ++index)
        require(store.save(scopeFor(index), record(scopeFor(index), kNow.addSecs(index)), kNow.addSecs(index)) == "saved", "fill failed");
    write(directory + "/notes.txt", "user file");
    // A new scope retains 12: exactly the oldest valid mark leaves, in the same commit.
    require(store.save(scopeFor(12), record(scopeFor(12), kNow.addSecs(20)), kNow.addSecs(20)) == "saved", "13th scope refused");
    auto entries = indexEntries(directory);
    require(entries.size() == 12 && !entries.contains(scopeFor(0)) && entries.contains(scopeFor(1)) && entries.contains(scopeFor(12)) &&
        bytesOf(directory + "/notes.txt") == "user file", "retention removed the wrong entry or an unknown file");
    // Replacing an existing scope at the cap removes nothing.
    require(store.save(scopeFor(5), record(scopeFor(5), kNow.addSecs(30)), kNow.addSecs(30)) == "saved" && indexEntries(directory).size() == 12,
        "replace changed the entry count");
    // A malformed record of another scope leaves before any valid one.
    entries = indexEntries(directory);
    entries[scopeFor(7)] = QJsonObject{{"kind", "broken"}};
    write(indexPath(directory), envelope(entries));
    require(store.save(scopeFor(13), record(scopeFor(13), kNow.addSecs(40)), kNow.addSecs(40)) == "saved", "save beside malformed entry failed");
    entries = indexEntries(directory);
    require(entries.size() == 12 && !entries.contains(scopeFor(7)) && entries.contains(scopeFor(1)), "malformed entry was not retired first");
    require(QJsonDocument::fromJson(bytesOf(indexPath(directory))).isObject() && bytesOf(indexPath(directory)).size() <= kWorkingBaselineIndexMaxBytes,
        "index exceeded its total bound");
    // An envelope over 12 entries is unreadable and refused rather than trimmed.
    entries[scopeFor(20)] = record(scopeFor(20));
    const auto crowded = envelope(entries);
    write(indexPath(directory), crowded);
    require(store.load(scopeFor(1), kNow).status == "index_unreadable" &&
        store.save(scopeFor(21), record(scopeFor(21)), kNow) == "index_unreadable" && bytesOf(indexPath(directory)) == crowded,
        "over-full index was trusted or rewritten");
}
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    require(kNow.isValid(), "fixture time invalid");
    testScope();
    testFreshness();
    testRecordSelection();
    testValidation();
    testCompare();
    testBuilderContract();
    testCompareSentinels();
    testReadsAreReadOnly();
    testSaveReplaceClear();
    testCapAndRetention();
    std::cout << "working baseline pure/store checks passed\n";
    return 0;
}
