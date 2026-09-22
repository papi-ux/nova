#include "runtime/deck_library_artwork.h"
#include <QBuffer>
#include <QCoreApplication>
#include <QImage>
#include <cstdlib>
#include <iostream>

using namespace nova::deck;
using namespace nova::deck::runtime;
namespace {
void require(bool value, const char* message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}
std::string png(int width, int height, QColor color = Qt::cyan) {
    QImage image(width, height, QImage::Format_ARGB32);
    image.fill(color);
    QByteArray bytes;
    QBuffer buffer(&bytes);
    buffer.open(QIODevice::WriteOnly);
    require(image.save(&buffer, "PNG"), "PNG fixture encoding failed");
    return bytes.toStdString();
}
QString key(const QVariantList& games, const char* kind = "poster") {
    return games.front().toMap().value(kind).toString().mid(QString("image://library-art/").size());
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    DeckLibraryArtwork provider;
    backend::DeckLiveHostLibrarySnapshot snapshot;
    snapshot.selectedHostId = "pc-a";
    snapshot.library.games.push_back({.id = "game-7", .appId = 7,
        .coverUrl = "https://untrusted.invalid/launch"});
    QVariantList games{QVariantMap{{"id", "game-7"}, {"title", "Test game"}}};
    std::string body = png(20, 30), path;
    bool trusted = true, changePinDuringRead = false, replaceDuringRead = false;
    bool metadataDuringRead = false, revisionDuringRead = false;
    int reads = 0;
    int status = 200;
    DeckNativeTargetResolver resolver = [&](const QString& host, const QString& game)
        -> std::optional<DeckNativeLaunchTarget> {
        require(host == "pc-a" && game == "game-7", "artwork borrowed another PC or game");
        if (!trusted) return {};
        DeckNativeLaunchTarget target;
        target.fetch = [&](const std::string& route) {
            ++reads;
            path = route;
            if (changePinDuringRead) trusted = false;
            if (replaceDuringRead) provider.publish(snapshot, {}, games);
            if (metadataDuringRead || revisionDuringRead) {
                ++snapshot.library.games.front().lastLaunched;
                if (revisionDuringRead) snapshot.library.games.front().artwork.revision += "-changed";
                provider.publish(snapshot, resolver, games);
            }
            return stream::DeckHttpResponse{true, status, body};
        };
        return target;
    };
    auto published = provider.publish(snapshot, resolver, games);
    snapshot.library.games.front().artwork.logoScale = 2.5;
    snapshot.library.games.front().artwork.logoX = 0.25;
    published = provider.publish(snapshot, resolver, games);
    require(published.front().toMap().value("logoTransform").toMap() == QVariantMap{{"scale",2.5},{"x",0.25},{"y",0.5}}, "logo placement missing from public model");
    const auto firstKey = key(published);
    QSize size;
    auto image = provider.requestImage(firstKey, &size, {});
    require(!image.isNull() && size.width() <= 400 && size.height() <= 600, "bounded poster did not decode");
    require(image.pixelColor(0, 0) == QColor(Qt::cyan), "poster pixels changed");
    require(path == "/polaris/v1/games/game-7/cover", "host artwork URL escaped fixed cover route");
    const int beforeInvalid = reads;
    require(provider.requestImage("../../launch", &size, {}).isNull(), "arbitrary path accepted");
    require(provider.requestImage(firstKey + "/extra", &size, {}).isNull(), "invalid opaque key accepted");
    require(reads == beforeInvalid, "invalid image key performed I/O");
    trusted = false;
    require(provider.requestImage(firstKey, &size, {}).isNull() && reads == beforeInvalid,
        "changed pairing performed artwork I/O");
    trusted = true;
    changePinDuringRead = true;
    require(provider.requestImage(firstKey, &size, {}).isNull(), "artwork survived a pin change during I/O");
    changePinDuringRead = false;
    trusted = true;
    status = 403;
    require(provider.requestImage(firstKey, &size, {}).isNull(), "denied artwork rendered");
    status = 200;
    for (const auto& bad : {std::string("<svg xmlns='http://www.w3.org/2000/svg'/>"),
                           std::string(4 * 1024 * 1024 + 1, 'x'), png(5000, 1)}) {
        body = bad;
        require(provider.requestImage(firstKey, &size, {}).isNull(), "unsupported or oversized artwork rendered");
    }
    body = png(20, 30);
    replaceDuringRead = true;
    require(provider.requestImage(firstKey, &size, {}).isNull(), "old-host artwork appeared after replacing library");
    replaceDuringRead = false;
    const int beforeStale = reads;
    require(provider.requestImage(firstKey, &size, {}).isNull() && reads == beforeStale,
        "stale image key performed I/O");
    backend::DeckLiveHostProbe probe;
    probe.hostId = "pc-a";
    probe.standardHost = true;
    snapshot.probes.push_back(probe);
    published = provider.publish(snapshot, resolver, games);
    require(!provider.requestImage(key(published), &size, {}).isNull(), "standard app asset did not decode");
    require(path == "/appasset?appid=7&AssetType=2&AssetIdx=0", "standard app asset request is wrong");
    snapshot.probes.clear();
    auto& artwork = snapshot.library.games.front().artwork;
    artwork.revision = "one";
    artwork.poster = "/polaris/v1/games/game-7/artwork/poster";
    artwork.hero = "/polaris/v1/games/game-7/artwork/hero?revision=one";
    artwork.logo = "/polaris/v1/games/game-7/artwork/logo";
    artwork.icon = "/polaris/v1/games/game-7/artwork/icon";
    published = provider.publish(snapshot, resolver, games);
    const auto original = published;
    body = png(2000, 1125);
    image = provider.requestImage(key(published, "hero"), &size, {});
    require(!image.isNull() && size == QSize(1280, 720), "hero did not use its bounded landscape decode");
    require(path == artwork.hero, "hero route lost revision query");
    body = png(1200, 400, QColor(255, 255, 255, 128));
    image = provider.requestImage(key(published, "logo"), &size, {});
    require(!image.isNull() && size.width() == 640 && size.height() <= 256 && image.pixelColor(0, 0).alpha() == 128,
        "logo decode lost transparency or bounds");
    body = png(512, 512);
    require(!provider.requestImage(key(published, "icon"), &size, {}).isNull() && size == QSize(96, 96), "icon bounds wrong");
    body = png(20, 30);
    require(!provider.requestImage(key(published), &size, {}).isNull() && path == artwork.poster, "manifest poster did not replace legacy cover");
    snapshot.library.games.front().lastLaunched = 1700000000;
    published = provider.publish(snapshot, resolver, games);
    require(key(original, "hero") == key(published, "hero") && key(original) == key(published),
        "metadata-only refresh discarded stable artwork keys");
    metadataDuringRead = true;
    require(!provider.requestImage(key(published, "hero"), &size, {}).isNull(), "metadata refresh discarded unchanged artwork in flight");
    metadataDuringRead = false;
    revisionDuringRead = true;
    require(provider.requestImage(key(published, "hero"), &size, {}).isNull(), "changed revision retained artwork in flight");
    revisionDuringRead = false;
    artwork.revision = "two";
    published = provider.publish(snapshot, resolver, games);
    const int beforeRetired = reads;
    require(key(original, "hero") != key(published, "hero") && provider.requestImage(key(original, "hero"), &size, {}).isNull()
        && reads == beforeRetired, "revision update reused/served retired artwork");
    artwork.hero = "/launch";
    published = provider.publish(snapshot, resolver, games);
    require(!published.front().toMap().contains("hero"), "provider accepted a non-artwork backend route");
    artwork.hero.clear(); artwork.logo.clear(); artwork.icon.clear();
    published = provider.publish(snapshot, resolver, original);
    require(!published.front().toMap().contains("hero") && !published.front().toMap().contains("logo"),
        "removed manifest assets left old QML image URLs");
    snapshot.probes.push_back(probe);
    artwork.hero = "/polaris/v1/games/game-7/artwork/hero";
    published = provider.publish(snapshot, resolver, games);
    require(!published.front().toMap().contains("hero"), "standard host inherited Polaris artwork");
    const auto previews = provider.publishPreviews("pc-a","game-7", {QVariantMap{{"previewPath","/polaris/v1/games/game-7/artwork/candidate/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/poster"}},
        QVariantMap{{"previewPath","https://unpaired.invalid/art.png"}}});
    require(previews[0].toMap().contains("preview") && !previews[1].toMap().contains("preview"), "preview URL allowlist failed");
    const auto previewKey = previews[0].toMap().value("preview").toString().mid(20);
    provider.clearPreviews();
    require(provider.requestImage(previewKey,nullptr,{}).isNull(), "retired Studio preview survived");
    const auto currentPoster = key(published);
    provider.invalidateGame("pc-a","game-7");
    published = provider.publish(snapshot,resolver,games);
    require(key(published) != currentPoster, "confirmed artwork edit retained an old image key");
    snapshot.probes.clear();
    snapshot.library.games.front().appId = polaris::kSpaceAppId;
    snapshot.library.games.front().spaceId = "room";
    snapshot.library.games.front().artwork = {};
    for (const auto* target : {"big-picture-v1", "library-v1", "epic.AlanWake2", "id.42"}) {
        const auto id = QString("space.room.") + target;
        snapshot.library.games.front().id = id.toStdString();
        games = {QVariantMap{{"id", id}}};
        auto spaceResolver = [&](const QString& host, const QString& game) -> std::optional<DeckNativeLaunchTarget> {
            require(host == "pc-a" && game == id, "launcher artwork lost Space identity");
            DeckNativeLaunchTarget launch;
            launch.fetch = [&](const std::string& route) { path = route; ++reads; return stream::DeckHttpResponse{true, 200, body}; };
            return launch;
        };
        published = provider.publish(snapshot, spaceResolver, games);
        const bool launcher = std::string_view(target) == "big-picture-v1" || std::string_view(target) == "library-v1";
        for (const auto* kind : {"poster", "hero", "logo", "icon"}) {
            if (launcher) {
                require(!published.front().toMap().contains(kind), "launcher tile requested game artwork");
            } else {
                require(!provider.requestImage(key(published, kind), &size, {}).isNull(), "launcher title artwork did not decode");
                require(path == "/polaris/v1/games/" + id.toStdString() + "/space-artwork/" + kind, "launcher artwork lost dotted target");
            }
        }
    }
    std::cout << "Artwork passed: pinned resolver, fixed routes, byte/pixel limits, rejected formats, stale pairing and host replacement\n";
}
