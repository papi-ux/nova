#include "runtime/deck_doctor_receipts.h"
#include "runtime/deck_doctor_actions.h"
#include "runtime/deck_hud_host.h"
#include "deck_doctor_fixture.h"
#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QTemporaryDir>
#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool ok, const char* text) { if (!ok) { std::cerr << text << '\n'; std::exit(1); } }
template<class F> void until(F predicate) {
    QElapsedTimer timer; timer.start(); while (!predicate() && timer.elapsed() < 2000) QThread::msleep(1);
    require(predicate(), "receipt recovery timed out");
}
DeckHostTelemetry sample(int sequence = 1) { return *parseHostTelemetry(QJsonDocument(doctor_fixture::envelope(sequence)).toJson().toStdString()); }
QJsonObject checkpoint(bool withReceipt = true) {
    DeckDoctorActions flow; auto s = sample(); flow.observe(&s, true, 1000); require(flow.apply(1000), "fixture apply failed");
    ++s.live->sequence; flow.observe(&s, true, 1010); auto request = flow.next(1010); require(request.has_value(), "fixture preflight failed");
    if (withReceipt) flow.complete(parseDoctorReceipt(doctor_fixture::receipt(*request), 200, *request), 1020);
    return flow.checkpoint();
}
void recoveryContract() {
    const auto s = sample(); const auto saved = checkpoint(); const auto now = QDateTime::currentMSecsSinceEpoch();
    const auto bytes = QJsonDocument(saved).toJson();
    require(!bytes.contains("fixture-session") && !bytes.contains("fixture-instance") && !bytes.contains("private-token") &&
        !bytes.contains("app_session_id"), "raw session identity persisted");
    DeckDoctorActions flow;
    require(flow.restore(saved, *s.live, now) == DeckDoctorActions::Recovery::Restored, "valid recovery rejected");
    flow.observe(&s, true, 1000);
    require(!flow.undo(1000) && !flow.apply(1000) && !flow.next(1000000) && flow.view(1000).value("doctorActionState") == "recovered",
        "cached state authorized Undo, Apply or automatic verification");
    require(flow.check(1000), "recovered Check missing"); const auto check = flow.next(1010);
    require(check && check->action == "verify" && check->runId == "doctor-run-fixture", "recovered receipt changed scope/run");
    flow.complete(parseDoctorReceipt(doctor_fixture::receipt(*check), 200, *check), 1020);
    require(flow.view(1020).value("doctorCanUndo").toBool(), "fresh verification did not enable Undo");
    for (int mutation = 0; mutation < 6; ++mutation) {
        auto changed = saved; auto current = *s.live;
        if (mutation == 0) ++current.generation;
        if (mutation == 1) current.appSession = "replacement";
        if (mutation == 2) current.instance = "restarted-host";
        if (mutation == 3) changed["created_ms"] = now - 86400001;
        if (mutation == 4) changed["created_ms"] = now + 60000;
        if (mutation == 5) changed["version"] = "1";
        DeckDoctorActions wrong;
        require(wrong.restore(changed, current, now) != DeckDoctorActions::Recovery::Restored, "foreign/expired/malformed checkpoint recovered");
    }
    auto invalid = saved; auto body = invalid["initial"].toObject(); body["untrusted_extra"] = "private-token"; invalid["initial"] = body;
    DeckDoctorActions wrong; require(wrong.restore(invalid, *s.live, now) == DeckDoctorActions::Recovery::Invalid, "extra persisted payload accepted");
    auto uncertain = checkpoint(false); DeckDoctorActions lost;
    require(lost.restore(uncertain, *s.live, QDateTime::currentMSecsSinceEpoch()) == DeckDoctorActions::Recovery::Restored,
        "pre-dispatch intent not recoverable");
    lost.observe(&s, true, 1000); require(lost.check(1000), "lost-reply Check unavailable"); const auto replay = lost.next(1010);
    auto expected = uncertain["initial"].toObject(); expected["app_session_id"] = s.live->appSession;
    require(replay && doctorRequestBody(*replay) == expected, "restart changed original idempotency/payload");
}
void interruptedUndo() {
    auto s = sample(); DeckDoctorActions flow;
    require(flow.restore(checkpoint(), *s.live, QDateTime::currentMSecsSinceEpoch()) == DeckDoctorActions::Recovery::Restored, "Undo recovery fixture failed");
    flow.observe(&s,true,1000); require(flow.check(1000), "initial check failed"); auto request = flow.next(1010);
    flow.complete(parseDoctorReceipt(doctor_fixture::receipt(*request),200,*request),1020);
    require(flow.undo(1020), "Undo fixture failed"); request=flow.next(1030);
    require(request && request->action == "undo", "Undo not dispatched");
    const auto saved=flow.checkpoint();
    flow.complete({},1040);
    require(!flow.undo(1040) && flow.view(1040).value("doctorCheckLabel") == "Finish Undo", "unconfirmed Undo treated as verified");
    DeckDoctorActions restarted;
    require(restarted.restore(saved,*s.live,QDateTime::currentMSecsSinceEpoch()) == DeckDoctorActions::Recovery::Restored, "interrupted Undo not restored");
    restarted.observe(&s,true,1050);
    require(!restarted.next(1050) && !restarted.undo(1050) && restarted.check(1050), "interrupted Undo replayed automatically");
    auto retry=restarted.next(1060);
    require(retry && doctorRequestBody(*retry) == doctorRequestBody(*request), "interrupted Undo became Verify or changed scope");
    restarted.complete(parseDoctorReceipt(doctor_fixture::receipt(*retry),200,*retry),1070);
    require(restarted.checkpoint().isEmpty() && restarted.view(1070).value("doctorActionState") == "undone", "confirmed Undo not retired");
}

