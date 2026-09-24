#include "codec.h"
#include <vulkan/vulkan.h>
#include <pyrowave.h>
#include <algorithm>
#include <bit>
#include <limits>
#include <unistd.h>

namespace nova::pyrowave {
namespace {
bool validSize(int width, int height) {
    return width >= 16 && height >= 16 && width <= 4096 && height <= 4096 &&
        width % 2 == 0 && height % 2 == 0;
}
void put32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<std::uint8_t>(value >> (i * 8)));
}
std::uint32_t get32(std::span<const std::uint8_t> data, std::size_t offset) {
    std::uint32_t value = 0;
    for (int i = 0; i < 4; ++i) value |= std::uint32_t(data[offset + i]) << (i * 8);
    return value;
}
// Validate the pinned bitstream's block records as well as the outer lengths.
// In particular, duplicate blocks with zero payload_words must never reach an
// upstream parser that could skip validation on a previously seen block.
bool validPayloads(const std::vector<std::span<const std::uint8_t>>& packets, int width, int height) {
    const auto alignedWidth = std::max(128, (width + 31) & ~31);
    const auto alignedHeight = std::max(128, (height + 31) & ~31);
    unsigned blockLimit = 0;
    for (int level = 4; level >= 0; --level) {
        const auto w = alignedWidth >> (level + 1), h = alignedHeight >> (level + 1);
        blockLimit += ((w + 31) / 32) * ((h + 31) / 32) * (level == 4 ? 4 : 3) * (level == 0 ? 1 : 3);
    }
    unsigned sequence = 0, expectedBlocks = 0, seen = 0, previousBlock = 0;
    bool first = true;
    for (auto packet : packets) {
        while (!packet.empty()) {
            if (packet.size() < 8) return false;
            const auto word = get32(packet, 0), meta = get32(packet, 4);
            if (first) {
                if (!(word & 0x80000000u) || (word & 0x3fffu) + 1 != unsigned(width) ||
                    ((word >> 14) & 0x3fffu) + 1 != unsigned(height) || (meta >> 24) != 0) return false;
                sequence = (word >> 28) & 7;
                expectedBlocks = meta & 0xffffff;
                if (expectedBlocks > blockLimit) return false;
                first = false;
                packet = packet.subspan(8);
                continue;
            }
            if ((word & 0x80000000u) || ((word >> 28) & 7) != sequence) return false;
            const auto length = ((word >> 16) & 0xfff) * 4;
            const auto block = meta >> 8;
            if (length < 8 || length > packet.size() || block >= blockLimit ||
                (seen && block <= previousBlock) || ++seen > expectedBlocks) return false;
            previousBlock = block;
            const auto ballot = word & 0xffff;
            const auto active = std::popcount(ballot);
            std::size_t position = 8 + active * 3;
            if (position > length) return false;
            unsigned signs = 0;
            for (int subblock = 0; subblock < active; ++subblock) {
                const auto control = unsigned(packet[8 + subblock * 2]) | (unsigned(packet[9 + subblock * 2]) << 8);
                const auto basePlanes = packet[8 + active * 2 + subblock] & 15;
                for (int group = 0; group < 8; ++group) {
                    const auto planes = basePlanes + ((control >> (2 * group)) & 3);
                    if (planes > length - position) return false;
                    unsigned significant = 0;
                    for (unsigned p = 0; p < planes; ++p) significant |= packet[position++];
                    signs += std::popcount(significant);
                }
            }
            // The remaining bytes are sign bits, rounded to a 32-bit word.
            const auto expectedLength = (position + (signs + 7) / 8 + 3) & ~std::size_t(3);
            if (length != expectedLength) return false;
            packet = packet.subspan(length);
        }
    }
    return !first && seen == expectedBlocks;
}
pyrowave_cpu_buffer cpuBuffer(const Image& image) {
    pyrowave_cpu_buffer result{};
    result.width = image.width;
    result.height = image.height;
    result.format = PYROWAVE_CPU_BUFFER_FORMAT_YUV420P;
    for (int i = 0; i < 3; ++i) {
        result.data[i] = const_cast<std::uint8_t*>(image.planes[i].data());
        result.row_stride_in_bytes[i] = i ? image.width / 2 : image.width;
        result.plane_size_in_bytes[i] = image.planes[i].size();
    }
    return result;
}
}

