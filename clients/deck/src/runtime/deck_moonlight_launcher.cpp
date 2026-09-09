#include "runtime/deck_moonlight_launcher.h"

#include <QStandardPaths>
#include <QString>
#include <QStringList>

#include <algorithm>
#include <cstdlib>
#include <sys/stat.h>
#include <system_error>
#include <unistd.h>
#include <utility>

namespace nova::deck::runtime {

namespace {

constexpr std::string_view kFlatpakAppId = "com.moonlight_stream.Moonlight";

std::filesystem::path homeDirectory() {
    if (const char* home = std::getenv("HOME"); home != nullptr && *home != '\0') {
        return std::filesystem::path(home);
    }
    return {};
}

bool executableFile(const std::filesystem::path& path) {
    std::error_code ec;
    return std::filesystem::is_regular_file(path, ec) && ::access(path.c_str(), X_OK) == 0;
}

QStringList toQStringList(const std::vector<std::filesystem::path>& dirs) {
    QStringList list;
    for (const auto& dir : dirs) {
        list.push_back(QString::fromStdString(dir.string()));
    }
    return list;
}

QStringList toQStringList(const std::vector<std::string>& tokens) {
    QStringList list;
    for (const auto& token : tokens) {
        list.push_back(QString::fromStdString(token));
    }
    return list;
}

std::optional<std::filesystem::path> findOnPath(const std::vector<std::filesystem::path>& dirs, const std::string_view name) {
    const auto found = QStandardPaths::findExecutable(QString::fromUtf8(name.data(), static_cast<int>(name.size())), toQStringList(dirs));
    if (found.isEmpty()) {
        return std::nullopt;
    }
    return std::filesystem::path(found.toStdString());
}

void configureChild(QProcess& process, const std::vector<std::string>& argv) {
    process.setProgram(QString::fromStdString(argv.front()));
    process.setArguments(toQStringList(std::vector<std::string>(argv.begin() + 1, argv.end())));
    process.setProcessChannelMode(QProcess::MergedChannels);
}

} // namespace

bool isPlainArgvToken(const std::string_view token) {
    if (token.empty() || token.size() > 512) {
        return false;
    }
    for (const unsigned char ch : token) {
        if (ch < 0x20 || ch == 0x7f) {
            return false;
        }
    }
    return true;
}

std::string_view describe(const DeckMoonlightHandoffState state) {
    switch (state) {
    case DeckMoonlightHandoffState::Idle:
        return "idle";
    case DeckMoonlightHandoffState::Starting:
        return "starting";
    case DeckMoonlightHandoffState::Running:
        return "running";
    case DeckMoonlightHandoffState::Exited:
        return "exited";
    case DeckMoonlightHandoffState::FailedToStart:
        return "failed-to-start";
    }
    return "unknown";
}

std::string_view describe(const DeckMoonlightQuitState state) {
    switch (state) {
    case DeckMoonlightQuitState::NotRequested:
        return "not-requested";
    case DeckMoonlightQuitState::Requested:
        return "requested";
    case DeckMoonlightQuitState::Acknowledged:
        return "acknowledged";
    case DeckMoonlightQuitState::Failed:
        return "failed";
    }
    return "unknown";
}

DeckMoonlightInstallProbe defaultMoonlightInstallProbe() {
    DeckMoonlightInstallProbe probe;
    if (const char* override = std::getenv("NOVA_DECK_MOONLIGHT_BIN"); override != nullptr && *override != '\0') {
        probe.overrideBinary = std::filesystem::path(override);
    }
    const auto pushUnique = [&probe](std::filesystem::path dir) {
        if (!dir.empty() && std::find(probe.pathDirs.begin(), probe.pathDirs.end(), dir) == probe.pathDirs.end()) {
            probe.pathDirs.push_back(std::move(dir));
        }
    };
    if (const char* path = std::getenv("PATH"); path != nullptr && *path != '\0') {
        std::string_view remaining{path};
        while (!remaining.empty()) {
            const auto colon = remaining.find(':');
            pushUnique(std::filesystem::path(remaining.substr(0, colon)));
            if (colon == std::string_view::npos) {
                break;
            }
            remaining.remove_prefix(colon + 1);
        }
    }
    for (const char* dir : {"/usr/bin", "/usr/local/bin", "/var/lib/flatpak/exports/bin"}) {
        pushUnique(dir);
    }
    if (const auto home = homeDirectory(); !home.empty()) {
        probe.flatpakAppDir = home / ".var" / "app" / std::string(kFlatpakAppId);
        pushUnique(home / ".local" / "share" / "flatpak" / "exports" / "bin");
    }
    probe.flatpakInfoFile = "/.flatpak-info";
    const auto environmentValue = [](const char* name) {
        const char* value = std::getenv(name);
        return value == nullptr ? std::string{} : std::string{value};
    };
    probe.displayEnvironment.waylandDisplay = environmentValue("WAYLAND_DISPLAY");
    probe.displayEnvironment.display = environmentValue("DISPLAY");
    probe.displayEnvironment.runtimeDir = environmentValue("XDG_RUNTIME_DIR");
    return probe;
}

DeckMoonlightInstall detectMoonlightInstall(const DeckMoonlightInstallProbe& probe) {
    DeckMoonlightInstall install;
    std::error_code ec;
    install.novaInsideFlatpak = !probe.flatpakInfoFile.empty() && std::filesystem::exists(probe.flatpakInfoFile, ec);
    if (install.novaInsideFlatpak) {
        install.forwardedEnvironment = forwardedDisplayEnvironment(probe.displayEnvironment);
    }

    if (probe.overrideBinary && executableFile(*probe.overrideBinary)) {
        install.kind = DeckMoonlightInstallKind::Native;
        install.executable = probe.overrideBinary->string();
        install.label = "Moonlight (native, override)";
        return install;
    }
    for (const char* name : {"moonlight", "moonlight-qt"}) {
        if (const auto found = findOnPath(probe.pathDirs, name)) {
            install.kind = DeckMoonlightInstallKind::Native;
            install.executable = found->string();
            install.label = "Moonlight (native)";
            return install;
        }
    }
    const bool flatpakAppPresent = !probe.flatpakAppDir.empty() && std::filesystem::is_directory(probe.flatpakAppDir, ec);
    const auto flatpakTool = findOnPath(probe.pathDirs, "flatpak");
    if (flatpakAppPresent && (flatpakTool || install.novaInsideFlatpak)) {
        install.kind = DeckMoonlightInstallKind::Flatpak;
        install.executable = flatpakTool ? flatpakTool->string() : std::string{"flatpak"};
        install.label = "Moonlight (Flatpak)";
        return install;
    }
    install.label = "Moonlight not found";
    return install;
}

DeckMoonlightInstall detectMoonlightInstall() {
    return detectMoonlightInstall(defaultMoonlightInstallProbe());
}

namespace {

std::vector<std::string> programPrefix(const DeckMoonlightInstall& install) {
    std::vector<std::string> prefix;
    if (install.novaInsideFlatpak) {
        // Inside the sandbox nothing is on PATH; flatpak-spawn asks the host
        // session to run the command, which needs --talk-name=org.freedesktop.Flatpak.
        // The host runs it with its own environment, so the display Nova is on
        // has to travel with the command; detectMoonlightInstall() worked it out.
        prefix = {"flatpak-spawn", "--host"};
        for (const auto& assignment : install.forwardedEnvironment.assignments) {
            prefix.push_back("--env=" + assignment);
        }
    }
    if (install.kind == DeckMoonlightInstallKind::Flatpak) {
        prefix.push_back(install.novaInsideFlatpak ? std::string{"flatpak"} : install.executable);
        prefix.emplace_back("run");
        prefix.emplace_back(kFlatpakAppId);
    } else {
        prefix.push_back(install.executable);
    }
    return prefix;
}

} // namespace

std::string hostWaylandDisplayName(const DeckDisplayEnvironment& environment) {
    const std::string& seen = environment.waylandDisplay;
    if (seen != kFlatpakRenamedWaylandDisplay || environment.runtimeDir.empty()) {
        return seen;
    }
    struct stat renamed {};
    const auto renamedPath = environment.runtimeDir / seen;
    if (::stat(renamedPath.c_str(), &renamed) != 0 || !S_ISSOCK(renamed.st_mode)) {
        return seen;
    }
    // Every bind of one host socket shares its device and inode, so the socket
    // Flatpak renamed and the one the manifest binds under its host name are
    // told apart from any other socket in the directory (the bus proxy, say).
    std::error_code ec;
    std::vector<std::string> sameSocket;
    for (const auto& entry : std::filesystem::directory_iterator(environment.runtimeDir, ec)) {
        const std::string name = entry.path().filename().string();
        struct stat candidate {};
        if (name == seen || ::stat(entry.path().c_str(), &candidate) != 0 || !S_ISSOCK(candidate.st_mode)) {
            continue;
        }
        if (candidate.st_dev == renamed.st_dev && candidate.st_ino == renamed.st_ino) {
            sameSocket.push_back(name);
        }
    }
    if (ec || sameSocket.empty()) {
        return seen;
    }
    std::sort(sameSocket.begin(), sameSocket.end());
    return sameSocket.front();
}

DeckForwardedEnvironment forwardedDisplayEnvironment(const DeckDisplayEnvironment& environment) {
    DeckForwardedEnvironment forwarded;
    const auto offer = [&forwarded](std::string_view name, const std::string& value) {
        if (value.empty()) {
            return false;
        }
        std::string assignment = std::string(name) + "=" + value;
        if (!isPlainArgvToken("--env=" + assignment)) {
            forwarded.skipped.push_back(std::string(name) + " not forwarded: its value is not a plain argument");
            return false;
        }
        forwarded.assignments.push_back(std::move(assignment));
        return true;
    };
    if (offer("WAYLAND_DISPLAY", hostWaylandDisplayName(environment))) {
        offer("QT_QPA_PLATFORM", std::string(kForwardedQtPlatform));
    }
    offer("DISPLAY", environment.display);
    return forwarded;
}

DeckMoonlightArgvPlan buildMoonlightStreamArgv(const DeckMoonlightInstall& install, const DeckMoonlightLaunchRequest& request) {
    DeckMoonlightArgvPlan plan;
    if (!install.available()) {
        plan.reason = "Moonlight is not installed on this device.";
        return plan;
    }
    if (!isPlainArgvToken(request.hostSelector)) {
        plan.reason = "The host selector is not a plain token.";
        return plan;
    }
    if (!isPlainArgvToken(request.appName)) {
        plan.reason = "The app name is not a plain token.";
        return plan;
    }
    if (request.fps && (*request.fps < 10 || *request.fps > 240)) {
        plan.reason = "The frame rate is out of range.";
        return plan;
    }
    if (request.bitrateKbps && (*request.bitrateKbps < 500 || *request.bitrateKbps > 500000)) {
        plan.reason = "The bitrate is out of range.";
        return plan;
    }
    if (request.width.has_value() != request.height.has_value()
        || (request.width && (*request.width < 320 || *request.width > 7680 || *request.height < 200 || *request.height > 4320))) {
        plan.reason = "The resolution is out of range.";
        return plan;
    }

    plan.argv = programPrefix(install);
    plan.argv.emplace_back("stream");
    plan.argv.push_back(request.hostSelector);
    plan.argv.push_back(request.appName);
    // Moonlight-Qt's display mode is a value option; a bare --fullscreen flag
    // makes it print usage and exit 1 (found on the first real launch).
    plan.argv.emplace_back("--display-mode");
    plan.argv.emplace_back(request.fullscreen ? "fullscreen" : "windowed");
    if (request.quitAppAfter) {
        plan.argv.emplace_back("--quit-after");
    }
    if (request.fps) {
        plan.argv.emplace_back("--fps");
        plan.argv.push_back(std::to_string(*request.fps));
    }
    if (request.bitrateKbps) {
        plan.argv.emplace_back("--bitrate");
        plan.argv.push_back(std::to_string(*request.bitrateKbps));
    }
    if (request.width) {
        plan.argv.emplace_back("--resolution");
        plan.argv.push_back(std::to_string(*request.width) + "x" + std::to_string(*request.height));
    }
    plan.valid = true;
    plan.publicSummary = install.label + " will open \"" + request.appName + "\" on the paired host";
    if (request.quitAppAfter) {
        plan.publicSummary += " and quit it when the stream ends";
    }
    plan.publicSummary += ".";
    return plan;
}

DeckMoonlightArgvPlan buildMoonlightQuitArgv(const DeckMoonlightInstall& install, const std::string_view hostSelector) {
    DeckMoonlightArgvPlan plan;
    if (!install.available()) {
        plan.reason = "Moonlight is not installed on this device.";
        return plan;
    }
    if (!isPlainArgvToken(hostSelector)) {
        plan.reason = "The host selector is not a plain token.";
        return plan;
    }
    plan.argv = programPrefix(install);
    plan.argv.emplace_back("quit");
    plan.argv.emplace_back(hostSelector);
    plan.valid = true;
    plan.publicSummary = install.label + " will ask the paired host to end the running app.";
    return plan;
}

DeckMoonlightHandoffSession::DeckMoonlightHandoffSession(QObject* parent)
    : QObject(parent) {}

DeckMoonlightHandoffSession::~DeckMoonlightHandoffSession() {
    // Signals from a dying child must not reach a half-destroyed owner.
    if (process_) {
        process_->disconnect(this);
        if (process_->state() != QProcess::NotRunning) {
            process_->terminate();
            if (!process_->waitForFinished(1500)) {
                process_->kill();
                process_->waitForFinished(500);
            }
        }
    }
    if (quitProcess_) {
        quitProcess_->disconnect(this);
        if (quitProcess_->state() != QProcess::NotRunning) {
            quitProcess_->kill();
            quitProcess_->waitForFinished(500);
        }
    }
}

bool DeckMoonlightHandoffSession::launch(const DeckMoonlightArgvPlan& plan, const DeckMoonlightLaunchRequest& request) {
    if (!plan.valid || plan.argv.empty() || running()) {
        return false;
    }
    if (process_) {
        process_->disconnect(this);
    }
    process_ = std::make_unique<QProcess>(this);
    configureChild(*process_, plan.argv);
    QObject::connect(process_.get(), &QProcess::started, this, &DeckMoonlightHandoffSession::recordStarted);
    QObject::connect(process_.get(), &QProcess::finished, this, &DeckMoonlightHandoffSession::recordFinished);
    QObject::connect(process_.get(), &QProcess::errorOccurred, this, &DeckMoonlightHandoffSession::recordFailure);
    QObject::connect(process_.get(), &QProcess::readyReadStandardOutput, this, &DeckMoonlightHandoffSession::drainOutput);

    outcome_ = DeckMoonlightHandoffOutcome{};
    outcome_.state = DeckMoonlightHandoffState::Starting;
    outcome_.appName = request.appName;
    outcome_.hostSelector = request.hostSelector;
    outcome_.publicCopy = "Opening \"" + request.appName + "\" in Moonlight.";
    emit outcomeChanged();

    process_->start();
    return true;
}

bool DeckMoonlightHandoffSession::requestQuit(const DeckMoonlightInstall& install) {
    if (!running() || outcome_.hostSelector.empty()) {
        return false;
    }
    if (quitProcess_ && quitProcess_->state() != QProcess::NotRunning) {
        return true;  // one quit in flight is enough
    }
    const auto plan = buildMoonlightQuitArgv(install, outcome_.hostSelector);
    if (!plan.valid) {
        return false;
    }
    if (quitProcess_) {
        quitProcess_->disconnect(this);
    }
    quitProcess_ = std::make_unique<QProcess>(this);
    configureChild(*quitProcess_, plan.argv);
    quitProcess_->setStandardOutputFile(QProcess::nullDevice());
    QObject::connect(quitProcess_.get(), &QProcess::finished, this, [this](const int exitCode, const QProcess::ExitStatus status) {
        outcome_.quitState = (status == QProcess::NormalExit && exitCode == 0) ? DeckMoonlightQuitState::Acknowledged : DeckMoonlightQuitState::Failed;
        emit outcomeChanged();
    });
    QObject::connect(quitProcess_.get(), &QProcess::errorOccurred, this, [this](const QProcess::ProcessError) {
        if (outcome_.quitState == DeckMoonlightQuitState::Requested) {
            outcome_.quitState = DeckMoonlightQuitState::Failed;
            emit outcomeChanged();
        }
    });
    outcome_.quitState = DeckMoonlightQuitState::Requested;
    emit outcomeChanged();
    quitProcess_->start();
    return true;
}

void DeckMoonlightHandoffSession::terminate() {
    if (process_ && process_->state() != QProcess::NotRunning) {
        process_->terminate();
    }
}

const DeckMoonlightHandoffOutcome& DeckMoonlightHandoffSession::outcome() const {
    return outcome_;
}

bool DeckMoonlightHandoffSession::running() const {
    return process_ && process_->state() != QProcess::NotRunning;
}

void DeckMoonlightHandoffSession::recordStarted() {
    outcome_.state = DeckMoonlightHandoffState::Running;
    outcome_.publicCopy = "Moonlight is showing \"" + outcome_.appName + "\". Nova returns when it closes.";
    emit outcomeChanged();
}

void DeckMoonlightHandoffSession::drainOutput() {
    if (!process_) {
        return;
    }
    const auto chunk = process_->readAllStandardOutput();
    outcome_.outputTailForBackendOnly.append(chunk.constData(), static_cast<std::size_t>(chunk.size()));
    if (outcome_.outputTailForBackendOnly.size() > kOutputTailBytes) {
        outcome_.outputTailForBackendOnly.erase(0, outcome_.outputTailForBackendOnly.size() - kOutputTailBytes);
    }
}

void DeckMoonlightHandoffSession::recordFinished(const int exitCode, const QProcess::ExitStatus status) {
    drainOutput();
    outcome_.state = DeckMoonlightHandoffState::Exited;
    outcome_.exitCode = exitCode;
    outcome_.crashed = status == QProcess::CrashExit;
    if (outcome_.crashed) {
        outcome_.publicCopy = "Moonlight closed unexpectedly. Nova is back.";
    } else if (exitCode == 0) {
        outcome_.publicCopy = "Moonlight closed. Nova is back.";
    } else {
        outcome_.publicCopy = "Moonlight exited with code " + std::to_string(exitCode) + ". Nova is back.";
    }
    emit outcomeChanged();
}

void DeckMoonlightHandoffSession::recordFailure(const QProcess::ProcessError error) {
    if (outcome_.state != DeckMoonlightHandoffState::Starting) {
        return;
    }
    outcome_.state = DeckMoonlightHandoffState::FailedToStart;
    outcome_.exitCode = -1;
    outcome_.publicCopy = error == QProcess::FailedToStart
        ? "Moonlight could not be started on this device."
        : "Moonlight did not start cleanly.";
    emit outcomeChanged();
}

} // namespace nova::deck::runtime
