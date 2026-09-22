#include "runtime/deck_play_settings.h"

#include <QCoreApplication>
#include <QFile>
#include <QSettings>
#include <QTemporaryDir>
#include <QProcess>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <cmath>

using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (argc == 4 && QString::fromLocal8Bit(argv[1]) == "--deadzone-reload") {
        require(DeckPlaySettings(QString::fromLocal8Bit(argv[2])).stickDeadzonePercent() == QString::fromLocal8Bit(argv[3]).toInt(), "deadzone lost after process restart");
        return 0;
    }
    QTemporaryDir directory;
    require(directory.isValid(), "missing temporary settings directory");
    const auto file = directory.filePath("play.ini");
    {
        const auto path=directory.filePath("deadzone.ini"); DeckPlaySettings input(path);
        int changes=0; QObject::connect(&input,&DeckPlaySettings::stickDeadzonePercentChanged,[&] { ++changes; });
        require(input.stickDeadzonePercent()==5,"deadzone default differs from Android");
        require(input.setVideoScaleMode("fill") && input.setFramePacingMode("balanced") && input.saveChoice("pc","game",{{"fps",90}}),"deadzone scope fixture failed");
        const auto audio=input.audioSettings(), defaults=input.streamDefaults(), game=input.load("pc","game");
        for (int percent : {-20,0,20}) {
            require(input.setStickDeadzonePercent(percent),"valid deadzone refused");
            QProcess child; child.start(QCoreApplication::applicationFilePath(),{"--deadzone-reload",path,QString::number(percent)});
            require(child.waitForFinished(5000) && child.exitStatus()==QProcess::NormalExit && child.exitCode()==0,"deadzone process reload failed");
        }
        for (const QVariant& bad : QList<QVariant>{true,"5",QVariant{},-21,21,1.5,std::numeric_limits<double>::quiet_NaN(),std::numeric_limits<double>::infinity()})
            require(!input.setStickDeadzonePercent(bad),"invalid deadzone accepted");
        require(changes==3 && input.resetStickDeadzonePercent() && changes==4 && input.stickDeadzonePercent()==5,"deadzone reset/notification failed");
        require(input.videoScaleMode()=="fill" && input.framePacingMode()=="balanced" && input.audioSettings()==audio
            && input.streamDefaults()==defaults && input.load("pc","game")==game,"deadzone reset crossed scopes");
        require(input.setStickDeadzonePercent(-10) && input.resetRumble() && input.resetAudioSettings() && input.resetVideoScaleMode()
            && input.resetFramePacingMode() && input.resetStreamDefaults() && input.reset("pc","game") && input.stickDeadzonePercent()==-10,"other resets erased deadzone");
        QSettings corrupt(path,QSettings::IniFormat); corrupt.setValue("Input/v1/stickDeadzone",QVariantMap{{"percent",true}}); corrupt.sync();
        require(input.stickDeadzonePercent()==5,"corrupt deadzone bypassed default");
        QFile blocker(directory.filePath("deadzone-blocked")); require(blocker.open(QIODevice::WriteOnly),"blocked fixture failed"); blocker.close();
        DeckPlaySettings blocked(blocker.fileName()+"/settings.ini");
        require(!blocked.setStickDeadzonePercent(-20) && blocked.stickDeadzonePercent()==5,"failed deadzone save appeared applied");
    }
    {
        const auto path = directory.filePath("pacing.ini");
        DeckPlaySettings settings(path);
        int changed = 0; QObject::connect(&settings, &DeckPlaySettings::framePacingModeChanged, [&] { ++changed; });
        require(settings.framePacingMode() == "latency", "pacing default changed");
        require(settings.setVideoScaleMode("fill") && settings.saveChoice("pc", "game", {{"fps", 90}}), "pacing scope fixture failed");
        const auto game = settings.load("pc", "game"), audio = settings.audioSettings(), defaults = settings.streamDefaults();
        require(settings.setFramePacingMode("balanced") && DeckPlaySettings(path).framePacingMode() == "balanced", "pacing did not persist");
        for (const auto* bad : {"", "Balanced", "warp", "warp2", "cap-fps", "smoothness", " latency "})
            require(!settings.setFramePacingMode(bad), "unsupported pacing mode accepted");
        require(changed == 1 && settings.resetFramePacingMode() && settings.framePacingMode() == "latency" && changed == 2, "pacing reset failed");
        require(settings.videoScaleMode() == "fill" && settings.load("pc", "game") == game && settings.audioSettings() == audio
            && settings.streamDefaults() == defaults, "pacing reset crossed scope");
        require(settings.setFramePacingMode("balanced") && settings.resetVideoScaleMode() && settings.resetStreamDefaults()
            && settings.resetAudioSettings() && settings.reset("pc", "game") && settings.framePacingMode() == "balanced", "other resets erased pacing");
        QSettings corrupt(path, QSettings::IniFormat); corrupt.setValue("Video/v1/framePacing", true); corrupt.sync();
        require(settings.framePacingMode() == "latency", "corrupt pacing enabled buffering");
        QFile file(directory.filePath("pacing-blocked")); require(file.open(QIODevice::WriteOnly), "failure fixture failed"); file.close();
        DeckPlaySettings blocked(file.fileName() + "/settings.ini");
        require(!blocked.setFramePacingMode("balanced") && blocked.framePacingMode() == "latency", "failed pacing write appeared applied");
    }
    {
        const auto path=directory.filePath("video.ini");
        DeckPlaySettings video(path);
        int notifications=0; QObject::connect(&video,&DeckPlaySettings::videoScaleModeChanged,[&] { ++notifications; });
        const auto audio=video.audioSettings(), defaults=video.streamDefaults();
        require(video.saveChoice("pc","game",{{"fps",90}}),"scaling scope fixture failed");
        const auto game=video.load("pc","game");
        require(video.videoScaleMode()=="fit","scaling default is not Fit");
        for (const auto* mode : {"fill","stretch","fit"})
            require(video.setVideoScaleMode(mode) && DeckPlaySettings(path).videoScaleMode()==mode,"scaling did not persist");
        for (const auto* invalid : {"","FIT","unknown"," fit "})
            require(!video.setVideoScaleMode(invalid),"unknown scaling accepted");
        require(notifications==3 && video.setVideoScaleMode("fill") && video.resetVideoScaleMode() && video.videoScaleMode()=="fit" && notifications==5,
            "scaling reset/notification failed");
        require(video.load("pc","game")==game && video.audioSettings()==audio && video.streamDefaults()==defaults,
            "scaling reset crossed scopes");
        require(video.setVideoScaleMode("stretch") && video.reset("pc","game") && video.resetStreamDefaults() && video.resetAudioSettings()
            && video.videoScaleMode()=="stretch","unrelated reset erased scaling");
        QSettings corrupt(path,QSettings::IniFormat); corrupt.setValue("Video/v1/scaleMode",true); corrupt.sync();
        require(video.videoScaleMode()=="fit","corrupt scaling value enabled another mode");
        QFile blocker(directory.filePath("video-blocked")); require(blocker.open(QIODevice::WriteOnly),"scaling failure fixture"); blocker.close();
        DeckPlaySettings blocked(directory.filePath("video-blocked/settings.ini"));
        require(!blocked.setVideoScaleMode("fill") && blocked.videoScaleMode()=="fit","failed scaling save appeared applied");
    }
    {
        DeckPlaySettings art(directory.filePath("art.ini"));
        const QVariantMap center{{"scale",1.0},{"x",0.5},{"y",0.5}}, host{{"scale",1.4},{"x",0.1},{"y",0.9}}, custom{{"scale",2.5},{"x",0.75},{"y",0.25}};
        require(art.logoPlacement("pc","game",host)==host, "host logo fallback ignored");
        require(art.saveLogoPlacement("pc","game",custom,host,host), "logo layout not saved");
        require(DeckPlaySettings(directory.filePath("art.ini")).logoPlacement("pc","game",host)==custom, "logo layout lost on restart");
        require(art.logoPlacement("other","game",host)==host && art.logoPlacement("pc","other",host)==host, "logo layout leaked across PCs or games");
        require(!art.saveLogoPlacement("pc","game",center,host,host) && art.logoPlacement("pc","game",host)==custom, "stale logo edit overwrote saved layout");
        for (const auto& bad : QList<QVariant>{true,"1.0",QVariant{},std::numeric_limits<double>::quiet_NaN(),std::numeric_limits<double>::infinity(),-0.1,4.01}) {
            auto invalid=custom; invalid["scale"]=bad;
            require(!art.saveLogoPlacement("pc","game",invalid,custom,host), "invalid logo scale accepted");
        }
        for (const auto* field : {"x","y"}) {
            auto invalid=custom; invalid[field]=1.01;
            require(!art.saveLogoPlacement("pc","game",invalid,custom,host), "out-of-bounds logo position accepted");
        }
        auto extra=custom; extra["host"]="pc";
        require(!art.saveLogoPlacement("pc","game",extra,custom,host) && !art.saveLogoPlacement("","game",custom,center), "invalid logo record/identity accepted");
        require(art.reset("pc","game") && art.resetStreamDefaults() && art.logoPlacement("pc","game",host)==custom, "stream reset erased artwork layout");
        require(art.saveLogoPlacement("pc","game",center,custom,host) && art.logoPlacement("pc","game",host)==center, "center reset did not override host layout");
        QFile blocker(directory.filePath("blocked-art")); require(blocker.open(QIODevice::WriteOnly), "logo write failure fixture"); blocker.close();
        DeckPlaySettings blocked(directory.filePath("blocked-art/settings.ini"));
        require(!blocked.saveLogoPlacement("pc","game",custom,center) && blocked.logoPlacement("pc","game")==center, "failed logo save changed cached settings");
    }
    DeckPlaySettings settings(file);
    settings.setVideoDecodeSupport({.h264 = {4096, 4096}, .hevc = {1920, 1200}, .main10 = {4096, 4096}});
    const auto defaults = DeckPlayConfiguration{}.toMap();
    auto automaticCodec = defaults; automaticCodec["videoCodec"] = "auto";
    const QVariantMap bothCodecs{{"h264", true}, {"hevc", true}};
    auto codecPlan = settings.streamPlan(automaticCodec, bothCodecs, {});
    require(codecPlan.value("playable").toBool() && codecPlan.value("videoLabel") == "HEVC · SDR" &&
        codecPlan.value("configuration").toMap().value("videoCodec") == "hevc", "Auto did not review HEVC SDR");
    require(automaticCodec.value("videoCodec") == "auto", "review overwrote Auto preference");
    require(settings.streamPlan(automaticCodec, {}, {}).value("videoLabel") == "H.264 · SDR", "legacy host lost Auto fallback");
    auto forcedHevc = defaults; forcedHevc["videoCodec"] = "hevc";
    require(!settings.streamPlan(forcedHevc, {}, {}).value("playable").toBool(), "explicit HEVC silently fell back");
    require(!settings.streamPlan(forcedHevc, bothCodecs, {}, {}, true).value("playable").toBool(), "Space requested HEVC");
    require(settings.streamPlan(automaticCodec, bothCodecs, {}, {}, true).value("videoLabel") == "H.264 · SDR", "Space Auto upgraded codec");
    require(settings.streamPlan(forcedHevc, {{"h264", false}, {"hevc", true}}, {}).value("playable").toBool(), "HEVC-only PC rejected");
    require(!DeckPlaySettings{}.streamPlan(defaults, bothCodecs, {}).value("playable").toBool(), "unprobed decoder allowed playback");
    require(settings.saveChoice("codec-pc", "game", {{"videoCodec", "auto"}}), "codec choice not saved");
    require(DeckPlaySettings(file).load("codec-pc", "game").value("configuration").toMap().value("videoCodec") == "auto" &&
        settings.load("codec-pc", "another").value("configuration").toMap().value("videoCodec") == "h264", "codec choice lost scope/persistence");
    require(settings.resetChoice("codec-pc", "game", "videoCodec") &&
        settings.load("codec-pc", "game").value("configuration").toMap().value("videoCodec") == "h264", "codec reset lost compatible default");
    for (const auto& invalid : QList<QVariant>{"main10", "av1", "HEVC", "", true, 256}) {
        auto value = defaults; value["videoCodec"] = invalid;
        require(!settings.save("codec-pc", "game", value), "unsupported codec persisted");
    }
    auto oldRecord = defaults; oldRecord.remove("videoCodec");
    require(DeckPlayConfiguration::fromMap(oldRecord)->videoCodec == "h264", "old record silently upgraded codec");
    const auto chosen = DeckPlayConfiguration{1920, 1080, 30, 30000, "positions", "headless_stream"}.toMap();
    require(settings.keepInStep("one") == "off" && !settings.saveKeepInStep(" ", "on") && !settings.saveKeepInStep("one", "yes"), "invalid sync setting admitted");
    require(settings.saveKeepInStep("one", "on") && DeckPlaySettings(file).keepInStep("one") == "on" && settings.keepInStep("two") == "off", "sync persistence lost PC scope");
    require(settings.saveKeepInStep("one", "paused") && DeckPlaySettings(file).keepInStep("one") == "paused", "pending sync state lost on restart");
    const auto audioDefaults = DeckAudioConfiguration{}.toMap();
    const auto surround = DeckAudioConfiguration{8, true}.toMap();
    {
        DeckPlaySettings global(directory.filePath("stream-defaults.ini"));
        const auto initial = global.streamDefaults();
        require(global.saveChoice("one", "game", {{"fps", 90}}), "cannot prepare stream override");
        auto imported = global.defaultsFromHost("1920x1080x30", 30000);
        require(imported && global.saveStreamDefaults(*imported), "supported host profile refused");
        require(global.load("one", "game").value("configuration").toMap().value("fps") == 90 &&
            global.load("two", "other").value("configuration").toMap().value("fps") == 30, "defaults replaced game overrides or lost device scope");
        require(global.resetChoice("one", "game", "fps") && global.load("one", "game").value("configuration").toMap().value("fps") == 30,
            "choice reset ignored current stream defaults");
        require(global.defaultsFromHost("", 40000)->value("width") == 1920 && global.defaultsFromHost("1280x720x60", 0)->value("bitrateKbps") == 30000,
            "partial host profile removed unspecified local defaults");
        require(!global.defaultsFromHost("1920x1080x59.94", 30000) && !global.defaultsFromHost("8192x4320x60", 30000) &&
            !global.defaultsFromHost("1280x800x60", 300001) && !global.defaultsFromHost("", 0), "unsupported host profile partly imported");
        auto extra = *imported; extra["hostId"] = "one";
        require(!global.saveStreamDefaults(extra) && global.streamDefaults() == *imported, "malformed defaults changed preference");
        require(global.resetStreamDefaults() && global.streamDefaults() == initial, "device defaults reset failed");
        QSettings corrupt(directory.filePath("stream-defaults.ini"), QSettings::IniFormat);
        corrupt.setValue("Stream/v1/defaults", QVariantMap{{"fps", 1000}}); corrupt.sync();
        require(global.streamDefaults() == initial, "corrupt global profile became stream configuration");
    }
    {
        const auto custom = DeckPlayConfiguration{2560, 1440, 45, 27500}.toMap();
        require(settings.save("custom-host", "game", custom), "custom profile was rejected");
        require(DeckPlaySettings(file).load("custom-host", "game").value("configuration").toMap() == custom, "custom profile lost on restart");
        require(settings.streamPlan(custom, {}, {}).value("playable").toBool(), "supported custom resolution/rate rejected");
        auto hevc = custom; hevc["videoCodec"] = "hevc";
        require(!settings.streamPlan(hevc, bothCodecs, {}).value("playable").toBool(), "custom size bypassed codec-specific decoder limit");
        const auto profile = settings.defaultsFromHost("2560x1440x45", 27500);
        require(profile && profile->value("fps") == 45 && profile->value("bitrateKbps") == 27500, "custom Polaris profile was partly imported");
        for (const auto& size : {QPair{319,240}, QPair{320,239}, QPair{4098,2160}, QPair{1921,1080}, QPair{1920,1081}}) {
            auto invalid = custom; invalid["width"] = size.first; invalid["height"] = size.second;
            require(!DeckPlayConfiguration::fromMap(invalid), "invalid custom dimensions accepted");
        }
        const QVariantMap hints{{"available",true},{"choices",QVariantList{QVariantMap{{"width",2560},{"height",1440},{"advanced",true},{"recommended",true}}}}};
        const auto list = settings.streamPlan(custom, {}, hints).value("resolutions").toList();
        require(list.size() == 5 && list.back().toMap().value("recommended").toBool(), "advanced recommendation missing or duplicated");
        require(settings.resetChoice("custom-host", "game", "resolution"), "custom resolution reset failed");
        const auto reset = settings.load("custom-host", "game").value("configuration").toMap();
        require(reset.value("width") == 1280 && reset.value("fps") == 45 && reset.value("bitrateKbps") == 27500, "custom reset crossed choice scope");
    }
    require(settings.rumbleEnabled(), "rumble default differs from Android");
    require(settings.setRumbleEnabled(false) && !DeckPlaySettings(file).rumbleEnabled(), "rumble did not survive restart");
    for (const auto& bad : QList<QVariant>{"true", 1, QVariant{}})
        require(!settings.setRumbleEnabled(bad) && !settings.rumbleEnabled(), "malformed rumble setting changed preference");
    require(settings.audioSettings() == audioDefaults, "audio defaults differ from Android");
    for (const auto& bad : QList<QVariant>{true, "6", 0, 1, 5, 7, 6.5, -1}) {
        auto values = surround; values["channels"] = bad;
        require(!settings.saveAudioSettings(values), "malformed channel preference accepted");
    }
    for (const auto& bad : QList<QVariant>{"true", 1, QVariant{}}) {
        auto values = surround; values["playHostAudio"] = bad;
        require(!settings.saveAudioSettings(values), "malformed host-audio preference accepted");
    }
    auto extraAudio = surround; extraAudio["hostId"] = "other";
    require(!settings.saveAudioSettings(extraAudio) && !settings.saveAudioSettings({{"channels", 6}}), "extra/incomplete audio preference accepted");
    for (const int channels : {2, 6, 8}) {
        const auto value = DeckAudioConfiguration{channels, true}.toMap();
        require(settings.saveAudioSettings(value) && DeckPlaySettings(file).audioSettings() == value, "audio choice lost on restart");
    }
    require(settings.save("audio-pc", "game", chosen) && settings.setDefaultFaceButtonLayout("positions"), "audio scope fixture failed");
    require(settings.reset("audio-pc", "game") && settings.audioSettings() == surround, "game reset changed device audio");
    require(settings.save("audio-pc", "game", chosen) && settings.resetAudioSettings(), "audio reset failed");
    require(!settings.rumbleEnabled(), "game/audio reset changed rumble");
    require(settings.resetRumble() && settings.rumbleEnabled() && settings.defaultFaceButtonLayout() == "positions" &&
        settings.load("audio-pc", "game").value("configuration").toMap() == chosen, "rumble reset changed another scope");
    {
        QSettings corrupt(file, QSettings::IniFormat);
        corrupt.setValue("Input/v1/rumble", QVariantMap{{"enabled", "false"}}); corrupt.sync();
        require(settings.rumbleEnabled(), "corrupt rumble record was coerced into a preference");
    }
    require(settings.resetRumble(), "cannot restore rumble record");
    require(settings.audioSettings() == audioDefaults && settings.defaultFaceButtonLayout() == "positions" &&
        settings.load("audio-pc", "game").value("configuration").toMap() == chosen, "audio reset changed input/game choices");
    require(settings.setDefaultFaceButtonLayout("labels") && settings.saveAudioSettings(surround), "cannot restore audio fixture");
    const auto wide = DeckPlayConfiguration{1920, 1200, 60, 20000}.toMap();
    require(DeckPlayConfiguration::fromMap(wide).has_value(), "16:10 detailed resolution rejected");
    const QVariantMap lowRate{{"valid", true}, {"h264", true}, {"maxFps", 30}};
    const QVariantMap planner{{"available", true}, {"choices", QVariantList{QVariantMap{
        {"width", 1920}, {"height", 1200}, {"recommended", true}, {"detail", "Preserve aspect ratio."}}}}};
    auto plan = settings.streamPlan(wide, lowRate, planner);
    require(plan.value("playable").toBool() && plan.value("configuration").toMap().value("fps") == 30 &&
        !plan.value("adjustment").toString().isEmpty(), "host frame limit not reflected in effective plan");
    require(wide.value("fps") == 60 && plan.value("rates").toList().size() == 1, "planning changed preferences or offered unsupported rate");
    require(plan.value("resolutions").toList().back().toMap().value("recommended").toBool(), "PC recommendation missing");
    require(settings.save("plan", "game", wide), "cannot save profile");
    require(settings.load("plan", "game").value("configuration").toMap() == wide, "adjustment overwrote saved preference");
    plan = settings.streamPlan(wide, {{"maxFps", 240}}, {});
    require(plan.value("configuration").toMap() == wide && plan.value("rates").toList().size() == 2,
        "host capabilities exposed an unimplemented client rate or lost preference");
    for (const auto& unavailable : {QVariantMap{{"valid", false}}, QVariantMap{{"h264", false}}, QVariantMap{{"maxFps", 20}}}) {
        plan = settings.streamPlan(defaults, unavailable, {});
        require(!plan.value("playable").toBool() && !plan.value("reason").toString().isEmpty(), "unsupported stream remained playable");
    }
    require(settings.streamPlan(defaults, {}, {}).value("playable").toBool(), "legacy host lost basic H.264 stream");
    auto highRate = defaults; highRate["fps"] = 90;
    require(DeckPlayConfiguration::fromMap(highRate).has_value(), "90 fps preference rejected");
    for (const double hz : {0.0, -1.0, 60.0, 87.9, 88.0, 89.94, 90.0, 120.0, 1001.0,
            std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()}) {
        const auto result = settings.streamPlan(highRate, {{"maxFps", 120}}, {}, {{"known", true}, {"refreshHz", hz}});
        const bool accepts90 = std::isfinite(hz) && hz >= 88 && hz <= 1000;
        require(result.value("configuration").toMap().value("fps").toInt() == (accepts90 ? 90 : hz == 87.9 ? 87 : 60),
            "display threshold did not match effective FPS");
        require(result.value("rates").toList().size() == (accepts90 || hz == 87.9 ? 3 : 2), "unsupported display FPS offered");
        require(result.value("adjustment").toString().isEmpty() == accepts90, "display adjustment was hidden");
    }
    const QVariantMap fastDisplay{{"known", true}, {"refreshHz", 90}};
    for (const auto& host : {QVariantMap{}, QVariantMap{{"maxFps", 60}}}) {
        const auto result = settings.streamPlan(highRate, host, {}, fastDisplay);
        require(result.value("configuration").toMap().value("fps") == 60 && result.value("rates").toList().size() == 2,
            "fast display overrode unknown/limited host rate");
    }
    for (const int hz : {40,45,50,72,75}) {
        const auto customPlan = settings.streamPlan(highRate, {{"maxFps",120}}, {}, {{"known",true},{"refreshHz",hz}});
        require(customPlan.value("configuration").toMap().value("fps")==hz && customPlan.value("rates").toList().back().toMap().value("fps")==hz,
            "current nonstandard display mode did not bound the stream");
        require(highRate.value("fps")==90,"display fallback rewrote preference");
    }
    require(settings.save("fast", "game", highRate) && settings.load("fast", "game").value("configuration").toMap() == highRate,
        "saved high FPS lost during fallback planning");
    require(settings.defaultFaceButtonLayout() == "labels", "new device lost label default");
    require(!settings.setDefaultFaceButtonLayout("default") && !settings.setDefaultFaceButtonLayout("sideways"),
        "invalid device default persisted");
    require(settings.setDefaultFaceButtonLayout("positions"), "device default refused");
    require(settings.load("pc-one", "game").value("configuration").toMap() == defaults, "new game lost defaults");
    require(settings.save("pc-one", "game", chosen), "valid configuration refused");
    DeckPlaySettings restarted(file);
    require(restarted.defaultFaceButtonLayout() == "positions", "device default lost on restart");
    require(restarted.load("pc-one", "game").value("configuration").toMap() == chosen
        && restarted.load("pc-one", "game").value("custom").toBool(), "configuration lost on restart");
    require(restarted.load("pc-two", "game").value("configuration").toMap() == defaults, "settings crossed PC boundary");
    require(restarted.load("pc-one", "other-game").value("configuration").toMap() == defaults, "settings crossed game boundary");
    require(settings.save("a/b", "c", chosen) && settings.save("a", "b/c", defaults), "structured keys refused");
    require(settings.load("a/b", "c").value("configuration").toMap() == chosen, "host/game key collision");
    require(!settings.save("", "game", chosen) && !settings.save("host", "game-empty-state", chosen), "empty selection persisted");

    for (const auto& bad : QList<QVariant>{true, "60", 29.5, -1, 0, 120, 1e30,
            std::numeric_limits<double>::quiet_NaN()}) {
        auto values = chosen;
        values["fps"] = bad;
        require(!settings.save("pc-one", "game", values), "malformed/unsupported fps persisted");
    }
    auto values = chosen;
    values["height"] = 801;
    require(!DeckPlayConfiguration::fromMap(values), "unsupported resolution combination accepted");
    values = chosen; values["bitrateKbps"] = 300001;
    require(!DeckPlayConfiguration::fromMap(values), "unsupported bitrate accepted");
    values = chosen; values["hostId"] = "other-host";
    require(!DeckPlayConfiguration::fromMap(values), "transport/identity field accepted in settings");
    values = chosen; values.remove("width");
    require(!DeckPlayConfiguration::fromMap(values), "partial configuration accepted");
    for (const auto& bad : QList<QVariant>{true, 1, "", "sideways", " Positions "}) {
        values = chosen; values["faceButtonLayout"] = bad;
        require(!settings.save("pc-one", "game", values), "invalid face button layout persisted");
    }
    auto legacy = chosen;
    legacy.remove("launchMode");
    require(DeckPlayConfiguration::fromMap(legacy)->launchMode == "default", "five-field record lost host default");
    legacy.remove("faceButtonLayout");
    require(DeckPlayConfiguration::fromMap(legacy)->faceButtonLayout == "default", "legacy values lost inheritance");
    // Simulate an on-disk record from the prior four-field schema.
    {
        QSettings old(file, QSettings::IniFormat);
        for (auto key : old.allKeys()) if (key.startsWith("PlaySetup/")) {
            old.remove(key);
            old.setValue(key.replace("PlaySetup/v2/", "PlaySetup/v1/"), legacy);
        }
        old.sync();
    }
    auto inherited = legacy; inherited["faceButtonLayout"] = "default"; inherited["launchMode"] = "default";
    require(restarted.load("pc-one", "game").value("configuration").toMap() == inherited,
        "legacy saved stream choices did not survive upgrade");
    require(settings.save("pc-one", "game", chosen) && settings.save("a/b", "c", chosen), "cannot restore overrides");
    for (const auto& bad : QList<QVariant>{true, 1, "", "headless_dongle", "headless", "desktop_display&appid=9"}) {
        values = chosen; values["launchMode"] = bad;
        require(!settings.save("pc-one", "game", values), "invalid or host-wide launch override persisted");
    }
    require(settings.load("pc-one", "game").value("configuration").toMap() == chosen, "rejection changed prior settings");
    require(settings.reset("pc-one", "game") && !settings.load("pc-one", "game").value("custom").toBool(), "reset retained override");
    require(settings.defaultFaceButtonLayout() == "positions"
        && settings.load("pc-one", "game").value("configuration").toMap() == defaults,
        "game reset cleared device default or lost inheritance");
    require(settings.load("a/b", "c").value("configuration").toMap() == chosen, "reset cleared another game");
    require(settings.saveChoice("independent", "game", {{"fps", 30}}), "independent FPS choice refused");
    auto independent = defaults; independent["fps"] = 30;
    auto loaded = DeckPlaySettings(file).load("independent", "game");
    require(loaded.value("configuration").toMap() == independent && loaded.value("overrides").toMap().value("fps").toBool()
        && !loaded.value("overrides").toMap().value("resolution").toBool(), "saving FPS overrode unrelated defaults");
    require(settings.saveChoice("independent", "game", {{"width", 1920}, {"height", 1080}})
        && settings.saveChoice("independent", "game", {{"faceButtonLayout", "positions"}}), "independent choices refused");
    require(settings.resetChoice("independent", "game", "resolution"), "per-field reset failed");
    independent["faceButtonLayout"] = "positions";
    require(DeckPlaySettings(file).load("independent", "game").value("configuration").toMap() == independent,
        "resolution reset changed FPS or face buttons");
    require(settings.resetChoice("independent", "game", "fps") && settings.saveChoice("independent", "game", {{"faceButtonLayout", "default"}})
        && !settings.load("independent", "game").value("custom").toBool(), "last field reset did not restore inheritance");
    require(settings.saveChoice("independent", "space.arcade.7", {{"fps", 30}})
        && !settings.load("independent", "space.lounge.7").value("custom").toBool(), "settings crossed Space identity");
    for (const auto& bad : {QVariantMap{{"width", 1920}}, QVariantMap{{"fps", 30}, {"bitrateKbps", 10000}},
            QVariantMap{{"fps", "30"}}, QVariantMap{{"launchMode", "desktop_display&appid=7"}}, QVariantMap{{"hostId", "other"}}})
        require(!settings.saveChoice("independent", "game", bad), "invalid choice mutated settings");
    require(!settings.resetChoice("independent", "game", "hostId") && !settings.resetChoice("", "game", "fps"), "invalid reset accepted");
    // A v2 reset must remain authoritative even when a v1 record still exists.
    require(!DeckPlaySettings(file).load("pc-one", "game").value("custom").toBool(), "reset resurrected a legacy override");
    QFile saved(file);
    require(saved.open(QIODevice::ReadOnly), "settings file missing");
    const auto data = saved.readAll();
    require(!data.contains("pc-one") && !data.contains("a/b"), "settings keys exposed host/game identifiers");
    saved.close();
    // Corrupt stored values cannot silently become launch parameters.
    {
        QSettings corrupt(file, QSettings::IniFormat);
        for (const auto& key : corrupt.allKeys()) corrupt.setValue(key, QVariantMap{{"width", -1}});
        corrupt.sync();
    }
    require(settings.load("a/b", "c").value("configuration").toMap() == defaults
        && !settings.load("a/b", "c").value("custom").toBool(), "corrupt stored configuration used");
    require(settings.defaultFaceButtonLayout() == "labels", "corrupt device layout used");
    require(settings.audioSettings() == audioDefaults, "corrupt stored audio settings used");
    require(settings.keepInStep("one") == "off", "corrupt sync value enabled writes");
    DeckPlaySettings unwritable(directory.path());
    require(!unwritable.saveKeepInStep("one", "on") && unwritable.keepInStep("one") == "off", "failed sync save enabled writes");
    require(!unwritable.saveAudioSettings(surround) && unwritable.audioSettings() == audioDefaults,
        "failed audio write reported success or leaked cached values");
    require(!unwritable.save("host", "game", chosen), "failed write reported success");
    require(!unwritable.saveChoice("host", "game", {{"fps", 30}}) && !unwritable.resetChoice("host", "game", "fps"),
        "failed choice save/reset reported success");
    require(!unwritable.setDefaultFaceButtonLayout("positions") && unwritable.defaultFaceButtonLayout() == "labels",
        "failed device default write leaked through cache");
    require(!unwritable.setRumbleEnabled(false) && unwritable.rumbleEnabled() && !unwritable.resetRumble(),
        "failed rumble write/reset reported success or leaked cache state");
    require(unwritable.load("host", "game").value("configuration").toMap() == defaults
        && !unwritable.load("host", "game").value("custom").toBool(), "failed write leaked through the settings cache");
    std::cout << "Play settings passed: validation, persistence, scope, reset, corruption and write failure\n";
}
