#include "codec.h"
#include <iostream>

int main() {
    nova::pyrowave::Codec decoder;
    int limit = 0;
    if (decoder.open(128, 128, false)) limit = decoder.probeGpuLimit();
    if (limit < 0 || limit > 65536) limit = 0;
    std::cout << "{\"version\":1,\"available\":" << (limit > 0 ? "true" : "false")
              << ",\"maxWidth\":" << limit << ",\"maxHeight\":" << limit << "}\n";
    return 0;
}
