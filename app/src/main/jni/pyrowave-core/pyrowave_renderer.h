#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include "pyrowave_device.h"

#include <android/native_window.h>

#include <cstdint>
#include <vector>

namespace nova_vk {

  /**
   * Puts a decoded frame on a Surface.
   *
   * The decoder writes Y, Cb and Cr as three single channel planes, and a screen wants RGB, so a
   * swapchain and a two triangle draw stand between them. Everything here is ordinary Vulkan; the
   * only thing worth knowing is that the device belongs to Nova and the codec borrows it, because
   * the codec's own device has no swapchain extension and can never present.
   */
  class renderer_t {
  public:
    ~renderer_t();

    /**
     * @param window The Surface to draw into, retained for the renderer's lifetime by the caller.
     * @param width Frame width in luma samples.
     * @param height Frame height in luma samples.
     */
    bool create(ANativeWindow *window, uint32_t width, uint32_t height);

    /**
     * Upload three planes and show them.
     *
     * Planes are tightly packed: luma is width by height, each chroma plane half of each. The copy
     * is the price of feeding this from the decoder's host memory path, and it is the next thing to
     * remove rather than a design.
     */
    bool present(const uint8_t *luma, const uint8_t *cb, const uint8_t *cr);

    void destroy();

  private:
    bool create_surface(ANativeWindow *window);
    bool create_swapchain();
    bool create_render_pass();
    bool create_planes();
    bool create_pipeline();
    bool create_descriptors();
    bool create_frame_resources();
    void destroy_swapchain();
    bool upload(const uint8_t *luma, const uint8_t *cb, const uint8_t *cr);
    uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags want) const;

    struct plane_t {
      VkImage image = VK_NULL_HANDLE;
      VkDeviceMemory memory = VK_NULL_HANDLE;
      VkImageView view = VK_NULL_HANDLE;
      uint32_t width = 0;
      uint32_t height = 0;
      VkDeviceSize offset = 0;
    };

    device_t device;
    ANativeWindow *window = nullptr;
    uint32_t frame_width = 0;
    uint32_t frame_height = 0;

    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchain_format = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchain_extent = {};
    std::vector<VkImage> swapchain_images;
    std::vector<VkImageView> swapchain_views;
    std::vector<VkFramebuffer> framebuffers;

    VkRenderPass render_pass = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
    VkDescriptorPool descriptor_pool = VK_NULL_HANDLE;
    VkDescriptorSet descriptor_set = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;

    plane_t planes[3];
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory staging_memory = VK_NULL_HANDLE;
    void *staging_mapped = nullptr;
    VkDeviceSize staging_size = 0;
    bool planes_initialised = false;

    VkCommandPool command_pool = VK_NULL_HANDLE;
    VkCommandBuffer command_buffer = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE;
    VkSemaphore rendered = VK_NULL_HANDLE;
    VkFence in_flight = VK_NULL_HANDLE;
  };

}  // namespace nova_vk
