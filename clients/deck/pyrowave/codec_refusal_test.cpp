#include "codec.h"
#include <vulkan/vulkan.h>
#include <pyrowave.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <type_traits>

using nova::pyrowave::Codec;
using nova::pyrowave::GpuImage;
using nova::pyrowave::RefusalCause;
namespace {
std::string failure;
unsigned reached = 0, createdDevices = 0, destroyedDevices = 0, createdDecoders = 0, destroyedDecoders = 0;
bool at(const char* site) { if (failure == site) { ++reached; return true; } return false; }
VkResult result(const char* site) { return at(site) ? VK_ERROR_INITIALIZATION_FAILED : VK_SUCCESS; }
template<class T> T handle() { if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(1); else return T(1); }
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
std::vector<std::uint8_t> grayFrame() {
    const std::uint32_t word = 0x80000000u | 127u | (127u << 14);
    std::vector<std::uint8_t> frame(8);
    for (int i = 0; i < 4; ++i) frame[i] = static_cast<std::uint8_t>(word >> (i * 8));
    return frame;
}
}
// Link-time test dependencies exercise the real Codec implementation. No device,
// driver, PyroWave implementation or permission probe runs in this executable.
extern "C" {
void pyrowave_get_api_version(uint32_t* major, uint32_t* minor, uint32_t* patch) {
    *major = 0; *minor = at("api") ? 5 : 6; *patch = 0;
}
pyrowave_result pyrowave_create_default_device(pyrowave_device* device) {
    if (at("device")) return PYROWAVE_ERROR_NO_VULKAN;
    *device = handle<pyrowave_device>(); ++createdDevices; return PYROWAVE_SUCCESS;
}
void pyrowave_device_destroy(pyrowave_device) { ++destroyedDevices; }
pyrowave_result pyrowave_decoder_create(const pyrowave_decoder_create_info*, pyrowave_decoder* decoder) {
    if (at("decoder")) return PYROWAVE_ERROR_GENERIC;
    *decoder = handle<pyrowave_decoder>(); ++createdDecoders; return PYROWAVE_SUCCESS;
}
void pyrowave_decoder_destroy(pyrowave_decoder) { ++destroyedDecoders; }
bool pyrowave_device_confirm_interop_support(pyrowave_device) { return !at("interop"); }
void pyrowave_decoder_clear(pyrowave_decoder) {}
pyrowave_result pyrowave_decoder_push_packet(pyrowave_decoder, const void*, size_t) {
    return at("packet") ? PYROWAVE_ERROR_GENERIC : PYROWAVE_SUCCESS;
}
bool pyrowave_decoder_decode_is_ready(pyrowave_decoder, bool) { return !at("ready"); }
void pyrowave_device_get_vk_device_handles(pyrowave_device, VkInstance*, VkPhysicalDevice* physical, VkDevice* device) {
    *physical = handle<VkPhysicalDevice>(); *device = handle<VkDevice>();
}
void pyrowave_device_set_command_buffer(pyrowave_device, VkCommandBuffer) {}
pyrowave_result pyrowave_decoder_decode_gpu_buffer(pyrowave_decoder, const pyrowave_gpu_sync_operation*,
    const pyrowave_gpu_sync_operation*, const pyrowave_gpu_buffers*) {
    return at("decode") ? PYROWAVE_ERROR_GENERIC : PYROWAVE_SUCCESS;
}
VkResult vkGetMemoryFdKHR(VkDevice, const VkMemoryGetFdInfoKHR*, int* fd) {
    if (at("fd")) return VK_ERROR_INITIALIZATION_FAILED;
    *fd = open("/dev/null", O_RDONLY); return *fd >= 0 ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
}
VkResult vkGetImageDrmFormatModifierPropertiesEXT(VkDevice, VkImage, VkImageDrmFormatModifierPropertiesEXT* modifier) {
    modifier->drmFormatModifier = 0; return result("modifier-query");
}
PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice, const char* name) {
    if (at("export-api")) return nullptr;
    if (!std::strcmp(name, "vkGetMemoryFdKHR")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetMemoryFdKHR);
    if (!std::strcmp(name, "vkGetImageDrmFormatModifierPropertiesEXT")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetImageDrmFormatModifierPropertiesEXT);
    return nullptr;
}
void vkGetPhysicalDeviceMemoryProperties(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties* properties) {
    properties->memoryTypeCount = 1;
    properties->memoryTypes[0].propertyFlags = at("memory-type") ? 0 : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
}
void vkGetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice, uint32_t* count, VkQueueFamilyProperties* properties) {
    *count = 1;
    if (properties) properties[0].queueFlags = at("queue") ? VK_QUEUE_COMPUTE_BIT : VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
}
void vkGetDeviceQueue(VkDevice, uint32_t, uint32_t, VkQueue* queue) { *queue = handle<VkQueue>(); }
VkResult vkCreateCommandPool(VkDevice, const VkCommandPoolCreateInfo*, const VkAllocationCallbacks*, VkCommandPool* pool) {
    const auto status = result("pool"); if (status == VK_SUCCESS) *pool = handle<VkCommandPool>(); return status;
}
void vkDestroyCommandPool(VkDevice, VkCommandPool, const VkAllocationCallbacks*) {}
void vkGetPhysicalDeviceFormatProperties2(VkPhysicalDevice, VkFormat, VkFormatProperties2* properties) {
    auto* modifiers = static_cast<VkDrmFormatModifierPropertiesListEXT*>(properties->pNext);
    modifiers->drmFormatModifierCount = at("modifiers") ? 0 : 1;
    if (modifiers->pDrmFormatModifierProperties && modifiers->drmFormatModifierCount)
        modifiers->pDrmFormatModifierProperties[0] = {0, 1, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT};
}
VkResult vkCreateImage(VkDevice, const VkImageCreateInfo*, const VkAllocationCallbacks*, VkImage* image) {
    const auto status = result("image"); if (status == VK_SUCCESS) *image = handle<VkImage>(); return status;
}
void vkDestroyImage(VkDevice, VkImage, const VkAllocationCallbacks*) {}
void vkGetImageMemoryRequirements(VkDevice, VkImage, VkMemoryRequirements* requirements) {
    requirements->size = 16384; requirements->alignment = 1; requirements->memoryTypeBits = 1;
}
VkResult vkAllocateMemory(VkDevice, const VkMemoryAllocateInfo*, const VkAllocationCallbacks*, VkDeviceMemory* memory) {
    const auto status = result("memory"); if (status == VK_SUCCESS) *memory = handle<VkDeviceMemory>(); return status;
}
VkResult vkBindImageMemory(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize) { return result("bind"); }
void vkFreeMemory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) {}
void vkGetImageSubresourceLayout(VkDevice, VkImage, const VkImageSubresource*, VkSubresourceLayout* layout) {
    layout->offset = 0; layout->rowPitch = 128; layout->size = 16384;
}
VkResult vkAllocateCommandBuffers(VkDevice, const VkCommandBufferAllocateInfo*, VkCommandBuffer* command) {
    const auto status = result("commands"); if (status == VK_SUCCESS) *command = handle<VkCommandBuffer>(); return status;
}
VkResult vkCreateFence(VkDevice, const VkFenceCreateInfo*, const VkAllocationCallbacks*, VkFence* fence) {
    const auto status = result("fence"); if (status == VK_SUCCESS) *fence = handle<VkFence>(); return status;
}
void vkDestroyFence(VkDevice, VkFence, const VkAllocationCallbacks*) {}
VkResult vkResetCommandBuffer(VkCommandBuffer, VkCommandBufferResetFlags) { return result("command-reset"); }
VkResult vkBeginCommandBuffer(VkCommandBuffer, const VkCommandBufferBeginInfo*) { return result("command-begin"); }
void vkCmdPipelineBarrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags, VkDependencyFlags,
    uint32_t, const VkMemoryBarrier*, uint32_t, const VkBufferMemoryBarrier*, uint32_t, const VkImageMemoryBarrier*) {}
