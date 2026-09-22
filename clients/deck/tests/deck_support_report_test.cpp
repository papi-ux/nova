#include "runtime/deck_support_report.h"
#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }
int main(int argc, char** argv) {
    QCoreApplication app(argc,argv);
    const QString secret = "private-PC 192.168.22.45 https://private.host /tmp/private-user token=private-token certificate=private-cert";
    QVariantMap evidence{{"id", "packet_loss"}, {"source", "Control channel"}, {"grade", "Needs attention"}, {"reading", "9.2 %"}, {"detail", secret}};
    QVariantMap hud{{"hostFresh", true}, {"fresh", true}, {"appliedBitrate", "20.0M"}, {"qualityLimit", "40.0M"},
        {"rtt", "17ms"}, {"jitter", "2ms"}, {"host", "4.2ms"}, {"fps", "59.8"}, {"incoming", "60.0"}, {"decoded", "59.9"},
        {"bitrate", "19.6M"}, {"codec", "H.264"}, {"resolution", "1280×800"}, {"doctorActionState", "recovered"},
        {"doctorHasReceipt", true}, {"doctorCanUndo", false}, {"doctorActionMessage", secret}, {"hostName", secret},
        {"doctor", QVariantMap{{"available", true}, {"confidence", "medium"}, {"title", secret}, {"evidence", QVariantList{evidence}}}}};
    auto report = deckSupportReport(hud);
    require(report["host"].toObject()["encoder_applied_mbps"] == 20.0 && report["network"].toObject()["rtt_ms"] == 17 &&
        report["client_readings"].toObject()["composed_fps"] == 59.8, "measured report values missing");
    require(report["network"].toObject()["client_media_loss_percent"].isNull() &&
        report["doctor"].toObject()["evidence"].toArray()[0].toObject()["reading"].toObject()["value"].isNull(), "control loss became media loss");
    require(!QJsonDocument(report).toJson().contains("private-"), "private free-form content exported");
    for (const auto* field : {"fps", "incoming", "decoded", "bitrate", "host", "rtt", "jitter", "resolution", "codec", "appliedBitrate", "qualityLimit", "doctorActionState"}) {
        auto attack = hud; attack[field] = secret;
        require(!QJsonDocument(deckSupportReport(attack)).toJson().contains("private-"), "tainted top-level value exported");
    }
    for (const auto* field : {"id", "source", "grade", "reading", "group", "title", "detail"}) {
        auto row = evidence; row[field] = secret; auto finding = hud["doctor"].toMap(); finding["evidence"] = QVariantList{row};
        auto attack = hud; attack["doctor"] = finding;
        require(!QJsonDocument(deckSupportReport(attack)).toJson().contains("private-"), "tainted evidence value exported");
    }
    for (auto bad : {"nan", "inf", "-1", "60.0\nprivate-token", "192.168.1.1", "99999999999999999999"}) {
        auto attack = hud; attack["fps"] = bad;
        require(deckSupportReport(attack)["client_readings"].toObject()["composed_fps"].isNull(), "invalid measurement admitted");
    }
    auto stale = hud; stale["hostFresh"] = false; stale["fresh"] = false;
    report = deckSupportReport(stale);
    require(report["host"].toObject()["encoder_applied_mbps"].isNull() && report["client_readings"].toObject()["composed_fps"].isNull() &&
        report["doctor"].toObject()["evidence"].toArray().isEmpty(), "stale measurements exported as current");
    QTemporaryDir tmp; const auto directory = tmp.path()+"/reports";
    const auto saved = saveDeckSupportReport(hud,directory); require(saved["saved"].toBool(), "local export failed");
    QFile file(directory+"/"+saved["fileName"].toString()); require(file.open(QIODevice::ReadOnly), "saved report missing");
    const auto bytes = file.readAll();
    require(QJsonDocument::fromJson(bytes).object()["kind"] == "nova-deck-support-report-v1" && !bytes.contains("private-"), "saved payload not sanitized report");
    require(!(file.permissions() & (QFileDevice::ReadGroup|QFileDevice::WriteGroup|QFileDevice::ReadOther|QFileDevice::WriteOther)), "public report permissions");
    const auto linked = tmp.path()+"/linked"; require(QFile::link(directory, linked), "link fixture failed");
    require(!saveDeckSupportReport(hud,linked)["saved"].toBool() && !saveDeckSupportReport(hud,file.fileName())["saved"].toBool(), "invalid destination reported success");
    std::cout << "Sanitized support report, stale evidence, local export and storage failure checks passed\n";
}
