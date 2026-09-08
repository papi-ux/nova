// Tests for the Moonlight launcher: install detection, argv construction, and
// a real child process run against a fake `moonlight` script that records
// what it was asked to do.
#include "runtime/deck_moonlight_launcher.h"

#include <QCoreApplication>
#include <QEventLoop>
#include <QTemporaryDir>
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
    assert(!isPlainArgvToken(""));
    assert(!isPlainArgvToken("game; rm -rf"));
    assert(!isPlainArgvToken("$(id)"));
    assert(!isPlainArgvToken("a\nb"));
    assert(!isPlainArgvToken("quoted \"name\""));
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
    assert(joined(plain.argv) == "/usr/bin/moonlight stream 935B1F5B-D2EC-E720-6600-5EB7986004EC Slay the Spire 2 --fullscreen");
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
    assert(joined(viaFlatpak.argv) == "/usr/bin/flatpak run com.moonlight_stream.Moonlight stream 935B1F5B-D2EC-E720-6600-5EB7986004EC Slay the Spire 2 --fullscreen --quit-after --fps 60 --bitrate 20000 --resolution 1280x800");

    flatpak.novaInsideFlatpak = true;
    const auto sandboxed = buildMoonlightStreamArgv(flatpak, request);
    assert(sandboxed.valid);
    assert(sandboxed.argv[0] == "flatpak-spawn" && sandboxed.argv[1] == "--host" && sandboxed.argv[2] == "flatpak" && sandboxed.argv[3] == "run");

    request.appName = "bad; name";
    assert(!buildMoonlightStreamArgv(flatpak, request).valid);
    request.appName = "ok";
    request.fps = 1000;
    assert(!buildMoonlightStreamArgv(flatpak, request).valid);
    request.fps.reset();
    request.height.reset();
    assert(!buildMoonlightStreamArgv(flatpak, request).valid && "width without height");

    DeckMoonlightInstall none;
    assert(!buildMoonlightStreamArgv(none, request).valid);

    const auto quit = buildMoonlightQuitArgv(flatpak, "935B1F5B-D2EC-E720-6600-5EB7986004EC");
    assert(quit.valid);
    assert(joined(quit.argv) == "flatpak-spawn --host flatpak run com.moonlight_stream.Moonlight quit 935B1F5B-D2EC-E720-6600-5EB7986004EC");
    assert(!buildMoonlightQuitArgv(flatpak, "x y;").valid);
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
    const auto fake = writeExecutable(root / "fake-moonlight", "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + record.string() + "'\nexit 3\n");

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
    assert(session.outcome().appName == "Desktop");
    waitForOutcome(session, DeckMoonlightHandoffState::Exited, 5000);
    assert(session.outcome().state == DeckMoonlightHandoffState::Exited);
    assert(session.outcome().exitCode == 3);
    assert(!session.outcome().crashed);
    assert(contains(session.outcome().publicCopy, "code 3"));
    assert(!session.running());

    std::ifstream in(record);
    std::string recorded((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    assert(recorded == "stream\nhost-uuid\nDesktop\n--fullscreen\n");

    // A second launch after exit is allowed; a missing binary fails closed.
    DeckMoonlightInstall missing = install;
    missing.executable = (root / "does-not-exist").string();
    const auto badPlan = buildMoonlightStreamArgv(missing, request);
    assert(badPlan.valid && "argv construction does not stat the binary");
    DeckMoonlightHandoffSession second;
    assert(!second.launch(badPlan, request));
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
    assert(session.running());
    assert(session.requestQuit(install));
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