VkResult vkEndCommandBuffer(VkCommandBuffer) { return result("command-end"); }
VkResult vkResetFences(VkDevice, uint32_t, const VkFence*) { return result("fence-reset"); }
VkResult vkQueueSubmit(VkQueue, uint32_t, const VkSubmitInfo*, VkFence) { return result("submit"); }
VkResult vkWaitForFences(VkDevice, uint32_t, const VkFence*, VkBool32, uint64_t) { return result("wait"); }
void vkGetPhysicalDeviceProperties(VkPhysicalDevice, VkPhysicalDeviceProperties* properties) {
    properties->limits.maxImageArrayLayers = at("layers") ? 11 : 12;
    properties->limits.maxImageDimension2D = at("dimension") ? 127 : 8192;
}
}
int main() try {
    const std::pair<const char*, RefusalCause> cases[]{
        {"api", RefusalCause::Api}, {"device", RefusalCause::Device}, {"decoder", RefusalCause::Decoder},
        {"interop", RefusalCause::Interop}, {"packet", RefusalCause::Decoder}, {"ready", RefusalCause::Decoder},
        {"export-api", RefusalCause::DmaBuf}, {"queue", RefusalCause::Queue}, {"pool", RefusalCause::Queue},
        {"modifiers", RefusalCause::DmaBuf}, {"image", RefusalCause::DmaBuf}, {"memory-type", RefusalCause::DmaBuf},
        {"memory", RefusalCause::DmaBuf}, {"bind", RefusalCause::DmaBuf}, {"fd", RefusalCause::DmaBuf},
        {"modifier-query", RefusalCause::DmaBuf}, {"commands", RefusalCause::Queue}, {"fence", RefusalCause::Queue},
        {"command-reset", RefusalCause::Queue}, {"command-begin", RefusalCause::Queue}, {"decode", RefusalCause::Decoder},
        {"command-end", RefusalCause::Queue}, {"fence-reset", RefusalCause::Queue}, {"submit", RefusalCause::Queue},
        {"wait", RefusalCause::Queue}, {"layers", RefusalCause::Limits}, {"dimension", RefusalCause::Limits},
    };
    for (const auto& [site, expected] : cases) {
        failure = site; reached = 0;
        { Codec codec;
          const auto opened = codec.open(128, 128, false);
          const auto limit = opened ? codec.probeGpuLimit() : 0;
          require(limit == 0 && reached > 0, "fixture did not reach its actual failure call");
          require(codec.refusalCause() == expected && !codec.error().empty(), "actual codec failure lost its category");
          char output[256];
          require(nova::pyrowave::formatProbeResult(output, sizeof(output), false, 0, 0, codec.refusalCause()) > 0,
              "actual codec failure cannot be serialized safely");
          failure.clear();
          require(codec.open(128, 128, false) && codec.probeGpuLimit() == 4096 && codec.refusalCause() == RefusalCause::None,
              "new open retained an old refusal or changed the bounded GPU limit");
          codec.close(); require(codec.refusalCause() == RefusalCause::None, "close retained refusal"); }
        require(createdDevices == destroyedDevices && createdDecoders == destroyedDecoders, "failure/retry leaked codec ownership");
        std::cout << "PASS actual-site " << site << '\n';
    }
    Codec codec;
    require(codec.probeGpuLimit() == 0 && codec.refusalCause() == RefusalCause::Decoder, "closed decoder not classified");
    require(!codec.open(127, 128, false) && codec.refusalCause() == RefusalCause::Limits, "invalid size not classified");
    require(codec.open(128, 128, false), "fake decoder did not open");
    GpuImage image; image.width = 32; image.owner = std::make_shared<int>(1);
    const auto previous = image.owner;
    require(!codec.decodeGpu({}, image) && codec.refusalCause() == RefusalCause::Decoder && image.owner == previous && image.width == 32,
        "invalid frame classification replaced output");
    failure = "fd";
    require(!codec.decodeGpu(grayFrame(), image) && codec.refusalCause() == RefusalCause::DmaBuf && image.owner == previous,
        "failed export replaced the previous image");
    failure.clear();
    require(codec.decodeGpu(grayFrame(), image) && codec.refusalCause() == RefusalCause::None && image.owner != previous,
        "successful GPU decode retained a refusal");
    std::array<int, 3> fds{};
    for (int i = 0; i < 3; ++i) fds[i] = image.planes[i].fd;
    codec.close();
    for (const auto fd : fds) require(fcntl(fd, F_GETFD) >= 0, "close invalidated an exported owner");
    image = {};
    for (const auto fd : fds) require(fcntl(fd, F_GETFD) == -1, "fake export descriptor leaked");
    require(createdDevices == destroyedDevices && createdDecoders == destroyedDecoders, "final ownership leak");
    std::cout << "27 actual refusal sites and reset/output/ownership bookends passed; no GPU used.\n";
    return 0;
} catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
