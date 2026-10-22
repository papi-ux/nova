#pragma once
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonDocument>
#include <QDir>
#include <QFileInfo>
#include <QStorageInfo>
#include <iostream>

namespace host_settings_fixture {
// These fixtures verify real QSettings state and restart semantics, not disk throughput.
// Keep their small private files off container overlay storage when tmpfs is available.
inline QString settingsDirectoryTemplate() {
    const QString sharedMemory = "/dev/shm";
    const QStorageInfo storage(sharedMemory);
    if (storage.isValid() && storage.isReady() && !storage.isReadOnly()
        && storage.fileSystemType() == "tmpfs" && storage.bytesAvailable() >= 1024 * 1024
        && QFileInfo(sharedMemory).isWritable()) {
        std::cerr << "[settings fixture] storage=tmpfs\n";
        return sharedMemory + "/nova-settings-fixture-XXXXXX";
    }
    std::cerr << "[settings fixture] storage=default-temp\n";
    return QDir::tempPath() + "/nova-settings-fixture-XXXXXX";
}
inline QJsonObject settings() {
    const QJsonObject profile{{"stream_display_mode", "headless_stream"}, {"display_mode", "1280x800x60"}, {"target_bitrate_kbps", 20000}, {"disconnect_resume_timeout_seconds",300}};
    return {{"version", 1}, {"revision", "1"}, {"desired", profile}, {"effective", profile}, {"relaunch_required", false},
        {"capabilities", QJsonObject{{"disconnect_resume_timeout_control",true}, {"display_mode_override", true}, {"target_bitrate_override", true}, {"modes", QJsonArray{
            QJsonObject{{"value", "headless_stream"}, {"label", "Private Stream"}, {"available", true}, {"session_overridable", true}},
            QJsonObject{{"value", "desktop_display"}, {"label", "Mirror Desktop"}, {"available", true}, {"session_overridable", true}},
            QJsonObject{{"value", "headless_dongle"}, {"label", "Headless Dongle"}, {"available", false}, {"session_overridable", false}, {"unavailable_reason", "Connect a display adapter to use this mode."}}
        }}}}};
}
inline std::string json(const QJsonObject& value) { return QJsonDocument(value).toJson(QJsonDocument::Compact).toStdString(); }
}
