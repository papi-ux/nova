#include "runtime/deck_hud_host.h"
#include "polaris/deck_session_events.h"
#include "polaris/deck_doctor.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
template<class F> void until(F predicate) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < 2000) QThread::msleep(1);
    require(predicate(), "observer timed out");
}
DeckHudHostContext context{17, "private-game", "private-token"};
QJsonObject envelope() {
    return {{"streaming_active", true}, {"owned_by_client", true}, {"client_role", "owner"},
        {"game_id", 17}, {"game_uuid", "private-game"}, {"session_token", "private-token"},
        {"session_generation", 41}, {"app_session_id", "fixture-session"},
        {"controls", QJsonObject{{"host_tuning_allowed", false}}},
        {"doctor", QJsonObject{{"version", 2}, {"result_id", "private-result"}, {"status", "ok"},
            {"severity", "info"}, {"traffic_light", "green"}, {"primary_issue", "none"}}}};
}
DeckHostTelemetry parse(const QJsonObject& o) {
    auto value = parseHostTelemetry(QJsonDocument(o).toJson().toStdString());
    require(value.has_value(), "valid envelope rejected"); return *value;
}
QVariantMap view(const QJsonObject& o) { return DeckHudHostReducer(context).accept(parse(o)); }
DeckPolarisResult<DeckHostTelemetry> success(const DeckHostTelemetry& sample) {
    return {DeckPolarisRequestStatus::Ok, 200, {}, sample};
}
void parserAndReducer() {
    for (const QJsonValue port : {QJsonValue(-1), QJsonValue(0), QJsonValue(65536), QJsonValue(1.5), QJsonValue("47990"), QJsonValue(true)}) {
        auto o = envelope(); o["events_https_port"] = port;
        require(parse(o).eventsHttpsPort == 0, "untrusted event port accepted");
    }
    auto portEnvelope = envelope(); portEnvelope["events_https_port"] = 47990;
    require(parse(portEnvelope).eventsHttpsPort == 47990, "advertised event port missing");
    QFile file(QStringLiteral(NOVA_DECK_TUNING_FIXTURE)); require(file.open(QIODevice::ReadOnly), "fixture missing");
    const auto cases = QJsonDocument::fromJson(file.readAll()).array();
    require(cases.size() == 7, "Android fixture changed");
    QJsonObject applying;
    for (const auto& entry : cases) {
        const auto c = entry.toObject(), canonical = c.value("live_tuning").toObject();
        auto o = envelope(); o["live_tuning"] = canonical;
        const auto parsed = parse(o);
        require(parsed.live && parsed.live->state == c.value("name").toString(), "Android conformance state rejected");
        if (parsed.live->state == "applying") applying = canonical;
        o["streaming_active"] = c.value("streaming");
        const auto actual = view(o);
        if (!c.value("streaming").toBool()) require(!actual.value("hostFresh").toBool(), "inactive host presented readings");
        else if (!parsed.live->supported) require(actual.value("appliedBitrate") == "--", "unsupported encoder acknowledged bitrate");
    }
    auto o = envelope(); o["live_tuning"] = applying;
    auto actual = view(o);
    require(actual.value("tuningLabel") == "Tuning: Applying" && actual.value("appliedBitrate") == "20.0M" &&
        actual.value("qualityLimit") == "20.0M", "requested bitrate substituted for encoder acknowledgement");
    const auto serialized = QJsonDocument::fromVariant(actual).toJson();
    require(!serialized.contains("private-") && !serialized.contains("fixture-") && !serialized.contains("aaaaaaaa"), "private host data escaped projection");
    // Explicit malformed canonical data must never resurrect a legacy acknowledgement.
    for (const auto& pair : std::initializer_list<std::pair<QString, QJsonValue>>{
        {"enabled", "true"}, {"supported", 1}, {"version", 2}, {"scope", "client"},
        {"configuration_revision", "bad"}, {"host_instance", " "}, {"sequence", 0}, {"sequence", 1.5},
        {"sequence", 9007199254740992.0}, {"session_generation", -1}, {"quality_limit_kbps", 2147483648.0},
        {"requested_bitrate_kbps", "14000"}, {"applied_bitrate_kbps", -1}, {"app_session_id", 4}, {"state", "surprise"}}) {
        auto invalid = applying; invalid[pair.first] = pair.second; o["live_tuning"] = invalid;
        o["adaptive_bitrate_enabled"] = true;
        const auto parsed = parse(o);
        require(parsed.livePresent && !parsed.live, "malformed canonical fields accepted");
        require(view(o).value("tuningLabel") == "Tuning: Unknown" && view(o).value("appliedBitrate") == "--", "malformed canonical fell back to legacy");
    }
    o["live_tuning"] = QJsonValue::Null;
    require(view(o).value("tuningLabel") == "Tuning: Unknown", "explicit null fell back to legacy");
    o.remove("live_tuning");
    require(view(o).value("tuningLabel") == "Tuning: On (legacy)" && view(o).value("appliedBitrate") == "--", "legacy preference fabricated encoder bitrate");
    o["tuning"] = QJsonObject{{"adaptive_bitrate_enabled", false}};
    require(view(o).value("tuningLabel") == "Tuning: Off (legacy)", "nested legacy preference lost precedence");
    require(!parseHostTelemetry("[]") && !parseHostTelemetry("{") && !parseHostTelemetry(std::string(128*1024+1, ' ')), "unbounded or invalid JSON accepted");

    for (const auto& pair : std::initializer_list<std::pair<QString, QJsonValue>>{
        {"owned_by_client", false}, {"owned_by_client", "true"}, {"client_role", "viewer"},
        {"streaming_active", false}, {"game_id", 18}, {"game_uuid", "replacement"},
        {"session_token", "replacement"}, {"shutdown_requested", true}, {"shutdown_requested", "false"}}) {
        auto invalid = envelope(); invalid[pair.first] = pair.second;
        require(view(invalid) == DeckHudHostReducer::unavailable(), "foreign or ending session accepted");
    }
    for (const QJsonValue value : {QJsonValue(true), QJsonValue("false")}) {
        auto invalid = envelope(); auto controls = invalid["controls"].toObject(); controls["shutdown_in_progress"] = value; invalid["controls"] = controls;
        require(view(invalid) == DeckHudHostReducer::unavailable(), "invalid or ending controls accepted");
    }
    auto invalid = envelope(); invalid["controls"] = QJsonObject{};
    require(!view(invalid).value("hostFresh").toBool(), "missing permission envelope accepted");
    o = envelope(); o["live_tuning"] = applying;
    for (const auto* key : {"session_generation", "app_session_id"}) {
        invalid = o; invalid[key] = key == QString("session_generation") ? QJsonValue(42) : QJsonValue("replacement");
        require(!view(invalid).value("hostFresh").toBool(), "canonical session mismatch accepted");
    }
    DeckHudHostReducer ordered(context); auto sample = parse(o);
    require(ordered.accept(sample).value("hostFresh").toBool(), "first observation rejected");
    require(!ordered.accept(sample).value("hostFresh").toBool(), "duplicate observation refreshed stale data");
    --sample.live->sequence;
    require(!ordered.accept(sample).value("hostFresh").toBool(), "old sequence accepted");
    sample.live->sequence += 2;
    require(ordered.accept(sample).value("hostFresh").toBool(), "new observation rejected");
    ++sample.live->generation; ++*sample.generation;
    require(!ordered.accept(sample).value("hostFresh").toBool(), "replaced session generation accepted");
    --sample.live->generation; --*sample.generation;
    sample.live->instance = "new-instance"; sample.live->sequence = 1;
    require(ordered.accept(sample).value("hostFresh").toBool(), "new host instance rejected");
    sample.live->instance = "fixture-host"; sample.live->sequence = 500;
    require(!ordered.accept(sample).value("hostFresh").toBool(), "retired host instance accepted");
}
void doctorDetails() {
    QFile file(QStringLiteral(NOVA_DECK_DOCTOR_FIXTURE)); require(file.open(QIODevice::ReadOnly), "Doctor fixture missing");
    const auto d = QJsonDocument::fromJson(file.readAll()).object();
    auto actual = doctorPresentation(d);
    require(actual.value("available").toBool() && actual.value("title") == "A host display override changed the requested mode" &&
        actual.value("highlight") == "Display mode override" && actual.value("group") == "HOST", "healthy display override lost its diagnosis");
    const auto serialized = QJsonDocument::fromVariant(actual).toJson();
    require(!serialized.contains("private") && !serialized.contains("endpoint") && !serialized.contains("action") &&
        !serialized.contains("result_id"), "Doctor exposed private identity or executable envelope");
    auto rows = actual.value("evidence").toList();
    require(rows.size() == 9 && rows[3].toMap().value("reading") == "Unavailable" && rows[4].toMap().value("grade") == "Observation" &&
        rows[6].toMap().value("grade") == "Observation", "unavailable media or informational watch became a failure");
    for (const auto& change : std::initializer_list<std::pair<QString, QJsonValue>>{
        {"version", "2"}, {"version", 3}, {"result_id", ""}, {"result_id", QString(257, 'x')},
        {"status", "surprise"}, {"severity", true}, {"traffic_light", "blue"}, {"evidence", "bad"}}) {
        auto bad = d; bad[change.first] = change.second;
        require(doctorPresentation(bad).isEmpty(), "malformed Doctor contract accepted");
    }
    auto bad = d; auto evidence = d.value("evidence").toArray(); evidence.append(evidence.first()); bad["evidence"] = evidence;
    require(doctorPresentation(bad).isEmpty(), "ambiguous duplicate evidence accepted");
    evidence = {}; for (int i = 0; i < 129; ++i) evidence.append(QJsonObject{}); bad["evidence"] = evidence;
    require(doctorPresentation(bad).isEmpty(), "oversized Doctor evidence accepted");
    for (int scenario = 0; scenario < 8; ++scenario) {
        auto next = d; next["primary_issue"] = "network_jitter"; next["status"] = "needs_action";
        next["severity"] = "critical"; next["traffic_light"] = "red";
        QJsonObject item{{"id", "packet_loss"}, {"status", "fail"}, {"source", "media_transport"}, {"value", 4.2}};
        if (scenario == 1) item["source"] = "control_channel";
        if (scenario == 2) item["value"] = "4.2";
        if (scenario == 3) item["value"] = 101;
        if (scenario == 4) item["status"] = "watch";
        if (scenario >= 5) { item["id"] = "latency"; item["source"] = "stream_stats"; item["value"] = 60; }
        if (scenario == 6) item["value"] = QJsonValue::Null;
        if (scenario == 7) item["source"] = "unrecognized-private-source";
        next["evidence"] = QJsonArray{item}; next["summary"] = "Lower bitrate now: private-secret";
        next["ai_doctor"] = QJsonObject{{"explanation", "private instructions"}};
        actual = doctorPresentation(next);
        const bool confirmed = scenario == 0 || scenario == 5;
        require(actual.value("title") == (confirmed ? "Network pressure is affecting this stream" : "Network readings need another check"),
            "unproven network evidence drove diagnosis");
        require(!QJsonDocument::fromVariant(actual).toJson().contains("private"), "host free text crossed Doctor boundary");
    }
    auto o = envelope(); o["doctor"] = d;
    require(view(o).value("doctor").toMap().value("available").toBool(), "owned Doctor projection missing");
    o["session_token"] = "replacement";
    require(view(o).value("doctor").toMap().isEmpty(), "foreign session retained Doctor evidence");
    o = envelope(); o["doctor"] = QJsonValue::Null; o["health"] = QJsonObject{{"doctor", d}};
    require(view(o).value("doctor").toMap().isEmpty(), "explicit invalid Doctor revived a legacy envelope");
    o.remove("doctor");
    require(view(o).value("doctor").toMap().value("available").toBool(), "valid nested Doctor was lost");
}
void manualDiagnosticsRefresh() {
    auto sample = parse(envelope());
    sample.doctor = {{"available", true}, {"title", "No confirmed issue"}};
    std::atomic<int> reads{0}, mutations{0}; std::atomic<bool> blocked{false}, release{false};
    auto observer = std::make_unique<DeckHudHostObserver>([&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const auto& stop) {
            const int call = ++reads;
            if (call == 2) { blocked = true; while (!release && !stop()) QThread::msleep(1); }
            if (call == 3) return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Timeout};
            return success(sample);
        }, [] { return true; }, [&](bool, const auto&, const auto&) { ++mutations; return DeckPolarisResult<bool>{}; }};
    }, context, DeckHudHostTiming{10000, 1000, 10000});
    until([&] { return observer->snapshot().value("canRefreshDiagnostics").toBool(); });
    require(observer->refreshDiagnostics() && !observer->refreshDiagnostics(), "manual refresh not accepted or not coalesced");
    until([&] { return blocked.load(); });
    require(observer->snapshot().value("diagnosticsRefreshing").toBool() && !observer->snapshot().value("hostFresh").toBool() &&
        observer->snapshot().value("doctor").toMap().isEmpty(), "refresh kept old readings current");
    release = true;
    until([&] { return !observer->snapshot().value("diagnosticsRefreshing").toBool(); });
    require(observer->snapshot().value("hostFresh").toBool() && mutations == 0 && reads == 2, "refresh changed host or failed to read back");
    until([&] { return observer->snapshot().value("canRefreshDiagnostics").toBool(); });
    require(observer->refreshDiagnostics(), "second refresh unavailable after cooldown");
    until([&] { return !observer->snapshot().value("diagnosticsRefreshing").toBool(); });
    require(!observer->snapshot().value("hostFresh").toBool() && observer->snapshot().value("doctor").toMap().isEmpty() &&
        mutations == 0, "failed refresh kept stale evidence");
    until([&] { return observer->snapshot().value("canRefreshDiagnostics").toBool(); });
    require(observer->refreshDiagnostics(), "failed read prevented explicit retry");
    until([&] { return observer->snapshot().value("hostFresh").toBool(); });
    require(reads == 4 && mutations == 0, "read-only retry mutated or duplicated work");
}

