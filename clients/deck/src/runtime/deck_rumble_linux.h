#pragma once

#include "runtime/deck_rumble.h"
#include <QString>
#include <sys/stat.h>
#include <sys/types.h>

namespace nova::deck::runtime {
// Injectable syscall boundary for identity/capability/error tests. Production
// roots and calls are fixed; no host-provided path reaches this interface.
struct DeckRumbleLinuxIo {
    QString sysRoot = "/sys", inputRoot = "/dev/input";
    std::function<int(int, struct stat*)> stat;
    std::function<int(const char*, int)> open;
    std::function<int(int, unsigned long, std::uintptr_t)> ioctl;
    std::function<ssize_t(int, const void*, std::size_t)> write;
    std::function<void(int)> close;
};
std::unique_ptr<DeckRumbleDevice> openDeckRumbleDevice(int joystickFd, DeckRumbleLinuxIo io);
}
