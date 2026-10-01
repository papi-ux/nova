#include "runtime/deck_doctor_actions.h"
#include "runtime/deck_hud_host.h"
#include "deck_doctor_fixture.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QFile>
#include <QDateTime>
#include "polaris/deck_doctor.h"
#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::polaris;
using namespace nova::deck::runtime;
using namespace doctor_fixture;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
template<class F> void until(F predicate) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < 2000) QThread::msleep(1);
    require(predicate(), "Doctor observer timed out");
}
DeckHostTelemetry sample(int sequence = 1) { return *parseHostTelemetry(QJsonDocument(envelope(sequence)).toJson().toStdString()); }
DeckDoctorRequest initial() {
    auto offer = *parseDoctorOffer(doctor());
    return {offer.action, offer.appSession, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", {}, offer.generation, offer};
}
QJsonObject pyrowaveDoctor() {
    QFile fixture(QStringLiteral(NOVA_DECK_PYROWAVE_DOCTOR_FIXTURE));
    require(fixture.open(QIODevice::ReadOnly), "PyroWave Doctor fixture missing");
    return QJsonDocument::fromJson(fixture.readAll()).object();
}
void lossWithTuningOwner() {
    DeckDoctorActions flow; auto s = sample(); s.live->enabled = true;
    flow.observe(&s, true, 1000);
    require(flow.view(1000).value("doctorCanApply").toBool() && flow.apply(1000),
        "host loss step hidden while Live Tuning owns bitrate");
    ++s.live->sequence; flow.observe(&s, true, 1010);
    const auto request = flow.next(1010);
    require(request && request->action == "lower_bitrate", "guarded loss step did not dispatch");
    s.doctorOffer = parseDoctorOffer(doctor(true)); DeckDoctorActions restore;
    restore.observe(&s, true, 1000);
    require(!restore.apply(1000), "quality restore bypassed Live Tuning ownership");
}
void pyrowaveOfferContract() {
    const auto pyro = pyrowaveDoctor();
    const auto offer = parseDoctorOffer(pyro);
    require(offer.has_value(), "current host PyroWave restore offer rejected");
    const DeckDoctorRequest request{offer->action, offer->appSession, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", {}, offer->generation, offer};
    const auto body = doctorRequestBody(request);
    require(body && body->value("goal_source") == "pyrowave_advice" && body->value("target_bitrate_kbps") == 100148,
        "PyroWave reviewed goal source or request units lost");
    require(doctorPresentation(pyro).value("title").toString().contains("PyroWave"), "PyroWave cause hidden in generic Doctor title");
    for (const auto* source : {"", "launch_bitrate", "launch_ceiling", "other"}) {
        auto bad=pyro; auto action=bad["safe_recovery_action"].toObject(); auto payload=action["payload_preview"].toObject();
        if (QString(source).isEmpty()) payload.remove("goal_source"); else payload["goal_source"]=source;
        action["payload_preview"]=payload; bad["safe_recovery_action"]=action;
        require(!parseDoctorOffer(bad), "PyroWave restore accepted missing or different goal source");
    }
    auto bad=pyro; auto evidence=bad["evidence"].toArray(); auto latency=evidence[1].toObject();
    latency["value"]=45; evidence[1]=latency; bad["evidence"]=evidence;
    require(!parseDoctorOffer(bad), "PyroWave restore admitted RTT pressure");
    bad=pyro; evidence=bad["evidence"].toArray(); auto loss=evidence[0].toObject();
    loss["source"]="enet_control_channel"; evidence[0]=loss; bad["evidence"]=evidence;
    require(!parseDoctorOffer(bad), "PyroWave restore mistook control loss for media evidence");
    for (const auto* source : {"launch_bitrate", "launch_ceiling"}) {
        auto current=doctor(true); auto action=current["safe_recovery_action"].toObject(); auto payload=action["payload_preview"].toObject();
        payload["goal_source"]=source; action["payload_preview"]=payload; current["safe_recovery_action"]=action;
        const auto parsed=parseDoctorOffer(current); require(parsed.has_value(), "current host launch restore source rejected");
        auto launch=request; launch.offer=parsed; require(doctorRequestBody(launch)->value("goal_source")==source,
            "launch restore dropped reviewed goal source");
    }
}
void offerContract() {
    pyrowaveOfferContract();
    require(parseDoctorOffer(doctor()).has_value() && parseDoctorOffer(doctor(true)).has_value(), "host live-fix contract rejected");
    for (const auto* object : {"safe_recovery_action", "payload_preview", "verification", "undo"}) {
        auto d = doctor(); const auto action = d["safe_recovery_action"].toObject();
        const auto source = QString(object) == "safe_recovery_action" ? action : action[object].toObject();
        for (auto it = source.begin(); it != source.end(); ++it) {
            auto broken = source; broken[it.key()] = QJsonValue::Null;
            auto a = action;
            if (QString(object) == "safe_recovery_action") a = broken; else a[object] = broken;
            d["safe_recovery_action"] = a;
            require(!parseDoctorOffer(d), "missing typed action field accepted");
        }
    }
    for (const QJsonValue bad : {QJsonValue("16000"), QJsonValue(999), QJsonValue(300001), QJsonValue(16000.5)}) {
        auto d = doctor(); auto a = d["safe_recovery_action"].toObject(); auto p = a["payload_preview"].toObject();
        p["target_bitrate_kbps"] = bad; a["payload_preview"] = p; d["safe_recovery_action"] = a;
        require(!parseDoctorOffer(d), "invalid target accepted");
    }
    for (const auto* field : {"controller_revision", "session_generation", "evidence_revision"}) {
        auto d = doctor(); auto a = d["safe_recovery_action"].toObject(); auto p = a["payload_preview"].toObject();
        p[field] = 9007199254740992.0; a["payload_preview"] = p; d["safe_recovery_action"] = a;
        require(!parseDoctorOffer(d), "imprecise authority number accepted");
    }
    auto d = doctor(); auto evidence = d["evidence"].toArray();
    auto loss = evidence[0].toObject(); loss["source"] = "enet_control_channel"; evidence[0] = loss; d["evidence"] = evidence;
    require(!parseDoctorOffer(d), "control-channel loss authorized lower bitrate");
    auto latency = evidence[1].toObject(); latency["status"] = "fail"; latency["value"] = 45; evidence[1] = latency; d["evidence"] = evidence;
    require(parseDoctorOffer(d).has_value(), "confirmed RTT pressure rejected");
    latency["value"] = "45"; evidence[1] = latency; d["evidence"] = evidence;
    require(!parseDoctorOffer(d), "string RTT authorized mutation");
    d = doctor(true); evidence = d["evidence"].toArray(); loss = evidence[0].toObject();
    loss["source"] = "unavailable"; loss["status"] = "unknown"; loss["value"] = QJsonValue::Null;
    evidence[0] = loss; d["evidence"] = evidence; require(parseDoctorOffer(d).has_value(), "typed missing-media quality retry rejected");
    loss["value"] = 0; evidence[0] = loss; d["evidence"] = evidence; require(!parseDoctorOffer(d), "unavailable media fabricated clean loss");
    auto a = doctor()["safe_recovery_action"].toObject(); a["endpoint"] = "https://other.invalid/action"; d = doctor(); d["safe_recovery_action"] = a;
    require(!parseDoctorOffer(d), "foreign action URL admitted");
}
void pyrowaveRecoveryAndGoalFence() {
    auto s=sample(); s.doctorOffer=parseDoctorOffer(pyrowaveDoctor());
    DeckDoctorActions flow; flow.observe(&s,true,1000); require(flow.apply(1000), "PyroWave Apply unavailable");
    ++s.live->sequence; flow.observe(&s,true,1010); const auto request=flow.next(1010);
    require(request && doctorRequestBody(*request)->value("goal_source")=="pyrowave_advice", "PyroWave dispatch source lost");
    const auto saved=flow.checkpoint(); DeckDoctorActions recovered;
    require(recovered.restore(saved,*s.live,QDateTime::currentMSecsSinceEpoch())==DeckDoctorActions::Recovery::Restored,
        "PyroWave intent recovery rejected");
    recovered.observe(&s,true,1020); require(!recovered.next(1020) && recovered.check(1020), "PyroWave recovery replayed automatically");
    const auto replay=recovered.next(1030);
    require(replay && doctorRequestBody(*replay)==doctorRequestBody(*request), "recovery changed PyroWave source, units or request identity");
    flow.invalidate(); flow.complete(parseDoctorReceipt(receipt(*request),200,*request),1040);
    require(!flow.undo(1040), "PyroWave receipt enabled Undo before readback");
    ++s.live->sequence; flow.observe(&s,true,1050); require(flow.undo(1050), "PyroWave Undo unavailable after fresh readback");
    const auto undo=flow.next(1060);
    require(undo && undo->action=="undo" && !doctorRequestBody(*undo)->contains("goal_source"), "Undo inherited restore goal payload");
    flow.complete(parseDoctorReceipt(receipt(*undo),200,*undo),1070);
    require(!flow.view(1070).value("doctorCanUndo").toBool(), "confirmed PyroWave Undo remained enabled");
    DeckDoctorActions changed; changed.observe(&s,true,1100); require(changed.apply(1100), "goal drift fixture unavailable");
    ++s.live->sequence; s.doctorOffer->goalSource="launch_bitrate"; changed.observe(&s,true,1110);
    require(!changed.next(1110), "preflight silently rebased same numeric target onto another goal source");
}
void receipts() {
    const auto start = initial(); const auto b = receipt(start);
    require(parseDoctorReceipt(b, 200, start).has_value(), "applying receipt rejected");
    for (const auto& pair : std::initializer_list<std::pair<QString, QJsonValue>>{
        {"app_session_id", "other"}, {"session_generation", 42}, {"session_generation", "41"}, {"request_id", "other"},
        {"run_id", "recovery-run-other"}, {"status", "true"}, {"changed", "true"}, {"state", "resolved"}}) {
        auto bad = b; bad[pair.first] = pair.second; require(!parseDoctorReceipt(bad, 200, start), "foreign/malformed receipt accepted");
    }
    auto bad = b; auto undo = b["undo"].toObject(); undo["run_id"] = "doctor-run-other"; bad["undo"] = undo;
    require(!parseDoctorReceipt(bad, 200, start), "foreign Undo run accepted");
    bad = b; auto v = b["verification"].toObject(); v["session_generation"] = 42; bad["verification"] = v;
    require(!parseDoctorReceipt(bad, 200, start), "foreign nested verification scope accepted");
    DeckDoctorRequest verify{"verify", start.appSession, {}, "doctor-run-fixture", start.generation, {}};
    auto resolved = receipt(verify); require(parseDoctorReceipt(resolved, 200, verify).has_value(), "verified window rejected");
    resolved["verification_window"] = QJsonObject{{"complete", false}, {"samples", 8}};
    require(!parseDoctorReceipt(resolved, 200, verify), "incomplete evidence claimed verified");
    for (const auto* terminal : {"rolled_back", "superseded", "rollback_unconfirmed"}) {
        auto r = receipt(verify, terminal); const int code = QString(terminal) == "rollback_unconfirmed" ? 409 : 200;
        require(parseDoctorReceipt(r, code, verify).has_value(), "typed terminal receipt rejected");
        r["undo"] = QJsonObject{{"available", true}, {"action_id", "undo"}, {"run_id", verify.runId}};
        require(!parseDoctorReceipt(r, code, verify), "terminal receipt retained Undo");
    }
    require(!parseDoctorReceipt(b, 302, start), "redirect accepted");
    auto invalid = start; invalid.action = "disable_steam_input_xbox"; require(!doctorRequestBody(invalid), "unsupported action serialized");
    invalid = start; invalid.requestId.clear(); require(!doctorRequestBody(invalid), "missing idempotency identity serialized");
}
void stateMachine() {
    DeckDoctorActions flow; auto s = sample(); flow.observe(&s, true, 1000);
    require(flow.view(1000).value("doctorCanApply").toBool() && flow.apply(1000) && !flow.apply(1000), "Apply admission/duplicate failed");
    ++s.live->sequence; flow.observe(&s, true, 1010); const auto request = flow.next(1010);
    require(request && request->action == "lower_bitrate", "fresh action did not dispatch");
    flow.invalidate(); flow.complete(parseDoctorReceipt(receipt(*request), 200, *request), 1020);
    require(!flow.view(1020).value("doctorCanUndo").toBool(), "receipt authorized before readback");
    ++s.live->sequence; flow.observe(&s, true, 9000);
    require(!flow.next(9019), "verification started before host delay");
    const auto verify = flow.next(9020); require(verify && verify->action == "verify", "verification not scheduled");
    flow.complete(parseDoctorReceipt(receipt(*verify), 200, *verify), 9030);
    require(flow.view(9030).value("doctorActionState") == "resolved" && flow.undo(9030) && !flow.undo(9030), "verified Undo admission failed");
    const auto undo = flow.next(9040); require(undo && undo->runId == verify->runId, "Undo changed run identity");
    flow.complete(parseDoctorReceipt(receipt(*undo), 200, *undo), 9050);
    require(!flow.view(9050).value("doctorCanUndo").toBool(), "terminal Undo remained enabled");
    auto serialized = QJsonDocument::fromVariant(flow.view(9050)).toJson();
    require(!serialized.contains("private-") && !serialized.contains("fixture") && !serialized.contains("run_id"), "private receipt data reached UI");

    DeckDoctorActions uncertain; s = sample(); uncertain.observe(&s, true, 1000); require(uncertain.apply(1000), "uncertain Apply failed");
    ++s.live->sequence; uncertain.observe(&s, true, 1010); const auto first = uncertain.next(1010); require(first.has_value(), "uncertain dispatch missing");
    uncertain.complete({}, 1020);
    require(!uncertain.next(1030) && !uncertain.apply(1030) && uncertain.check(1030), "ambiguous change replayed or discarded");
    const auto retry = uncertain.next(1040);
    require(retry && doctorRequestBody(*retry) == doctorRequestBody(*first), "explicit recovery replaced idempotency or payload");
    uncertain.complete(parseDoctorReceipt(receipt(*retry), 200, *retry), 1050);
    uncertain.observe(nullptr, false, 1060);
    require(!uncertain.undo(1060) && !uncertain.check(1060), "failed read retained mutation authority");
    ++s.live->generation; uncertain.observe(&s, true, 1070);
    require(!uncertain.undo(1070) && !uncertain.check(1070) && !uncertain.next(20000), "replacement stream inherited receipt");

    DeckDoctorActions changed; s = sample(); changed.observe(&s, true, 1000); require(changed.apply(1000), "changed fixture failed");
    ++s.live->sequence; ++s.doctorOffer->targetKbps; changed.observe(&s, true, 1010);
    require(!changed.next(1010), "preflight silently changed reviewed bitrate target");
    require(!changed.apply(5000), "stale offer authorized action");

    // A complete loop is bounded even if a peer keeps returning Watching.
    DeckDoctorActions bounded; s = sample(); bounded.observe(&s, true, 1000); require(bounded.apply(1000), "bounded Apply failed");
    ++s.live->sequence; bounded.observe(&s, true, 1010); auto operation = bounded.next(1010);
    bounded.complete(parseDoctorReceipt(receipt(*operation), 200, *operation), 1020);
    qint64 tick = 9020;
    for (int i = 0; i < 64; ++i) {
        ++s.live->sequence; bounded.observe(&s, true, tick); operation = bounded.next(tick);
        require(operation && operation->action == "verify", "bounded verification stopped too early");
        auto watching = receipt(*operation, "watching"); watching["retry_after_seconds"] = 1;
        bounded.complete(parseDoctorReceipt(watching, 200, *operation), tick); tick += 1000;
    }
    ++s.live->sequence; bounded.observe(&s, true, tick);
    require(!bounded.next(tick) && bounded.view(tick).value("doctorActionState") == "attention" && bounded.undo(tick),
        "verification exceeded its bound or lost Undo");
    DeckDoctorActions deadline; s = sample(); deadline.observe(&s, true, 1000); require(deadline.apply(1000), "deadline Apply failed");
    ++s.live->sequence; deadline.observe(&s, true, 1010); operation = deadline.next(1010);
    deadline.complete(parseDoctorReceipt(receipt(*operation), 200, *operation), 1020);
    ++s.live->sequence; deadline.observe(&s, true, 181010);
    require(!deadline.next(181010) && deadline.view(181010).value("doctorCanUndo").toBool(), "verification exceeded elapsed deadline");

    // Host refusal proves no new mutation; its free-form error stays private.
    auto rejected = QJsonObject{{"status", false}, {"changed", false}, {"state", "evidence_changed"},
        {"app_session_id", request->appSession}, {"session_generation", request->generation}, {"error", "private-path"}};
    const auto refusal = parseDoctorReceipt(rejected, 409, *request);
    require(refusal && refusal->state == "rejected" && !refusal->undo, "typed scoped refusal discarded");
}
void eventInvalidation() {
    std::atomic<int> reads{0}, posts{0}; std::atomic<bool> listening{false}, fire{false}, delivered{false}, entered{false}, cancelled{false};
    DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget t;
        t.fetch = [&](const auto&) { auto s = sample(++reads); s.eventsHttpsPort = 1234;
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, s}; };
        t.identityValid = [] { return true; };
        t.setEnabled = [](bool, const auto&, const auto&) { return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true}; };
        t.events = [&](int, const auto& refresh, const auto& stop) {
            listening = true;
            while (!stop()) {
                if (fire.exchange(false)) { refresh(); delivered = true; }
                QThread::msleep(1);
            }
            return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        };
        t.doctorAction = [&](const auto&, const auto& stop) {
            ++posts; entered = true; while (!stop()) QThread::msleep(1); cancelled = true;
            return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        };
        return t;
    }, {17, "private-game", "private-token"}, {20, 300, 100});
    until([&] { return listening && observer.snapshot().value("doctorCanApply").toBool(); });
    require(observer.applyDoctorFix(), "event fixture Apply failed"); until([&] { return entered.load(); });
    fire = true; until([&] { return delivered && cancelled && observer.snapshot().value("doctorCanCheck").toBool(); });
    QThread::msleep(60);
    require(posts == 1 && !observer.applyDoctorFix() && !observer.undoDoctorFix(), "event cancellation replayed or authorized an unknown receipt");
}
void observerBoundary() {
    std::atomic<int> reads{0}, posts{0}; std::atomic<bool> foreign{false}, blocked{false}, entered{false}, cancelled{false};
    auto observer = std::make_unique<DeckHudHostObserver>([&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget target;
        target.fetch = [&](const auto&) { auto s = sample(++reads); if (foreign) s.sessionToken = "replacement";
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, s}; };
        target.identityValid = [] { return true; };
        target.setEnabled = [](bool, const auto&, const auto&) { return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true}; };
        target.doctorAction = [&](const auto& request, const auto& stop) {
            ++posts; entered = true;
            while (blocked && !stop()) QThread::msleep(1);
            if (stop()) { cancelled = true; return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Timeout, 0, {}, {}}; }
            return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Ok, 200, {}, parseDoctorReceipt(receipt(request), 200, request)};
        };
        return target;
    }, DeckHudHostContext{17, "private-game", "private-token"}, DeckHudHostTiming{20, 300, 100});
    until([&] { return observer->snapshot().value("doctorCanApply").toBool(); });
    blocked = true;
    require(observer->applyDoctorFix() && !observer->applyDoctorFix(), "observer duplicated Apply");
    until([&] { return entered.load(); });
    require(!observer->setLiveTuningEnabled(true) && !observer->refreshDiagnostics(), "conflicting operation admitted");
    blocked = false;
    until([&] { return observer->snapshot().value("doctorCanUndo").toBool(); });
    require(posts == 1 && observer->checkDoctorResult(), "initial request repeated or check rejected");
    until([&] { return observer->snapshot().value("doctorActionState") == "resolved"; });
    require(posts == 2 && observer->undoDoctorFix(), "verified Undo missing");
    until([&] { return observer->snapshot().value("doctorActionState") == "undone"; });
    require(posts == 3, "Undo duplicated");
    foreign = true; until([&] { return !observer->snapshot().value("hostFresh").toBool(); });
    require(!observer->applyDoctorFix() && !observer->undoDoctorFix(), "foreign session action admitted");
    foreign = false; until([&] { return observer->snapshot().value("doctorCanApply").toBool(); });
    blocked = true; entered = false; require(observer->applyDoctorFix(), "second apply failed");
    until([&] { return entered.load(); }); QElapsedTimer timer; timer.start(); observer.reset();
    require(cancelled && timer.elapsed() < 600, "Doctor request blocked teardown");
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (argc > 1 && QString::fromLocal8Bit(argv[1]) == "--loss-owner") { lossWithTuningOwner(); return 0; }
    offerContract(); lossWithTuningOwner(); pyrowaveRecoveryAndGoalFence(); receipts(); stateMachine(); observerBoundary(); eventInvalidation();
    std::cout << "Doctor contracts, receipts, verification, Undo and observer boundaries passed\n";
}
