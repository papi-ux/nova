#include "deck_game_tools_fixture.h"
#include "runtime/deck_play_settings.h"
#include "stream/deck_stream_core.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QTemporaryDir>
#include <iostream>
using namespace nova::deck::polaris;
using namespace nova::deck::runtime;
namespace {
void check(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
void wait(const std::function<bool()>& done) { QElapsedTimer t; t.start(); while (!done() && t.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(1); } check(done(), "timed out"); }
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const auto config = DeckPlayConfiguration{}.toMap();
    {
        const QString game="space.room.42";
        const std::string updated=R"({"version":1,"revision":"two","assets":{},"resolution":{"status":"updated","requested_kinds":["poster"],"remaining_kinds":[]}})";
        const std::string partial=R"({"version":1,"revision":"two","assets":{},"resolution":{"status":"partial_failure","requested_kinds":["poster","hero"],"remaining_kinds":["hero"]}})";
        check(gameToolRequest(game,"spaceRefresh",{})->path=="/polaris/v1/games/space.room.42/space-artwork/resolve", "Space artwork route drift");
        for(const auto* action:{"search","choices","apply","reset","refreshArt","steam","settings","plan"})
            check(!gameToolRequest(game,action,{}),"Space escaped to desktop action");
        for(const auto* id:{"space.room.big-picture-v1","space.room.0","space.room.042","space.other/room.42","space.room.4294967296","game"})
            check(!gameToolRequest(id,"spaceRefresh",{}),"invalid Space artwork identity admitted");
        check(!gameToolRequest(game,"spaceRefresh",{{"game","other"}}),"Space refresh accepted extra fields");
        check(gameToolReply(game,"spaceRefresh",{},updated)->value("resolution")=="updated", "Space receipt not parsed");
        check(gameToolReply(game,"spaceRefresh",{},partial)->value("remaining").toStringList()==QStringList{"hero"},"partial artwork result lost");
        for(const auto* reply:{R"({"revision":"two","assets":{}})",R"({"version":1,"revision":"two","assets":{},"resolution":{"status":"updated","requested_kinds":["poster"],"remaining_kinds":["poster"]}})",R"({"version":1,"revision":"two","assets":{},"resolution":{"status":"partial_failure","requested_kinds":["poster"],"remaining_kinds":["logo"]}})"})
            check(!gameToolReply(game,"spaceRefresh",{},reply),"unconfirmed Space response accepted");
        std::atomic<bool> allowed{true}, valid{true}, lost{false}; std::atomic<int> writes{0};
        DeckGameTools space;
        space.setTarget("host",[&]() -> std::optional<DeckGameToolsTarget> { return DeckGameToolsTarget{
            [&](const QString& id,const QString& action,const QVariantMap& values,const auto&) {
                check(id==game && action=="spaceRefresh" && values.isEmpty(),"Space review sent a desktop request");++writes;
                return lost ? DeckPolarisResult<QVariantMap>{DeckPolarisRequestStatus::Timeout,0,{}, {}} : game_tools_fixture::ok(*gameToolReply(id,action,values,partial));
            },[&](const auto&) { DeckPolarisGame item;item.id=game.toStdString();item.spaceId="room";item.installed=true;
                return game_tools_fixture::ok(allowed ? std::vector{item} : std::vector<DeckPolarisGame>{}); },[&]{return valid.load();}}; });
        check(space.prepare("host",game,config),"Space prepare failed");wait([&]{return !space.busy();});
        check(space.state()["canRefreshArtwork"].toBool() && !space.search("Title") && !space.resetArtwork() && !space.setSteamMode("direct"),"Space manual actions exposed");
        check(space.refreshArtwork(),"Space refresh refused");wait([&]{return !space.busy();});
        check(writes==1 && space.state()["copy"].toString().contains("backdrop") && space.state()["artworkResolution"].toMap()["resolution"]=="partial_failure", "partial refresh presented as success");
        allowed=false;check(space.refreshArtwork(),"Space permission fixture failed");wait([&]{return !space.busy();});check(writes==1,"removed Space game mutated");
        allowed=true;check(space.checkArtwork(),"Space read-back failed");wait([&]{return !space.busy();});check(writes==1,"read-back repeated refresh");
        lost=true;check(space.refreshArtwork(),"lost Space fixture failed");wait([&]{return !space.busy();});
        check(writes==2 && space.state()["uncertain"].toBool() && !space.refreshArtwork(),"lost Space reply retried");
        check(space.checkArtwork(),"Space uncertainty could not reconcile");wait([&]{return !space.busy();});check(writes==2 && !space.state()["uncertain"].toBool(),"GET reconciliation sent a POST");
        valid=false;check(space.refreshArtwork(),"stale pairing fixture failed");wait([&]{return !space.busy();});check(writes==2,"changed pairing mutated Space artwork");
        check(!space.prepare("host","space.room.big-picture-v1",config) && !space.refreshArtwork(),"launcher borrowed previous game authority");
    }
    check(gameToolRequest("game", "search", {{"query","A & B / ? #"}})->path == "/polaris/v1/games/game/artwork/candidates?query=A%20%26%20B%20%2F%20%3F%20%23", "query escaped incorrectly");
    for (const auto* game : {"../host", "space.test.game", "game?x=y", "game/cover"}) check(!gameToolRequest(game,"reset",{}), "unsafe game route");
    check(!gameToolRequest("game","apply",{{"candidate",game_tools_fixture::candidate()},{"selections",QVariantMap{{"poster","../host"}}}}), "invalid token allowed");
    check(gameToolRequest("game","reset",{})->method == "DELETE", "reset contract drift");
    check(gameToolReply("game","settings",{},game_tools_fixture::settings())->value("encoders").toList().size() == 1, "unavailable encoder exposed");
    check(gameToolReply("game","plan",config,game_tools_fixture::plan())->value("fields").toList().size() == 5, "resolved fields missing");
    auto malformed = game_tools_fixture::plan(); malformed.replace(malformed.find("1280x800x60"),10,"1280x800x90");
    check(!gameToolReply("game","plan",config,malformed), "inconsistent resolved profile accepted");
    check(!gameToolReply("game","plan",config,"{\"source\":\"ai_cache\",\"resolved_profile\":{}}"), "untrusted plan displayed");
    check(gameToolReply("game","reset",{},R"({"status":true,"data":{"game":{"artwork":{"version":1,"assets":{},"revision":""}}}})").has_value(),"reset without cached artwork refused");
    auto hostile = game_tools_fixture::choices("poster"); auto location = hostile.find("/polaris/v1/games/game/artwork/candidate/"); hostile.replace(location, 38, "https://unpaired.example/");
    check(gameToolReply("game","choices",{{"kind","poster"}},hostile)->value("choices").toList().size() == 3, "external preview admitted");
    QTemporaryDir temp; DeckPlaySettings settings(temp.filePath("settings.ini"));
    check(settings.saveChoice("host","game",{{"profilePreference","quality"}}) && settings.saveChoice("host","game",{{"encoderBackend","vaapi"}}), "new choices not saved");
    check(DeckPlaySettings(temp.filePath("settings.ini")).load("host","game")["configuration"].toMap()["encoderBackend"] == "vaapi", "restart lost encoder");
    check(settings.load("host","other")["configuration"].toMap()["profilePreference"] == "auto", "preset escaped game scope");
    check(!settings.saveChoice("host","game",{{"encoderBackend","vaapi&appid=9"}}), "injected encoder saved");
    check(settings.resetChoice("host","game","profilePreference") && settings.load("host","game")["configuration"].toMap()["encoderBackend"] == "vaapi", "reset touched sibling setting");
    nova::deck::stream::DeckStreamRequest stream; stream.profilePreference = "quality"; stream.encoderBackend = "vaapi";
    auto launch = nova::deck::stream::launchRequestForStream(stream,42,"game");
    const auto keys = nova::deck::stream::buildStreamKeys({},1);
    auto path = nova::deck::stream::buildLaunchTarget(launch,keys);
    check(path.find("profilePreference=quality") != std::string::npos && path.find("encoderBackend=vaapi") != std::string::npos,"choices lost before launch");
    launch.resume = true; path = nova::deck::stream::buildLaunchTarget(launch,keys);
    check(path.find("profilePreference=") == std::string::npos && path.find("encoderBackend=") == std::string::npos,"resume changed launch settings");
    game_tools_fixture::Host host; DeckGameTools tools; tools.setTarget("host",host.resolver());
    check(tools.prepare("host","game",config),"prepare failed"); wait([&]{return !tools.busy();});
    check(tools.state()["available"].toBool() && !tools.state()["plan"].toMap().isEmpty(),"review missing");
    check(tools.search("Moonlit Harbor"),"search failed"); wait([&]{return !tools.busy();});
    check(tools.selectCandidate(0),"match failed"); wait([&]{return !tools.busy();});
    check(tools.selectArtwork(0) && host.writes == 0,"preview wrote to host");
    host.lost = true; check(tools.applyArtwork(),"apply refused"); wait([&]{return !tools.busy();});
    check(host.writes == 1 && tools.state()["uncertain"].toBool() && !tools.applyArtwork() && !tools.resetArtwork(),"lost write replayed");
    host.lost = false; check(tools.search("Moonlit Harbor"),"fresh search refused"); wait([&]{return !tools.busy();});
    host.expired = true; check(tools.selectCandidate(0),"expired setup failed"); wait([&]{return !tools.busy();});
    check(!tools.selectArtwork(0),"expired token selected");
    host.expired = false; check(tools.search("Moonlit Harbor"),"search failed"); wait([&]{return !tools.busy();});
    check(tools.selectCandidate(0),"match failed"); wait([&]{return !tools.busy();}); check(tools.selectArtwork(1),"selection failed");
    host.allowed = false; check(tools.applyArtwork(),"revoked setup failed"); wait([&]{return !tools.busy();}); check(host.writes == 1,"revoked game mutated");
    host.allowed = true; check(tools.prepare("host","game",config),"prepare failed"); wait([&]{return !tools.busy();});
    check(tools.setSteamMode("big-picture"),"Steam change failed"); wait([&]{return !tools.busy();}); check(tools.state()["steam"].toMap()["mode"] == "big-picture" && host.writes == 2,"Steam receipt not verified");
    host.hold = true; host.entered = false; check(tools.search("stale"),"hold failed"); wait([&]{return host.entered.load();});
    tools.close(); host.hold = false; wait([&]{return !tools.busy();}); check(tools.state()["candidates"].toList().isEmpty(),"closed search published stale data");
    tools.setSessionActive(true); check(!tools.prepare("host","game",config),"tools active during stream");
    std::cout << "Game tools contracts, scopes, launch parameters, stale replies and mutation lifecycle passed.\n";
}
