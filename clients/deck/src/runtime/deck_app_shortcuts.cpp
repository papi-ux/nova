#include "runtime/deck_app_shortcuts.h"
#include <QCoreApplication>
#include <unistd.h>

namespace nova::deck::runtime {
DeckSteamShortcut novaSteamShortcut(const bool insideFlatpak, const std::string& executable) {
    DeckSteamShortcut shortcut;
    shortcut.appName = "Nova";
    shortcut.exe = "\"" + (insideFlatpak ? std::string{"/usr/bin/flatpak"} : executable) + "\"";
    shortcut.launchOptions = insideFlatpak ? "run com.papi_ux.Nova --standalone" : "--standalone";
    shortcut.startDir = "\"/usr/bin/\"";
    shortcut.tags = {"Nova"};
    return shortcut;
}
DeckShortcutWriteResult registerNovaSteamShortcut(const DeckSteamShortcut& shortcut, const bool insideFlatpak) {
    std::vector<std::filesystem::path> files;
    for (const auto& root : defaultSteamRoots()) {
        for (auto& file : defaultShortcutFiles(root)) files.push_back(std::move(file));
    }
    const auto running = steamClientRunningForAccount(insideFlatpak, static_cast<unsigned>(::getuid()));
    if (!running) return {.detail = "Could not tell whether Steam is running. Close Steam and try again; on Steam Deck, use Desktop Mode."};
    return writeShortcutForAccount(files, shortcut, *running);
}
DeckAppShortcuts::DeckAppShortcuts(Operation operation, QObject* parent) : QObject(parent), operation_(std::move(operation)) {
    if (!operation_) {
        std::error_code error;
        const bool flatpak = std::filesystem::exists("/.flatpak-info", error);
        const auto shortcut = novaSteamShortcut(flatpak, QCoreApplication::applicationFilePath().toStdString());
        operation_ = [shortcut, flatpak] { return registerNovaSteamShortcut(shortcut, flatpak); };
    }
    timer_.setInterval(20);
    connect(&timer_, &QTimer::timeout, this, &DeckAppShortcuts::poll);
}
DeckAppShortcuts::~DeckAppShortcuts() { shutdown(); }
QVariantMap DeckAppShortcuts::state() const {
    return {{"busy", worker_ != nullptr}, {"ok", ok_}, {"retry", attempted_ && !ok_ && !worker_},
        {"canAdd", !worker_ && !blocked_}, {"copy", copy_.isEmpty()
            ? QStringLiteral("Close Steam before adding Nova. On Steam Deck, use Desktop Mode.") : copy_}};
}
void DeckAppShortcuts::setBlocked(const bool blocked) { if (blocked_ != blocked) { blocked_ = blocked; emit stateChanged(); } }
void DeckAppShortcuts::shutdown() {
    timer_.stop();
    if (worker_) { worker_->wait(); delete worker_; worker_ = nullptr; result_.reset(); }
}
bool DeckAppShortcuts::add() {
    if (worker_ || blocked_) return false;
    attempted_ = true; ok_ = false; copy_ = QStringLiteral("Adding Nova to Steam…");
    result_ = std::make_shared<DeckShortcutWriteResult>();
    worker_ = QThread::create([result = result_, operation = operation_] {
        try { *result = operation(); }
        catch (...) { result->detail = "Could not add Nova. Close Steam and try again."; }
    });
    worker_->start(); timer_.start(); emit stateChanged(); return true;
}
void DeckAppShortcuts::poll() {
    if (!worker_ || !worker_->isFinished()) return;
    worker_->wait(); delete worker_; worker_ = nullptr; timer_.stop();
    ok_ = result_->ok;
    copy_ = ok_ ? QStringLiteral("Nova was added to Steam. Reopen Steam; on Steam Deck, return to Gaming Mode and find Nova in Non-Steam games.")
        : QString::fromStdString(result_->detail);
    result_.reset(); emit stateChanged();
}
}
