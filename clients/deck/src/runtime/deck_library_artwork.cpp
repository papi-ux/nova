#include "runtime/deck_library_artwork.h"

#include <QBuffer>
#include <QImageReader>
#include <QMutexLocker>
#include <QRegularExpression>
#include <QJsonArray>
#include <QJsonDocument>
#include <algorithm>
#include <future>

namespace nova::deck::runtime {

DeckLibraryArtwork::DeckLibraryArtwork()
    : QQuickImageProvider(QQuickImageProvider::Image, QQmlImageProviderBase::ForceAsynchronousImageLoading) {}

QVariantList DeckLibraryArtwork::publish(const backend::DeckLiveHostLibrarySnapshot& snapshot,
    DeckNativeTargetResolver resolver, QVariantList games) {
    QMutexLocker lock(&mutex_);
    resolver_ = std::move(resolver);
    previews_.clear();
    QHash<QByteArray, QString> existing;
    for (auto it = entries_.cbegin(); it != entries_.cend(); ++it) existing.insert(it->identity, it.key());
    QHash<QString, Entry> next;
    bool standard = false;
    for (const auto& probe : snapshot.probes)
        if (probe.hostId == snapshot.selectedHostId) standard = probe.standardHost;
    static const QRegularExpression safeId(QStringLiteral("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$"));
    for (auto& value : games) {
        auto item = value.toMap();
        for (const auto* kind : {"poster", "hero", "logo", "icon"}) item.remove(kind);
        item.remove("logoTransform");
        const auto id = item.value("id").toString();
        const auto game = std::find_if(snapshot.library.games.begin(), snapshot.library.games.end(),
            [&](const auto& candidate) { return candidate.id == id.toStdString(); });
        if (game == snapshot.library.games.end() || !resolver_ || game->appId <= 0) { value = item; continue; }
        item["logoTransform"] = QVariantMap{{"scale", game->artwork.logoScale}, {"x", game->artwork.logoX}, {"y", game->artwork.logoY}};
        std::string path;
        if (standard) path = "/appasset?appid=" + std::to_string(game->appId) + "&AssetType=2&AssetIdx=0";
        else if (const auto space = polaris::spaceGameIdentity(game->id)) {
            if (space->spaceId != game->spaceId || space->target == "big-picture-v1" || space->target == "library-v1") { value = item; continue; }
            path = "/polaris/v1/games/" + game->id + "/space-artwork/poster";
            if (!game->artwork.poster.empty()) path = game->artwork.poster;
        }
        else if (game->id.starts_with("space.") || polaris::isSpaceGame(game->id)) { value = item; continue; }
        else if (safeId.match(id).hasMatch()) path = game->artwork.poster.empty()
            ? "/polaris/v1/games/" + game->id + "/cover" : game->artwork.poster;
        else { value = item; continue; }
        const auto publishAsset = [&](const char* kind, const std::string& route, QSize bounds) {
            if (route.empty()) return;
            if (!standard && !(std::string_view(kind) == "poster" && route == "/polaris/v1/games/" + game->id + "/cover")) {
                const auto expected = QString("/polaris/v1/games/%1/%2/%3").arg(id,
                    polaris::spaceGameIdentity(game->id) ? "space-artwork" : "artwork", kind);
                const QRegularExpression allowed("^" + QRegularExpression::escape(expected) + "(?:\\?revision=[A-Za-z0-9._~-]{1,128})?$");
                if (!allowed.match(QString::fromStdString(route)).hasMatch()) return;
            }
            const auto host = QString::fromStdString(snapshot.selectedHostId);
            const auto identity = QJsonDocument(QJsonArray{host, id, QString::fromLatin1(kind),
                QString::fromStdString(route), QString::fromStdString(game->artwork.key),
                QString::fromStdString(game->artwork.revision)}).toJson(QJsonDocument::Compact);
            auto key = existing.value(identity);
            if (key.isEmpty()) key = QString::number(++nextKey_);
            item.insert(kind, "image://library-art/" + key);
            next.insert(key, {host, id, route, identity, bounds});
        };
        publishAsset("poster", path, {400, 600});
        if (!standard) {
            const auto route = [&](const char* kind, const std::string& cached) {
                return cached.empty() && polaris::spaceGameIdentity(game->id)
                    ? "/polaris/v1/games/" + game->id + "/space-artwork/" + kind : cached;
            };
            publishAsset("hero", route("hero", game->artwork.hero), {1280, 800});
            publishAsset("logo", route("logo", game->artwork.logo), {640, 256});
            publishAsset("icon", route("icon", game->artwork.icon), {96, 96});
        }
        value = item;
    }
    entries_ = std::move(next);
    return games;
}

void DeckLibraryArtwork::invalidateGame(const QString& host, const QString& game) {
    QMutexLocker lock(&mutex_);
    for (auto it = entries_.begin(); it != entries_.end();) {
        if (it->host == host && it->game == game) it = entries_.erase(it); else ++it;
    }
    previews_.clear();
}
void DeckLibraryArtwork::clearPreviews() { QMutexLocker lock(&mutex_); previews_.clear(); }
QVariantList DeckLibraryArtwork::publishPreviews(const QString& host, const QString& game, QVariantList items) {
    QMutexLocker lock(&mutex_);
    if (previews_.size() + items.size() > 128) previews_.clear();
    const QRegularExpression allowed("^/polaris/v1/games/" + QRegularExpression::escape(game) + "/artwork/candidate/[0-9a-f]{32}/(poster|hero|logo|icon)$");
    for (auto& raw : items) {
        auto item = raw.toMap(); const auto path = item.take("previewPath").toString();
        if (allowed.match(path).hasMatch()) {
            const auto key = "s" + QString::number(++nextKey_);
            previews_.insert(key, {host, game, path.toStdString(), {}, {960, 600}});
            item["preview"] = "image://library-art/" + key;
        }
        raw = item;
    }
    return items;
}

QImage DeckLibraryArtwork::requestImage(const QString& id, QSize* size, const QSize&) {
    if (size) *size = {};
    Entry entry;
    DeckNativeTargetResolver resolver;
    {
        QMutexLocker lock(&mutex_);
        if ((!entries_.contains(id) && !previews_.contains(id)) || !resolver_) return {};
        entry = entries_.contains(id) ? entries_.value(id) : previews_.value(id);
        resolver = resolver_;
    }
    // The HTTP adapter runs a bounded event loop. Keep that loop off Qt's
    // pixmap-reader thread: reentering its job queue during a resize/cancel can
    // destroy the image reply that is still executing this request.
    const auto response = std::async(std::launch::async, [resolver, entry] {
        auto target = resolver(entry.host, entry.game);
        if (!target || !target->fetch) return stream::DeckHttpResponse{};
        auto response = target->fetch(entry.path);
        // Recheck pairing after I/O, before exposing returned pixels.
        if (!resolver(entry.host, entry.game)) return stream::DeckHttpResponse{};
        return response;
    }).get();
    if (!response.transportOk || response.status != 200 || response.body.empty() ||
        response.body.size() > 4 * 1024 * 1024) return {};
    QByteArray bytes(response.body.data(), static_cast<qsizetype>(response.body.size()));
    QBuffer buffer(&bytes);
    buffer.open(QIODevice::ReadOnly);
    QImageReader reader(&buffer);
    const auto format = reader.format();
    if (format != "png" && format != "jpeg" && format != "webp") return {};
    const auto dimensions = reader.size();
    if (!dimensions.isValid() || dimensions.width() > 4096 || dimensions.height() > 4096 ||
        qint64(dimensions.width()) * dimensions.height() > 8 * 1024 * 1024) return {};
    if (dimensions.width() > entry.bounds.width() || dimensions.height() > entry.bounds.height())
        reader.setScaledSize(dimensions.scaled(entry.bounds, Qt::KeepAspectRatio));
    auto image = reader.read();
    QMutexLocker lock(&mutex_);
    // A metadata-only refresh may retain this key. A changed revision, removed
    // asset or PC switch retires it permanently, including requests in flight.
    if (!entries_.contains(id) && !previews_.contains(id)) return {};
    if (size) *size = image.size();
    return image;
}

} // namespace nova::deck::runtime