Image Image::allocate(int width, int height) {
    Image image;
    if (!validSize(width, height)) return image;
    image.width = width; image.height = height;
    const auto pixels = std::size_t(width) * height;
    image.planes[0].resize(pixels);
    image.planes[1].resize(pixels / 4);
    image.planes[2].resize(pixels / 4);
    return image;
}
bool Image::valid() const {
    if (!validSize(width, height)) return false;
    const auto pixels = std::size_t(width) * height;
    return planes[0].size() == pixels && planes[1].size() == pixels / 4 && planes[2].size() == pixels / 4;
}

bool unpackFrame(std::span<const std::uint8_t> frame, int width, int height,
                 std::vector<std::span<const std::uint8_t>>& packets) {
    packets.clear();
    if (!validSize(width, height) || frame.size() < 8 || frame.size() > maxFrameBytes || frame.size() % 4)
        return false;
    // The upstream bitstream is self-delimiting. GameStream carries one whole
    // frame and preserves its exact length; no private outer header is needed.
    std::vector<std::span<const std::uint8_t>> parsed{frame};
    if (!validPayloads(parsed, width, height)) return false;
    packets = std::move(parsed);
    return true;
}

struct Codec::State {
    pyrowave_device device = nullptr;
    pyrowave_encoder encoder = nullptr;
    pyrowave_decoder decoder = nullptr;
    int width = 0, height = 0;
    std::string error;
    VkDevice vkDevice = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    std::uint32_t queueFamily = 0;
    ~State() {
        if (decoder) pyrowave_decoder_destroy(decoder);
        if (encoder) pyrowave_encoder_destroy(encoder);
        if (commandPool) vkDestroyCommandPool(vkDevice, commandPool, nullptr);
        if (device) pyrowave_device_destroy(device);
    }
    bool fail(std::string message) { error = std::move(message); return false; }
    bool checked(pyrowave_result result, const char* operation) {
        return result == PYROWAVE_SUCCESS || fail(std::string(operation) + " failed (" + std::to_string(result) + ")");
    }
};
Codec::Codec() : state_(std::make_shared<State>()) {}
Codec::~Codec() = default;
const std::string& Codec::error() const { return state_->error; }
void Codec::close() {
    // Exported images keep their original device alive after session teardown.
    state_ = std::make_shared<State>();
}
bool Codec::open(int width, int height, bool encoder) {
    close(); state_->error.clear();
    if (!validSize(width, height)) return state_->fail("Unsupported PyroWave frame size");
    std::uint32_t major = 0, minor = 0, patch = 0;
    pyrowave_get_api_version(&major, &minor, &patch);
    if (major != 0 || minor != 6 || patch != 0) return state_->fail("Incompatible PyroWave C API; expected 0.6.0");
    if (!state_->checked(pyrowave_create_default_device(&state_->device), "Vulkan device creation")) return false;
    state_->width = width; state_->height = height;
    bool ok;
    if (encoder) {
        pyrowave_encoder_create_info info{};
        info.device = state_->device; info.width = width; info.height = height;
        info.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
        ok = state_->checked(pyrowave_encoder_create(&info, &state_->encoder), "PyroWave encoder creation");
    } else {
        pyrowave_decoder_create_info info{};
        info.device = state_->device; info.width = width; info.height = height;
        info.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
        ok = state_->checked(pyrowave_decoder_create(&info, &state_->decoder), "PyroWave decoder creation");
    }
    if (!ok) {
        auto error = state_->error;
        close();
        state_->error = std::move(error);
    }
    return ok;
}
bool Codec::encode(const Image& image, std::size_t byteBudget, std::vector<std::uint8_t>& frame) {
    frame.clear(); state_->error.clear();
    if (!state_->encoder || !image.valid() || image.width != state_->width || image.height != state_->height ||
        byteBudget < 1024 || byteBudget > maxFrameBytes / 2) return state_->fail("Invalid PyroWave encode request");
    auto buffer = cpuBuffer(image);
    pyrowave_rate_control rate{byteBudget};
    if (!state_->checked(pyrowave_encoder_encode_cpu_synchronous(state_->encoder, &buffer, &rate), "PyroWave encode")) return false;
    std::size_t count = 0;
    if (!state_->checked(pyrowave_encoder_compute_num_packets(state_->encoder, packetBytes, &count), "PyroWave packet count") ||
        !count || count > maxFrameBytes / packetBytes) return state_->fail("PyroWave frame exceeds transport bounds");
    std::vector<pyrowave_packet> packets(count);
    std::vector<std::uint8_t> payload(count * packetBytes);
    const auto capacity = count;
    if (!state_->checked(pyrowave_encoder_packetize(state_->encoder, packets.data(), packetBytes, &count,
            payload.data(), payload.size()), "PyroWave packetization") || count > capacity) return false;
    std::vector<std::uint8_t> result;
    for (std::size_t i = 0; i < count; ++i) {
        const auto& p = packets[i];
        if (p.offset > payload.size() || p.size > payload.size() - p.offset || p.size > packetBytes || p.size < 8)
            return state_->fail("Invalid PyroWave packetization result");
        result.insert(result.end(), payload.begin() + p.offset, payload.begin() + p.offset + p.size);
    }
    if (result.size() > maxFrameBytes) return state_->fail("PyroWave frame exceeds transport bounds");
    frame = std::move(result);
    return true;
}
bool Codec::decode(std::span<const std::uint8_t> frame, Image& image) {
    state_->error.clear();
    std::vector<std::span<const std::uint8_t>> packets;
    if (!state_->decoder || !unpackFrame(frame, state_->width, state_->height, packets))
        return state_->fail("Invalid or incompatible PyroWave frame");
    pyrowave_decoder_clear(state_->decoder);
    for (const auto packet : packets) {
        if (!state_->checked(pyrowave_decoder_push_packet(state_->decoder, packet.data(), packet.size()), "PyroWave packet decode")) {
            pyrowave_decoder_clear(state_->decoder);
            return false;
        }
    }
    if (!pyrowave_decoder_decode_is_ready(state_->decoder, false)) {
        pyrowave_decoder_clear(state_->decoder);
        return state_->fail("Incomplete PyroWave frame");
    }
    // A failed decode never replaces the previously displayed image.
    auto next = Image::allocate(state_->width, state_->height);
    auto buffer = cpuBuffer(next);
    if (!state_->checked(pyrowave_decoder_decode_cpu_buffer_synchronous(state_->decoder, &buffer), "PyroWave GPU decode")) return false;
    image = std::move(next);
    return true;
}

