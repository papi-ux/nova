#include "codec.h"
#include <cstdlib>
#include <iostream>
#include <random>
#include <stdexcept>
using namespace nova::pyrowave;
namespace {
void require(bool ok) { if (!ok) throw std::runtime_error("PyroWave parser contract failed"); }
void put(std::vector<std::uint8_t>& bytes, std::uint32_t word) {
    for (int i = 0; i < 4; ++i) bytes.push_back(word >> (8 * i));
}
std::vector<std::uint8_t> frame(std::initializer_list<std::uint32_t> payload) {
    std::vector<std::uint8_t> bytes;
    for (auto word : payload) put(bytes, word);
    return bytes;
}
}
int main() try {
    constexpr auto header = 0x80000000u | 127u | (95u << 14);
    std::vector<std::span<const std::uint8_t>> packets;
    auto valid = frame({header, 1, 2u << 16, 0});
    require(unpackFrame(valid, 128, 96, packets));
    // Truncated control data, missing coefficient/sign bytes, zero progress,
    // duplicate block indices, wrong sequence/chroma, and out-of-range blocks.
    for (auto bad : {frame({header, 1, (2u << 16) | 1, 0}),
                     frame({header, 1, (4u << 16) | 1, 0, 0x000f0000, 0}),
                     frame({header, 1, 0, 0}),
                     frame({header, 2, 2u << 16, 0, 2u << 16, 0}),
                     frame({header, 1, (2u << 16) | (1u << 28), 0}),
                     frame({header, 0x01000001, 2u << 16, 0}),
                     frame({header, 1, 2u << 16, 0xffffff00})}) {
        require(!unpackFrame(bad, 128, 96, packets));
        require(packets.empty());
    }
    // Deterministic mutations run under ASan/UBSan as well as regular CTest.
    std::mt19937 random(1860393);
    for (int iteration = 0; iteration < 100000; ++iteration) {
        auto bytes = valid;
        bytes.resize(random() % 128);
        for (unsigned j = 0, count = random() % 12; j < count && !bytes.empty(); ++j)
            bytes[random() % bytes.size()] = random();
        const bool ok = unpackFrame(bytes, 128, 96, packets);
        if (!ok) require(packets.empty());
        else for (auto packet : packets)
            require(packet.data() >= bytes.data() && packet.data() + packet.size() <= bytes.data() + bytes.size());
    }
    std::cout << "PyroWave inner-payload rejection and 100000 parser mutations passed\n";
} catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
