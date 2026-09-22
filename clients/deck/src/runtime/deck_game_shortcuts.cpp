#include "runtime/deck_game_shortcuts.h"
#include "polaris/deck_spaces.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSaveFile>
#include <QFile>
#include <QDir>
#include <QLockFile>
#include <QStandardPaths>
#include <QBuffer>
#include <unistd.h>

namespace nova::deck::runtime {
namespace {
bool safe(const QString& text, int size) { return !text.isEmpty() && text.size() <= size && !text.contains(QRegularExpression("[\\x00-\\x1f\\x7f]")); }
bool valid(const DeckGameLink& link) {
    if (!safe(link.host, 512) || !safe(link.game, 256) || !safe(link.destination, 128) || link.game == "game-empty-state") return false;
    if (link.destination == "desktop") return !polaris::isSpaceGame(link.game.toStdString()) && !link.game.startsWith("space.");
    const auto space = polaris::spaceGameIdentity(link.game.toStdString());
    return space && QString::fromStdString(space->spaceId) == link.destination;
}
std::optional<DeckVdfObject> readVdf(const std::filesystem::path& file) {
    QFile in(QString::fromStdString(file.string()));
    if (!in.exists()) return DeckVdfObject{};
    if (!in.open(QIODevice::ReadOnly) || in.size() > 16 * 1024 * 1024) return {};
    const auto bytes = in.readAll(); return parseBinaryVdf(std::string_view(bytes.constData(), bytes.size()));
}
}
QString encodeGameLink(const DeckGameLink& link) {
    if (!valid(link)) return {};
    return QString::fromLatin1(QJsonDocument(QJsonObject{{"v",1},{"host",link.host},{"game",link.game},{"destination",link.destination}})
        .toJson(QJsonDocument::Compact).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}
std::optional<DeckGameLink> decodeGameLink(const QString& value) {
    if (value.size() > 2048 || !QRegularExpression("^[A-Za-z0-9_-]+$").match(value).hasMatch()) return {};
    const auto decoded = QByteArray::fromBase64Encoding(value.toLatin1(), QByteArray::Base64UrlEncoding | QByteArray::AbortOnBase64DecodingErrors);
    if (!decoded) return {};
    const auto d = QJsonDocument::fromJson(decoded.decoded); if (!d.isObject()) return {};
    const auto o = d.object(); if (o.size() != 4 || o.value("v") != QJsonValue(1)) return {};
    DeckGameLink link{o.value("host").toString(),o.value("game").toString(),o.value("destination").toString()};
    return valid(link) && encodeGameLink(link) == value ? std::optional{link} : std::nullopt;
}
DeckSteamShortcut steamGameShortcut(const DeckGameLink& link, const QString& title) {
    DeckSteamShortcut entry;
    if (!valid(link) || !safe(title, 300)) return entry;
    entry.appName = title.toStdString(); entry.canonicalId = encodeGameLink(link).toStdString();
    entry.exe = "\"/usr/bin/flatpak\""; entry.startDir = "\"/usr/bin/\"";
    entry.launchOptions = "run com.papi_ux.Nova --standalone --game-link " + entry.canonicalId;
    entry.tags = {"Nova"}; return entry;
}
DeckShortcutWriteResult writeGameShortcut(const std::vector<std::filesystem::path>& files,
    const DeckSteamShortcut& shortcut, const QMap<QString, QImage>& artwork, const std::function<std::optional<bool>()>& steamRunning) {
    DeckShortcutWriteResult result;
    const auto stopped = [&] { const auto running = steamRunning ? steamRunning() : std::nullopt; return running && !*running; };
    if (shortcut.canonicalId.empty() || !decodeGameLink(QString::fromStdString(shortcut.canonicalId))) { result.detail = "This game link is invalid."; return result; }
    if (!stopped()) { result.detail = "Close Steam in Desktop Mode, then choose Retry. Nova will keep this game ready."; return result; }
    if (files.empty()) { result.detail = "Open Steam and sign in once, then close it and retry."; return result; }
    // Prepare every image and inspect every VDF before writing. Library URLs,
    // game titles and host IDs never become filesystem names or shell text.
    QMap<QString, QByteArray> images;
    for (const auto* kind : {"poster", "hero", "logo", "icon"}) {
        if (!artwork.contains(kind) || artwork.value(kind).isNull()) continue;
        QByteArray png; QBuffer buffer(&png); buffer.open(QIODevice::WriteOnly);
        if (!artwork.value(kind).save(&buffer, "PNG")) { result.detail = "Couldn't prepare the selected artwork. Retry."; return result; }
        images[kind] = png;
    }
    struct Pending { std::filesystem::path file; QByteArray before, after; std::uint32_t appId; };
    std::vector<Pending> pending;
    for (const auto& file : files) {
        auto document = readVdf(file); if (!document) { result.detail = "A Steam shortcuts file could not be read. Nothing was changed."; return result; }
        const auto registration = registerShortcut(*document, shortcut);
        QFile old(QString::fromStdString(file.string())); old.open(QIODevice::ReadOnly);
        pending.push_back({file, old.readAll(), QByteArray::fromStdString(serializeBinaryVdf(registration.document)), registration.appId});
    }
    for (const auto& item : pending) {
        if (!stopped()) { result.detail = "Steam opened before the update finished. Close it and retry to finish all profiles."; return result; }
        const auto directory = QString::fromStdString(item.file.parent_path().string());
        if (!QDir().mkpath(directory + "/grid")) { result.detail = "Couldn't create Steam's artwork folder."; return result; }
        QLockFile lock(directory + "/nova-shortcuts.lock");
        if (!lock.tryLock(0)) { result.detail = "Another Nova shortcut update is running. Retry shortly."; return result; }
        QFile current(QString::fromStdString(item.file.string())); current.open(QIODevice::ReadOnly);
        if (current.readAll() != item.before) { result.detail = "Steam shortcuts changed during this update. Retry to use the latest file."; return result; }
        const auto write = [](const QString& path, const QByteArray& bytes) { QSaveFile out(path); return out.open(QIODevice::WriteOnly) && out.write(bytes) == bytes.size() && out.commit(); };
        for (auto it = images.cbegin(); it != images.cend(); ++it) {
            const auto suffix = it.key() == "poster" ? "p" : it.key() == "hero" ? "_hero" : it.key() == "logo" ? "_logo" : "_icon";
            if (!write(directory + "/grid/" + QString::number(item.appId) + suffix + ".png", it.value())) { result.detail = "Couldn't save Steam artwork. Retry to finish the shortcut."; return result; }
        }
        // Add the icon using the profile's own grid path. appid is already stable.
        auto document = parseBinaryVdf(std::string_view(item.after.constData(), item.after.size()));
        auto withIcon = shortcut;
        if (images.contains("icon")) withIcon.icon = (directory + "/grid/" + QString::number(item.appId) + "_icon.png").toStdString();
        const auto final = registerShortcut(*document, withIcon);
        if (!stopped() || !write(QString::fromStdString(item.file.string()), QByteArray::fromStdString(serializeBinaryVdf(final.document)))) {
            result.detail = "The shortcut update did not finish. Close Steam and retry."; return result;
        }
        result.written.push_back(item.file); result.appId = item.appId;
    }
    result.ok = true; result.detail = "Added to Steam with the available artwork. Open Steam to play."; return result;
}
struct DeckGameShortcuts::Job { DeckShortcutWriteResult result; std::atomic<bool> cancelled{false}; };
DeckGameShortcuts::DeckGameShortcuts(QObject* parent) : QObject(parent) { timer_.setInterval(20); connect(&timer_, &QTimer::timeout, this, &DeckGameShortcuts::poll); }
DeckGameShortcuts::~DeckGameShortcuts() { shutdown(); }
void DeckGameShortcuts::shutdown() { ++*generation_; timer_.stop(); if (worker_) { worker_->wait(); delete worker_; worker_ = nullptr; } reader_ = {}; }
QVariantMap DeckGameShortcuts::state() const { return {{"busy", worker_ != nullptr}, {"ok", ok_}, {"retry", retry_}, {"copy", copy_}}; }
void DeckGameShortcuts::setCatalog(QString host, QString destination, QVariantList games) { ++*generation_; host_ = std::move(host); destination_ = std::move(destination); games_ = std::move(games); }
bool DeckGameShortcuts::add(const QString& game) {
    if (worker_) return false;
    QVariantMap selected;
    for (const auto& raw : games_) if (raw.toMap().value("id") == game) selected = raw.toMap();
    const auto shortcut = steamGameShortcut({host_, game, destination_}, selected.value("title").toString());
    if (shortcut.canonicalId.empty()) { copy_ = "Refresh this game before adding it to Steam."; emit stateChanged(); return false; }
    auto job = std::make_shared<Job>(); job_ = job; ok_ = false; retry_ = false; copy_ = "Preparing the Steam entry…";
    worker_ = QThread::create([job, selected, shortcut, reader = reader_, generation = generation_, accepted = generation_->load()] {
        try {
            const auto running = [generation, accepted]() -> std::optional<bool> {
                if (generation->load() != accepted) return {};
                return steamClientRunningForAccount(QFile::exists("/.flatpak-info"), getuid());
            };
            const auto initial = running();
            if (!initial || *initial) { job->result.detail = "Close Steam in Desktop Mode, then choose Retry. Nova will keep this game ready."; return; }
            std::vector<std::filesystem::path> files;
            for (const auto& root : defaultSteamRoots()) { files = defaultShortcutFiles(root); if (!files.empty()) break; }
            QMap<QString, QImage> artwork;
            for (const auto* kind : {"poster", "hero", "logo", "icon"}) {
                const auto url = selected.value(kind).toString();
                if (reader && url.startsWith("image://library-art/")) {
                    artwork[kind] = reader(url.mid(20));
                    if (artwork[kind].isNull()) { job->result.detail = "Couldn't load this game's artwork. Refresh the library and retry."; return; }
                }
            }
            job->result = writeGameShortcut(files, shortcut, artwork, running);
        } catch (...) { job->result.detail = "Couldn't finish the Steam entry. Retry when Steam is closed."; }
    });
    worker_->start(); timer_.start(); emit stateChanged(); return true;
}
void DeckGameShortcuts::poll() {
    if (!worker_ || !worker_->isFinished()) return;
    worker_->wait(); delete worker_; worker_ = nullptr; timer_.stop();
    copy_ = QString::fromStdString(job_->result.detail); ok_ = job_->result.ok; retry_ = !ok_; job_.reset(); emit stateChanged();
}
}