void doctorProvenance() {
    auto o = envelope();
    o["health"] = QJsonObject{{"grade", "degraded"}, {"host_render_limited", true}, {"primary_issue", "network_jitter"}};
    require(view(o).value("healthLabel") == "Stable", "legacy flags overrode healthy v2 Doctor");
    auto doctor = o["doctor"].toObject();
    const auto evidence = [&](const char* id, const char* state, const char* source = "") {
        doctor["evidence"] = QJsonArray{QJsonObject{{"id", id}, {"status", state}, {"source", source}}};
        o["doctor"] = doctor; return view(o);
    };
    require(evidence("encoder", "watch").value("healthLabel") == "Stable", "informational healthy watch became warning");
    require(evidence("display_mode_decision", "watch").value("healthLabel") == "Display override", "v1.4.11 display decision was hidden");
    require(evidence("encoder", "fail").value("hostTone") == "warning", "hard evidence hidden by healthy verdict");
    require(evidence("control_channel_packet_loss", "fail").value("netTone") == "stable", "control loss graded as media loss");
    require(evidence("packet_loss", "fail", "control_channel").value("netTone") == "stable", "unproven packet provenance graded as media loss");
    require(evidence("packet_loss", "watch", "media_transport").value("netTone") == "stable", "watch media evidence graded as failure");
    require(evidence("packet_loss", "fail", "media_transport").value("netTone") == "warning", "confirmed media loss hidden");
    require(evidence("latency", "fail").value("netTone") == "warning", "failed latency evidence hidden");
    require(evidence("latency", "watch").value("netTone") == "stable", "latency watch graded as failure");
    doctor.remove("evidence"); doctor["primary_issue"] = "control_channel_observation"; o["doctor"] = doctor;
    require(view(o).value("healthLabel") == "Link retries" && view(o).value("netTone") == "muted", "observation became network fault");
    doctor["primary_issue"] = "none"; doctor.remove("severity"); o["doctor"] = doctor;
    require(view(o).value("healthTone") == "warning", "partial Doctor verdict fabricated green health");
    o.remove("doctor"); o.remove("health");
    require(view(o).value("healthLabel") == "Host health unknown" && view(o).value("healthTone") == "muted", "missing health fabricated stable stream");
    o["health"] = QJsonObject{{"primary_issue", "network_jitter"}, {"grade", "watch"}};
    require(view(o).value("healthLabel") == "Network recheck", "legacy ambiguous network finding graded as confirmed loss");
}

