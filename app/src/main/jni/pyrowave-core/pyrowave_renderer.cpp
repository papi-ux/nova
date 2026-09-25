#include "pyrowave_renderer.h"

#include "pyrowave_renderer_c.h"

#include "shaders/present_frag_spv.h"
#include "shaders/present_vert_spv.h"

#include <android/log.h>
#include <sys/system_properties.h>

#include <chrono>
#include <cstring>

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace nova_vk {

  namespace {

// Every entry point this renderer uses, resolved rather than linked, for the same reason the device
// layer resolves its own: libvulkan.so does not exist below API 24 and linking it would stop the
// whole app loading there.
#define NOVA_VK_DEVICE_FUNCTIONS(X) \
  X(vkDeviceWaitIdle) \
  X(vkCreateSwapchainKHR) X(vkDestroySwapchainKHR) X(vkGetSwapchainImagesKHR) \
  X(vkAcquireNextImageKHR) X(vkQueuePresentKHR) \
  X(vkCreateImageView) X(vkDestroyImageView) \
  X(vkCreateRenderPass) X(vkDestroyRenderPass) \
  X(vkCreateFramebuffer) X(vkDestroyFramebuffer) \
  X(vkCreateShaderModule) X(vkDestroyShaderModule) \
  X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) \
  X(vkCreateGraphicsPipelines) X(vkDestroyPipeline) \
  X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) \
  X(vkCreateDescriptorPool) X(vkDestroyDescriptorPool) \
  X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
  X(vkCreateSampler) X(vkDestroySampler) \
  X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkBindImageMemory) \
  X(vkAllocateMemory) X(vkFreeMemory) X(vkMapMemory) \
  X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) \
  X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) \
  X(vkBeginCommandBuffer) X(vkEndCommandBuffer) X(vkCmdPipelineBarrier) X(vkCmdCopyBufferToImage) \
  X(vkCmdBeginRenderPass) X(vkCmdEndRenderPass) X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) \
  X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdDraw) X(vkCmdPushConstants) \
  X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkCreateFence) X(vkDestroyFence) \
  X(vkWaitForFences) X(vkResetFences) X(vkQueueSubmit)

#define NOVA_VK_INSTANCE_FUNCTIONS(X) \
  X(vkCreateAndroidSurfaceKHR) X(vkDestroySurfaceKHR) \
  X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) X(vkGetPhysicalDeviceSurfaceFormatsKHR) \
  X(vkGetPhysicalDeviceSurfacePresentModesKHR) X(vkGetPhysicalDeviceSurfaceSupportKHR) \
  X(vkGetPhysicalDeviceMemoryProperties)

#define NOVA_VK_DECLARE(name) PFN_##name name = nullptr;

// Optional diagnostics must never make an otherwise usable device fail initialization.
#define NOVA_VK_TIMING_DEVICE_FUNCTIONS(X) \
  X(vkCreateQueryPool) X(vkDestroyQueryPool) X(vkCmdResetQueryPool) \
  X(vkCmdWriteTimestamp) X(vkGetQueryPoolResults)
