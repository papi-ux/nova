#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace nova::pyrowave {

// A bitstream revision is separate from the upstream C ABI. Both peers must
// negotiate this exact identifier before sending data. Never auto-select it.
inline constexpr auto bitstreamId = "pyrowave-186f0393-sdr420-v1";
inline constexpr std::size_t maxFrameBytes = 8 * 1024 * 1024;
inline constexpr std::size_t packetBytes = 60 * 1024;

struct Image {
    int width = 0, height = 0;
    std::array<std::vector<std::uint8_t>, 3> planes;
    bool valid() const;
    static Image allocate(int width, int height);
};

struct DmaBufPlane {
    int fd = -1;
    std::uint64_t modifier = 0;
    std::uint64_t offset = 0, pitch = 0, size = 0;
};
struct GpuImage {
    int width = 0, height = 0;
    std::array<DmaBufPlane, 3> planes;
    // Owns Vulkan images, memory, DMA-BUF handles, and the device. The consumer
    // must retain this object until its imported textures are retired.
    std::shared_ptr<void> owner;
};

// Checks the complete upstream bitstream before invoking the GPU decoder. Views
// borrow the caller's frame. No partial frame reaches the decoder.
// One GameStream decode unit is one raw, self-delimiting PyroWave frame.
bool unpackFrame(std::span<const std::uint8_t> frame, int width, int height,
                 std::vector<std::span<const std::uint8_t>>& packets);

// Owning, single-threaded C API boundary. Destruction waits for upstream GPU
// work. CPU buffers are a bring-up path, not a zero-copy presentation claim.
class Codec final {
public:
    Codec();
    ~Codec();
    Codec(const Codec&) = delete;
    Codec& operator=(const Codec&) = delete;
    bool open(int width, int height, bool encoder);
    void close();
    bool encode(const Image& image, std::size_t byteBudget, std::vector<std::uint8_t>& frame);
    bool decode(std::span<const std::uint8_t> frame, Image& image);
    bool decodeGpu(std::span<const std::uint8_t> frame, GpuImage& image);
    // Requires an open decoder; proves a gray frame can be decoded/exported
    // and returns the device's image limit bounded by our wire contract.
    int probeGpuLimit();
    const std::string& error() const;
private:
    struct State;
    std::shared_ptr<State> state_;
};
}
