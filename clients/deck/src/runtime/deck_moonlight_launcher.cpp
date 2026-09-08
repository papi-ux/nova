#include "runtime/deck_moonlight_launcher.h"

#include <QString>
#include <QStringList>

#include <algorithm>
#include <cctype>
#include <cstdlib>
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

std::optional<std::filesystem::path> findOnPath(const std::vector<std::filesystem::path>& dirs, const std::string_view name) {
    for (const auto& dir : dirs) {
        const auto candidate = dir / name;
        if (executableFile(candidate)) {
            return candidate;
        }
    }
    return std::nullopt;
}

QStringList toQStringList(const std::vector<std::string>& tokens) {
    QStringList list;
    for (const auto& token : tokens) {
        list.push_back(QString::fromStdString(token));
    }
    return list;
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
        if (ch == '`' || ch == '$' || ch == ';' || ch == '|' || ch == '&' || ch == '<' || ch == '>' || ch == '"') {
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

DeckMoonlightInstallProbe defaultMoonlightInstallProbe() {
    DeckMoonlightInstallProbe probe;
    if (const char* override = std::getenv("NOVA_DECK_MOONLIGHT_BIN"); override != nullptr && *override != '\0') {
        probe.overrideBinary = std::filesystem::path(override);
    }
    if (const char* path = std::getenv("PATH"); path != nullptr && *path != '\0') {
        std::string_view remaining{path};
        while (!remaining.empty()) {
            const auto colon = remaining.find(':');
            const auto entry = remaining.substr(0, colon);
            if (!entry.empty()) {
                probe.pathDirs.emplace_back(entry);
            }
            if (colon == std::string_view::npos) {
                break;
            }
            remaining.remove_prefix(colon + 1);
        }
    }
    for (const char* dir : {"/usr/bin", "/usr/local/bin", "/var/lib/flatpak/exports/bin"}) {
        if (std::find(probe.pathDirs.begin(), probe.pathDirs.end(), std::filesystem::path(dir)) == probe.pathDirs.end()) {
            probe.pathDirs.emplace_back(dir);
        }
    }
    if (const auto home = homeDirectory(); !home.empty()) {
        probe.flatpakAppDir = home / ".var" / "app" / std::string(kFlatpakAppId);
        probe.pathDirs.push_back(home / ".local" / "share" / "flatpak" / "exports" / "bin");
    }
    probe.flatpakInfoFile = "/.flatpak-info";
    return probe;
}

DeckMoonlightInstall detectMoonlightInstall(const DeckMoonlightInstallProbe& probe) {
    DeckMoonlightInstall install;
    std::error_code ec;
    install.novaInsideFlatpak = !probe.flatpakInfoFile.empty() && std::filesystem::exists(probe.flatpakInfoFile, ec);

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
        prefix = {"flatpak-spawn", "--host"};
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
    if (process_ && process_->state() != QProcess::NotRunning) {
        process_->kill();
        process_->waitForFinished(1000);
    }
}

bool DeckMoonlightHandoffSession::launch(const DeckMoonlightArgvPlan& plan, const DeckMoonlightLaunchRequest& request) {
    if (!plan.valid || plan.argv.empty() || running()) {
        return false;
    }
    process_ = std::make_unique<QProcess>(this);
    process_->setProgram(QString::fromStdString(plan.argv.front()));
    process_->setArguments(toQStringList(std::vector<std::string>(plan.argv.begin() + 1, plan.argv.end())));
    process_->setStandardOutputFile(QProcess::nullDevice());
    process_->setStandardErrorFile(QProcess::nullDevice());
    QObject::connect(process_.get(), &QProcess::finished, this, &DeckMoonlightHandoffSession::recordFinished);
    QObject::connect(process_.get(), &QProcess::errorOccurred, this, &DeckMoonlightHandoffSession::recordFailure);

    outcome_ = DeckMoonlightHandoffOutcome{};
    outcome_.state = DeckMoonlightHandoffState::Starting;
    outcome_.appName = request.appName;
    outcome_.hostSelector = request.hostSelector;
    outcome_.publicCopy = "Opening \"" + request.appName + "\" in Moonlight.";
    emit outcomeChanged();

    process_->start();
    if (!process_->waitForStarted(5000)) {
        if (outcome_.state == DeckMoonlightHandoffState::Starting) {
            recordFailure(process_->error());
        }
        return false;
    }
    outcome_.state = DeckMoonlightHandoffState::Running;
    outcome_.publicCopy = "Moonlight is streaming \"" + request.appName + "\". Nova returns when it closes.";
    emit outcomeChanged();
    return true;
}

bool DeckMoonlightHandoffSession::requestQuit(const DeckMoonlightInstall& install) {
    if (!running() || outcome_.hostSelector.empty()) {
        return false;
    }
    const auto plan = buildMoonlightQuitArgv(install, outcome_.hostSelector);
    if (!plan.valid) {
        return false;
    }
    QProcess quit;
    quit.setProgram(QString::fromStdString(plan.argv.front()));
    quit.setArguments(toQStringList(std::vector<std::string>(plan.argv.begin() + 1, plan.argv.end())));
    quit.setStandardOutputFile(QProcess::nullDevice());
    quit.setStandardErrorFile(QProcess::nullDevice());
    quit.start();
    const bool started = quit.waitForStarted(5000);
    if (started) {
        quit.waitForFinished(15000);
    }
    return started;
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

qint64 DeckMoonlightHandoffSession::processId() const {
    return process_ ? process_->processId() : 0;
}

void DeckMoonlightHandoffSession::recordFinished(const int exitCode, const QProcess::ExitStatus status) {
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