int Codec::probeGpuLimit() {
    if (!state_->decoder) return 0;
    std::vector<std::uint8_t> frame;
    put32(frame, 0x80000000u | std::uint32_t(state_->width - 1) | (std::uint32_t(state_->height - 1) << 14));
    put32(frame, 0);
    GpuImage image;
    if (!decodeGpu(frame, image)) return 0;
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(state_->physical, &properties);
    return properties.limits.maxImageArrayLayers >= 12 ? int(std::min(4096u, properties.limits.maxImageDimension2D)) : 0;
}

bool Codec::decodeGpu(std::span<const std::uint8_t> frame, GpuImage& image) {
    state_->error.clear();
    std::vector<std::span<const std::uint8_t>> packets;
    if (!state_->decoder || !unpackFrame(frame, state_->width, state_->height, packets))
        return state_->fail("Invalid or incompatible PyroWave frame");
    if (!pyrowave_device_confirm_interop_support(state_->device))
        return state_->fail("Vulkan external memory is unavailable");
    pyrowave_decoder_clear(state_->decoder);
    for (auto packet : packets)
        if (!state_->checked(pyrowave_decoder_push_packet(state_->decoder, packet.data(), packet.size()), "PyroWave packet decode")) return false;
    if (!pyrowave_decoder_decode_is_ready(state_->decoder, false)) return state_->fail("Incomplete PyroWave frame");
    pyrowave_device_get_vk_device_handles(state_->device, nullptr, &state_->physical, &state_->vkDevice);
    auto device = state_->vkDevice;
    const auto getMemoryFd = reinterpret_cast<PFN_vkGetMemoryFdKHR>(vkGetDeviceProcAddr(device, "vkGetMemoryFdKHR"));
    const auto getModifier = reinterpret_cast<PFN_vkGetImageDrmFormatModifierPropertiesEXT>(vkGetDeviceProcAddr(device, "vkGetImageDrmFormatModifierPropertiesEXT"));
    if (!getMemoryFd || !getModifier) return state_->fail("Vulkan DMA-BUF export is unavailable");
    if (!state_->commandPool) {
        std::uint32_t count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(state_->physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(state_->physical, &count, families.data());
        bool found = false;
        for (std::uint32_t i = 0; i < count; ++i)
            if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { state_->queueFamily = i; found = true; break; }
        if (!found) return state_->fail("Vulkan graphics/compute queue unavailable");
        vkGetDeviceQueue(device, state_->queueFamily, 0, &state_->queue);
        VkCommandPoolCreateInfo info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        info.queueFamilyIndex = state_->queueFamily;
        if (vkCreateCommandPool(device, &info, nullptr, &state_->commandPool) != VK_SUCCESS)
            return state_->fail("Vulkan command pool creation failed");
    }
    struct Owner {
        std::shared_ptr<State> state;
        std::array<VkImage, 3> images{};
        std::array<VkDeviceMemory, 3> memory{};
        std::array<int, 3> fds{-1, -1, -1};
        ~Owner() {
            for (int i = 0; i < 3; ++i) {
                if (fds[i] >= 0) ::close(fds[i]);
                if (images[i]) vkDestroyImage(state->vkDevice, images[i], nullptr);
                if (memory[i]) vkFreeMemory(state->vkDevice, memory[i], nullptr);
            }
        }
    };
    auto owner = std::make_shared<Owner>();
    owner->state = state_;
    GpuImage next{state_->width, state_->height, {}, owner};
    VkDrmFormatModifierPropertiesListEXT modifiers{VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT};
    VkFormatProperties2 properties{VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2, &modifiers};
    vkGetPhysicalDeviceFormatProperties2(state_->physical, VK_FORMAT_R8_UNORM, &properties);
    std::vector<VkDrmFormatModifierPropertiesEXT> supported(modifiers.drmFormatModifierCount);
    modifiers.pDrmFormatModifierProperties = supported.data();
    vkGetPhysicalDeviceFormatProperties2(state_->physical, VK_FORMAT_R8_UNORM, &properties);
    std::vector<std::uint64_t> candidates;
    for (const auto& modifier : supported) {
        const auto flags = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if (modifier.drmFormatModifierPlaneCount == 1 && (modifier.drmFormatModifierTilingFeatures & flags) == flags)
            candidates.push_back(modifier.drmFormatModifier);
    }
    if (candidates.empty()) return state_->fail("No exportable R8 storage image modifier");
    pyrowave_gpu_buffers buffers{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(state_->physical, &memoryProperties);
    for (int i = 0; i < 3; ++i) {
        const auto w = std::uint32_t(i ? state_->width / 2 : state_->width);
        const auto h = std::uint32_t(i ? state_->height / 2 : state_->height);
        VkImageDrmFormatModifierListCreateInfoEXT modifierInfo{VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT};
        modifierInfo.drmFormatModifierCount = static_cast<std::uint32_t>(candidates.size());
        modifierInfo.pDrmFormatModifiers = candidates.data();
        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, &modifierInfo};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &external};
        info.imageType = VK_IMAGE_TYPE_2D; info.format = VK_FORMAT_R8_UNORM;
        info.extent = {w, h, 1}; info.mipLevels = info.arrayLayers = 1;
        info.samples = VK_SAMPLE_COUNT_1_BIT; info.tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
        info.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        if (vkCreateImage(device, &info, nullptr, &owner->images[i]) != VK_SUCCESS)
            return state_->fail("Vulkan exportable image creation failed");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device, owner->images[i], &requirements);
        std::uint32_t memoryType = memoryProperties.memoryTypeCount;
        for (std::uint32_t j = 0; j < memoryProperties.memoryTypeCount; ++j)
            if ((requirements.memoryTypeBits & (1u << j)) &&
                (memoryProperties.memoryTypes[j].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { memoryType = j; break; }
        if (memoryType == memoryProperties.memoryTypeCount) return state_->fail("No device-local export memory");
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.image = owner->images[i];
        VkExportMemoryAllocateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &dedicated};
        exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exportInfo};
        allocation.allocationSize = requirements.size; allocation.memoryTypeIndex = memoryType;
        if (vkAllocateMemory(device, &allocation, nullptr, &owner->memory[i]) != VK_SUCCESS ||
            vkBindImageMemory(device, owner->images[i], owner->memory[i], 0) != VK_SUCCESS)
            return state_->fail("Vulkan export memory allocation failed");
        VkMemoryGetFdInfoKHR fdInfo{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
        fdInfo.memory = owner->memory[i]; fdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        if (getMemoryFd(device, &fdInfo, &owner->fds[i]) != VK_SUCCESS) return state_->fail("DMA-BUF export failed");
        VkImageDrmFormatModifierPropertiesEXT modifier{VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT};
        if (getModifier(device, owner->images[i], &modifier) != VK_SUCCESS) return state_->fail("DMA-BUF modifier query failed");
        VkImageSubresource subresource{VK_IMAGE_ASPECT_MEMORY_PLANE_0_BIT_EXT, 0, 0};
        VkSubresourceLayout layout{};
        vkGetImageSubresourceLayout(device, owner->images[i], &subresource, &layout);
        next.planes[i] = {owner->fds[i], modifier.drmFormatModifier, layout.offset, layout.rowPitch, requirements.size};
        buffers.planes[i] = {owner->images[i], w, h, VK_FORMAT_R8_UNORM, VK_FORMAT_R8_UNORM,
            0, 0, VK_IMAGE_ASPECT_COLOR_BIT, VK_COMPONENT_SWIZZLE_IDENTITY, VK_IMAGE_LAYOUT_GENERAL};
    }
    struct Commands {
        VkDevice device; VkCommandPool pool;
        VkCommandBuffer buffer = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        ~Commands() {
            if (fence) vkDestroyFence(device, fence, nullptr);
            if (buffer) vkFreeCommandBuffers(device, pool, 1, &buffer);
        }
    } commands{device, state_->commandPool};
    VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = commands.pool; allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; allocate.commandBufferCount = 1;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkAllocateCommandBuffers(device, &allocate, &commands.buffer) != VK_SUCCESS ||
        vkCreateFence(device, &fenceInfo, nullptr, &commands.fence) != VK_SUCCESS) return state_->fail("Vulkan command allocation failed");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commands.buffer, &begin) != VK_SUCCESS) return state_->fail("Vulkan command begin failed");
    std::array<VkImageMemoryBarrier, 3> barriers{};
    for (int i = 0; i < 3; ++i) {
        auto& b = barriers[i]; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = owner->images[i]; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }
    vkCmdPipelineBarrier(commands.buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 3, barriers.data());
    pyrowave_device_set_command_buffer(state_->device, commands.buffer);
    const auto decoded = pyrowave_decoder_decode_gpu_buffer(state_->decoder, nullptr, nullptr, &buffers);
    pyrowave_device_set_command_buffer(state_->device, VK_NULL_HANDLE);
    if (!state_->checked(decoded, "PyroWave Vulkan decode")) return false;
    for (auto& b : barriers) {
        b.oldLayout = VK_IMAGE_LAYOUT_GENERAL; b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; b.dstAccessMask = 0;
        b.srcQueueFamilyIndex = state_->queueFamily; b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    }
    vkCmdPipelineBarrier(commands.buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0, 0, nullptr, 0, nullptr, 3, barriers.data());
    if (vkEndCommandBuffer(commands.buffer) != VK_SUCCESS) return state_->fail("Vulkan command end failed");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1; submit.pCommandBuffers = &commands.buffer;
    if (vkQueueSubmit(state_->queue, 1, &submit, commands.fence) != VK_SUCCESS) return state_->fail("Vulkan decode submission failed");
    const auto waited = vkWaitForFences(device, 1, &commands.fence, VK_TRUE, UINT64_MAX);
    if (waited != VK_SUCCESS) return state_->fail("Vulkan decode completion failed");
    image = std::move(next);
    return true;
}
}
