#include "codec.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <fcntl.h>

using namespace nova::pyrowave;
namespace {
void require(bool ok, const char* detail) {
    if (!ok) throw std::runtime_error(detail);
}
void write32(std::vector<std::uint8_t>& frame, std::size_t offset, std::uint32_t value) {
    for (int i = 0; i < 4; ++i) frame[offset + i] = static_cast<std::uint8_t>(value >> (8 * i));
}
}
int main() try {
    constexpr int width = 1280, height = 800;
    require(!Image::allocate(0, height).valid(), "zero size accepted");
    require(!Image::allocate(4098, height).valid(), "oversized image accepted");
    require(!Image::allocate(1279, height).valid(), "odd 420 size accepted");
    std::vector<std::span<const std::uint8_t>> packets;
    require(!unpackFrame({}, width, height, packets), "empty packet accepted");
    Codec encoder, decoder;
    if (!encoder.open(width, height, true) || !decoder.open(width, height, false)) {
        std::cerr << "GPU unavailable: " << encoder.error() << ' ' << decoder.error() << '\n';
        return 77;
    }
    auto source = Image::allocate(width, height);
    std::fill(source.planes[1].begin(), source.planes[1].end(), 128);
    std::fill(source.planes[2].begin(), source.planes[2].end(), 128);
    Image output;
    std::vector<std::uint8_t> frame;
    unsigned freshGpuSequences = 0;
    for (int index = 0; index < 12; ++index) {
        for (int y = 0; y < height; ++y)
            for (int x = 0; x < width; ++x)
                source.planes[0][y * width + x] = 48 + ((x / 16 + y / 16 + index * 3) % 112) +
                    ((std::uint32_t(x * 1664525u + y * 1013904223u) >> 16) & 31);
        require(encoder.encode(source, 400000, frame), encoder.error().c_str());
        require(unpackFrame(frame, width, height, packets), "own frame failed bitstream validation");
        require(frame.size() > packetBytes, "did not exercise a frame spanning coefficient packet boundaries");
        // Exercise every sequence value on a fresh GPU decoder. A preceding CPU
        // decode or gray capability probe must not hide first-frame readiness bugs.
        if (index < 8) {
            Codec cold;
            require(cold.open(width, height, false), cold.error().c_str());
            GpuImage first;
            require(cold.decodeGpu(frame, first), cold.error().c_str());
            require(first.owner && first.width == width && first.height == height,
                "fresh GPU decoder did not produce the first moving frame");
            freshGpuSequences |= 1u << ((frame[3] >> 4) & 7);
        }
        require(decoder.decode(frame, output), decoder.error().c_str());
        require(output.valid(), "decoded image invalid");
        double sum = 0;
        for (std::size_t i = 0; i < source.planes[0].size(); ++i)
            sum += std::abs(int(source.planes[0][i]) - int(output.planes[0][i]));
        require(sum / source.planes[0].size() < 8, "decoded luma diverged from moving source");
    }
    require(freshGpuSequences == 0xff, "fresh GPU test did not cover all sequence values");
    const auto previous = output.planes;
    auto reject = [&](const std::vector<std::uint8_t>& bad) {
        require(!decoder.decode(bad, output), "malformed frame decoded");
        require(output.planes == previous, "bad frame replaced display image");
        require(!unpackFrame(bad, width, height, packets), "malformed bitstream accepted");
        require(packets.empty(), "failed parser retained borrowed packets");
    };
    for (std::size_t cut : {std::size_t(0), std::size_t(7), std::size_t(19), frame.size() - 1})
        reject({frame.begin(), frame.begin() + cut});
    auto bad = frame; bad[0] ^= 2; reject(bad); // size changed within session
    bad = frame; bad[7] |= 1; reject(bad); // unsupported chroma
    bad = frame; write32(bad, 4, 0xffffffff); reject(bad); // huge block count
    bad = frame; write32(bad, 8, 0); reject(bad); // no block progress
    bad = frame; bad.push_back(0); reject(bad);
    require(!encoder.encode(source, maxFrameBytes, frame), "unbounded rate budget accepted");
    require(frame.empty(), "failed encode retained stale frame");
    // Restarting the decoder resets upstream's small sequence counter.
    decoder.close();
    require(decoder.open(width, height, false), decoder.error().c_str());
    require(encoder.encode(source, 400000, frame), encoder.error().c_str());
    GpuImage gpu;
    require(decoder.decodeGpu(frame, gpu), decoder.error().c_str());
    require(gpu.owner && gpu.width == width && gpu.height == height, "missing GPU frame owner");
    const auto previousOwner = gpu.owner.get();
    bad = frame; bad[0] ^= 2;
    require(!decoder.decodeGpu(bad, gpu), "malformed GPU frame decoded");
    require(!decoder.error().empty() && gpu.owner.get() == previousOwner,
        "failed GPU decode lost its error or replaced the display image");
    require(decoder.decodeGpu(frame, gpu), decoder.error().c_str());
    decoder.close();
    for (const auto& plane : gpu.planes)
        require(plane.fd >= 0 && fcntl(plane.fd, F_GETFD) >= 0 && plane.pitch > 0 && plane.size > 0,
            "GPU frame did not survive session teardown");
    const auto fd = gpu.planes[0].fd;
    gpu = {};
    require(fcntl(fd, F_GETFD) == -1, "GPU frame leaked its DMA-BUF descriptor");
    std::cout << "PyroWave: fresh GPU decode for all eight sequences, moving-frame roundtrip, "
                 "restart, malformed bitstreams, and DMA-BUF lifetime passed\n";
    return 0;
} catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
}
