// Drives the debug SGSR 1 readback in pyrowave_renderer.cpp through a fake Vulkan loader, to check
// that each step it now checks ends the readback when it fails: a false result, no work after the
// failing call, the caller's pixels untouched, and everything made destroyed exactly once.
//
// The renderer is compiled from its own source. The Android headers, the device and the codec are
// stand-ins (test_pyrowave_readback_failures.py writes the headers), and nothing touches a GPU.
#include "pyrowave_renderer.cpp"

#include <cstdint>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

static_assert(sizeof(void *) == 8, "handles are faked as 64-bit pointers");

namespace fake {

  std::vector<std::string> calls;        // every call, in order
  std::map<std::string, int> counts;
  std::string fail_name;                 // the call to fail, or empty
  int fail_nth = 0;                      // which call of that name, from 1
  long failed_at = -1;                   // its index in calls
  std::vector<std::string> problems;
  std::map<uint64_t, std::string> live;  // made and not yet destroyed
  std::map<uint64_t, std::vector<uint8_t>> memory;
  std::map<uint64_t, VkDeviceSize> sizes;
  std::map<uint64_t, uint64_t> buffer_memory;
  struct copy_t {
    uint64_t buffer;
    VkDeviceSize bytes;
  };
  std::vector<copy_t> recorded, submitted;
  bool recording = false;
  uint64_t next = 0x1000;
  int devices_made = 0, devices_destroyed = 0;

  void reset() {
    calls.clear();
    counts.clear();
    fail_name.clear();
    fail_nth = 0;
    failed_at = -1;
    problems.clear();
    live.clear();
    memory.clear();
    sizes.clear();
    buffer_memory.clear();
    recorded.clear();
    submitted.clear();
    recording = false;
    devices_made = devices_destroyed = 0;
  }

  bool teardown(const std::string &name) {
    return name.compare(0, 9, "vkDestroy") == 0 || name == "vkFreeMemory" || name == "vkDeviceWaitIdle" ||
           name == "device.destroy";
  }

  // Records the call; true when it is the one chosen to fail.
  bool call(const char *name) {
    if (failed_at >= 0 && !teardown(name)) problems.push_back(std::string("after the failure: ") + name);
    calls.push_back(name);
    const int nth = ++counts[name];
    if (failed_at < 0 && name == fail_name && nth == fail_nth) {
      failed_at = static_cast<long>(calls.size()) - 1;
      return true;
    }
    return false;
  }

  template <typename T> uint64_t id(T handle) {
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(handle));
  }

  template <typename T> T make(const char *kind) {
    const uint64_t handle = next++;
    live[handle] = kind;
    return reinterpret_cast<T>(static_cast<uintptr_t>(handle));
  }

  template <typename T> void destroy(const char *kind, T handle) {
    if (!handle) return;
    const auto found = live.find(id(handle));
    if (found == live.end()) {
      problems.push_back(std::string("destroyed twice or never made: ") + kind);
    } else if (found->second != kind) {
      problems.push_back(std::string("destroyed as ") + kind + ", made as " + found->second);
      live.erase(found);
    } else {
      live.erase(found);
    }
  }

  uint8_t pattern(VkDeviceSize i) {
    return static_cast<uint8_t>((i * 37 + 11) & 0xff);
  }

  // Calls the readback is never expected to make; one per name so the report can say which.
  template <const char *Name, typename F> struct unexpected;
  template <const char *Name, typename R, typename... A> struct unexpected<Name, R(VKAPI_PTR *)(A...)> {
    static R VKAPI_CALL run(A...) {
      call(Name);
      problems.push_back(std::string("unexpected call: ") + Name);
      return R();
    }
  };

#define FAKE_NAME(name) constexpr char name##_name[] = #name;
  NOVA_VK_DEVICE_FUNCTIONS(FAKE_NAME)
  NOVA_VK_INSTANCE_FUNCTIONS(FAKE_NAME)
