#include "codec.h"
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>

int main() {
    // Keep loader, layer and driver stdout away from the result pipe.
    const int resultFd = dup(STDOUT_FILENO);
    const int nullFd = open("/dev/null", O_WRONLY);
    if (resultFd < 0 || nullFd < 0 || dup2(nullFd, STDOUT_FILENO) < 0) return 1;
    close(nullFd);
    nova::pyrowave::Codec decoder;
    int limit = 0;
    if (decoder.open(128, 128, false)) limit = decoder.probeGpuLimit();
    if (limit < 0 || limit > 65536) limit = 0;
    char output[128];
    const int size = std::snprintf(output, sizeof(output),
        "{\"version\":1,\"available\":%s,\"maxWidth\":%d,\"maxHeight\":%d}\n",
        limit > 0 ? "true" : "false", limit, limit);
    if (size <= 0 || size >= static_cast<int>(sizeof(output))) return 1;
    for (int sent = 0; sent < size;) {
        const auto count = write(resultFd, output + sent, size - sent);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 1;
        sent += count;
    }
    close(resultFd);
    return 0;
}