void liveTuningSaves() {
    for (int scenario = 0; scenario < 19; ++scenario) {
        std::atomic<int> reads{0}, writes{0};
        const bool initial = scenario == 1;
        std::atomic<bool> saved{initial}, ended{false}, entered{false}, identityValid{true};
        DeckHostTelemetry sample = parse(envelope());
        sample.hostTuningAllowed = true;
        sample.livePresent = true;
        sample.live = DeckLiveTuningTelemetry{initial, true, initial ? "stable" : "off", QString(64, 'a'),
            "instance", "fixture-session", 1, 41, 20000, 14000, 20000};
        if (scenario == 12) { sample.live.reset(); sample.livePresent = false; sample.legacyTuning = false; }
        if (scenario == 13) sample.owned = false;
        if (scenario == 14) sample.live->supported = false; // The saved preference is independent of runtime support.
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            return DeckHudHostTarget{[&](const std::function<bool()>& stop) {
                const int call = ++reads; auto next = sample;
                if (next.live) {
                    next.live->sequence = call; next.live->enabled = saved;
                    next.live->state = saved ? "stable" : "off";
                    if (writes && saved != initial) next.live->revision = QString(64, 'b');
                }
                if (call >= 2) {
                    if (scenario == 2) next.hostTuningAllowed = false;
                    if (scenario == 3) next.live->revision = QString(64, 'c');
                    if (scenario == 4) next.sessionToken = "another-session";
                    if (scenario == 5) next.live->instance = "restarted-host";
                    if (scenario == 15) identityValid = false;
                    if (scenario == 16) { ++*next.generation; ++next.live->generation; }
                    if (scenario == 17) next.live.reset();
                    if (scenario == 18) next.live->sequence = 1;
                    if (scenario == 10 && call == 2) {
                        entered = true; while (!stop()) QThread::msleep(1);
                    }
                }
                if (scenario == 9 && call >= 3) return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                return success(next);
            }, [&] { return identityValid.load(); }, [&](bool enabled, const DeckLiveTuningTelemetry& observed, const std::function<bool()>& stop) {
                ++writes;
                require(enabled != initial && observed.revision == QString(64, 'a') && observed.generation == 41 &&
                    observed.appSession == "fixture-session" && observed.sequence == 2, "save lost reviewed revision/session or fresh preflight");
                if (scenario == 11) {
                    entered = true; while (!stop()) QThread::msleep(1);
                    return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                }
                if (scenario == 6) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::HttpError, 412, {}, {}};
                if (scenario == 8) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Unauthorized, 403, {}, {}};
                saved = enabled;
                if (scenario == 7) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true};
            }};
        }, context, {1000, 2000, 2000}, [&] { return ended.load(); });
        if (scenario == 12 || scenario == 13) {
            until([&] { return reads >= 1; }); QThread::msleep(20);
            require(!observer.setLiveTuningEnabled(true) && writes == 0, "legacy or foreign session allowed mutation");
            continue;
        }
        until([&] { return observer.snapshot().value("canTune").toBool(); });
        require(!observer.setLiveTuningEnabled(initial), "unchanged preference sent mutation");
        require(observer.setLiveTuningEnabled(!initial), "valid operator decision rejected");
        require(!observer.setLiveTuningEnabled(!initial), "double click queued a second save");
        if (scenario == 10 || scenario == 11) {
            until([&] { return entered.load(); }); ended = true;
            until([&] { return !observer.snapshot().value("tuningBusy").toBool(); });
            require(writes == (scenario == 11 ? 1 : 0) && !observer.snapshot().value("canTune").toBool(), "cancelled save replayed or remained eligible");
            continue;
        }
        until([&] { return !observer.snapshot().value("tuningBusy").toBool(); });
        const auto actual = observer.snapshot();
        if (scenario == 15) {
            require(writes == 0 && !actual.value("canTune").toBool(), "pairing change during preflight sent a mutation");
            continue;
        }
        const bool blocked = (scenario >= 2 && scenario <= 5) || scenario >= 16;
        require(writes == (blocked ? 0 : 1), "save was replayed or authority change ignored");
        if (blocked || scenario == 6) {
            require(saved == initial && actual.value("tuningCopy").toString().contains("changed"), "conflict hid refusal or changed preference");
        } else if (scenario == 8 || scenario == 9) {
            require(!actual.value("canTune").toBool() && !actual.value("tuningKnown").toBool(), "failed confirmation kept stale authority");
        } else {
            require(actual.value("tuningEnabled").toBool() != initial, "readback did not paint committed preference");
            require(actual.value("appliedBitrate") == (scenario == 14 ? "--" : "20.0M"), "toggle invented an encoder bitrate change");
            require(actual.value("tuningCopy").toString().contains(scenario == 7 ? "Couldn't confirm" : "turned"), "lost reply claimed confirmed success");
        }
        const auto count = writes.load(); QThread::msleep(40); require(writes == count, "mutation retried after response");
    }
}


