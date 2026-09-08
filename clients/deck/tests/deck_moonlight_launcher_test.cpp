// Tests for the Moonlight launcher: install detection, argv construction, and
// a real child process run against a fake `moonlight` script that records
// what it was asked to do.
#include "runtime/deck_moonlight_launcher.h"

#include <QCoreApplication>
#include <QEventLoop>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>

#include <cassert>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace {

using namespace nova::deck::runtime;
namespace fs = std::filesystem;

fs::path writeExecutable(const fs::path& path, const std::string& body) {
    fs::create_directories(path.parent_path());
    {
        std::ofstream out(path);
        out << body;
    }
    fs::permissions(path, fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec | fs::perms::others_read | fs::perms::others_exec, fs::perm_options::replace);
    return path;
}

bool contains(const std::string& text, const std::string& needle) {
    return text.find(needle) != std::string::npos;
}

std::string joined(const std::vector<std::string>& argv) {
    std::string out;
    for (const auto& token : argv) {
        if (!out.empty()) {
            out += ' ';
        }
        out += token;
    }
    return out;
}

void testPlainTokens() {
    assert(isPlainArgvToken("935B1F5B-D2EC-E720-6600-5EB7986004EC"));
    assert(isPlainArgvToken("Slay the Spire 2"));
    assert(isPlainArgvToken("No, I'm not a Human"));
    // No shell is involved, so titles with shell-looking characters are fine.
    assert(isPlainArgvToken("Ratchet & Clank"));
    assert(isPlainArgvToken("quoted \"name\""));
    assert(isPlainArgvToken("$(not a shell)"));
    assert(!isPlainArgvToken(""));
    assert(!isPlainArgvToken("a\nb"));
    assert(!isPlainArgvToken(std::string(600, 'x')));
}

void testDetectionPrefersOverrideThenPathThenFlatpak() {
    QTemporaryDir dir;
    const fs::path root(dir.path().toStdString());
    DeckMoonlightInstallProbe probe;
    probe.pathDirs = {root / "bin"};
    probe.flatpakAppDir = root / "flatpak-app";
    probe.flatpakInfoFile = root / "no-flatpak-info";

    assert(!detectMoonlightInstall(probe).available());
    assert(detectMoonlightInstall(probe).label == "Moonlight not found");

    fs::create_directories(probe.flatpakAppDir);
    auto onlyApp = detectMoonlightInstall(probe);
    assert(!onlyApp.available() && "the Flatpak app dir alone is not enough without the flatpak tool");

    writeExecutable(root / "bin" / "flatpak", "#!/bin/sh\nexit 0\n");
    auto flatpak = detectMoonlightInstall(probe);
    assert(flatpak.kind == DeckMoonlightInstallKind::Flatpak);
    assert(flatpak.executable == (root / "bin" / "flatpak").string());
    assert(!flatpak.novaInsideFlatpak);

    writeExecutable(root / "bin" / "moonlight-qt", "#!/bin/sh\nexit 0\n");
    auto native = detectMoonlightInstall(probe);
    assert(native.kind == DeckMoonlightInstallKind::Native);
    assert(contains(native.executable, "moonlight-qt"));

    probe.overrideBinary = writeExecutable(root / "override" / "fake-moonlight", "#!/bin/sh\nexit 0\n");
    auto overridden = detectMoonlightInstall(probe);
    assert(overridden.kind == DeckMoonlightInstallKind::Native);
    assert(overridden.executable == probe.overrideBinary->string());

    probe.flatpakInfoFile = writeExecutable(root / "flatpak-info", "[Application]\n");
    assert(detectMoonlightInstall(probe).novaInsideFlatpak);
}

