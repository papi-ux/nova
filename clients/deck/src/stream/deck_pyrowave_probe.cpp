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
namespace {
const char* refusalMessage(nova::pyrowave::RefusalCause cause) {
    using enum nova::pyrowave::RefusalCause;
    switch (cause) {
    case Api: return "PyroWave needs its bundled 0.6.0 codec library. Reinstall Nova or choose another codec.";
    case Device: return "PyroWave could not create a Vulkan device. Check this device's Vulkan driver or choose another codec.";
    case Decoder: return "PyroWave could not initialize or run its decoder. Restart Nova or choose another codec.";
    case Interop: return "PyroWave needs Vulkan external-memory support on this device. Choose another codec.";
    case DmaBuf: return "PyroWave cannot export DMA-BUF images on this device. Check the Vulkan driver or choose another codec.";
    case Queue: return "PyroWave could not use a Vulkan graphics/compute queue. Restart Nova or choose another codec.";
    case Limits: return "This device's Vulkan image limits cannot support PyroWave. Choose another codec.";
    case Unavailable: return "PyroWave Vulkan decoding is unavailable on this Linux device. Choose another codec.";
    case None: return "";
    }
    return ""; // Unknown wire tokens are rejected before this mapping.
}
}

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
    const bool dimensions = object.value("maxWidth").isDouble() && object.value("maxHeight").isDouble() && std::isfinite(width) && std::isfinite(height) && width == std::floor(width) &&
        height == std::floor(height) && width >= 0 && height >= 0 && width <= 65536 && height <= 65536;
    const auto token = object.value("reason").toString().toUtf8();
    const auto cause = nova::pyrowave::parseRefusalCause(std::string_view(token.constData(), token.size()));
    const auto available = object.value("available").toBool();
    if (output.size() > 4096 || !document.isObject() || object.size() != 5 ||
        !object.value("version").isDouble() || object.value("version").toDouble() != nova::pyrowave::probeProtocolVersion ||
        !object.value("available").isBool() || !object.value("reason").isString() || !dimensions || !cause ||
        !nova::pyrowave::validProbeResult(available, static_cast<int>(width), static_cast<int>(height), *cause))
        return failed("PyroWave device check returned an invalid result. Choose another codec.");
    if (!available)
        return {{}, QString::fromUtf8(refusalMessage(*cause)), cause};
    return {{static_cast<int>(width), static_cast<int>(height)}, {}, cause};
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