void fixedBitrateSaves() {
    for (int scenario = 0; scenario < 16; ++scenario) {
        std::atomic<int> reads{0}, writes{0}, confirmations{0};
        std::atomic<bool> committed{false};
        DeckHostTelemetry sample = parse(envelope()); sample.hostTuningAllowed = true; sample.livePresent = true;
        sample.live = DeckLiveTuningTelemetry{true, scenario != 3, "stable", QString(64, 'a'), "instance", "fixture-session", 1, 41, 20000, 14000, 20000};
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            return DeckHudHostTarget{[&](const std::function<bool()>&) {
                auto next = sample; const int call = ++reads; next.live->sequence = call;
                if (call >= 2) {
                    if (scenario == 4) next.hostTuningAllowed = false;
                    if (scenario == 5) next.live->revision = QString(64, 'c');
                    if (scenario == 6) next.live->qualityLimit = 25000;
                    if (scenario == 7) next.sessionToken = "replacement";
                }
                if (committed) {
                    const int confirmation = ++confirmations;
                    next.live->enabled = scenario == 13;
                    next.live->qualityLimit = scenario == 14 ? 16000 : 15000;
                    next.live->revision = QString(64, 'b'); next.live->state = "off";
                    const bool delayed = (scenario == 1 && confirmation == 1) || scenario == 2;
                    next.live->applied = delayed ? 20000 : 15000;
                    if (scenario == 12) return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                    if (scenario == 15) next.live->instance = "restarted-host";
                }
                return success(next);
            }, [] { return true; }, [&](bool, const DeckLiveTuningTelemetry&, const std::function<bool()>&) {
                require(false, "fixed bitrate sent a separate tuning toggle"); return DeckPolarisResult<bool>{};
            }, [&](int kbps, const DeckLiveTuningTelemetry& observed, const std::function<bool()>&) {
                ++writes;
                require(kbps == 15000 && observed.revision == QString(64, 'a') && observed.generation == 41 && observed.appSession == "fixture-session",
                    "fixed target lost original scope");
                if (scenario == 9 || scenario == 10) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::HttpError, scenario == 9 ? 500 : 409, {}, {}};
                if (scenario == 11) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Unauthorized, 403, {}, {}};
                committed = true;
                if (scenario == 8) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true};
            }};
        }, context, {20, 200, 100, 70});
        if (scenario == 3) {
            until([&] { return observer.snapshot().value("hostFresh").toBool(); });
            require(!observer.snapshot().value("canSetBitrate").toBool() && !observer.setFixedBitrate(15000) && writes == 0, "unsupported encoder allowed fixed rate");
            continue;
        }
        until([&] { return observer.snapshot().value("canSetBitrate").toBool(); });
        require(!observer.setFixedBitrate(999) && !observer.setFixedBitrate(300001), "out-of-range bitrate accepted");
        require(observer.setFixedBitrate(15000), "valid fixed target rejected");
        require(!observer.setFixedBitrate(16000) && !observer.setLiveTuningEnabled(false), "pending fixed target admitted a competing mutation");
        if (scenario == 1 || scenario == 2) {
            until([&] { return confirmations >= 1; });
            require(observer.snapshot().value("appliedBitrate") == "20.0M", "requested target painted as applied before acknowledgement");
        }
        until([&] { return !observer.snapshot().value("tuningBusy").toBool(); });
        const auto actual = observer.snapshot();
        require(writes == (scenario >= 4 && scenario <= 7 ? 0 : 1), "fixed request replayed or changed preflight accepted");
        require(actual.value("bitrateRequestKbps") == 15000, "requested rate lost its separate label");
        const auto message = actual.value("bitrateCopy").toString();
        if (scenario == 0 || scenario == 1) {
            require(message.startsWith("Applied 15.0M") && actual.value("appliedBitrateKbps") == 15000 && !actual.value("tuningEnabled").toBool(), "encoder acknowledgement or tuning Off was not required");
        } else {
            require(!message.startsWith("Applied"), "failed/conflicting save claimed encoder acknowledgement");
            if (scenario == 2) require(message.contains("wasn't confirmed") && actual.value("appliedBitrateKbps") == 20000, "ack timeout fabricated bitrate");
            if (scenario == 8) require(message.contains("Couldn't confirm") && actual.value("appliedBitrateKbps") == 15000, "lost receipt suppressed fresh truth or claimed save confirmation");
            if (scenario == 11 || scenario == 12) require(!actual.value("canSetBitrate").toBool() && actual.value("appliedBitrateKbps") == 0, "failed resync retained fixed-rate authority");
        }
        const int count = writes; QThread::msleep(30); require(writes == count, "fixed target retried automatically");
    }
}

