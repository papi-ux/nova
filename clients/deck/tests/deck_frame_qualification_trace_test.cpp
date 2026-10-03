#include "runtime/deck_frame_qualification_trace.h"
#include <QCoreApplication>
#include <QTemporaryDir>
#include <QJsonDocument>
#include <QJsonObject>
#include <cassert>
using namespace nova::deck::runtime;
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory; assert(directory.isValid());
    const auto path = directory.filePath("soak.jsonl");
    const QVariantMap hud{{"fresh", true}, {"requestedFps", "90"}, {"incoming", "90.0"},
        {"decoded", "89.0"}, {"submitted", "88.0"}, {"cpuUploadCompositions", "87.0"},
        {"decoderBackend", "V4L2"}, {"frameTransferPath", "CPU upload"},
        {"deliveryDrops", "3"}, {"deliveryQueueDepth", "1"}, {"hostName", "private-token"}};
    DeckFrameQualificationTrace trace(path);
    assert(trace.append(hud, 1000));
    auto stale = hud; stale["fresh"] = false;
    assert(trace.append(stale, 2000));
    assert(!trace.append(hud, -1));
    QFile file(path); assert(file.open(QIODevice::ReadOnly));
    const auto first = file.readLine(); assert(!first.contains("private-token"));
    const auto row = QJsonDocument::fromJson(first).object();
    assert(row["sample_time_ms"] == 1000 && row["client_readings"].toObject()["submitted_fps"] == 88);
    assert(QJsonDocument::fromJson(file.readLine()).object()["client_readings"].toObject()["decoded_fps"].isNull());
    assert(file.atEnd());
    assert(!(file.permissions() & (QFileDevice::ReadGroup | QFileDevice::WriteGroup | QFileDevice::ReadOther | QFileDevice::WriteOther)));
    const auto savedSize = file.size();
    DeckFrameQualificationTrace collision(path); assert(!collision.append(hud, 3000));
    assert(file.size() == savedSize);
    DeckFrameQualificationTrace capped(directory.filePath("capped.jsonl"), first.size());
    assert(capped.append(hud, 1000)); assert(!capped.append(hud, 2000));
    QFile cappedFile(directory.filePath("capped.jsonl")); assert(cappedFile.size() == first.size());
}