void privateStore() {
    QTemporaryDir tmp; const auto path = tmp.path()+"/receipts", key = DeckDoctorReceiptStore::key("host-cert", "client-cert", "game");
    const auto filename = path+"/"+key+".json"; const auto saved = checkpoint();
    require(key != DeckDoctorReceiptStore::key("other-host", "client-cert", "game") &&
        key != DeckDoctorReceiptStore::key("host-cert", "other-client", "game") &&
        key != DeckDoctorReceiptStore::key("host-cert", "client-cert", "other-game"), "host/client/game store key aliased");
    {
        DeckDoctorReceiptStore store(path,key); require(store.ready() && store.load().isEmpty() && store.save(saved), "private store failed");
        DeckDoctorReceiptStore second(path,key); require(!second.ready(), "competing writer acquired journal");
        const auto flags = QFileInfo(filename).permissions();
        require(!(flags & (QFileDevice::ReadGroup | QFileDevice::WriteGroup | QFileDevice::ReadOther | QFileDevice::WriteOther)), "journal permissions too broad");
        require(store.load() == saved && !store.save(QJsonObject{{"oversized", QString(16384,'x')}}), "bounded roundtrip failed");
    }
    {
        DeckDoctorReceiptStore reopened(path,key); require(reopened.ready() && reopened.load() == saved, "journal did not survive restart");
        require(reopened.save({}) && !QFileInfo::exists(filename), "terminal journal not retired");
        require(QFile::link(tmp.path()+"/missing", filename), "symlink fixture failed");
        require(reopened.load().isEmpty() && !reopened.ready(), "symlink journal admitted"); QFile::remove(filename);
    }
    {
        QFile corrupt(filename); require(corrupt.open(QIODevice::WriteOnly), "corrupt fixture failed"); corrupt.write("{"); corrupt.close();
        QFile::setPermissions(filename,QFileDevice::ReadOwner|QFileDevice::WriteOwner);
        DeckDoctorReceiptStore store(path,key); require(store.load().isEmpty() && !store.ready(), "corrupt journal silently accepted");
    }
}
void failedIntentWrite() {
    QTemporaryDir tmp; const QString key(64,'b'); const auto path=tmp.path()+"/receipts";
    std::atomic<int> posts{0}, reads{0};
    auto factory = [&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget t;
        t.fetch = [&](const auto&) { return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok,200,{},sample(++reads)}; };
        t.identityValid=[] { return true; };
        t.setEnabled=[](bool,const auto&,const auto&) { return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok,200,{},true}; };
        t.doctorReceipts=std::make_shared<DeckDoctorReceiptStore>(path,key);
        t.doctorAction=[&](const auto&,const auto&) { ++posts; return DeckPolarisResult<DeckDoctorReceipt>{}; };
        return t;
    };
    DeckHudHostObserver observer(factory,{17,"private-game","private-token"},{20,300,100});
    until([&] { return observer.snapshot().value("doctorCanApply").toBool(); });
    require(QFile::link(tmp.path()+"/missing",path+"/"+key+".json"), "failed-write fixture failed");
    require(observer.applyDoctorFix(), "failed-write Apply fixture failed");
    until([&] { return observer.snapshot().value("doctorActionState") == "attention"; });
    require(posts==0 && !observer.snapshot().value("doctorCanApply").toBool() && !observer.applyDoctorFix() &&
        !observer.checkDoctorResult() && !observer.undoDoctorFix(), "failed durable intent dispatched or allowed recovery mutation");
}

