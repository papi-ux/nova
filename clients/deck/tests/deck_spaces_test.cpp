#include "runtime/deck_library_controller.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <cstdlib>
#include <iostream>
#include <mutex>
using namespace nova::deck;
using namespace nova::deck::polaris;
using namespace nova::deck::runtime;
namespace {
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
std::string json(const QJsonObject& o) { return QJsonDocument(o).toJson(QJsonDocument::Compact).toStdString(); }
QJsonObject spacesBody(const std::string& selected = "desktop") {
    return {{"schema", 1}, {"status", true}, {"enabled", true}, {"available", true}, {"can_switch", true},
        {"desktop_allowed", true}, {"selected_space_id", QString::fromStdString(selected)},
        {"spaces", QJsonArray{QJsonObject{{"id", "Arcade_1"}, {"name", "Arcade"}, {"state", "ready"},
            {"selected", selected == "Arcade_1"}, {"library_enabled", true}, {"can_open", true}}}}};
}
void parsing() {
    auto good = spacesBody();
    const auto desktop = parseSpaces(json(good));
    require(desktop && desktop->permitsPlay("desktop") && desktop->permitsSelection("Arcade_1"), "valid Desktop/Space choice rejected");
    auto space = parseSpaces(json(spacesBody("Arcade_1")));
    require(space && space->selected() && space->permitsPlay("Arcade_1") && !space->permitsPlay("desktop"), "Space play authority mismatch");
    auto unavailable = good; unavailable["available"] = false;
    require(!parseSpaces(json(unavailable)), "contradictory available/selection accepted");
    for (const auto* field : {"schema", "status", "enabled", "available", "can_switch", "desktop_allowed", "selected_space_id", "spaces"}) {
        auto bad = good; bad[field] = QJsonValue::Null;
        require(!parseSpaces(json(bad)), "wrong required type accepted");
    }
    auto denied = good; denied["desktop_allowed"] = false;
    require(!parseSpaces(json(denied)), "selected Desktop without access accepted");
    auto duplicate = good; auto rows = good["spaces"].toArray(); rows.append(rows.first()); duplicate["spaces"] = rows;
    require(!parseSpaces(json(duplicate)), "duplicate Space identities accepted");
    const auto prefix = json(good);
    require(!parseSpaces(prefix.substr(0, prefix.size()-1) + ",\"desktop_allowed\":false}"), "duplicate root key accepted");
    require(!uniqueJsonFields(R"({"name":1,"\u006eame":2})"), "escaped duplicate keys accepted");
    require(!uniqueJsonFields(R"({"a":[[[[[0]]]]]})", 4), "deep Space document accepted");
    for (const auto* id : {"space.Arcade_1.7", "space.Arcade_1.4294967295", "space.Arcade_1.big-picture-v1"})
        require(spaceGameIdentity(id).has_value(), "valid Space target rejected");
    for (const auto* id : {"space../7", "space.Arcade_1.0", "space.Arcade_1.01", "space.Arcade_1.4294967296", "space.Arcade_1.7.extra", "space.desktop.7"})
        require(!spaceGameIdentity(id), "invalid Space target accepted");
    require(isSpaceGame("space.fixture") && isSpaceGame(kLegacySpaceAppUuid) && isSpaceGame("1347244801"),
        "old Space identity lost ordinary-session restrictions");
    QJsonObject game{{"id", "space.Arcade_1.7"}, {"app_id", kSpaceAppId}, {"name", "Fixture"},
        {"space", QJsonObject{{"id", "Arcade_1"}, {"name", "Arcade"}, {"target", "7"}}},
        {"artwork", QJsonObject{{"version", 1}, {"assets", QJsonObject{{"poster", QJsonObject{{"cached", true},
            {"url", "/polaris/v1/games/space.Arcade_1.7/space-artwork/poster"}}}}}}}};
    const auto page = [&](const QJsonObject& g) { return parseGamesPage(json({{"games", QJsonArray{g}}})); };
    require(page(game) && !page(game)->games.front().artwork.poster.empty(), "Space artwork contract lost");
    auto bad = game; bad.remove("space"); require(!page(bad), "Space missing context accepted");
    bad = game; bad["app_id"] = 7; require(!page(bad), "Space borrowed Desktop app id");
    bad = game; bad["space"] = QJsonObject{{"id", "Other"}, {"name", "Other"}, {"target", "7"}};
    require(!page(bad), "cross-Space context accepted");
    for (const auto* route : {"/polaris/v1/games/space.Other.7/space-artwork/poster", "/polaris/v1/games/space.Arcade_1.7/artwork/poster",
            "https://foreign.invalid/poster", "/polaris/v1/games/space.Arcade_1.7/space-artwork/resolve"}) {
        bad = game; bad["artwork"] = QJsonObject{{"assets", QJsonObject{{"poster", QJsonObject{{"cached", true}, {"url", route}}}}}};
        require(page(bad) && page(bad)->games.front().artwork.poster.empty(), "Space artwork escaped its fixed route");
    }
}
template<class F> void until(F done) {
    QElapsedTimer timer; timer.start();
    while (!done() && timer.elapsed() < 3000) { QCoreApplication::processEvents(); QThread::msleep(1); }
    require(done(), "controller did not settle");
}
void selection() {
    identity::DeckMoonlightIdentity identity;
    identity.loaded = true; identity.sourceLabel = "nova-native";
    identity.clientCertificatePem = "client"; identity.clientPrivateKeyPemForBackendOnly = "key";
    identity::DeckMoonlightHostRecord host;
    host.uuid = "pc"; host.hostname = "PC"; host.manualAddress = "fixture"; host.serverCertificatePem = "pin";
    identity.hosts.push_back(host);
    std::string selected = "desktop"; bool ambiguous = false;
    std::atomic<int> posts = 0;
    auto fetch = [&](const auto&, bool) {
        backend::DeckLivePolarisFetch value;
        value.status = DeckPolarisRequestStatus::Ok; value.httpsPort = 47984; value.spacesSupported = true;
        value.spaces = parseSpaces(json(spacesBody(selected)));
        DeckPolarisGame game;
        game.id = selected == "desktop" ? "game-7" : "space.Arcade_1.7";
        game.appId = selected == "desktop" ? 7 : kSpaceAppId; game.name = "Fixture";
        if (selected != "desktop") { game.spaceId = selected; game.spaceName = "Arcade"; }
        value.games.push_back(game); return value;
    };
    auto snapshot = backend::buildLiveSnapshot(identity, fetch);
    auto selector = [&](const auto&, const auto&, int port, const auto& desired, const auto& previous, const auto& cancelled) {
        require(port == 47984 && previous == selected && !cancelled(), "choice lost paired destination context");
        ++posts; selected = desired;
        return ambiguous ? DeckPolarisResult<DeckSpaces>{DeckPolarisRequestStatus::Timeout, 0, {}, {}}
            : DeckPolarisResult<DeckSpaces>{DeckPolarisRequestStatus::Ok, 200, {}, parseSpaces(json(spacesBody(selected)))};
    };
    DeckLibraryController controller(identity, snapshot, [&] { return std::optional{identity}; },
        [&](const auto&) { return fetch; }, nullptr, selector);
    auto old = controller.targetResolver();
    controller.setSessionBusy(true);
    require(!controller.selectDestination("Arcade_1") && posts == 0, "stream allowed destination mutation");
    controller.setSessionBusy(false);
    require(!controller.selectDestination("Other") && !controller.selectDestination("desktop"), "unknown/repeated selection accepted");
    require(controller.selectDestination("Arcade_1"), "permitted selection refused");
    require(controller.snapshot().library.games.empty() && !controller.targetResolver() && !old("pc", "game-7"), "old launch authority survived switch");
    require(!controller.selectDestination("desktop") && !controller.refresh(), "choice overlapped pending operation");
    until([&] { return !controller.busy(); });
    require(posts == 1 && controller.state().value("selectionConfirmed").toBool() && controller.snapshot().library.games.front().spaceId == selected, "selection did not publish exact library");
    ambiguous = true;
    require(controller.selectDestination("desktop"), "ambiguous scenario did not start");
    until([&] { return !controller.busy(); });
    require(posts == 2 && controller.state().value("failed").toBool() && !controller.targetResolver() && controller.snapshot().library.games.empty(), "uncertain selection retained authority");
    for (const auto& value : controller.state().value("spaces").toMap().value("rows").toList()) {
        const auto row = value.toMap();
        require(!row.value("selected").toBool() && !row.value("available").toBool() &&
            row.value("status") != "Ready" && row.value("caption") != "Current destination", "uncertain selection displayed stale authority");
    }
    require(controller.refresh(), "explicit reconciliation refused");
    until([&] { return !controller.busy(); });
    require(posts == 2 && controller.state().value("destinationId") == "desktop" && controller.snapshot().library.games.front().id == "game-7", "refresh replayed choice or kept wrong library");
    identity.hosts.front().serverCertificatePem = "changed";
    require(controller.selectDestination("Arcade_1"), "identity-race fixture did not start");
    until([&] { return !controller.busy(); });
    require(posts == 2 && controller.state().value("failed").toBool(), "changed identity sent a selection");
}
}
int main(int argc, char** argv) { QCoreApplication app(argc, argv); parsing(); selection(); }
