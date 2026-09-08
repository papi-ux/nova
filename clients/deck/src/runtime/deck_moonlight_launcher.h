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
// which process, and what it said when it ended. Nothing blocks the caller;
// results arrive through outcomeChanged().
namespace nova::deck::runtime {

enum class DeckMoonlightInstallKind {
    None,
    Native,  ///< a `moonlight` or `moonlight-qt` executable on PATH, or NOVA_DECK_MOONLIGHT_BIN
    Flatpak,  ///< com.moonlight_stream.Moonlight, run through `flatpak run`
};

/// Where Nova's own window lives, as this process sees it. defaultMoonlightInstallProbe()
/// reads it from the environment; tests inject values.
struct DeckDisplayEnvironment {
    std::string waylandDisplay;  ///< WAYLAND_DISPLAY, empty when unset
    std::string display;  ///< DISPLAY, empty when unset
    std::filesystem::path runtimeDir;  ///< XDG_RUNTIME_DIR, where Wayland sockets live
};

/// What a sandboxed Nova hands to `flatpak-spawn --env=` so the host command can find the display.
struct DeckForwardedEnvironment {
    std::vector<std::string> assignments;  ///< NAME=VALUE, in argv order
    std::vector<std::string> skipped;  ///< backend-only notes about values that were set but not forwarded
};

struct DeckMoonlightInstall {
    DeckMoonlightInstallKind kind = DeckMoonlightInstallKind::None;
    std::string executable;  ///< the native binary, or `flatpak` for the Flatpak kind
    bool novaInsideFlatpak = false;  ///< Nova runs sandboxed, so the launch goes through flatpak-spawn --host
    DeckForwardedEnvironment forwardedEnvironment;  ///< filled only when novaInsideFlatpak
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
    DeckDisplayEnvironment displayEnvironment;  ///< forwarded to the host command when Nova is sandboxed
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

/// Build the exact command line. Tokens go straight to the child, never through a shell.
DeckMoonlightArgvPlan buildMoonlightStreamArgv(const DeckMoonlightInstall& install, const DeckMoonlightLaunchRequest& request);

/// `moonlight quit <host>`: ask the host to end the running app.
DeckMoonlightArgvPlan buildMoonlightQuitArgv(const DeckMoonlightInstall& install, std::string_view hostSelector);

/// The name Flatpak gives a host Wayland socket whose name does not start with "wayland-".
inline constexpr std::string_view kFlatpakRenamedWaylandDisplay = "wayland-0";
/// Forwarded with the Wayland socket so Moonlight draws on it directly; Qt tries the platforms in order.
inline constexpr std::string_view kForwardedQtPlatform = "wayland;xcb";

/**
 * The host's name for the Wayland socket Nova is on. Flatpak binds the host
 * socket into the sandbox but renames any WAYLAND_DISPLAY that does not start
 * with "wayland-" to "wayland-0" (gamescope-0 on a Steam Deck), so the value
 * Nova reads cannot be handed to a host command as it is. The bound socket
 * keeps its inode, so when Nova sees the renamed value this looks for another
 * socket in the runtime directory with the same inode and returns that name;
 * the Flatpak manifest binds xdg-run/gamescope-0 for exactly this reason. Any
 * other value is returned unchanged.
 */
std::string hostWaylandDisplayName(const DeckDisplayEnvironment& environment);

/**
 * What a sandboxed Nova forwards to the host command. flatpak-spawn runs it
 * with the host session's environment, not Nova's; under gamescope that
 * environment names no display at all, so Moonlight would fall back to driving
 * DRM directly and be refused. Forwarded: WAYLAND_DISPLAY translated back to
 * the host's socket name, QT_QPA_PLATFORM=wayland;xcb whenever that socket
 * travels, and DISPLAY as Nova sees it (Flatpak passes it through unchanged).
 * Nothing else: XAUTHORITY and XDG_RUNTIME_DIR are sandbox-synthesized paths,
 * and Nova's own platform choice (offscreen in the headless proofs) must not
 * be imposed on Moonlight. Each assembled `--env=` token has to be plain;
 * anything skipped is named in `skipped`.
 */
DeckForwardedEnvironment forwardedDisplayEnvironment(const DeckDisplayEnvironment& environment);

/// True when a token can go straight to a child process: no control characters and a sane length.
/// Shell metacharacters are fine because no shell is involved; game titles carry ampersands and quotes.
bool isPlainArgvToken(std::string_view token);

enum class DeckMoonlightHandoffState {
    Idle,
    Starting,
    Running,
    Exited,
    FailedToStart,
};

std::string_view describe(DeckMoonlightHandoffState state);

enum class DeckMoonlightQuitState {
    NotRequested,
    Requested,
    Acknowledged,
    Failed,
};

std::string_view describe(DeckMoonlightQuitState state);

struct DeckMoonlightHandoffOutcome {
    DeckMoonlightHandoffState state = DeckMoonlightHandoffState::Idle;
    DeckMoonlightQuitState quitState = DeckMoonlightQuitState::NotRequested;
    int exitCode = -1;
    bool crashed = false;
    std::string publicCopy;
    std::string appName;
    std::string hostSelector;
    /// Bounded tail of Moonlight's own output. It can carry host addresses, so it stays out of every public surface;
    /// the headless proof prints it, the shell never does.
    std::string outputTailForBackendOnly;
};

/**
 * One Moonlight child at a time. The process is started without a shell and
 * with a clean argument vector. Its output is drained into a bounded tail
 * that only backend-facing callers read. Every state change is announced
 * through outcomeChanged(); no method blocks on the child.
 */
class DeckMoonlightHandoffSession final : public QObject {
    Q_OBJECT

public:
    static constexpr std::size_t kOutputTailBytes = 4096;

    explicit DeckMoonlightHandoffSession(QObject* parent = nullptr);
    ~DeckMoonlightHandoffSession() override;

    /// Start Moonlight. Returns false when the plan is invalid or a child is already running; success and failure to
    /// start both arrive later through outcomeChanged().
    [[nodiscard]] bool launch(const DeckMoonlightArgvPlan& plan, const DeckMoonlightLaunchRequest& request);
    /// Run `moonlight quit` for the current host as a separate child; its result arrives through outcomeChanged().
    [[nodiscard]] bool requestQuit(const DeckMoonlightInstall& install);
    /// Ask the child to stop (SIGTERM). Used when Nova itself is closing.
    void terminate();

    [[nodiscard]] const DeckMoonlightHandoffOutcome& outcome() const;
    [[nodiscard]] bool running() const;

signals:
    void outcomeChanged();

private:
    void recordStarted();
    void recordFinished(int exitCode, QProcess::ExitStatus status);
    void recordFailure(QProcess::ProcessError error);
    void drainOutput();

    std::unique_ptr<QProcess> quitProcess_;
    DeckMoonlightHandoffOutcome outcome_;
    std::unique_ptr<QProcess> process_;  ///< last member so it is destroyed first, before anything its signals touch
};

} // namespace nova::deck::runtime