void observerRestart(bool lostReply) {
    QTemporaryDir tmp; const auto key = QString(64,'a'), path = tmp.path()+"/receipts", file = path+"/"+key+".json";
    std::atomic<int> reads{0}, posts{0}; std::atomic<bool> hold{lostReply}, entered{false}, dropped{false};
    QJsonObject firstBody;
    const auto factory = [&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget t;
        t.fetch = [&](const auto&) { return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok,200,{},sample(++reads)}; };
        t.identityValid = [] { return true; };
        t.setEnabled = [](bool,const auto&,const auto&) { return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok,200,{},true}; };
        t.doctorReceipts = std::make_shared<DeckDoctorReceiptStore>(path,key);
        t.doctorAction = [&](const auto& request,const auto& stop) {
            ++posts;
            const auto body = *doctorRequestBody(request);
            QFile journal(file); require(journal.open(QIODevice::ReadOnly), "POST dispatched before durable intent");
            const auto saved = QJsonDocument::fromJson(journal.readAll()).object()["checkpoint"].toObject();
            require(saved.contains("initial"), "durable intent missing before POST");
            if (posts == 1) firstBody = body;
            else if (lostReply && posts == 2) require(body == firstBody, "restart recovery issued a different initial request");
            entered = true;
            while (hold && !stop()) QThread::msleep(1);
            if (stop()) { dropped = true; return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Timeout,0,{},{}}; }
            return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Ok,200,{},parseDoctorReceipt(doctor_fixture::receipt(request),200,request)};
        };
        return t;
    };
    const auto make = [&] { return std::make_unique<DeckHudHostObserver>(factory,DeckHudHostContext{17,"private-game","private-token"},DeckHudHostTiming{20,300,100}); };
    auto first = make(); until([&] { return first->snapshot().value("doctorCanApply").toBool(); });
    require(first->applyDoctorFix(), "initial observer Apply failed");
    until([&] { return lostReply ? entered.load() : first->snapshot().value("doctorCanUndo").toBool(); });
    first.reset(); require(!lostReply || dropped, "lost reply was not cancelled"); hold = false;
    auto second = make(); until([&] { return second->snapshot().value("doctorCanCheck").toBool(); });
    QThread::msleep(40);
    require(posts == 1 && !second->undoDoctorFix() && !second->applyDoctorFix(), "restart silently mutated or trusted cached Undo");
    require(second->checkDoctorResult(), "explicit recovered check failed");
    until([&] { return second->snapshot().value("doctorCanUndo").toBool(); });
    require(posts == 2 && second->undoDoctorFix(), "revalidated Undo failed");
    until([&] { return second->snapshot().value("doctorActionState") == "undone"; });
    require(posts == 3, "Undo replayed"); second.reset(); require(!QFileInfo::exists(file), "terminal receipt survived retirement");
}
}
int main(int argc,char**argv) {
    QCoreApplication app(argc,argv); recoveryContract(); interruptedUndo(); privateStore(); failedIntentWrite(); observerRestart(false); observerRestart(true);
    std::cout << "Private receipt storage, restart recovery, exact scope and crash-window tests passed\n";
}