QJsonObject eventLive(int sequence = 1) {
    return {{"version", 1}, {"scope", "host"}, {"enabled", true}, {"supported", true}, {"state", "stable"},
        {"configuration_revision", QString(64, 'a')}, {"host_instance", "instance"}, {"app_session_id", "fixture-session"},
        {"session_generation", 41}, {"sequence", sequence}, {"quality_limit_kbps", 20000},
        {"requested_bitrate_kbps", 20000}, {"applied_bitrate_kbps", 20000}};
}
void eventParser() {
    int refreshes = 0;
    DeckSessionEventParser parser([&] { ++refreshes; });
    const auto record = [](const QByteArray& id, const QJsonObject& live, const QByteArray& ending = "\n") {
        const auto json = QJsonDocument(QJsonObject{{"session_state", "streaming"}, {"live_tuning", live}}).toJson(QJsonDocument::Compact);
        return "id: " + id + ending + "event: state" + ending + "data: " + json + ending + ending;
    };
    auto bytes = QByteArray("\xef\xbb\xbf: comment\r\n\r\n") + record("epoch:1", eventLive(), "\r\n");
    for (char c : bytes) require(parser.feed(QByteArray(1, c)), "fragmented event rejected");
    require(refreshes == 1 && parser.complete(), "first event did not resync");
    require(parser.feed(record("epoch:2", eventLive(2))) && refreshes == 1, "unchanged heartbeat caused GET churn");
    require(parser.feed(record("epoch:4", eventLive(3))) && refreshes == 2, "event gap did not resync");
    require(parser.feed(record("epoch:4", eventLive(4))) && refreshes == 3, "duplicate ID did not resync");
    require(parser.feed(record("new-epoch:1", eventLive(5), "\r")) && refreshes == 4, "new connection ID did not resync");
    require(parser.feed(record("new-epoch:2", eventLive(4))) && refreshes == 5, "regressed tuning sequence did not resync");
    auto changed = eventLive(6); changed["applied_bitrate_kbps"] = 15000;
    require(parser.feed(record("new-epoch:3", changed)) && refreshes == 6, "encoder change was not refreshed");
    require(parser.feed(record("new-epoch:4", {})) && refreshes == 7, "invalid tuning was not refreshed");
    require(parser.feed("id: new-epoch:5\nevent: session\ndata: {\ndata: \"event\":\"session_ended\"}\n\n") && refreshes == 8,
        "multiline terminal hint was not refreshed");
    require(parser.feed(record("", eventLive(7))) && refreshes == 9, "missing ID did not resync");
    for (const QByteArray bad : {QByteArray("data: []\n\n"), QByteArray("data: {\n\n"),
        QByteArray("data: {\"x\":\"\xff\"}\n\n"), QByteArray("id: ") + QByteArray(257, 'a') + "\n",
        QByteArray("data: ") + QByteArray(65537, 'a')}) {
        DeckSessionEventParser invalid([] {});
        require(!invalid.feed(bad) && !invalid.feed("\n\n"), "malformed/unbounded event did not close parser");
    }
    DeckSessionEventParser partial([] {});
    require(partial.feed("data: {}\n") && !partial.complete(), "partial event became a completed record");
    DeckSessionEventParser overflow([&] { ++refreshes; });
    const int before = refreshes;
    require(overflow.feed(record("epoch:18446744073709551615", eventLive())) &&
        overflow.feed(record("epoch:0", eventLive(2))) && refreshes == before + 2, "wrapped ID was considered consecutive");
}
void eventResynchronization() {
    for (int scenario = 0; scenario < 12; ++scenario) {
        std::atomic<int> reads{0}, listens{0}, command{0}, port{47990}, writes{0};
        std::atomic<bool> block{false}, reading{false}, release{false}, identity{true}, streamCancelled{false};
        auto sample = parse(envelope()); sample.hostTuningAllowed = true;
        sample.livePresent = true; sample.live = parseLiveTuningTelemetry(eventLive());
        auto observer = std::make_unique<DeckHudHostObserver>([&]() -> std::optional<DeckHudHostTarget> {
            return DeckHudHostTarget{[&](const std::function<bool()>& stop) {
                const int count = ++reads;
                if (block) { reading = true; while (!release && !stop()) QThread::msleep(1); }
                auto next = sample; next.live->sequence = count; next.eventsHttpsPort = scenario == 6 ? 0 : port.load();
                return success(next);
            }, [&] { return identity.load(); }, [&](bool, const auto&, const auto& stop) {
                ++writes;
                if (scenario == 9) { while (!stop()) QThread::msleep(1); return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout}; }
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true};
            }, {}, [&](int advertised, const auto& refresh, const auto& stop) {
                require(advertised == port, "event worker guessed or retained old port");
                ++listens;
                while (!stop()) {
                    const int action = command.exchange(0);
                    if (action == 1) { for (int i = 0; i < 100; ++i) refresh(); }
                    if (action == 2) {
                        if (scenario == 2) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::HttpError, 404};
                        if (scenario == 3) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::CertMismatch};
                        if (scenario == 10) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Unauthorized};
                        if (scenario == 11) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::InvalidIdentity};
                        if (scenario == 7) return DeckPolarisResult<bool>{DeckPolarisRequestStatus::MalformedBody};
                        return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout};
                    }
                    QThread::msleep(1);
                }
                // A late callback from the retired endpoint must be inert.
                refresh(); streamCancelled = true;
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout};
            }};
        }, context, DeckHudHostTiming{30, 200, 60, 100, 20});
        until([&] { return observer->snapshot().value("canTune").toBool(); });
        if (scenario == 6) {
            QThread::msleep(70); require(listens == 0 && reads >= 2, "missing advertised endpoint was guessed");
            continue;
        }
        until([&] { return listens == 1; });
        if (scenario == 0 || scenario == 8) {
            block = true;
            if (scenario == 8) require(observer->setLiveTuningEnabled(false), "preflight fixture did not queue");
            until([&] { return reading.load(); });
            command = 1;
            until([&] { return !observer->snapshot().value("hostFresh").toBool(); });
            require(!observer->setLiveTuningEnabled(false), "event invalidation retained mutation authority");
            const int before = reads; release = true;
            until([&] { return observer->snapshot().value("canTune").toBool(); });
            require(reads >= before + 1 && reads < before + 5 && writes == 0, "event burst was not coalesced or stale GET restored authority");
        } else if (scenario == 9) {
            require(observer->setLiveTuningEnabled(false), "write fixture did not queue");
            until([&] { return writes == 1; }); command = 1;
            until([&] { return !observer->snapshot().value("tuningBusy").toBool(); });
            require(writes == 1 && !observer->snapshot().value("tuningCopy").toString().contains("turned off"), "event-interrupted mutation replayed or claimed success");
        } else if (scenario == 4) {
            identity = false;
            until([&] { return streamCancelled.load(); });
            // The event callback marks cancellation before returning. The
            // observer still has to join it and publish its unavailable state.
            // Wait for observation to end too, so stale-age expiry cannot pass.
            until([&] { const auto view = observer->snapshot(); return !view.value("canTune").toBool() &&
                !view.value("canRefreshDiagnostics").toBool(); });
            require(!observer->setLiveTuningEnabled(false) && writes == 0, "changed identity kept event authority");
        } else if (scenario == 5) {
            port = 47991;
            until([&] { return listens == 2; });
            until([&] { return observer->snapshot().value("canTune").toBool(); });
        } else {
            command = 2;
            if (scenario == 1) until([&] { return listens >= 2; });
            else if (scenario == 3 || scenario >= 10) {
                until([&] { return !observer->snapshot().value("canTune").toBool(); });
                QThread::msleep(90);
                require(!observer->setLiveTuningEnabled(false) && listens == 1, "event pin failure restored authority or retried");
            } else {
                QThread::msleep(100);
                require(listens == 1 && reads >= 3 && observer->snapshot().value("canTune").toBool(), "unsupported/invalid SSE did not fall back to paired polling");
            }
        }
        QElapsedTimer elapsed; elapsed.start(); observer.reset();
        require(elapsed.elapsed() < 500, "event teardown waited for a socket deadline");
    }
}

