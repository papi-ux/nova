#include "stream/deck_pyrowave_probe.h"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <cmath>
#include <algorithm>
#include <memory>
#include <QDebug>

namespace nova::deck::stream {
DeckPyrowaveProbeResult probePyrowaveInChild(const QString& program, const QStringList& arguments, int timeoutMs) {
    const auto failed = [](const char* reason) { return DeckPyrowaveProbeResult{{}, QString::fromUtf8(reason)}; };
    if (timeoutMs <= 0) return failed("PyroWave device check timed out. Restart Nova to check again.");
    const auto dispose = [](QProcess* process) {
        if (process->state() != QProcess::NotRunning) {
            process->kill();
            if (!process->waitForFinished(1000) && process->state() != QProcess::NotRunning) {
                // A driver call can remain in uninterruptible sleep even after SIGKILL.
                // Retain this rare stranded child until app exit instead of letting
                // QProcess destruction block the worker and application shutdown.
                qWarning("PyroWave device checker did not exit after termination.");
                return;
            }
        }
        delete process;
    };
    std::unique_ptr<QProcess, decltype(dispose)> child(new QProcess, dispose);
    child->setStandardErrorFile(QProcess::nullDevice());
    child->setStandardInputFile(QProcess::nullDevice());
    QElapsedTimer elapsed;
    elapsed.start();
    child->start(program, arguments, QIODevice::ReadOnly);
    if (!child->waitForStarted(timeoutMs)) {
        return failed("The PyroWave device checker could not start. Reinstall Nova or choose another codec.");
    }
    QByteArray output;
    while (child->state() != QProcess::NotRunning) {
        const auto remaining = timeoutMs - elapsed.elapsed();
        if (remaining <= 0) {
            return failed("PyroWave device check timed out. Restart Nova to check again.");
        }
        child->waitForReadyRead(static_cast<int>(std::min<qint64>(remaining, 50)));
        output += child->readAllStandardOutput();
        if (output.size() > 4096) {
            return failed("PyroWave device check returned an invalid result. Choose another codec.");
        }
    }
    output += child->readAllStandardOutput();
    if (child->exitStatus() != QProcess::NormalExit || child->exitCode() != 0)
        return failed("PyroWave device check failed. Choose another codec; other codecs remain available.");
    const auto document = QJsonDocument::fromJson(output);
    const auto object = document.object();
    const auto width = object.value("maxWidth").toDouble(-1);
    const auto height = object.value("maxHeight").toDouble(-1);
    const bool dimensions = std::isfinite(width) && std::isfinite(height) && width == std::floor(width) &&
        height == std::floor(height) && width >= 0 && height >= 0 && width <= 65536 && height <= 65536;
    if (output.size() > 4096 || !document.isObject() || object.value("version").toDouble() != 1 ||
        !object.value("available").isBool() || !dimensions ||
        (object.value("available").toBool() ? width == 0 || height == 0 : width != 0 || height != 0))
        return failed("PyroWave device check returned an invalid result. Choose another codec.");
    if (!object.value("available").toBool())
        return failed("PyroWave Vulkan decoding is unavailable on this Linux device. Choose another codec.");
    return {{static_cast<int>(width), static_cast<int>(height)}, {}};
}

DeckPyrowaveProbeResult DeckPyrowaveProbeCache::get(const QString& key,
    const std::function<DeckPyrowaveProbeResult()>& probe) {
    std::lock_guard lock(mutex_);
    if (!result_ || key != key_) { result_ = probe(); key_ = key; }
    return *result_;
}

namespace {
QString environmentKey(const QString& program) {
    QCryptographicHash hash(QCryptographicHash::Sha256);
    const auto fileIdentity = [&](const QString& path) {
        const QFileInfo file(path);
        hash.addData(path.toUtf8());
        hash.addData(file.canonicalFilePath().toUtf8());
        hash.addData(QByteArray::number(file.size()));
        hash.addData(QByteArray::number(file.lastModified().toMSecsSinceEpoch()));
    };
    fileIdentity(program);
    // Flatpak mounts a new driver extension after a restart. Native installs can
    // update the ICDs/libraries in place, so include their identities as well.
    for (const auto* path : {"/usr/share/vulkan/icd.d", "/etc/vulkan/icd.d", "/usr/lib64", "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/x86_64-linux-gnu/GL/default/lib"}) {
        QDir dir(QString::fromUtf8(path));
        for (const auto& name : dir.entryList({"*.json", "libvulkan*", "libGLX_nvidia*"}, QDir::Files, QDir::Name))
            fileIdentity(dir.filePath(name));
    }
    QDir drm("/sys/class/drm");
    for (const auto& name : drm.entryList({"renderD*"}, QDir::Dirs, QDir::Name)) {
        for (const auto* field : {"vendor", "device", "driver/module/version"}) {
            QFile file(drm.filePath(name + "/device/" + field));
            hash.addData(file.fileName().toUtf8());
            if (file.open(QIODevice::ReadOnly)) hash.addData(file.read(4096));
        }
    }
    for (const auto* name : {"VK_DRIVER_FILES", "VK_ICD_FILENAMES", "VK_ADD_DRIVER_FILES", "MESA_VK_DEVICE_SELECT",
            "DRI_PRIME", "LD_LIBRARY_PATH", "VK_LAYER_PATH", "VK_INSTANCE_LAYERS"}) {
        hash.addData(name); hash.addData(qgetenv(name));
    }
    return QString::fromLatin1(hash.result().toHex());
}
}
DeckPyrowaveProbeResult cachedPyrowaveDecodeSupport() {
#ifdef NOVA_DECK_BUILD_PYROWAVE
    static DeckPyrowaveProbeCache cache;
    const auto program = QCoreApplication::applicationDirPath() + "/nova-deck-pyrowave-probe";
    return cache.get(environmentKey(program), [&] { return probePyrowaveInChild(program); });
#else
    return {{}, "This Nova build does not include PyroWave. Choose another codec."};
#endif
}
} // namespace nova::deck::stream
