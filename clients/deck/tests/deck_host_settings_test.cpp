#include "runtime/deck_host_settings.h"
#include "deck_host_settings_fixture.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QTemporaryDir>
#include <atomic>
#include <iostream>

using namespace nova::deck;
using namespace nova::deck::polaris;
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
void until(const std::function<bool()>& predicate, int timeout = 3000) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < timeout) { QCoreApplication::processEvents(); QThread::msleep(1); }
    require(predicate(), "controller timed out");
}
template<class T> DeckPolarisResult<T> ok(T value) { return {DeckPolarisRequestStatus::Ok, 200, {}, std::move(value)}; }
void parser() {
    const auto good = host_settings_fixture::settings();
    auto result = parseHostSettings(host_settings_fixture::json(good));
    require(result && result->permits("desktop_display") && !result->permits("headless_dongle"), "catalog permission lost");
    require(result->publicState().value("desiredLabel") == "Private Stream", "host mode label missing");
    require(!result->publicState().contains("revision"), "backend revision leaked to QML");
    require(parseHostSettings(host_settings_fixture::json(QJsonObject{{"status", true}, {"client_settings", good}})).has_value(), "envelope refused");
    auto bad = good; bad["version"] = "1";
    require(!parseHostSettings(host_settings_fixture::json(bad)), "coerced schema");
    bad = good; bad["revision"] = ""; require(!parseHostSettings(host_settings_fixture::json(bad)), "empty revision accepted");
    bad = good; bad["relaunch_required"] = "false"; require(!parseHostSettings(host_settings_fixture::json(bad)), "coerced relaunch");
    bad = good; auto desired = good["desired"].toObject(); desired["target_bitrate_kbps"] = 20000.5; bad["desired"] = desired;
    require(!parseHostSettings(host_settings_fixture::json(bad)), "fractional bitrate accepted");
    desired = good["desired"].toObject(); desired["display_mode"] = "1280x800x0"; bad["desired"] = desired;
    require(!parseHostSettings(host_settings_fixture::json(bad)), "invalid display accepted");
    auto duplicate = host_settings_fixture::json(good); duplicate.insert(1, "\"rev\\u0069sion\":\"bad\",");
    require(!parseHostSettings(duplicate), "escaped duplicate keys accepted");
    require(!parseHostSettings(host_settings_fixture::json(QJsonObject{{"status", false}, {"client_settings", good}})), "rejected mutation accepted");
    auto modes = good["capabilities"].toObject()["modes"].toArray(); modes.append(modes.first());
    bad = good; bad["capabilities"] = QJsonObject{{"modes", modes}};
    require(!parseHostSettings(host_settings_fixture::json(bad)), "duplicate modes accepted");
    bad = good; auto capabilities = good["capabilities"].toObject(); capabilities["display_mode_override"] = "true"; bad["capabilities"] = capabilities;
    require(!parseHostSettings(host_settings_fixture::json(bad)), "coerced profile permission accepted");
    capabilities.remove("display_mode_override"); capabilities.remove("target_bitrate_override"); bad["capabilities"] = capabilities;
    result = parseHostSettings(host_settings_fixture::json(bad));
    require(result && !result->displayOverride && !result->bitrateOverride, "missing profile permission granted");
    require(parseHostSettingsIdle(R"({"state":"idle","streaming_active":false,"game_uuid":""})") == true, "idle not admitted");
    require(parseHostSettingsIdle(R"({"state":"paused","streaming_active":false,"game_uuid":"game"})") == false, "paused game treated idle");
    require(!parseHostSettingsIdle(R"({"state":"idle","game_uuid":""})"), "missing activity assumed idle");
    require(!parseHostSettingsIdle(R"({"state":"idle","streaming_active":"false","game_uuid":""})"), "coerced activity");
}
void controller() {
    auto current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    std::atomic<int> writes{0}, reads{0}, changed{0};
    std::atomic_bool identityValid{true}, supported{true}, idle{true}, lost{false}, entered{false}, hold{false};
    DeckHostSettingsResolver resolver = [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget t;
        t.identityValid = [&] { return identityValid.load(); };
        t.capabilities = [&] { DeckPolarisCapabilities c; c.clientSettings = supported; return ok(c); };
        t.read = [&](const auto& cancelled) {
            ++reads; entered = true;
            while (hold && !cancelled()) QThread::msleep(1);
            return ok(current);
        };
        t.idle = [&](const auto&) { return ok(idle.load()); };
        t.writeMode = [&](const QString& mode, const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
            require(!cancelled(), "cancelled write dispatched"); ++writes;
            current.desiredMode = mode; current.revision = QString::number(writes.load() + 10);
            current.relaunchRequired = current.desiredMode != current.effectiveMode;
            if (lost) return {DeckPolarisRequestStatus::Unreachable, 0, {}, {}};
            return ok(current);
        };
        return t;
    };
    DeckHostSettingsController c;
    QObject::connect(&c, &DeckHostSettingsController::hostDefaultsMayHaveChanged, [&] { ++changed; });
    c.setTarget("host-one", "Fixture PC", resolver); c.setWindowActive(true); c.open();
    until([&] { return !c.busy(); });
    require(c.state().value("canChange").toBool() && writes == 0, "opening mutated host");
    require(!c.selectMode("headless_dongle") && !c.selectMode("headless_stream"), "unavailable/current mode dispatched");
    require(c.selectMode("desktop_display") && !c.selectMode("desktop_display"), "busy gate failed");
    until([&] { return !c.busy(); });
    require(writes == 1 && changed == 1 && c.state().value("settings").toMap().value("relaunchRequired").toBool(), "confirmed desired/effective collapsed");
    current.revision = "external-change";
    require(c.selectMode("headless_stream"), "stale choice not checked"); until([&] { return !c.busy(); });
    require(writes == 1 && c.state().value("copy").toString().contains("settings changed"), "stale settings overwritten");
    idle = false; c.refresh(); until([&] { return !c.busy(); });
    require(!c.state().value("canChange").toBool() && !c.selectMode("headless_stream"), "active host admitted write");
    idle = true; c.refresh(); until([&] { return !c.busy(); });
    lost = true; require(c.selectMode("headless_stream"), "write not accepted"); until([&] { return !c.busy(); });
    require(writes == 2 && changed == 2 && c.state().value("phase") == "unconfirmed" && c.state().value("settings").toMap().isEmpty(), "lost answer retained authority");
    require(!c.selectMode("desktop_display"), "unconfirmed write replayed");
    lost = false; c.refresh(); until([&] { return !c.busy(); });
    require(writes == 2 && c.state().value("settings").toMap().value("desiredMode") == "headless_stream", "refresh failed to reconcile without replay");
    entered = false; hold = true; require(c.selectMode("desktop_display"), "pending write not started");
    until([&] { return entered.load(); }); c.close(); until([&] { return !c.busy(); }); hold = false;
    require(writes == 2, "close allowed pending write");
    c.open(); until([&] { return !c.busy(); }); identityValid = false;
    require(c.selectMode("desktop_display"), "identity preflight not started"); until([&] { return !c.busy(); });
    require(writes == 2 && !c.state().value("canChange").toBool(), "changed identity retained authority");
    identityValid = true; supported = false; c.refresh(); until([&] { return !c.busy(); });
    require(c.state().value("settings").toMap().isEmpty(), "capability loss retained profile");
    supported = true; c.refresh(); until([&] { return !c.busy(); });
    c.setWindowActive(false); require(!c.selectMode("desktop_display") && !c.refresh(), "unfocused write admitted");
    c.setWindowActive(true); c.refresh(); until([&] { return !c.busy(); });
    c.setSessionActive(true); require(!c.selectMode("desktop_display") && !c.refresh(), "local stream admitted write");
    c.setSessionActive(false); c.setTarget("host-two", "Other PC", {});
    require(!c.state().value("supported").toBool() && c.state().value("settings").toMap().isEmpty(), "host switch retained settings");
}
void resumeTimeoutAndDefaults() {
    QTemporaryDir directory; DeckPlaySettings preferences(directory.filePath("custom.ini"));
    auto current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    int writes = 0; bool lost = false, valid = true, idle = true;
    DeckHostSettingsResolver resolver = [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget target;
        target.identityValid = [&] { return valid; };
        target.capabilities = [] { DeckPolarisCapabilities c; c.clientSettings = true; return ok(c); };
        target.read = [&](const auto&) { return ok(current); };
        target.idle = [&](const auto&) { return ok(idle); };
        target.writeMode = [&](const auto&,const auto&) { require(false,"timeout changed topology"); return ok(current); };
        target.writeResumeTimeout = [&](int seconds,const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
            require(!cancelled(),"cancelled timeout sent"); ++writes; current.desiredResumeTimeout = seconds; current.revision = QString::number(writes);
            if (lost) return {DeckPolarisRequestStatus::Timeout,0,{}, {}};
            return ok(current);
        };
        return target;
    };
    DeckHostSettingsController controller; controller.setPlaySettings(&preferences);
    controller.setTarget("one","Test PC",resolver); controller.setWindowActive(true); controller.open(); until([&]{return !controller.busy();});
    require(controller.state().value("canChangeResumeTimeout").toBool() && !controller.setResumeTimeout(-1) && !controller.setResumeTimeout(86401),"timeout range/gate failed");
    require(controller.setResumeTimeout(600),"timeout could not be selected"); until([&]{return !controller.busy();});
    require(writes==1 && current.desiredResumeTimeout==600 && current.effectiveResumeTimeout==300
        && controller.state().value("copy").toString().contains("different effective"),"desired timeout was treated as applied");
    current.revision="other-writer"; require(controller.setResumeTimeout(1800),"stale timeout preflight not started"); until([&]{return !controller.busy();});
    require(writes==1,"stale timeout overwrote host");
    lost=true; require(controller.setResumeTimeout(1800),"lost-reply test not started"); until([&]{return !controller.busy();});
    require(writes==2 && controller.state().value("phase")=="unconfirmed" && !controller.setResumeTimeout(60),"lost timeout replayed");
    lost=false; controller.refresh(); until([&]{return !controller.busy();}); require(writes==2,"refresh retried timeout");
    idle=false; controller.refresh(); until([&]{return !controller.busy();}); require(!controller.setResumeTimeout(60),"active host timeout changed");
    idle=true; controller.refresh(); until([&]{return !controller.busy();}); valid=false;
    require(controller.setResumeTimeout(60),"identity preflight did not start"); until([&]{return !controller.busy();}); require(writes==2,"changed pairing wrote timeout");
    valid=true; controller.refresh(); until([&]{return !controller.busy();});
    current.resumeTimeoutControl=false; require(controller.setResumeTimeout(60),"withdrawn capability was not rechecked"); until([&]{return !controller.busy();});
    require(writes==2 && !controller.state().value("canChangeResumeTimeout").toBool(),"withdrawn timeout capability retained authority");
    auto defaults=preferences.streamDefaults(), custom=defaults; custom["width"]=2560; custom["height"]=1440; custom["fps"]=45; custom["bitrateKbps"]=27500;
    require(preferences.saveChoice("one","game",{{"fps",60}}),"scope fixture failed");
    require(controller.saveDefaults(custom,defaults) && writes==2,"local defaults wrote host");
    require(!controller.saveDefaults(defaults,defaults) && preferences.streamDefaults()==custom,"stale defaults replaced newer choices");
    require(preferences.load("one","game").value("configuration").toMap().value("fps")==60
        && preferences.load("two","another").value("configuration").toMap().value("fps")==45,"device defaults crossed override scope");
    controller.setSessionActive(true); require(!controller.saveDefaults(defaults,custom),"active session changed defaults");
    auto malformed=host_settings_fixture::settings(); auto desired=malformed["desired"].toObject(); desired["disconnect_resume_timeout_seconds"]="300"; malformed["desired"]=desired;
    require(!parseHostSettings(host_settings_fixture::json(malformed)),"timeout string was coerced");
}
void profiles() {
    QTemporaryDir directory;
    DeckPlaySettings preferences(directory.filePath("defaults.ini"));
    const auto original = preferences.streamDefaults();
    require(preferences.saveChoice("other-pc", "custom-game", {{"bitrateKbps", 40000}}), "fixture preference failed");
    auto current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    current.desiredDisplay = current.effectiveDisplay = "1920x1080x30";
    current.desiredBitrate = current.effectiveBitrate = 30000;
    std::atomic<int> writes{0}, imports{0};
    std::atomic_bool lost{false}, mismatch{false}, valid{true}, idle{true}, hold{false}, entered{false};
    DeckHostSettingsResolver resolver = [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget t;
        t.identityValid = [&] { return valid.load(); };
        t.capabilities = [] { DeckPolarisCapabilities c; c.clientSettings = true; return ok(c); };
        t.read = [&](const auto& cancelled) { entered = true; while (hold && !cancelled()) QThread::msleep(1); return ok(current); };
        t.idle = [&](const auto&) { return ok(idle.load()); };
        t.writeMode = [&](const auto&, const auto&) { require(false, "profile action changed topology"); return ok(current); };
        t.writeProfile = [&](const QString& display, int bitrate, bool clear, const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
            require(!cancelled(), "cancelled profile write sent"); ++writes;
            require(clear ? display.isEmpty() && bitrate == 0 : validHostProfile(display, bitrate), "invalid profile sent");
            current.desiredDisplay = current.effectiveDisplay = display;
            current.desiredBitrate = current.effectiveBitrate = bitrate;
            current.revision = QString::number(10 + writes.load());
            if (lost) return {DeckPolarisRequestStatus::Unreachable, 0, {}, {}};
            auto reply = current; if (mismatch) reply.desiredBitrate = 10000;
            return ok(reply);
        };
        return t;
    };
    DeckHostSettingsController c;
    c.setPlaySettings(&preferences);
    QObject::connect(&c, &DeckHostSettingsController::novaDefaultsChanged, [&] { ++imports; });
    c.setTarget("host", "PC", resolver); c.setWindowActive(true); c.open();
    const auto settle = [&] { until([&] { return !c.busy(); }); };
    const auto refresh = [&] { require(c.refresh(), "profile refresh refused"); settle(); };
    const auto action = [&](const QString& id) { require(c.profileAction(id), "profile action refused"); settle(); };
    settle();
    require(c.state().value("profileState") == "Different from Nova" && writes == 0, "profile comparison failed");
    action("use");
    require(writes == 0 && imports == 1 && preferences.streamDefaults().value("width") == 1920, "Use did not import locally");
    require(DeckPlaySettings(directory.filePath("defaults.ini")).streamDefaults() == preferences.streamDefaults(), "defaults lost after restart");
    const auto other = preferences.load("other-pc", "custom-game").value("configuration").toMap();
    require(other.value("width") == 1920 && other.value("bitrateKbps") == 40000, "Use damaged per-game override/inheritance");
    require(c.state().value("profileState") == "Matches Nova" && !c.profileAction("match"), "matched profile offered automatic overwrite");
    action("send"); require(writes == 1, "Send not explicit");
    require(preferences.saveStreamDefaults(original), "cannot restore defaults");
    current.revision = "other-writer"; action("match");
    require(writes == 1 && c.state().value("copy").toString().contains("settings changed"), "stale profile overwritten");
    action("match"); require(writes == 2 && current.desiredDisplay == "1280x800x60", "Match sent game overrides");
    action("clear");
    require(writes == 3 && current.desiredDisplay.isEmpty() && current.desiredBitrate == 0 && preferences.streamDefaults() == original,
        "Clear changed local defaults or did not clear profile");
    require(!c.profileAction("clear") && !c.profileAction("use") && c.state().value("profileState") == "No Polaris profile", "unset profile remained actionable");
    lost = true; action("send");
    require(writes == 4 && c.state().value("phase") == "unconfirmed" && !c.profileAction("send"), "uncertain profile retained authority/replayed");
    lost = false; refresh(); require(writes == 4 && c.state().value("profileState") == "Matches Nova", "read reconciliation replayed profile");
    mismatch = true; action("send");
    require(writes == 5 && c.state().value("phase") == "unconfirmed", "mismatched receipt acknowledged");
    mismatch = false; refresh();
    current.displayOverride = false; action("send");
    require(writes == 5 && !c.profileAction("send"), "withdrawn override capability still wrote");
    current.displayOverride = true; current.desiredDisplay = "1920x1080x59.94"; refresh();
    require(!c.profileAction("use") && preferences.streamDefaults() == original, "unsupported rate partly imported");
    current.desiredDisplay = "1920x1080x30"; refresh();
    entered = false; hold = true; require(c.profileAction("use"), "local-conflict preflight refused");
    until([&] { return entered.load(); });
    auto externalDefaults = original; externalDefaults["height"] = 720;
    require(preferences.saveStreamDefaults(externalDefaults), "local writer fixture failed");
    hold = false; settle();
    require(imports == 1 && preferences.streamDefaults() == externalDefaults && c.state().value("copy").toString().contains("Nova's defaults changed"),
        "pending import replaced newer local defaults");
    require(preferences.saveStreamDefaults(original), "cannot restore local-conflict fixture");
    entered = false; hold = true; require(c.profileAction("use"), "Use preflight refused");
    until([&] { return entered.load(); }); c.close(); settle(); hold = false;
    require(imports == 1 && preferences.streamDefaults() == original, "closed view imported pending profile");
    c.open(); settle(); valid = false; action("use");
    require(imports == 1 && preferences.streamDefaults() == original, "revoked identity imported profile");
    valid = true; idle = false; refresh(); require(!c.profileAction("send"), "active host admitted profile send");
    idle = true; refresh();
    DeckPlaySettings unwritable(directory.path()); c.setPlaySettings(&unwritable); action("use");
    require(imports == 1 && c.state().value("copy").toString().contains("Couldn't save"), "failed local save acknowledged");
    c.setPlaySettings(&preferences); action("use"); action("reset");
    require(imports == 3 && preferences.streamDefaults() == original && writes == 5, "reset crossed host scope");
    require(preferences.load("other-pc", "custom-game").value("configuration").toMap().value("bitrateKbps") == 40000,
        "reset removed per-game choices");
}
void automaticProfiles() {
    QTemporaryDir directory;
    DeckPlaySettings preferences(directory.filePath("sync.ini"));
    const auto original = preferences.streamDefaults();
    require(preferences.saveChoice("one", "game", {{"bitrateKbps", 40000}}), "cannot seed game override");
    auto current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    current.desiredBitrate = current.effectiveBitrate = 30000;
    std::atomic<int> writes{0}, reads{0}, slowRead{0};
    QElapsedTimer wireClock; wireClock.start();
    QList<qint64> writeTimes;
    std::atomic_bool hold{false}, entered{false}, lost{false}, valid{true}, idle{true};
    DeckHostSettingsResolver resolver = [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget t;
        t.identityValid = [&] { return valid.load(); };
        t.capabilities = [] { DeckPolarisCapabilities c; c.clientSettings = true; return ok(c); };
        t.read = [&](const auto& cancelled) {
            ++reads; entered = true;
            for (int remaining = slowRead.exchange(0); remaining > 0 && !cancelled(); remaining -= 10) QThread::msleep(10);
            while (hold && !cancelled()) QThread::msleep(1);
            return ok(current);
        };
        t.idle = [&](const auto&) { return ok(idle.load()); };
        t.writeMode = [&](const auto&, const auto&) { require(false, "automatic sync changed topology"); return ok(current); };
        t.writeProfile = [&](const QString& display, int bitrate, bool clear, const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
            require(!cancelled(), "cancelled automatic request sent"); writeTimes.append(wireClock.elapsed()); ++writes;
            require(preferences.keepInStep("one") == "paused" || clear, "sync write missing durable pending state");
            require(clear || (display == "1280x800x60" && bitrate == 20000), "sync sent per-game overrides");
            current.desiredDisplay = current.effectiveDisplay = display;
            current.desiredBitrate = current.effectiveBitrate = bitrate;
            current.revision = QString::number(100 + writes.load());
            if (lost) return {DeckPolarisRequestStatus::Unreachable, 0, {}, {}};
            return ok(current);
        };
        return t;
    };
    const auto drain = [](int ms) { QElapsedTimer t; t.start(); until([&] { return t.elapsed() >= ms; }, ms + 1000); };
    const auto prepare = [&](DeckHostSettingsController& c) {
        c.setPlaySettings(&preferences); c.setTarget("one", "Fixture PC", resolver); c.setWindowActive(true); c.open();
        until([&] { return !c.busy(); });
    };
    {
        DeckHostSettingsController c; prepare(c);
        drain(3100);
        require(writes == 0 && reads == 1 && c.state().value("keepInStep") == "off", "default-off polling mutated host");
        slowRead = 3500;
        require(c.setKeepInStep(true), "cannot enable sync"); until([&] { return !c.busy(); }, 6500);
        require(writes == 1 && c.state().value("keepInStep") == "on", "enable did not save mismatched device defaults");
        current.desiredBitrate = current.effectiveBitrate = 30000; current.revision = "external-edit";
        require(c.refresh(), "cannot read external profile"); until([&] { return !c.busy(); });
        require(writes == 1, "automatic sync ignored five-second throttle");
        until([&] { return writes == 2 && !c.busy(); }, 7500);
        require(writeTimes[1] - writeTimes[0] >= 5000, "slow preflight compressed automatic POSTs to less than five seconds apart");
        const int stable = writes; drain(3100);
        require(writes == stable, "matching profile repeatedly posted");
        require(c.profileAction("clear"), "clear while enabled refused"); until([&] { return !c.busy(); });
        require(writes == 3 && c.state().value("keepInStep") == "off", "clear did not turn sync off");
        c.close();
    }
    {
        // A dropped reply pauses durably; GET, reopening and a new controller do
        // not authorize a replay even when the returned profile still differs.
        DeckHostSettingsController c; prepare(c); lost = true;
        require(c.setKeepInStep(true), "cannot enable lost-reply fixture"); until([&] { return !c.busy(); });
        require(writes == 4 && c.state().value("phase") == "unconfirmed" && preferences.keepInStep("one") == "paused", "lost result rearmed sync");
        current.desiredBitrate = current.effectiveBitrate = 30000; current.revision = "after-loss";
        lost = false; c.refresh(); until([&] { return !c.busy(); });
        require(writes == 4 && c.state().value("keepInStep") == "paused", "GET replayed failed POST");
        c.close(); c.open(); until([&] { return !c.busy(); });
        require(writes == 4 && preferences.keepInStep("one") == "paused", "reopening replayed failed POST");
    }
    {
        DeckHostSettingsController c; prepare(c); drain(3100);
        require(writes == 4 && c.state().value("keepInStep") == "paused", "restart replayed failed POST");
        // Enable preflight must still refuse a change after the reviewed read.
        current.revision = "unreviewed-edit";
        require(c.setKeepInStep(true), "resume refused"); until([&] { return !c.busy(); });
        require(writes == 4 && c.state().value("keepInStep") == "paused" && c.state().value("copy").toString().contains("settings changed"), "stale review overwritten automatically");
        require(c.setKeepInStep(false), "paused state cannot turn off");
        c.setTarget("two", "Other PC", resolver); c.open(); until([&] { return !c.busy(); });
        require(c.state().value("keepInStep") == "off" && writes == 4, "preference crossed PC identity");
    }
    {
        DeckHostSettingsController c; prepare(c);
        hold = true; entered = false; require(c.setKeepInStep(true), "pending toggle failed");
        until([&] { return entered.load(); });
        require(c.setKeepInStep(false), "off was blocked by in-flight sync");
        until([&] { return !c.busy(); }); hold = false;
        require(writes == 4 && preferences.keepInStep("one") == "off", "off allowed pending POST or rearmed preference");
    }
    {
        DeckHostSettingsController c; prepare(c);
        hold = true; entered = false; require(c.setKeepInStep(true), "cannot stage close fixture"); until([&] { return entered.load(); });
        c.close(); until([&] { return !c.busy(); }); hold = false;
        require(writes == 4 && preferences.keepInStep("one") == "paused", "close rearmed interrupted sync");
    }
    {
        DeckHostSettingsController c; prepare(c); valid = false;
        require(c.setKeepInStep(true), "cannot stage identity fixture"); until([&] { return !c.busy(); });
        require(writes == 4 && c.state().value("keepInStep") == "paused", "revoked identity permitted auto write");
        valid = true; idle = false; c.refresh(); until([&] { return !c.busy(); });
        require(!c.setKeepInStep(true) && writes == 4, "busy host permitted auto sync");
        idle = true; c.refresh(); until([&] { return !c.busy(); });
        c.setSessionActive(true); require(!c.setKeepInStep(true), "local session permitted auto sync");
        c.setSessionActive(false); c.refresh(); until([&] { return !c.busy(); });
        c.setWindowActive(false); require(!c.setKeepInStep(true), "unfocused view enabled sync");
    }
    {
        DeckHostSettingsController c; prepare(c);
        hold = true; entered = false; require(c.setKeepInStep(true), "cannot stage local change fixture"); until([&] { return entered.load(); });
        auto newer = original; newer["fps"] = 30;
        require(preferences.saveStreamDefaults(newer), "local default change failed");
        until([&] { return !c.busy(); }); hold = false;
        require(writes == 4 && preferences.keepInStep("one") == "paused", "older device profile sent after local change");
        require(preferences.saveStreamDefaults(original), "cannot restore defaults");
    }
    {
        DeckHostSettingsController c; prepare(c);
        // A matched profile can enable without a POST; removing the advertised
        // permission on the next read must pause instead of sending a change.
        current.desiredBitrate = current.effectiveBitrate = 20000;
        c.refresh(); until([&] { return !c.busy(); });
        require(c.setKeepInStep(true) && !c.busy(), "matching enable dispatched unnecessary work");
        current.bitrateOverride = false;
        c.refresh(); until([&] { return !c.busy(); });
        require(writes == 4 && c.state().value("keepInStep") == "paused", "withdrawn profile permission retained auto sync");
        current.bitrateOverride = true;
        c.refresh(); until([&] { return !c.busy(); });
        require(c.setKeepInStep(true), "cannot reenable matched fixture");
        require(c.profileAction("use"), "manual import while sync enabled failed"); until([&] { return !c.busy(); });
        require(c.state().value("keepInStep") == "off" && writes == 4, "Use Polaris left automatic sync armed");
        auto newer = original; newer["fps"] = 30;
        require(preferences.saveStreamDefaults(newer), "cannot prepare reset fixture");
        current.desiredDisplay = current.effectiveDisplay = "1280x800x30";
        c.refresh(); until([&] { return !c.busy(); });
        require(c.setKeepInStep(true), "cannot enable matched reset fixture");
        require(c.profileAction("reset") && c.state().value("keepInStep") == "off" && writes == 4, "Reset kept automatic sync armed");
        require(preferences.streamDefaults() == original, "reset failed to restore device defaults");
        DeckPlaySettings unwritable(directory.path()); c.setPlaySettings(&unwritable); c.open(); until([&] { return !c.busy(); });
        require(!c.setKeepInStep(true) && writes == 4 && c.state().value("keepInStep") == "paused", "failed persistence authorized automatic write");
    }
    require(preferences.streamDefaults() == original && preferences.load("one", "game").value("configuration").toMap().value("bitrateKbps") == 40000,
        "automatic sync changed local defaults or game choices");
}