void encoderConfirmationAfterEvent() {
    std::atomic<int> reads{0}, writes{0};
    std::atomic<bool> eventReady{false}, send{false}, releaseOld{false}, releaseFresh{false};
    auto sample = parse(envelope()); sample.hostTuningAllowed = true;
    sample.eventsHttpsPort = 47990; sample.livePresent = true; sample.live = parseLiveTuningTelemetry(eventLive());
    DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const auto& stop) {
            const int count = ++reads;
            if (count == 3) while (!releaseOld && !stop()) QThread::msleep(1);
            if (count == 4) while (!releaseFresh && !stop()) QThread::msleep(1);
            auto next = sample; next.live->sequence = count;
            if (writes) { next.live->enabled = false; next.live->qualityLimit = next.live->applied = 15000; }
            return success(next);
        }, [] { return true; }, {}, [&](int, const auto&, const auto&) {
            ++writes; return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true};
        }, [&](int, const auto& refresh, const auto& stop) {
            eventReady = true;
            while (!stop()) { if (send.exchange(false)) refresh(); QThread::msleep(1); }
            return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout};
        }};
    }, context, DeckHudHostTiming{10000, 1000, 10000, 1000});
    until([&] { return eventReady && observer.snapshot().value("canSetBitrate").toBool(); });
    require(observer.setFixedBitrate(15000), "event ack fixture did not queue");
    until([&] { return reads == 3; }); send = true;
    until([&] { return observer.snapshot().value("hostRefreshing").toBool(); }); releaseOld = true;
    until([&] { return reads == 4; });
    const auto waiting = observer.snapshot();
    require(waiting.value("bitrateBusy").toBool() && waiting.value("appliedBitrateKbps") == 0 &&
        !waiting.value("bitrateCopy").toString().startsWith("Applied"), "event-invalidated GET acknowledged encoder");
    releaseFresh = true;
    until([&] { return !observer.snapshot().value("bitrateBusy").toBool(); });
    require(writes == 1 && observer.snapshot().value("appliedBitrateKbps") == 15000 &&
        observer.snapshot().value("bitrateCopy").toString().startsWith("Applied"), "fresh encoder resync lost pending confirmation or replayed write");
}

