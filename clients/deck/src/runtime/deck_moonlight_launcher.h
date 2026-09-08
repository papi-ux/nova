#pragma once

#include <QObject>
#include <QProcess>

#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// The one place in the Deck client that starts another program. Nova hands a
// launch to Moonlight-Qt by its command line: `moonlight stream <host> <app>`
// with the host named by the UUID Moonlight already knows, so no address ever
// appears in argv. Everything here is explicit: which Moonlight, which argv,
// which process, and what it said when it ended.
namespace nova::deck::runtime {

enum class DeckMoonlightInstallKind {
    None,
    Native,  ///< a `moonlight` or `moonlight-qt` executable on PATH, or NOVA_DECK_MOONLIGHT_BIN
    Flatpak,  ///< com.moonlight_stream.Moonlight, run through `flatpak run`
};

struct DeckMoonlightInstall {
    DeckMoonlightInstallKind kind = DeckMoonlightInstallKind::None;
    std::string executable;  ///< the native binary, or `flatpak` for the Flatpak kind
    bool novaInsideFlatpak = false;  ///< Nova runs sandboxed, so the launch goes through flatpak-spawn --host
    std::string label;  ///< public wording: "Moonlight (Flatpak)", "Moonlight (native)", "Moonlight not found"

    [[nodiscard]] bool available() const {
        return kind != DeckMoonlightInstallKind::None;
    }
};

struct DeckMoonlightInstallProbe {
    std::optional<std::filesystem::path> overrideBinary;  ///< NOVA_DECK_MOONLIGHT_BIN
    std::vector<std::filesystem::path> pathDirs;
    std::filesystem::path flatpakAppDir;  ///< ~/.var/app/com.moonlight_stream.Moonlight
    std::filesystem::path flatpakInfoFile;  ///< /.flatpak-info, present inside a sandbox
};

DeckMoonlightInstallProbe defaultMoonlightInstallProbe();
DeckMoonlightInstall detectMoonlightInstall(const DeckMoonlightInstallProbe& probe);
DeckMoonlightInstall detectMoonlightInstall();

struct DeckMoonlightLaunchRequest {
    std::string hostSelector;  ///< Moonlight host UUID (preferred) or the exact host name Moonlight shows
    std::string appName;  ///< exact app name as the host lists it
    bool quitAppAfter = false;
    bool fullscreen = true;
    std::optional<int> fps;
    std::optional<int> bitrateKbps;
    std::optional<int> width;
    std::optional<int> height;
};

struct DeckMoonlightArgvPlan {
    bool valid = false;
    std::string reason;  ///< why it is not valid; public-safe wording
    std::vector<std::string> argv;  ///< program first
    std::string publicSummary;  ///< what will run, without private material
};

/// Build the exact command line, refusing anything that is not a plain token.
DeckMoonlightArgvPlan buildMoonlightStreamArgv(const DeckMoonlightInstall& install, const DeckMoonlightLaunchRequest& request);

/// `moonlight quit <host>`: ask the host to end the running app.
DeckMoonlightArgvPlan buildMoonlightQuitArgv(const DeckMoonlightInstall& install, std::string_view hostSelector);

/// True when a token can go straight to a child process: no control characters, quotes or shell syntax.
bool isPlainArgvToken(std::string_view token);

enum class DeckMoonlightHandoffState {
    Idle,
    Starting,
    Running,
    Exited,
    FailedToStart,
};

std::string_view describe(DeckMoonlightHandoffState state);

struct DeckMoonlightHandoffOutcome {
    DeckMoonlightHandoffState state = DeckMoonlightHandoffState::Idle;
    int exitCode = -1;
    bool crashed = false;
    std::string publicCopy;
    std::string appName;
    std::string hostSelector;
};

/**
 * One Moonlight child at a time. The process is started without a shell and
 * with a clean argument vector; stdout and stderr are discarded so Moonlight's
 * own logs, which include host addresses, never enter Nova's output.
 */
class DeckMoonlightHandoffSession final : public QObject {
    Q_OBJECT

public:
    explicit DeckMoonlightHandoffSession(QObject* parent = nullptr);
    ~DeckMoonlightHandoffSession() override;

    [[nodiscard]] bool launch(const DeckMoonlightArgvPlan& plan, const DeckMoonlightLaunchRequest& request);
    /// Run `moonlight quit` for the current host as a separate short-lived process; no-op without a running session.
    [[nodiscard]] bool requestQuit(const DeckMoonlightInstall& install);
    void terminate();

    [[nodiscard]] const DeckMoonlightHandoffOutcome& outcome() const;
    [[nodiscard]] bool running() const;
    [[nodiscard]] qint64 processId() const;

signals:
    void outcomeChanged();

private:
    void recordFinished(int exitCode, QProcess::ExitStatus status);
    void recordFailure(QProcess::ProcessError error);

    std::unique_ptr<QProcess> process_;
    DeckMoonlightHandoffOutcome outcome_;
};

} // namespace nova::deck::runtime