void testStreamArgvShapes() {
    DeckMoonlightLaunchRequest request;
    request.hostSelector = "935B1F5B-D2EC-E720-6600-5EB7986004EC";
    request.appName = "Slay the Spire 2";

    DeckMoonlightInstall native;
    native.kind = DeckMoonlightInstallKind::Native;
    native.executable = "/usr/bin/moonlight";
    native.label = "Moonlight (native)";
    const auto plain = buildMoonlightStreamArgv(native, request);
    assert(plain.valid);
    assert(joined(plain.argv) == "/usr/bin/moonlight stream 935B1F5B-D2EC-E720-6600-5EB7986004EC Slay the Spire 2 --display-mode fullscreen");
    assert(contains(plain.publicSummary, "Moonlight (native) will open \"Slay the Spire 2\""));
    assert(!contains(plain.publicSummary, "935B1F5B"));

    DeckMoonlightInstall flatpak;
    flatpak.kind = DeckMoonlightInstallKind::Flatpak;
    flatpak.executable = "/usr/bin/flatpak";
    flatpak.label = "Moonlight (Flatpak)";
    request.quitAppAfter = true;
    request.fps = 60;
    request.bitrateKbps = 20000;
    request.width = 1280;
    request.height = 800;
    const auto viaFlatpak = buildMoonlightStreamArgv(flatpak, request);
    assert(viaFlatpak.valid);
    assert(joined(viaFlatpak.argv) == "/usr/bin/flatpak run com.moonlight_stream.Moonlight stream 935B1F5B-D2EC-E720-6600-5EB7986004EC Slay the Spire 2 --display-mode fullscreen --quit-after --fps 60 --bitrate 20000 --resolution 1280x800");

    flatpak.novaInsideFlatpak = true;
    // Under gamescope the sandbox sees these; the host command must too.
    for (const char* name : {"WAYLAND_DISPLAY", "DISPLAY", "XDG_RUNTIME_DIR", "XDG_SESSION_TYPE", "XAUTHORITY", "QT_QPA_PLATFORM"}) {
        unsetenv(name);
    }
    setenv("WAYLAND_DISPLAY", "gamescope-0", 1);
    setenv("XDG_RUNTIME_DIR", "/run/user/1000", 1);
    setenv("QT_QPA_PLATFORM", "wayland", 1);
    setenv("DISPLAY", "bad\nvalue", 1);  // not plain: never forwarded
    const auto sandboxed = buildMoonlightStreamArgv(flatpak, request);
    assert(sandboxed.valid);
    assert(sandboxed.argv[0] == "flatpak-spawn" && sandboxed.argv[1] == "--host");
    assert(sandboxed.argv[2] == "--env=WAYLAND_DISPLAY=gamescope-0");
    assert(sandboxed.argv[3] == "--env=XDG_RUNTIME_DIR=/run/user/1000");
    assert(sandboxed.argv[4] == "--env=QT_QPA_PLATFORM=wayland");
    assert(sandboxed.argv[5] == "flatpak" && sandboxed.argv[6] == "run");
    assert(joined(sandboxed.argv).find("DISPLAY=bad") == std::string::npos);
    unsetenv("DISPLAY");
    const auto quitSandboxed = buildMoonlightQuitArgv(flatpak, "935B1F5B-D2EC-E720-6600-5EB7986004EC");
    assert(quitSandboxed.valid && quitSandboxed.argv[2] == "--env=WAYLAND_DISPLAY=gamescope-0");
    for (const char* name : {"WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "QT_QPA_PLATFORM"}) {
        unsetenv(name);
    }
    flatpak.novaInsideFlatpak = false;

    request.appName = "bad\nname";
    assert(!buildMoonlightStreamArgv(flatpak, request).valid);
    request.appName = "ok";
    request.fullscreen = false;
    const auto windowed = buildMoonlightStreamArgv(flatpak, request);
    assert(windowed.valid && contains(joined(windowed.argv), "--display-mode windowed"));
    request.fps = 1000;
    assert(!buildMoonlightStreamArgv(flatpak, request).valid);
    request.fps.reset();
    request.height.reset();
    assert(!buildMoonlightStreamArgv(flatpak, request).valid && "width without height");

    DeckMoonlightInstall none;
    assert(!buildMoonlightStreamArgv(none, request).valid);

    flatpak.novaInsideFlatpak = true;
    const auto quit = buildMoonlightQuitArgv(flatpak, "935B1F5B-D2EC-E720-6600-5EB7986004EC");
    assert(quit.valid);
    assert(joined(quit.argv) == "flatpak-spawn --host flatpak run com.moonlight_stream.Moonlight quit 935B1F5B-D2EC-E720-6600-5EB7986004EC" && "no display variables set: nothing forwarded");
    assert(!buildMoonlightQuitArgv(flatpak, "x\ty").valid);
}

void waitForOutcome(DeckMoonlightHandoffSession& session, const DeckMoonlightHandoffState wanted, const int timeoutMs) {
    QEventLoop loop;
    QTimer timer;
    timer.setSingleShot(true);
    QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
    QObject::connect(&session, &DeckMoonlightHandoffSession::outcomeChanged, &loop, [&]() {
        if (session.outcome().state == wanted) {
            loop.quit();
        }
    });
    timer.start(timeoutMs);
    while (session.outcome().state != wanted && timer.isActive()) {
        loop.exec();
    }
}

void testRealChildProcessAgainstAFakeMoonlight() {
    QTemporaryDir dir;
    const fs::path root(dir.path().toStdString());
    const auto record = root / "argv.txt";
    const auto fake = writeExecutable(root / "fake-moonlight", "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + record.string() + "'\necho 'recorder says hi' >&2\nexit 3\n");

    DeckMoonlightInstall install;
    install.kind = DeckMoonlightInstallKind::Native;
    install.executable = fake.string();
    install.label = "Moonlight (native, override)";

    DeckMoonlightLaunchRequest request;
    request.hostSelector = "host-uuid";
    request.appName = "Desktop";
    const auto plan = buildMoonlightStreamArgv(install, request);
    assert(plan.valid);

    DeckMoonlightHandoffSession session;
    assert(session.outcome().state == DeckMoonlightHandoffState::Idle);
    assert(session.launch(plan, request));
    assert(session.outcome().state == DeckMoonlightHandoffState::Starting && "launch never blocks on the child");
    assert(session.outcome().appName == "Desktop");
    waitForOutcome(session, DeckMoonlightHandoffState::Exited, 5000);
    assert(session.outcome().state == DeckMoonlightHandoffState::Exited);
    assert(session.outcome().exitCode == 3);
    assert(!session.outcome().crashed);
    assert(contains(session.outcome().publicCopy, "code 3"));
    assert(contains(session.outcome().outputTailForBackendOnly, "recorder says hi"));
    assert(!contains(session.outcome().publicCopy, "recorder says hi") && "child output never reaches public copy");
    assert(!session.running());

    std::ifstream in(record);
    std::string recorded((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    assert(recorded == "stream\nhost-uuid\nDesktop\n--display-mode\nfullscreen\n");

    // A second launch after exit is allowed; a missing binary fails closed.
    DeckMoonlightInstall missing = install;
    missing.executable = (root / "does-not-exist").string();
    const auto badPlan = buildMoonlightStreamArgv(missing, request);
    assert(badPlan.valid && "argv construction does not stat the binary");
    DeckMoonlightHandoffSession second;
    assert(second.launch(badPlan, request) && "the attempt is accepted; the failure arrives through the signal");
    waitForOutcome(second, DeckMoonlightHandoffState::FailedToStart, 5000);
    assert(second.outcome().state == DeckMoonlightHandoffState::FailedToStart);
    assert(contains(second.outcome().publicCopy, "could not be started"));
}

void testQuitRunsTheQuitCommandWhileRunning() {
    QTemporaryDir dir;
    const fs::path root(dir.path().toStdString());
    const auto record = root / "argv.txt";
    // stream: sleep until told; quit: record and exit.
    const auto fake = writeExecutable(root / "fake-moonlight",
        "#!/bin/sh\nif [ \"$1\" = quit ]; then printf '%s\\n' \"$@\" > '" + record.string() + "'; exit 0; fi\nsleep 20\n");
    DeckMoonlightInstall install;
    install.kind = DeckMoonlightInstallKind::Native;
    install.executable = fake.string();
    install.label = "Moonlight (native, override)";
    DeckMoonlightLaunchRequest request;
    request.hostSelector = "host-uuid";
    request.appName = "Desktop";
    DeckMoonlightHandoffSession session;
    assert(session.launch(buildMoonlightStreamArgv(install, request), request));
    waitForOutcome(session, DeckMoonlightHandoffState::Running, 5000);
    assert(session.running());
    assert(session.requestQuit(install));
    assert(session.outcome().quitState == DeckMoonlightQuitState::Requested);
    // The timed processEvents overload never waits, so sleep between passes.
    for (int i = 0; i < 100 && session.outcome().quitState == DeckMoonlightQuitState::Requested; ++i) {
        QCoreApplication::processEvents();
        QThread::msleep(50);
    }
    assert(session.outcome().quitState == DeckMoonlightQuitState::Acknowledged);
    std::ifstream in(record);
    std::string recorded((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    assert(recorded == "quit\nhost-uuid\n");
    session.terminate();
    waitForOutcome(session, DeckMoonlightHandoffState::Exited, 5000);
    assert(session.outcome().state == DeckMoonlightHandoffState::Exited);
    assert(!session.running());
    DeckMoonlightHandoffSession idle;
    assert(!idle.requestQuit(install));
}

} // namespace

int main(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);
    testPlainTokens();
    testDetectionPrefersOverrideThenPathThenFlatpak();
    testStreamArgvShapes();
    testRealChildProcessAgainstAFakeMoonlight();
    testQuitRunsTheQuitCommandWhileRunning();
    return 0;
}