void observerBoundaries() {
    const auto sample = parse(envelope());
    std::atomic<int> reads{0}; std::atomic<bool> blocked{false}, cancelled{false};
    const auto mainThread = QThread::currentThread();
    auto observer = std::make_unique<DeckHudHostObserver>([&]() -> std::optional<DeckHudHostTarget> {
        require(QThread::currentThread() != mainThread, "factory ran on GUI thread");
        return DeckHudHostTarget{[&](const std::function<bool()>& stop) {
            require(QThread::currentThread() != mainThread, "HTTP ran on GUI thread");
            if (++reads > 1) { blocked = true; while (!stop()) QThread::msleep(1); cancelled = true; }
            return success(sample);
        }, [] { return true; }};
    }, context, DeckHudHostTiming{20, 100, 40});
    until([&] { return observer->snapshot().value("hostFresh").toBool(); });
    until([&] { return blocked.load(); });
    until([&] { return !observer->snapshot().value("hostFresh").toBool(); });
    QElapsedTimer elapsed; elapsed.start(); observer.reset();
    require(cancelled && elapsed.elapsed() < 500, "teardown did not cancel pending observer read");
    const auto stoppedReads = reads.load(); QThread::msleep(40); require(reads == stoppedReads, "observer polled after teardown");
    for (int scenario = 0; scenario < 5; ++scenario) {
        reads = 0; std::atomic<bool> identity{scenario != 0};
        DeckHudHostObserver failing([&]() -> std::optional<DeckHudHostTarget> {
            return DeckHudHostTarget{[&](const std::function<bool()>&) {
                const int count = ++reads;
                if (scenario == 1) identity = false;
                if (count == 1) return success(sample);
                return DeckPolarisResult<DeckHostTelemetry>{scenario == 2 ? DeckPolarisRequestStatus::Unauthorized : DeckPolarisRequestStatus::HttpError,
                    scenario == 2 ? 403 : scenario == 3 ? 404 : 500, {}, {}};
            }, [&] { return identity.load(); }};
        }, context, {30, 500, 60});
        if (scenario == 0) { QThread::msleep(60); require(reads == 0, "invalid identity reached network"); }
        else if (scenario == 1) { until([&] { return reads == 1; }); QThread::msleep(30); require(!failing.snapshot().value("hostFresh").toBool(), "identity changed during request but result published"); }
        else {
            until([&] { return failing.snapshot().value("hostFresh").toBool(); });
            until([&] { return reads >= 2; });
            until([&] { return !failing.snapshot().value("hostFresh").toBool(); });
            QThread::msleep(130);
            require(scenario == 4 ? reads >= 3 : reads == 2, "observer permanent/transient failure policy wrong");
        }
    }
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    parserAndReducer(); eventParser(); eventResynchronization(); encoderConfirmationAfterEvent(); doctorProvenance(); doctorDetails(); manualDiagnosticsRefresh(); observerBoundaries(); liveTuningSaves(); fixedBitrateSaves();
    std::cout << "HUD host telemetry passed: Android contract, session authority, provenance, staleness, worker isolation and cancellation\n";
}