void syncView() {
    QTemporaryDir directory;
    DeckPlaySettings preferences(directory.filePath("settings.ini"));
    require(preferences.saveKeepInStep("one","on"), "sync preference fixture failed");
    auto current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    current.desiredDisplay="1920x1080x60"; current.effectiveDisplay="1280x800x60";
    std::atomic<int> reads{0}, writes{0}; std::atomic_bool valid{true}, idle{true}, fail{false}, hold{false}, entered{false};
    DeckHostSettingsResolver resolver = [&]() -> std::optional<DeckHostSettingsTarget> {
        DeckHostSettingsTarget t; t.identityValid=[&] { return valid.load(); };
        t.capabilities=[] { DeckPolarisCapabilities c; c.clientSettings=true; return ok(c); };
        t.read=[&](const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
            ++reads; entered=true; while(hold && !cancelled()) QThread::msleep(1);
            if(fail) return {DeckPolarisRequestStatus::Unreachable,0,{},{}};
            return ok(current);
        };
        t.idle=[&](const auto&) { return ok(idle.load()); };
        t.writeMode=[&](const auto&,const auto&) { ++writes; return ok(current); };
        t.writeProfile=[&](const auto&,int,bool,const auto&) { ++writes; return ok(current); };
        t.writeResumeTimeout=[&](int,const auto&) { ++writes; return ok(current); };
        return t;
    };
    DeckHostSettingsController c; c.setPlaySettings(&preferences); c.setTarget("one","Fixture PC",resolver); c.setWindowActive(true);
    c.openSync(true); until([&] { return !c.busy(); });
    const auto original=preferences.streamDefaults();
    require(c.state().value("readOnly").toBool() && c.state().value("canRefresh").toBool() &&
        !c.state().value("canChange").toBool() && !c.state().value("canEditDefaults").toBool(), "review exposed mutations");
    require(!c.selectMode("desktop_display") && !c.setKeepInStep(false) && !c.setKeepInStep(true) && !c.setResumeTimeout(60) &&
        !c.saveDefaults(original,original), "read-only method allowed a write");
    for(auto action:{"match","send","use","clear","reset"}) require(!c.profileAction(action), "read-only profile action admitted");
    until([&] { return reads>=2 && !c.busy(); },4000);
    require(writes==0 && preferences.keepInStep("one")=="on" && preferences.streamDefaults()==original, "review changed saved preferences or host");
    idle=false; c.setSessionActive(true); require(c.refresh(), "active-session review refused"); until([&] { return !c.busy(); });
    require(!c.state().value("settings").toMap().isEmpty() && writes==0, "active-session profile read failed");
    fail=true; require(c.refresh(), "offline read refused"); until([&] { return !c.busy(); });
    require(c.state().value("settings").toMap().isEmpty() && preferences.keepInStep("one")=="on", "offline review showed stale profiles or changed saved sync preference");
    fail=false; require(c.refresh(), "recovery read refused"); until([&] { return !c.busy(); });
    c.setWindowActive(false); const int stoppedReads=reads;
    QElapsedTimer timer; timer.start(); while(timer.elapsed()<3100) { QCoreApplication::processEvents(); QThread::msleep(1); }
    require(reads==stoppedReads && !c.refresh(), "unfocused sync kept polling");
    c.setWindowActive(true); entered=false; hold=true; require(c.refresh(), "held read not started"); until([&] { return entered.load(); });
    c.close(); until([&] { return !c.busy(); }); hold=false;
    require(c.state().value("settings").toMap().isEmpty() && writes==0, "closed read republished settings");
    valid=false; c.openSync(true); until([&] { return !c.busy(); });
    require(c.state().value("settings").toMap().isEmpty(), "changed identity restored cached profiles");
    c.close(); valid=true; idle=true; c.setSessionActive(false); require(preferences.saveKeepInStep("one","off"), "sync Off fixture failed");
    c.openSync(false); until([&] { return !c.busy(); });
    require(!c.state().value("readOnly").toBool() && c.state().value("canChange").toBool(), "library sync did not restore normal review authority");
}

}
int main(int argc, char** argv) { QCoreApplication app(argc, argv); parser(); controller(); resumeTimeoutAndDefaults(); profiles(); automaticProfiles(); syncView(); std::cout << "Host settings parser, controller and profile scope passed\n"; }