#define NOVA_VK_TIMING_INSTANCE_FUNCTIONS(X) \
  X(vkGetPhysicalDeviceProperties) X(vkGetPhysicalDeviceQueueFamilyProperties)

    struct api_t {
      NOVA_VK_INSTANCE_FUNCTIONS(NOVA_VK_DECLARE)
      NOVA_VK_DEVICE_FUNCTIONS(NOVA_VK_DECLARE)
      NOVA_VK_TIMING_DEVICE_FUNCTIONS(NOVA_VK_DECLARE)
      NOVA_VK_TIMING_INSTANCE_FUNCTIONS(NOVA_VK_DECLARE)

      bool resolve(PFN_vkGetInstanceProcAddr get_instance_proc_addr, VkInstance instance, VkDevice device) {
        auto get_device_proc_addr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
          get_instance_proc_addr(instance, "vkGetDeviceProcAddr"));
        if (!get_device_proc_addr) {
          return false;
        }

        bool ok = true;
#define NOVA_VK_RESOLVE_INSTANCE(name) \
  name = reinterpret_cast<PFN_##name>(get_instance_proc_addr(instance, #name)); \
  if (!name) { LOGW("missing %s", #name); ok = false; }
        NOVA_VK_INSTANCE_FUNCTIONS(NOVA_VK_RESOLVE_INSTANCE)
#undef NOVA_VK_RESOLVE_INSTANCE

#define NOVA_VK_RESOLVE_DEVICE(name) \
  name = reinterpret_cast<PFN_##name>(get_device_proc_addr(device, #name)); \
  if (!name) { LOGW("missing %s", #name); ok = false; }
        NOVA_VK_DEVICE_FUNCTIONS(NOVA_VK_RESOLVE_DEVICE)
#undef NOVA_VK_RESOLVE_DEVICE

        return ok;
      }
    };

    api_t vk;

    uint64_t monotonic_ns() {
      return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    void finish_timing(timing_metric_t &metric, uint64_t start_ns) {
      if (start_ns) metric.add((monotonic_ns() - start_ns) / 1e6);
    }

  }  // namespace

  renderer_t::~renderer_t() {
    destroy();
  }

  uint32_t renderer_t::memory_type(uint32_t bits, VkMemoryPropertyFlags want) const {
    VkPhysicalDeviceMemoryProperties properties = {};
    vk.vkGetPhysicalDeviceMemoryProperties(device.physical_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
      if ((bits & (1u << i)) && (properties.memoryTypes[i].propertyFlags & want) == want) {
        return i;
      }
    }
    return UINT32_MAX;
  }

  bool renderer_t::create(ANativeWindow *native_window, uint32_t width, uint32_t height,
                          bool chroma_444, bool stream_is_hdr) {
    destroy();

    if (!native_window || width == 0 || height == 0) {
      return false;
    }
    window = native_window;
    frame_width = width;
    frame_height = height;
    chroma_shift = chroma_444 ? 0 : 1;
    hdr = stream_is_hdr;

    if (!device.create(true)) {
      return false;
    }
    if (!vk.resolve(device.get_instance_proc_addr(), device.instance, device.device)) {
      LOGW("this driver is missing entry points the renderer needs");
      destroy();
      return false;
    }

    if (!create_surface(native_window) || !create_swapchain() || !create_render_pass() ||
        !create_planes() || !create_descriptors() || !create_pipeline() || !create_frame_resources() ||
        !create_decoder()) {
      destroy();
      return false;
    }

    LOGI("renderer ready: %ux%u %s into a %ux%u surface", frame_width, frame_height,
         chroma_shift == 0 ? "4:4:4" : "4:2:0", swapchain_extent.width, swapchain_extent.height);
    create_timing();
    return true;
  }

  void renderer_t::create_timing() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("debug.nova.pyrowave_timing", value);
    timing_enabled = std::strcmp(value, "1") == 0;
    if (!timing_enabled) return;
    timing_window_ns = monotonic_ns();

    auto get_instance = device.get_instance_proc_addr();
    auto get_device = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
      get_instance(device.instance, "vkGetDeviceProcAddr"));
    bool available = get_device != nullptr;
#define NOVA_VK_TIMING_INSTANCE(name) \
    vk.name = reinterpret_cast<PFN_##name>(get_instance(device.instance, #name)); \
    available = available && vk.name != nullptr;
    NOVA_VK_TIMING_INSTANCE_FUNCTIONS(NOVA_VK_TIMING_INSTANCE)
#undef NOVA_VK_TIMING_INSTANCE
#define NOVA_VK_TIMING_DEVICE(name) \
    vk.name = get_device ? reinterpret_cast<PFN_##name>(get_device(device.device, #name)) : nullptr; \
    available = available && vk.name != nullptr;
    NOVA_VK_TIMING_DEVICE_FUNCTIONS(NOVA_VK_TIMING_DEVICE)
#undef NOVA_VK_TIMING_DEVICE
    if (!available) {
      LOGI("timing enabled: CPU only (timestamp entry points unavailable)");
      return;
    }

    uint32_t count = 0;
    vk.vkGetPhysicalDeviceQueueFamilyProperties(device.physical_device, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vk.vkGetPhysicalDeviceQueueFamilyProperties(device.physical_device, &count, families.data());
    VkPhysicalDeviceProperties properties = {};
    vk.vkGetPhysicalDeviceProperties(device.physical_device, &properties);
    timestamp_bits = device.graphics_family < count ? families[device.graphics_family].timestampValidBits : 0;
    timestamp_period_ns = properties.limits.timestampPeriod;
    if (timestamp_bits == 0 || timestamp_bits > 64 || !std::isfinite(timestamp_period_ns) ||
        timestamp_period_ns <= 0) {
      LOGI("timing enabled: CPU only (queue timestamps unsupported)");
      return;
    }
    VkQueryPoolCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    info.queryType = VK_QUERY_TYPE_TIMESTAMP;
    info.queryCount = 3;
    if (vk.vkCreateQueryPool(device.device, &info, nullptr, &timing_queries) != VK_SUCCESS) {
      timing_queries = VK_NULL_HANDLE;
      LOGI("timing enabled: CPU only (query pool unavailable)");
      return;
    }
    LOGI("timing enabled: GPU bits=%u period_ns=%.6f; intervals include barriers and GPU waits",
         timestamp_bits, timestamp_period_ns);
  }

  void renderer_t::collect_gpu_timing() {
    if (!timing_queries) return;
    timestamp_result_t queries[3] = {};
    const auto result = vk.vkGetQueryPoolResults(device.device, timing_queries, 0, 3, sizeof(queries),
      queries, sizeof(queries[0]), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
    gpu_intervals_t intervals = {};
    if (result == VK_SUCCESS && timestamp_intervals(queries, timestamp_bits, timestamp_period_ns, intervals)) {
      gpu_planes.add(intervals.planes_ms);
      gpu_draw.add(intervals.draw_ms);
    } else {
      ++timing_unavailable;
    }
  }

  void renderer_t::report_timing(bool final) {
    if (!timing_enabled) return;
    const auto now = monotonic_ns();
    if (!final && now - timing_window_ns < 5000000000ULL) return;
    // Each triple is mean/max/count; -1 means no measurement, not zero GPU work. The draw interval
    // ends at command completion, not scanout, and may include the swapchain semaphore wait.
#define NOVA_TIMING_VALUES(metric) metric.mean(), metric.maximum(), static_cast<unsigned long long>(metric.count)
    LOGI("timing final=%d window_ms=%.1f gpu_unavailable=%llu "
         "gpu_planes_ms=%.3f/%.3f/%llu gpu_draw_ms=%.3f/%.3f/%llu "
         "cpu_fence_ms=%.3f/%.3f/%llu cpu_prepare_ms=%.3f/%.3f/%llu "
         "cpu_record_ms=%.3f/%.3f/%llu cpu_acquire_ms=%.3f/%.3f/%llu "
         "cpu_submit_ms=%.3f/%.3f/%llu cpu_present_ms=%.3f/%.3f/%llu",
         final, (now - timing_window_ns) / 1e6, static_cast<unsigned long long>(timing_unavailable),
         NOVA_TIMING_VALUES(gpu_planes), NOVA_TIMING_VALUES(gpu_draw), NOVA_TIMING_VALUES(cpu_fence),
         NOVA_TIMING_VALUES(cpu_prepare), NOVA_TIMING_VALUES(cpu_record), NOVA_TIMING_VALUES(cpu_acquire),
         NOVA_TIMING_VALUES(cpu_submit), NOVA_TIMING_VALUES(cpu_present));
#undef NOVA_TIMING_VALUES
    gpu_planes = {}; gpu_draw = {};
    cpu_fence = {}; cpu_prepare = {}; cpu_record = {};
    cpu_acquire = {}; cpu_submit = {}; cpu_present = {};
    timing_unavailable = 0;
    timing_window_ns = now;
  }

  bool renderer_t::create_surface(ANativeWindow *native_window) {
    VkAndroidSurfaceCreateInfoKHR info = {};
    info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    info.window = native_window;
    if (vk.vkCreateAndroidSurfaceKHR(device.instance, &info, nullptr, &surface) != VK_SUCCESS) {
      LOGW("could not make a Vulkan surface from this window");
      return false;
    }

    VkBool32 supported = VK_FALSE;
    vk.vkGetPhysicalDeviceSurfaceSupportKHR(device.physical_device, device.graphics_family, surface, &supported);
    if (!supported) {
      LOGW("the graphics queue cannot present to this surface");
      return false;
    }
    return true;
  }

  bool renderer_t::create_swapchain() {
    VkSurfaceCapabilitiesKHR caps = {};
    if (vk.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physical_device, surface, &caps) != VK_SUCCESS) {
      return false;
    }

    uint32_t count = 0;
    vk.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physical_device, surface, &count, nullptr);
    if (count == 0) {
      return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(count);
    vk.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physical_device, surface, &count, formats.data());

    // For HDR the surface has to offer ten bits a channel and the ST.2084 colour space, and if it
    // does not there is nothing sensible to fall back to: the frames arriving are PQ encoded BT.2020,
    // and presenting them to an sRGB swapchain shows a dark, oversaturated picture that looks like the
    // stream is broken. So this fails, the renderer says why, and the session ends with a reason.
    //
    // Android reports this colour space only when the instance asked for VK_EXT_swapchain_colorspace,
    // which the device does when it is created for presentation.
    bool found = false;
    VkSurfaceFormatKHR chosen = formats.front();
    if (hdr) {
      for (const auto &format : formats) {
        if (format.colorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT) {
          continue;
        }
        if (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
            format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32 ||
            format.format == VK_FORMAT_R16G16B16A16_SFLOAT) {
          chosen = format;
          found = true;
          break;
        }
      }
      if (!found) {
        LOGW("this surface cannot present HDR10, and an HDR stream has nothing else to be shown as");
        return false;
      }
    } else {
      for (const auto &format : formats) {
        if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
          chosen = format;
          found = true;
          break;
        }
      }
    }
    swapchain_format = chosen.format;
    swapchain_colour_space = chosen.colorSpace;

    swapchain_extent = caps.currentExtent;
    if (swapchain_extent.width == UINT32_MAX) {
      swapchain_extent.width = frame_width;
      swapchain_extent.height = frame_height;
    }

    // Mailbox if the driver has it, immediate if not, FIFO if neither.
    //
    // FIFO waits for vblank on every present, and a stream is not paced by this display: the frames
    // arrive on the host's clock, and the two are never quite in phase. Waiting for vblank inside
    // the call that delivers a frame means the submit thread stalls for up to a whole refresh, and
    // because the decode happens on that same thread the next frame is late as well. That is what a
    // bad one percent low is made of, and this renderer measured 52 against HEVC's 60 on the same
    // device and content while it was doing this.
    //
    // Mailbox keeps the queue full and replaces what has not been shown yet, so a frame that arrives
    // early does not block and a frame that arrives late is simply the next one shown. Immediate
    // does not synchronise at all, which can tear but never waits. FIFO stays as the fallback
    // because it is the only one every implementation is required to offer.
    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t mode_count = 0;
    vk.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physical_device, surface, &mode_count, nullptr);
    if (mode_count > 0) {
      std::vector<VkPresentModeKHR> modes(mode_count);
      vk.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physical_device, surface, &mode_count, modes.data());
      for (auto mode : modes) {
        if (mode == VK_PRESENT_MODE_MAILBOX_KHR) {
          present_mode = mode;
          break;
        }
        if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR) {
          present_mode = mode;
        }
      }
    }

    // Mailbox needs a third image to have anything to replace; with two it degrades into waiting.
    uint32_t images = caps.minImageCount + 1;
    if (present_mode == VK_PRESENT_MODE_MAILBOX_KHR && images < 3) {
      images = 3;
    }
    if (caps.maxImageCount > 0 && images > caps.maxImageCount) {
      images = caps.maxImageCount;
    }

    VkSwapchainCreateInfoKHR info = {};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = surface;
    info.minImageCount = images;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = swapchain_extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Identity when the surface offers it, rather than currentTransform.
    //
    // Passing currentTransform through is the efficient path and it is also a promise: it tells
    // Vulkan the application will apply the display rotation itself. A panel whose native
    // orientation is portrait reports ROTATE_90 while held in landscape, so a renderer that passes
    // it on and then draws a plain fullscreen triangle puts the picture up on its side. Measured
    // exactly that way on a Pixel Tablet before this line existed.
    //
    // Identity hands the rotation back to the compositor, which costs a pass it was likely doing
    // anyway. Worth revisiting with a rotation in the vertex shader if that pass ever shows up.
    info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                          ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                          : caps.currentTransform;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    info.presentMode = present_mode;
    info.clipped = VK_TRUE;

    LOGI("swapchain: %u images, present mode %d, format %d, colour space %d (%s)", images,
         static_cast<int>(present_mode), static_cast<int>(swapchain_format),
         static_cast<int>(swapchain_colour_space), hdr ? "HDR10 PQ" : "SDR");
    if (vk.vkCreateSwapchainKHR(device.device, &info, nullptr, &swapchain) != VK_SUCCESS) {
      LOGW("could not create a swapchain");
      return false;
    }

    vk.vkGetSwapchainImagesKHR(device.device, swapchain, &count, nullptr);
    swapchain_images.resize(count);
    vk.vkGetSwapchainImagesKHR(device.device, swapchain, &count, swapchain_images.data());

    swapchain_views.resize(count);
    for (uint32_t i = 0; i < count; i++) {
      VkImageViewCreateInfo view = {};
      view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
      view.image = swapchain_images[i];
      view.viewType = VK_IMAGE_VIEW_TYPE_2D;
      view.format = swapchain_format;
      view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
      view.subresourceRange.levelCount = 1;
      view.subresourceRange.layerCount = 1;
      if (vk.vkCreateImageView(device.device, &view, nullptr, &swapchain_views[i]) != VK_SUCCESS) {
        return false;
      }
    }
    return true;
  }

  bool renderer_t::create_render_pass() {
    VkAttachmentDescription colour = {};
    colour.format = swapchain_format;
    colour.samples = VK_SAMPLE_COUNT_1_BIT;
    colour.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colour.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colour.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colour.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colour.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colour.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference reference = {};
    reference.attachment = 0;
    reference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass = {};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;

    VkSubpassDependency dependency = {};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 1;
    info.pAttachments = &colour;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dependency;

    if (vk.vkCreateRenderPass(device.device, &info, nullptr, &render_pass) != VK_SUCCESS) {
      return false;
    }

    framebuffers.resize(swapchain_views.size());
    for (size_t i = 0; i < swapchain_views.size(); i++) {
      VkFramebufferCreateInfo fb = {};
      fb.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
      fb.renderPass = render_pass;
      fb.attachmentCount = 1;
      fb.pAttachments = &swapchain_views[i];
      fb.width = swapchain_extent.width;
      fb.height = swapchain_extent.height;
      fb.layers = 1;
      if (vk.vkCreateFramebuffer(device.device, &fb, nullptr, &framebuffers[i]) != VK_SUCCESS) {
        return false;
      }
    }
    return true;
  }

  bool renderer_t::create_decoder() {
    pyrowave_decoder_create_info info = {};
    info.device = device.codec;
    info.width = static_cast<int>(frame_width);
    info.height = static_cast<int>(frame_height);
    info.chroma = chroma_shift == 0 ? PYROWAVE_CHROMA_SUBSAMPLING_444
                                    : PYROWAVE_CHROMA_SUBSAMPLING_420;

    // The compute path, on hardware that says it would rather have the fragment one.
    //
    // pyrowave_decoder_device_prefers_fragment_path() returns true on both Android devices this has
    // been run on, and it is telling the truth about what the hardware likes: the fragment path
    // exists because mobile GPUs have weak compute. But upstream's own roundtrip test fails on that
    // path on those same devices, and the compute path decodes bit exactly on both. A slower answer
    // that is right beats a faster one that is wrong, so this is a deliberate refusal of the advice
    // and it should be revisited when upstream's fragment path passes its own test.
    info.fragment_path = false;

    const auto result = pyrowave_decoder_create(&info, &decoder);
    if (result != PYROWAVE_SUCCESS) {
      LOGW("could not make a %ux%u decoder (%d)", frame_width, frame_height, static_cast<int>(result));
      decoder = nullptr;
      return false;
    }

    // Says which queue the command buffer handed over later belongs to, which for the compute path
    // is a compute capable one. The device picked a family with both bits for exactly this.
    if (pyrowave_device_set_queue_type(device.codec, VK_QUEUE_COMPUTE_BIT) != PYROWAVE_SUCCESS) {
      LOGW("this device will not take compute work for the decoder");
      return false;
    }

    return true;
  }

  bool renderer_t::create_planes() {
    const uint32_t extents[3][2] = {
      {frame_width, frame_height},
      {frame_width >> chroma_shift, frame_height >> chroma_shift},
      {frame_width >> chroma_shift, frame_height >> chroma_shift},
    };

    // Sixteen bits a sample for HDR, because PQ spends most of its code space on the dark end and
    // eight bits of it bands where it shows. Eight for SDR, which is what the stream carries.
    const VkFormat plane_format = hdr ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM;
    const VkDeviceSize bytes_a_sample = hdr ? 2 : 1;

    VkDeviceSize offset = 0;
    for (int i = 0; i < 3; i++) {
      planes[i].width = extents[i][0];
      planes[i].height = extents[i][1];
      planes[i].offset = offset;
      offset += static_cast<VkDeviceSize>(planes[i].width) * planes[i].height * bytes_a_sample;

      VkImageCreateInfo info = {};
      info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
      info.imageType = VK_IMAGE_TYPE_2D;
      info.format = plane_format;
      info.extent = {planes[i].width, planes[i].height, 1};
      info.mipLevels = 1;
      info.arrayLayers = 1;
      info.samples = VK_SAMPLE_COUNT_1_BIT;
      info.tiling = VK_IMAGE_TILING_OPTIMAL;
      // Storage as well, because the decoder writes these with a compute shader. Sampled for the
      // draw, transfer for the bring-up path that uploads a known picture instead of decoding one.
      info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                   VK_IMAGE_USAGE_STORAGE_BIT;
      info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      if (vk.vkCreateImage(device.device, &info, nullptr, &planes[i].image) != VK_SUCCESS) {
        return false;
      }

      VkMemoryRequirements requirements = {};
      vk.vkGetImageMemoryRequirements(device.device, planes[i].image, &requirements);
      VkMemoryAllocateInfo allocate = {};
      allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
      allocate.allocationSize = requirements.size;
      allocate.memoryTypeIndex = memory_type(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
      if (allocate.memoryTypeIndex == UINT32_MAX ||
          vk.vkAllocateMemory(device.device, &allocate, nullptr, &planes[i].memory) != VK_SUCCESS) {
        return false;
      }
      vk.vkBindImageMemory(device.device, planes[i].image, planes[i].memory, 0);

      VkImageViewCreateInfo view = {};
      view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
      view.image = planes[i].image;
      view.viewType = VK_IMAGE_VIEW_TYPE_2D;
      view.format = plane_format;
      view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
      view.subresourceRange.levelCount = 1;
      view.subresourceRange.layerCount = 1;
      if (vk.vkCreateImageView(device.device, &view, nullptr, &planes[i].view) != VK_SUCCESS) {
        return false;
      }
    }

    staging_size = offset;
    VkBufferCreateInfo buffer = {};
    buffer.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer.size = staging_size;
    buffer.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    buffer.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vk.vkCreateBuffer(device.device, &buffer, nullptr, &staging) != VK_SUCCESS) {
      return false;
    }

    VkMemoryRequirements requirements = {};
    vk.vkGetBufferMemoryRequirements(device.device, staging, &requirements);
    VkMemoryAllocateInfo allocate = {};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = memory_type(
      requirements.memoryTypeBits,
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (allocate.memoryTypeIndex == UINT32_MAX ||
        vk.vkAllocateMemory(device.device, &allocate, nullptr, &staging_memory) != VK_SUCCESS) {
      return false;
    }
    vk.vkBindBufferMemory(device.device, staging, staging_memory, 0);
    if (vk.vkMapMemory(device.device, staging_memory, 0, staging_size, 0, &staging_mapped) != VK_SUCCESS) {
      return false;
    }
    return true;
  }

  bool renderer_t::create_descriptors() {
    VkSamplerCreateInfo sampler_info = {};
    sampler_info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampler_info.magFilter = VK_FILTER_LINEAR;
    sampler_info.minFilter = VK_FILTER_LINEAR;
    sampler_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler_info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.maxLod = VK_LOD_CLAMP_NONE;
    if (vk.vkCreateSampler(device.device, &sampler_info, nullptr, &sampler) != VK_SUCCESS) {
      return false;
    }

    VkDescriptorSetLayoutBinding bindings[3] = {};
    for (uint32_t i = 0; i < 3; i++) {
      bindings[i].binding = i;
      bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      bindings[i].descriptorCount = 1;
      bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    }

    VkDescriptorSetLayoutCreateInfo layout = {};
    layout.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layout.bindingCount = 3;
    layout.pBindings = bindings;
    if (vk.vkCreateDescriptorSetLayout(device.device, &layout, nullptr, &set_layout) != VK_SUCCESS) {
      return false;
    }

    VkDescriptorPoolSize size = {};
    size.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    size.descriptorCount = 3;

    VkDescriptorPoolCreateInfo pool = {};
    pool.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pool.maxSets = 1;
    pool.poolSizeCount = 1;
    pool.pPoolSizes = &size;
    if (vk.vkCreateDescriptorPool(device.device, &pool, nullptr, &descriptor_pool) != VK_SUCCESS) {
      return false;
    }

    VkDescriptorSetAllocateInfo allocate = {};
    allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocate.descriptorPool = descriptor_pool;
    allocate.descriptorSetCount = 1;
    allocate.pSetLayouts = &set_layout;
    if (vk.vkAllocateDescriptorSets(device.device, &allocate, &descriptor_set) != VK_SUCCESS) {
      return false;
    }

    VkDescriptorImageInfo images[3] = {};
    VkWriteDescriptorSet writes[3] = {};
    for (uint32_t i = 0; i < 3; i++) {
      images[i].sampler = sampler;
      images[i].imageView = planes[i].view;
      // GENERAL rather than the read only layout a texture would normally sit in, because the
      // decoder writes these as storage images and the codec performs no layout transitions of its
      // own in the GPU paths. One resting layout for writing and reading means no transition per
      // frame and no pair of paths to keep in step.
      images[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;

      writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[i].dstSet = descriptor_set;
      writes[i].dstBinding = i;
      writes[i].descriptorCount = 1;
      writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      writes[i].pImageInfo = &images[i];
    }
    vk.vkUpdateDescriptorSets(device.device, 3, writes, 0, nullptr);
    return true;
  }

  bool renderer_t::create_pipeline() {
    const auto make_module = [&](const uint32_t *code, size_t bytes) {
      VkShaderModuleCreateInfo info = {};
      info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
      info.codeSize = bytes;
      info.pCode = code;
      VkShaderModule module = VK_NULL_HANDLE;
      vk.vkCreateShaderModule(device.device, &info, nullptr, &module);
      return module;
    };

    VkShaderModule vertex = make_module(present_vert_spv, sizeof(present_vert_spv));
    VkShaderModule fragment = make_module(present_frag_spv, sizeof(present_frag_spv));
    if (!vertex || !fragment) {
      LOGW("the driver refused the vendored shaders");
      return false;
    }

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertex;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragment;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertex_input = {};
    vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo assembly = {};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewport = {};
    viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster = {};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample = {};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blend_attachment = {};
    blend_attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                      VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    VkPipelineColorBlendStateCreateInfo blend = {};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blend_attachment;

    const VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic = {};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamic_states;

    // The colour matrix, pushed rather than compiled in, so the vendored shader blob serves both
    // colourimetries and there is one code path to be wrong in rather than two.
    VkPushConstantRange matrix_range = {};
    matrix_range.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    matrix_range.offset = 0;
    matrix_range.size = sizeof(colour_matrix_t);

    VkPipelineLayoutCreateInfo layout = {};
    layout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout.setLayoutCount = 1;
    layout.pSetLayouts = &set_layout;
    layout.pushConstantRangeCount = 1;
    layout.pPushConstantRanges = &matrix_range;
    if (vk.vkCreatePipelineLayout(device.device, &layout, nullptr, &pipeline_layout) != VK_SUCCESS) {
      return false;
    }

    VkGraphicsPipelineCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertex_input;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = pipeline_layout;
    info.renderPass = render_pass;

    const auto result = vk.vkCreateGraphicsPipelines(device.device, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline);
    vk.vkDestroyShaderModule(device.device, vertex, nullptr);
    vk.vkDestroyShaderModule(device.device, fragment, nullptr);
    return result == VK_SUCCESS;
  }

  bool renderer_t::create_frame_resources() {
    VkCommandPoolCreateInfo pool = {};
    pool.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = device.graphics_family;
    if (vk.vkCreateCommandPool(device.device, &pool, nullptr, &command_pool) != VK_SUCCESS) {
      return false;
    }

    VkCommandBufferAllocateInfo allocate = {};
    allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate.commandPool = command_pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    if (vk.vkAllocateCommandBuffers(device.device, &allocate, &command_buffer) != VK_SUCCESS) {
      return false;
    }

    VkSemaphoreCreateInfo semaphore = {};
    semaphore.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vk.vkCreateSemaphore(device.device, &semaphore, nullptr, &acquired) != VK_SUCCESS ||
        vk.vkCreateSemaphore(device.device, &semaphore, nullptr, &rendered) != VK_SUCCESS) {
      return false;
    }

    VkFenceCreateInfo fence = {};
    fence.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    return vk.vkCreateFence(device.device, &fence, nullptr, &in_flight) == VK_SUCCESS;
  }

  bool renderer_t::upload(const uint8_t *luma, const uint8_t *cb, const uint8_t *cr) {
    const uint8_t *sources[3] = {luma, cb, cr};
    auto *mapped = static_cast<uint8_t *>(staging_mapped);
    for (int i = 0; i < 3; i++) {
      const size_t bytes = static_cast<size_t>(planes[i].width) * planes[i].height;
      std::memcpy(mapped + planes[i].offset, sources[i], bytes);
    }
    return true;
  }

  void renderer_t::await_last_frame() {
    if (!frame_submitted) {
      report_timing();
      return;
    }
    const auto start = timing_enabled ? monotonic_ns() : 0;
    const auto waited = vk.vkWaitForFences(device.device, 1, &in_flight, VK_TRUE, UINT64_MAX);
    finish_timing(cpu_fence, start);
    // Never read a query from a refused/failed submit, or before GPU completion. No WAIT flag:
    // unavailable measurements are counted and skipped instead of adding another blocking wait.
    if (waited == VK_SUCCESS) collect_gpu_timing();
    frame_submitted = false;
    report_timing();
  }

  void renderer_t::barrier_planes(VkCommandBuffer cmd, VkPipelineStageFlags from,
                                  VkAccessFlags from_access, VkPipelineStageFlags to,
                                  VkAccessFlags to_access) {
    VkImageMemoryBarrier barriers[3] = {};
    for (int i = 0; i < 3; i++) {
      barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
      // UNDEFINED the first time and GENERAL after, which is the only transition these images ever
      // make. Discarding the previous contents on the first pass is correct: nothing has read them.
      barriers[i].oldLayout = planes_initialised ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
      barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
      barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barriers[i].image = planes[i].image;
      barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      barriers[i].srcAccessMask = planes_initialised ? from_access : 0;
      barriers[i].dstAccessMask = to_access;
    }
    vk.vkCmdPipelineBarrier(cmd, planes_initialised ? from : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            to, 0, 0, nullptr, 0, nullptr, 3, barriers);

    // Set here rather than at the end of a frame, because two of these run per frame and the second
    // has to see the layout the first one left. Marking it later made the post barrier claim the
    // images were still UNDEFINED and discard what the decode had just written into them.
    planes_initialised = true;
  }

  bool renderer_t::record_decode(VkCommandBuffer cmd) {
    barrier_planes(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);

    // The images are described rather than handed over: Nova made them, so the codec takes views of
    // them. Each view's extent is its own image's, which is the luma size only for the first; the
    // note in the codec's header about using luma dimensions is about one planar image backing three
    // views, and these are three separate single channel images.
    pyrowave_gpu_buffers buffers = {};
    for (int i = 0; i < 3; i++) {
      buffers.planes[i].image = planes[i].image;
      buffers.planes[i].width = planes[i].width;
      buffers.planes[i].height = planes[i].height;
      buffers.planes[i].image_format = hdr ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM;
      buffers.planes[i].view_format = buffers.planes[i].image_format;
      buffers.planes[i].mip_level = 0;
      buffers.planes[i].layer = 0;
      buffers.planes[i].aspect = VK_IMAGE_ASPECT_COLOR_BIT;
      buffers.planes[i].swizzle = VK_COMPONENT_SWIZZLE_IDENTITY;
      buffers.planes[i].layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    // Into this command buffer rather than one of the codec's own, so that the decode and the draw
    // that reads its output are one submit under one fence. Cleared straight after, because the
    // codec keeps the handle and would record the next call into a buffer already submitted.
    pyrowave_device_set_command_buffer(device.codec, cmd);
    const auto result = pyrowave_decoder_decode_gpu_buffer(decoder, nullptr, nullptr, &buffers);
    pyrowave_device_set_command_buffer(device.codec, VK_NULL_HANDLE);

    if (result != PYROWAVE_SUCCESS) {
      LOGW("the decode failed (%d)", static_cast<int>(result));
      return false;
    }

    barrier_planes(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                   VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    return true;
  }

  bool renderer_t::decode_and_present(const uint8_t *bitstream, std::size_t size) {
    if (!swapchain || !decoder || !bitstream || size == 0) {
      return false;
    }

    await_last_frame();

    // One push for the whole frame. The bitstream delimits itself, so the decoder walks the blocks
    // inside it and this is the same work as pushing each of the codec's packets in turn.
    //
    // Complete or nothing. The codec can decode a frame that lost packets, and this transport does
    // not lose them one at a time: it reassembles a whole frame under FEC or drops it, so a frame
    // that arrives here incomplete arrived corrupt.
    //
    // The sequence number this frame carries, read the way the decoder reads it.
    //
    // push_packet judges every frame by this and says nothing: a frame whose sequence looks like it
    // belongs to the past is dropped and success is still returned. The field is three bits, so it
    // wraps every eight frames, and "the past" is any gap of more than four. Logging it beside the
    // outcome is the only way to see that happening.
    uint32_t frame_sequence = 0;
    if (size >= 4) {
      uint32_t first = 0;
      std::memcpy(&first, bitstream, sizeof(first));
      frame_sequence = (first >> 28) & 0x7;
    }

    // A frame that lost blocks is still a frame, and drawing it is the reason to have this codec.
    //
    // This used to insist on a whole one, on the reasoning that the transport reassembles a frame
    // under FEC or drops it. That is not what happens: moonlight hands over a full sized buffer with
    // the holes still in it where packets did not arrive, so the frame is the right length and some
    // blocks inside it are missing. Refusing those threw away fifty two frames in forty five seconds
    // on one of the two handhelds here, every one of which the codec could have drawn.
    //
    // The codec sets its own floor for this and it is a sensible one: the two lowest frequency bands
    // have to be intact and at least ninety percent of the blocks present. Those bands carry the
    // structure of the picture, so what comes back is the frame with some fine detail missing rather
    // than anything corrupt, which is what an intra-only wavelet codec degrades into. Below that
    // floor it still says no and the frame is dropped.
    //
    // The retry around it is for the first frame of a decoder's life and nothing else, and the flag
    // is what makes that true rather than the comment. On both Android devices here a decoder that
    // has never produced a frame accepts its first one, reports success and counts none of its
    // blocks; clearing and pushing the same bytes works every time, and clearing at creation does
    // not, so it is something the first push leaves behind. The codec's own CPU entry point does not
    // need it and the Linux client does not see it on desktop NVIDIA or a Steam Deck, so it looks
    // like this library build or these drivers rather than the codec.
    bool ready = false;
    bool partial = false;
    const int attempts = decoder_warmed ? 1 : 2;
    const auto prepare_start = timing_enabled ? monotonic_ns() : 0;

    for (int attempt = 0; attempt < attempts && !ready; attempt++) {
      const auto pushed = pyrowave_decoder_push_packet(decoder, bitstream, size);
      if (pushed != PYROWAVE_SUCCESS) {
        finish_timing(cpu_prepare, prepare_start);
        LOGW("the decoder refused the frame (%d)", static_cast<int>(pushed));
        return false;
      }

      if (pyrowave_decoder_decode_is_ready(decoder, false)) {
        ready = true;
      }
      else if (pyrowave_decoder_decode_is_ready(decoder, true)) {
        ready = true;
        partial = true;
      }
      else if (attempt + 1 < attempts) {
        pyrowave_decoder_clear(decoder);
      }
    }

    finish_timing(cpu_prepare, prepare_start);
    if (!ready) {
      // Temporary, for the burst of unusable frames at the start of a session. Two stories fit what
      // has been seen so far and they want different fixes: either the decoder is still warming and
      // is counting none of the blocks it parses, or the link really is losing enough of each frame
      // that even a partial decode is out of reach. Whether a frame ever gets close to the codec's
      // floor separates them, so say how many of these have happened and whether the decoder has
      // ever produced anything.
      unusable_frames++;
      if (unusable_frames <= 40) {
        LOGW("unusable %llu: seq %u, %zu bytes (last drawn seq %u)",
             static_cast<unsigned long long>(unusable_frames), frame_sequence, size,
             last_drawn_sequence);
      }
      return false;
    }

    decoder_warmed = true;
    if (drawn_frames < 40) {
      LOGI("drawn %llu: seq %u, %zu bytes", static_cast<unsigned long long>(drawn_frames),
           frame_sequence, size);
    }
    drawn_frames++;
    last_drawn_sequence = frame_sequence;
    if (partial) {
      partial_frames++;
      if (partial_frames == 1 || partial_frames % 120 == 0) {
        LOGI("%llu frames drawn with blocks missing", static_cast<unsigned long long>(partial_frames));
      }
    }

    return present_recorded([this](VkCommandBuffer cmd) { return record_decode(cmd); });
  }

  bool renderer_t::present(const uint8_t *luma, const uint8_t *cb, const uint8_t *cr) {
    if (!swapchain || !luma || !cb || !cr) {
      return false;
    }

    await_last_frame();

    if (!upload(luma, cb, cr)) {
      return false;
    }

    return present_recorded([this](VkCommandBuffer cmd) {
      barrier_planes(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

      for (int i = 0; i < 3; i++) {
        VkBufferImageCopy copy = {};
        copy.bufferOffset = planes[i].offset;
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {planes[i].width, planes[i].height, 1};
        vk.vkCmdCopyBufferToImage(cmd, staging, planes[i].image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
      }

      barrier_planes(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
      return true;
    });
  }

  bool renderer_t::present_recorded(const std::function<bool(VkCommandBuffer)> &fill_planes) {
    VkCommandBufferBeginInfo begin = {};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.vkBeginCommandBuffer(command_buffer, &begin);
    if (timing_queries) {
      vk.vkCmdResetQueryPool(command_buffer, timing_queries, 0, 3);
      vk.vkCmdWriteTimestamp(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timing_queries, 0);
    }

    // Recorded before a swapchain image is asked for, so that a decode that fails does not leave an
    // acquired image with nothing presenting it and a semaphore signalled with nothing waiting on it.
    const auto record_start = timing_enabled ? monotonic_ns() : 0;
    const bool filled = fill_planes(command_buffer);
    finish_timing(cpu_record, record_start);
    if (!filled) {
      vk.vkEndCommandBuffer(command_buffer);
      return false;
    }
    if (timing_queries) {
      vk.vkCmdWriteTimestamp(command_buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timing_queries, 1);
    }

    uint32_t index = 0;
    const auto acquire_start = timing_enabled ? monotonic_ns() : 0;
    const auto acquire = vk.vkAcquireNextImageKHR(
      device.device, swapchain, UINT64_MAX, acquired, VK_NULL_HANDLE, &index);
    finish_timing(cpu_acquire, acquire_start);
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
      LOGW("could not acquire a swapchain image (%d)", static_cast<int>(acquire));
      vk.vkEndCommandBuffer(command_buffer);
      return false;
    }

    VkRenderPassBeginInfo pass = {};
    pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    pass.renderPass = render_pass;
    pass.framebuffer = framebuffers[index];
    pass.renderArea.extent = swapchain_extent;
    vk.vkCmdBeginRenderPass(command_buffer, &pass, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport view = {};
    view.width = static_cast<float>(swapchain_extent.width);
    view.height = static_cast<float>(swapchain_extent.height);
    view.maxDepth = 1.0f;
    vk.vkCmdSetViewport(command_buffer, 0, 1, &view);

    VkRect2D scissor = {};
    scissor.extent = swapchain_extent;
    vk.vkCmdSetScissor(command_buffer, 0, 1, &scissor);

    vk.vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    const auto matrix = colour_matrix();
    vk.vkCmdPushConstants(command_buffer, pipeline_layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                          sizeof(matrix), &matrix);
    vk.vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout,
                               0, 1, &descriptor_set, 0, nullptr);
    vk.vkCmdDraw(command_buffer, 3, 1, 0, 0);
    vk.vkCmdEndRenderPass(command_buffer);
    if (timing_queries) {
      vk.vkCmdWriteTimestamp(command_buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timing_queries, 2);
    }
    vk.vkEndCommandBuffer(command_buffer);

    const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit = {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &acquired;
    submit.pWaitDstStageMask = &wait_stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command_buffer;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &rendered;
    // Here and nowhere earlier. Everything above this line can still refuse the frame, and a fence
    // reset for work that is then never submitted is a wait that never ends.
    vk.vkResetFences(device.device, 1, &in_flight);
    const auto submit_start = timing_enabled ? monotonic_ns() : 0;
    const auto submitted = vk.vkQueueSubmit(device.graphics_queue, 1, &submit, in_flight);
    finish_timing(cpu_submit, submit_start);
    if (submitted != VK_SUCCESS) {
      LOGW("could not submit the frame");
      // Left unsignalled, which is why nothing will wait on it: the next frame resets it again
      // before its own submit, and resetting an already unsignalled fence is allowed.
      return false;
    }
    frame_submitted = true;

    VkPresentInfoKHR present_info = {};
    present_info.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present_info.waitSemaphoreCount = 1;
    present_info.pWaitSemaphores = &rendered;
    present_info.swapchainCount = 1;
    present_info.pSwapchains = &swapchain;
    present_info.pImageIndices = &index;
    const auto present_start = timing_enabled ? monotonic_ns() : 0;
    const auto presented = vk.vkQueuePresentKHR(device.graphics_queue, &present_info);
    finish_timing(cpu_present, present_start);
    if (presented != VK_SUCCESS && presented != VK_SUBOPTIMAL_KHR) {
      LOGW("could not present (%d)", static_cast<int>(presented));
      return false;
    }
    return true;
  }

  void renderer_t::destroy_swapchain() {
    for (auto framebuffer : framebuffers) {
      if (framebuffer) vk.vkDestroyFramebuffer(device.device, framebuffer, nullptr);
    }
    framebuffers.clear();
    for (auto view : swapchain_views) {
      if (view) vk.vkDestroyImageView(device.device, view, nullptr);
    }
    swapchain_views.clear();
    swapchain_images.clear();
    if (swapchain) {
      vk.vkDestroySwapchainKHR(device.device, swapchain, nullptr);
      swapchain = VK_NULL_HANDLE;
    }
  }

  void renderer_t::destroy() {
    if (device.device && vk.vkDeviceWaitIdle) {
      const auto waited = vk.vkDeviceWaitIdle(device.device);
      if (waited == VK_SUCCESS && frame_submitted) collect_gpu_timing();
    }
    report_timing(true);
    timing_enabled = false;
    frame_submitted = false;

    // Before anything else Vulkan, and before device_t's destructor takes the codec device with it:
    // the codec requires every decoder to be gone before its device is, and it idles the GPU itself
    // on the way out. Also clears any command buffer still set on the device, since the one this
    // renderer lent it is about to stop existing.
    if (decoder) {
      pyrowave_device_set_command_buffer(device.codec, VK_NULL_HANDLE);
      pyrowave_decoder_destroy(decoder);
      decoder = nullptr;
    }

    if (device.device) {
      if (timing_queries) vk.vkDestroyQueryPool(device.device, timing_queries, nullptr);
      if (in_flight) vk.vkDestroyFence(device.device, in_flight, nullptr);
      if (rendered) vk.vkDestroySemaphore(device.device, rendered, nullptr);
      if (acquired) vk.vkDestroySemaphore(device.device, acquired, nullptr);
      if (command_pool) vk.vkDestroyCommandPool(device.device, command_pool, nullptr);
      if (pipeline) vk.vkDestroyPipeline(device.device, pipeline, nullptr);
      if (pipeline_layout) vk.vkDestroyPipelineLayout(device.device, pipeline_layout, nullptr);
      if (descriptor_pool) vk.vkDestroyDescriptorPool(device.device, descriptor_pool, nullptr);
      if (set_layout) vk.vkDestroyDescriptorSetLayout(device.device, set_layout, nullptr);
      if (sampler) vk.vkDestroySampler(device.device, sampler, nullptr);
      if (staging) vk.vkDestroyBuffer(device.device, staging, nullptr);
      if (staging_memory) vk.vkFreeMemory(device.device, staging_memory, nullptr);
      for (auto &plane : planes) {
        if (plane.view) vk.vkDestroyImageView(device.device, plane.view, nullptr);
        if (plane.image) vk.vkDestroyImage(device.device, plane.image, nullptr);
        if (plane.memory) vk.vkFreeMemory(device.device, plane.memory, nullptr);
        plane = {};
      }
      destroy_swapchain();
      if (render_pass) vk.vkDestroyRenderPass(device.device, render_pass, nullptr);
    }

    if (surface && device.instance && vk.vkDestroySurfaceKHR) {
      vk.vkDestroySurfaceKHR(device.instance, surface, nullptr);
    }

    in_flight = VK_NULL_HANDLE;
    timing_queries = VK_NULL_HANDLE;
    timestamp_bits = 0;
    timestamp_period_ns = 0;
    timing_window_ns = 0;
    rendered = VK_NULL_HANDLE;
    acquired = VK_NULL_HANDLE;
    command_pool = VK_NULL_HANDLE;
    command_buffer = VK_NULL_HANDLE;
    pipeline = VK_NULL_HANDLE;
    pipeline_layout = VK_NULL_HANDLE;
    descriptor_pool = VK_NULL_HANDLE;
    descriptor_set = VK_NULL_HANDLE;
    set_layout = VK_NULL_HANDLE;
    sampler = VK_NULL_HANDLE;
    staging = VK_NULL_HANDLE;
    staging_memory = VK_NULL_HANDLE;
    staging_mapped = nullptr;
    staging_size = 0;
    planes_initialised = false;
    render_pass = VK_NULL_HANDLE;
    surface = VK_NULL_HANDLE;
    window = nullptr;

    device.destroy();
  }

}  // namespace nova_vk

extern "C" void *pyrowave_renderer_create(ANativeWindow *window, uint32_t width, uint32_t height,
                                          bool chroma_444, bool hdr) {
  auto *renderer = new nova_vk::renderer_t();
  if (!renderer->create(window, width, height, chroma_444, hdr)) {
    delete renderer;
    return nullptr;
  }
  return renderer;
}

extern "C" bool pyrowave_renderer_present(void *handle, const uint8_t *luma, const uint8_t *cb, const uint8_t *cr) {
  return handle && static_cast<nova_vk::renderer_t *>(handle)->present(luma, cb, cr);
}

extern "C" bool pyrowave_renderer_decode_and_present(void *handle, const uint8_t *bitstream, size_t size) {
  return handle && static_cast<nova_vk::renderer_t *>(handle)->decode_and_present(bitstream, size);
}

extern "C" uint64_t pyrowave_renderer_partial_frames(void *handle) {
  return handle ? static_cast<nova_vk::renderer_t *>(handle)->frames_decoded_partially() : 0;
}

extern "C" void pyrowave_renderer_destroy(void *handle) {
  delete static_cast<nova_vk::renderer_t *>(handle);
}