#undef FAKE_NAME

  // ------------------------------------------------------------------ the calls the readback makes

  VKAPI_ATTR VkResult VKAPI_CALL vkCreateImage(VkDevice, const VkImageCreateInfo *info, const VkAllocationCallbacks *,
                                               VkImage *out) {
    if (call("vkCreateImage")) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *out = make<VkImage>("image");
    sizes[id(*out)] = static_cast<VkDeviceSize>(info->extent.width) * info->extent.height * 4;
    return VK_SUCCESS;
  }
  VKAPI_ATTR void VKAPI_CALL vkGetImageMemoryRequirements(VkDevice, VkImage image, VkMemoryRequirements *out) {
    call("vkGetImageMemoryRequirements");
    *out = {};
    out->size = sizes[id(image)];
    out->alignment = 16;
    out->memoryTypeBits = 1;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkCreateBuffer(VkDevice, const VkBufferCreateInfo *info,
                                                const VkAllocationCallbacks *, VkBuffer *out) {
    if (call("vkCreateBuffer")) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *out = make<VkBuffer>("buffer");
    sizes[id(*out)] = info->size;
    return VK_SUCCESS;
  }
  VKAPI_ATTR void VKAPI_CALL vkGetBufferMemoryRequirements(VkDevice, VkBuffer buffer, VkMemoryRequirements *out) {
    call("vkGetBufferMemoryRequirements");
    *out = {};
    out->size = sizes[id(buffer)];
    out->alignment = 16;
    out->memoryTypeBits = 1;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(VkDevice, const VkMemoryAllocateInfo *info,
                                                  const VkAllocationCallbacks *, VkDeviceMemory *out) {
    if (call("vkAllocateMemory")) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *out = make<VkDeviceMemory>("memory");
    memory[id(*out)].assign(static_cast<size_t>(info->allocationSize), 0);
    return VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkBindImageMemory(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize) {
    return call("vkBindImageMemory") ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkBindBufferMemory(VkDevice, VkBuffer buffer, VkDeviceMemory bound, VkDeviceSize) {
    if (call("vkBindBufferMemory")) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    buffer_memory[id(buffer)] = id(bound);
    return VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkMapMemory(VkDevice, VkDeviceMemory mapped, VkDeviceSize offset, VkDeviceSize,
                                             VkMemoryMapFlags, void **out) {
    if (call("vkMapMemory")) return VK_ERROR_MEMORY_MAP_FAILED;
    *out = memory[id(mapped)].data() + offset;
    return VK_SUCCESS;
  }
  VKAPI_ATTR void VKAPI_CALL vkFreeMemory(VkDevice, VkDeviceMemory freed, const VkAllocationCallbacks *) {
    call("vkFreeMemory");
    destroy("memory", freed);
    memory.erase(id(freed));
  }

#define FAKE_CREATE(name, info, type, kind) \
  VKAPI_ATTR VkResult VKAPI_CALL name(VkDevice, const info *, const VkAllocationCallbacks *, type *out) { \
    if (call(#name)) return VK_ERROR_OUT_OF_HOST_MEMORY; \
    *out = make<type>(kind); \
    return VK_SUCCESS; \
  }
#define FAKE_DESTROY(name, type, kind) \
  VKAPI_ATTR void VKAPI_CALL name(VkDevice, type handle, const VkAllocationCallbacks *) { \
    call(#name); \
    destroy(kind, handle); \
  }
  FAKE_CREATE(vkCreateImageView, VkImageViewCreateInfo, VkImageView, "image view")
  FAKE_CREATE(vkCreateRenderPass, VkRenderPassCreateInfo, VkRenderPass, "render pass")
  FAKE_CREATE(vkCreateFramebuffer, VkFramebufferCreateInfo, VkFramebuffer, "framebuffer")
  FAKE_CREATE(vkCreateSampler, VkSamplerCreateInfo, VkSampler, "sampler")
  FAKE_CREATE(vkCreateDescriptorSetLayout, VkDescriptorSetLayoutCreateInfo, VkDescriptorSetLayout, "set layout")
  FAKE_CREATE(vkCreateDescriptorPool, VkDescriptorPoolCreateInfo, VkDescriptorPool, "descriptor pool")
  FAKE_CREATE(vkCreateShaderModule, VkShaderModuleCreateInfo, VkShaderModule, "shader module")
  FAKE_CREATE(vkCreatePipelineLayout, VkPipelineLayoutCreateInfo, VkPipelineLayout, "pipeline layout")
  FAKE_CREATE(vkCreateCommandPool, VkCommandPoolCreateInfo, VkCommandPool, "command pool")
  FAKE_CREATE(vkCreateSemaphore, VkSemaphoreCreateInfo, VkSemaphore, "semaphore")
  FAKE_CREATE(vkCreateFence, VkFenceCreateInfo, VkFence, "fence")
  FAKE_DESTROY(vkDestroyImage, VkImage, "image")
  FAKE_DESTROY(vkDestroyBuffer, VkBuffer, "buffer")
  FAKE_DESTROY(vkDestroyImageView, VkImageView, "image view")
  FAKE_DESTROY(vkDestroyRenderPass, VkRenderPass, "render pass")
  FAKE_DESTROY(vkDestroyFramebuffer, VkFramebuffer, "framebuffer")
  FAKE_DESTROY(vkDestroySampler, VkSampler, "sampler")
  FAKE_DESTROY(vkDestroyDescriptorSetLayout, VkDescriptorSetLayout, "set layout")
  FAKE_DESTROY(vkDestroyDescriptorPool, VkDescriptorPool, "descriptor pool")
  FAKE_DESTROY(vkDestroyShaderModule, VkShaderModule, "shader module")
  FAKE_DESTROY(vkDestroyPipelineLayout, VkPipelineLayout, "pipeline layout")
  FAKE_DESTROY(vkDestroyPipeline, VkPipeline, "pipeline")
  FAKE_DESTROY(vkDestroyCommandPool, VkCommandPool, "command pool")
  FAKE_DESTROY(vkDestroySemaphore, VkSemaphore, "semaphore")
  FAKE_DESTROY(vkDestroyFence, VkFence, "fence")
#undef FAKE_CREATE
#undef FAKE_DESTROY

  VKAPI_ATTR VkResult VKAPI_CALL vkAllocateDescriptorSets(VkDevice, const VkDescriptorSetAllocateInfo *info,
                                                          VkDescriptorSet *out) {
    if (call("vkAllocateDescriptorSets")) return VK_ERROR_OUT_OF_POOL_MEMORY;
    // Freed with their pool, so not tracked.
    for (uint32_t i = 0; i < info->descriptorSetCount; i++) out[i] = reinterpret_cast<VkDescriptorSet>(next++);
    return VK_SUCCESS;
  }
  VKAPI_ATTR void VKAPI_CALL vkUpdateDescriptorSets(VkDevice, uint32_t, const VkWriteDescriptorSet *, uint32_t,
                                                    const VkCopyDescriptorSet *) {
    call("vkUpdateDescriptorSets");
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkCreateGraphicsPipelines(VkDevice, VkPipelineCache, uint32_t count,
                                                           const VkGraphicsPipelineCreateInfo *,
                                                           const VkAllocationCallbacks *, VkPipeline *out) {
    if (call("vkCreateGraphicsPipelines")) return VK_ERROR_OUT_OF_HOST_MEMORY;
    for (uint32_t i = 0; i < count; i++) out[i] = make<VkPipeline>("pipeline");
    return VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkAllocateCommandBuffers(VkDevice, const VkCommandBufferAllocateInfo *info,
                                                          VkCommandBuffer *out) {
    if (call("vkAllocateCommandBuffers")) return VK_ERROR_OUT_OF_HOST_MEMORY;
    // Freed with their pool, so not tracked.
    for (uint32_t i = 0; i < info->commandBufferCount; i++) {
      out[i] = reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(next++));
    }
    return VK_SUCCESS;
  }

  VKAPI_ATTR VkResult VKAPI_CALL vkBeginCommandBuffer(VkCommandBuffer, const VkCommandBufferBeginInfo *) {
    if (call("vkBeginCommandBuffer")) return VK_ERROR_OUT_OF_HOST_MEMORY;
    if (recording) problems.push_back("began a command buffer already recording");
    recording = true;
    recorded.clear();
    return VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkEndCommandBuffer(VkCommandBuffer) {
    if (call("vkEndCommandBuffer")) return VK_ERROR_OUT_OF_HOST_MEMORY;
    if (!recording) problems.push_back("ended a command buffer that was not recording");
    recording = false;
    return VK_SUCCESS;
  }

  void command(const char *name) {
    call(name);
    if (!recording) problems.push_back(std::string("recorded outside a command buffer: ") + name);
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
                                                  VkDependencyFlags, uint32_t, const VkMemoryBarrier *, uint32_t,
                                                  const VkBufferMemoryBarrier *, uint32_t,
                                                  const VkImageMemoryBarrier *) {
    command("vkCmdPipelineBarrier");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdCopyBufferToImage(VkCommandBuffer, VkBuffer, VkImage, VkImageLayout, uint32_t,
                                                    const VkBufferImageCopy *) {
    command("vkCmdCopyBufferToImage");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdCopyImageToBuffer(VkCommandBuffer, VkImage, VkImageLayout, VkBuffer buffer,
                                                    uint32_t count, const VkBufferImageCopy *regions) {
    command("vkCmdCopyImageToBuffer");
    for (uint32_t i = 0; i < count; i++) {
      recorded.push_back({id(buffer), static_cast<VkDeviceSize>(regions[i].imageExtent.width) *
                                        regions[i].imageExtent.height * 4});
    }
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdBeginRenderPass(VkCommandBuffer, const VkRenderPassBeginInfo *,
                                                  VkSubpassContents) {
    command("vkCmdBeginRenderPass");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdEndRenderPass(VkCommandBuffer) { command("vkCmdEndRenderPass"); }
  VKAPI_ATTR void VKAPI_CALL vkCmdBindPipeline(VkCommandBuffer, VkPipelineBindPoint, VkPipeline) {
    command("vkCmdBindPipeline");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdBindDescriptorSets(VkCommandBuffer, VkPipelineBindPoint, VkPipelineLayout,
                                                     uint32_t, uint32_t, const VkDescriptorSet *, uint32_t,
                                                     const uint32_t *) {
    command("vkCmdBindDescriptorSets");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdSetViewport(VkCommandBuffer, uint32_t, uint32_t, const VkViewport *) {
    command("vkCmdSetViewport");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdSetScissor(VkCommandBuffer, uint32_t, uint32_t, const VkRect2D *) {
    command("vkCmdSetScissor");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdDraw(VkCommandBuffer, uint32_t, uint32_t, uint32_t, uint32_t) {
    command("vkCmdDraw");
  }
  VKAPI_ATTR void VKAPI_CALL vkCmdPushConstants(VkCommandBuffer, VkPipelineLayout, VkShaderStageFlags, uint32_t,
                                                uint32_t, const void *) {
    command("vkCmdPushConstants");
  }

  VKAPI_ATTR VkResult VKAPI_CALL vkResetFences(VkDevice, uint32_t, const VkFence *) {
    return call("vkResetFences") ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit(VkQueue, uint32_t, const VkSubmitInfo *, VkFence) {
    if (call("vkQueueSubmit")) return VK_ERROR_DEVICE_LOST;
    if (recording) problems.push_back("submitted a command buffer still recording");
    submitted = recorded;
    return VK_SUCCESS;
  }
  // The submitted copies land when the fence is waited on, so a readback that copies its pixels
  // out too early, or after a failure, sees zeros or the caller's own bytes rather than the pattern.
  VKAPI_ATTR VkResult VKAPI_CALL vkWaitForFences(VkDevice, uint32_t, const VkFence *, VkBool32, uint64_t) {
    if (call("vkWaitForFences")) return VK_ERROR_DEVICE_LOST;
    for (const auto &copy : submitted) {
      auto &bytes = memory[buffer_memory[copy.buffer]];
      if (bytes.size() < copy.bytes) {
        problems.push_back("copy larger than its buffer's memory");
        continue;
      }
      for (VkDeviceSize i = 0; i < copy.bytes; i++) bytes[static_cast<size_t>(i)] = pattern(i);
    }
    submitted.clear();
    return VK_SUCCESS;
  }
  VKAPI_ATTR VkResult VKAPI_CALL vkDeviceWaitIdle(VkDevice) {
    call("vkDeviceWaitIdle");
    return VK_SUCCESS;
  }
  VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(VkPhysicalDevice,
                                                                 VkPhysicalDeviceMemoryProperties *out) {
    call("vkGetPhysicalDeviceMemoryProperties");
    *out = {};
    out->memoryTypeCount = 1;
    out->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    out->memoryHeapCount = 1;
  }

  std::map<std::string, PFN_vkVoidFunction> &table() {
    static std::map<std::string, PFN_vkVoidFunction> functions;
    if (functions.empty()) {
#define FAKE_UNEXPECTED(name) \
  functions[#name] = reinterpret_cast<PFN_vkVoidFunction>(&unexpected<name##_name, PFN_##name>::run);
      NOVA_VK_DEVICE_FUNCTIONS(FAKE_UNEXPECTED)
      NOVA_VK_INSTANCE_FUNCTIONS(FAKE_UNEXPECTED)
#undef FAKE_UNEXPECTED
#define FAKE(name) functions[#name] = reinterpret_cast<PFN_vkVoidFunction>(&name);
      FAKE(vkCreateImage) FAKE(vkGetImageMemoryRequirements) FAKE(vkCreateBuffer)
      FAKE(vkGetBufferMemoryRequirements) FAKE(vkAllocateMemory) FAKE(vkBindImageMemory)
      FAKE(vkBindBufferMemory) FAKE(vkMapMemory) FAKE(vkFreeMemory)
      FAKE(vkCreateImageView) FAKE(vkCreateRenderPass) FAKE(vkCreateFramebuffer) FAKE(vkCreateSampler)
      FAKE(vkCreateDescriptorSetLayout) FAKE(vkCreateDescriptorPool) FAKE(vkCreateShaderModule)
      FAKE(vkCreatePipelineLayout) FAKE(vkCreateCommandPool) FAKE(vkCreateSemaphore) FAKE(vkCreateFence)
      FAKE(vkDestroyImage) FAKE(vkDestroyBuffer) FAKE(vkDestroyImageView) FAKE(vkDestroyRenderPass)
      FAKE(vkDestroyFramebuffer) FAKE(vkDestroySampler) FAKE(vkDestroyDescriptorSetLayout)
      FAKE(vkDestroyDescriptorPool) FAKE(vkDestroyShaderModule) FAKE(vkDestroyPipelineLayout)
      FAKE(vkDestroyPipeline) FAKE(vkDestroyCommandPool) FAKE(vkDestroySemaphore) FAKE(vkDestroyFence)
      FAKE(vkAllocateDescriptorSets) FAKE(vkUpdateDescriptorSets) FAKE(vkCreateGraphicsPipelines)
      FAKE(vkAllocateCommandBuffers) FAKE(vkBeginCommandBuffer) FAKE(vkEndCommandBuffer)
      FAKE(vkCmdPipelineBarrier) FAKE(vkCmdCopyBufferToImage) FAKE(vkCmdCopyImageToBuffer)
      FAKE(vkCmdBeginRenderPass) FAKE(vkCmdEndRenderPass) FAKE(vkCmdBindPipeline) FAKE(vkCmdBindDescriptorSets)
      FAKE(vkCmdSetViewport) FAKE(vkCmdSetScissor) FAKE(vkCmdDraw) FAKE(vkCmdPushConstants)
      FAKE(vkResetFences) FAKE(vkQueueSubmit) FAKE(vkWaitForFences) FAKE(vkDeviceWaitIdle)
      FAKE(vkGetPhysicalDeviceMemoryProperties)
#undef FAKE
    }
    return functions;
  }

  PFN_vkVoidFunction lookup(const char *name) {
    const auto found = table().find(name);
    return found == table().end() ? nullptr : found->second;
  }
  VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_device_proc_addr(VkDevice, const char *name) {
    return lookup(name);
  }
  VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_instance_proc_addr(VkInstance, const char *name) {
    if (std::strcmp(name, "vkGetDeviceProcAddr") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(&get_device_proc_addr);
    }
    return lookup(name);
  }

}  // namespace fake

// ---------------------------------------------------------------------------- stand-ins

namespace nova_vk {
  bool device_t::create(bool, uint32_t *) {
    fake::call("device.create");
    ++fake::devices_made;
    instance = reinterpret_cast<VkInstance>(static_cast<uintptr_t>(0x10));
    physical_device = reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(0x20));
    device = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(0x30));
    graphics_queue = reinterpret_cast<VkQueue>(static_cast<uintptr_t>(0x40));
    graphics_family = 0;
    return true;
  }
  void device_t::destroy() {
    if (!device) return;
    fake::call("device.destroy");
    ++fake::devices_destroyed;
    instance = VK_NULL_HANDLE;
    physical_device = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    graphics_queue = VK_NULL_HANDLE;
  }
  PFN_vkGetInstanceProcAddr device_t::get_instance_proc_addr() const {
    return &fake::get_instance_proc_addr;
  }
}  // namespace nova_vk

// The readback makes no decoder; any of these being reached is a failure of its own.
extern "C" {
  void pyrowave_device_set_command_buffer(pyrowave_device, VkCommandBuffer) {
    fake::problems.push_back("unexpected codec call");
  }
  pyrowave_result pyrowave_device_set_queue_type(pyrowave_device, VkQueueFlagBits) {
    fake::problems.push_back("unexpected codec call");
    return PYROWAVE_SUCCESS;
  }
  bool pyrowave_decoder_device_prefers_fragment_path(pyrowave_device) {
    fake::problems.push_back("unexpected codec call");
    return false;
  }
  pyrowave_result pyrowave_decoder_create(const pyrowave_decoder_create_info *, pyrowave_decoder *) {
    fake::problems.push_back("unexpected codec call");
    return PYROWAVE_SUCCESS;
  }
  void pyrowave_decoder_clear(pyrowave_decoder) { fake::problems.push_back("unexpected codec call"); }
  pyrowave_result pyrowave_decoder_push_packet(pyrowave_decoder, const void *, size_t) {
    fake::problems.push_back("unexpected codec call");
    return PYROWAVE_SUCCESS;
  }
  bool pyrowave_decoder_decode_is_ready(pyrowave_decoder, bool) {
    fake::problems.push_back("unexpected codec call");
    return false;
  }
  pyrowave_result pyrowave_decoder_decode_gpu_buffer(pyrowave_decoder, const pyrowave_gpu_sync_operation *,
                                                     const pyrowave_gpu_sync_operation *,
                                                     const pyrowave_gpu_buffers *) {
    fake::problems.push_back("unexpected codec call");
    return PYROWAVE_SUCCESS;
  }
  void pyrowave_decoder_destroy(pyrowave_decoder) { fake::problems.push_back("unexpected codec call"); }
}

// ---------------------------------------------------------------------------- cases

namespace {

  constexpr uint32_t k_width = 6, k_height = 4, k_output_width = 12, k_output_height = 8;
  constexpr size_t k_rgba = static_cast<size_t>(k_output_width) * k_output_height * 4;

  long first(const char *name) {
    for (size_t i = 0; i < fake::calls.size(); i++) {
      if (fake::calls[i] == name) return static_cast<long>(i);
    }
    return -1;
  }

  // Runs one readback and reports what went wrong with it, if anything.
  bool run(const char *label, const char *fail, bool expect_drawn) {
    fake::reset();
    if (fail) {
      fake::fail_name = fail;
      fake::fail_nth = 1;
    }
    std::vector<uint8_t> luma(k_width * k_height, 100), cb((k_width / 2) * (k_height / 2), 120),
      cr((k_width / 2) * (k_height / 2), 140);
    std::vector<uint8_t> rgba(k_rgba, 0xee);
    const bool drawn = pyrowave_renderer_sgsr_readback(k_width, k_height, false, luma.data(), cb.data(), cr.data(),
                                                       k_output_width, k_output_height, true, rgba.data());

    std::vector<std::string> wrong = fake::problems;
    if (drawn != expect_drawn) wrong.push_back(drawn ? "returned true" : "returned false");
    if (fail && fake::failed_at < 0) wrong.push_back(std::string("never reached ") + fail);
    if (fake::devices_made != 1 || fake::devices_destroyed != 1) wrong.push_back("device not made and destroyed once");
    for (const auto &left : fake::live) wrong.push_back("never destroyed: " + left.second);
    if (expect_drawn) {
      for (size_t i = 0; i < k_rgba; i++) {
        if (rgba[i] != fake::pattern(i)) {
          wrong.push_back("pixels are not the copied image");
          break;
        }
      }
      // The order the new checks guard: memory bound before use, a command buffer begun before it
      // is recorded and ended before it is submitted, and the fence reset before the submit uses it.
      const char *order[] = {"vkBindImageMemory", "vkBindBufferMemory", "vkMapMemory", "vkBeginCommandBuffer",
                             "vkCmdCopyImageToBuffer", "vkEndCommandBuffer", "vkResetFences", "vkQueueSubmit",
                             "vkWaitForFences"};
      for (size_t i = 1; i < sizeof(order) / sizeof(order[0]); i++) {
        if (first(order[i - 1]) < 0 || first(order[i - 1]) >= first(order[i])) {
          wrong.push_back(std::string(order[i - 1]) + " not before " + order[i]);
        }
      }
    } else {
      for (size_t i = 0; i < k_rgba; i++) {
        if (rgba[i] != 0xee) {
          wrong.push_back("pixels written after a failure");
          break;
        }
      }
      if (first("vkQueueSubmit") >= 0) wrong.push_back("submitted after a failure");
    }

    std::printf("%s: %s\n", label, wrong.empty() ? "ok" : "FAILED");
    for (const auto &reason : wrong) std::printf("  %s\n", reason.c_str());
    return wrong.empty();
  }

}  // namespace

int main() {
  bool all = true;
  all = run("success", nullptr, true) && all;
  // The first call of each name is the readback's own: its offscreen image and readback buffer are
  // bound before create_planes binds the plane images and the staging buffer.
  all = run("offscreen image bind fails", "vkBindImageMemory", false) && all;
  all = run("readback buffer bind fails", "vkBindBufferMemory", false) && all;
  all = run("command buffer begin fails", "vkBeginCommandBuffer", false) && all;
  all = run("command buffer end fails", "vkEndCommandBuffer", false) && all;
  all = run("fence reset fails", "vkResetFences", false) && all;
  if (!all) return 1;
  std::puts("all readback failure cases passed");
  return 0;
}
