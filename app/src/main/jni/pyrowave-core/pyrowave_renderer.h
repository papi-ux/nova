#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include "pyrowave_device.h"

#include <android/native_window.h>

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace nova_vk {

  /**
   * Turns an encoded frame into pixels on a Surface.
   *
   * Decode and present live in one class because they share everything that matters: the device, the
   * three single channel images the decoder writes and the shader samples, and one command buffer
   * holding the compute work and the draw so that a single submit and a single fence cover both.
   * Splitting them would mean handing those three things across a seam for no gain.
   *
   * Everything here is ordinary Vulkan. The only thing worth knowing is that the device belongs to
   * Nova and the codec borrows it, because the codec's own device is made without instance
   * extensions, so it has no swapchain and can never present.
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
     * Decode one complete frame's bitstream on the GPU and show it.
     *
     * What a stream uses. The bitstream is what Polaris sends as one frame: a sequence header then
     * coded blocks, each carrying its own length, so it goes to the decoder in one push and the
     * decoder walks it. Nothing touches host memory between the network and the screen.
     *
     * @param bitstream One frame, whole. A partial frame is a dropped frame, not a partial picture.
     * @param size Its length in bytes.
     */
    bool decode_and_present(const uint8_t *bitstream, std::size_t size);

    /**
     * Upload three planes and show them.
     *
     * The bring-up path, kept because it is the one that can be fed a known picture and checked
     * pixel by pixel. A stream has no use for it: it copies, and the data is already on the GPU.
     *
     * Planes are tightly packed: luma is width by height, each chroma plane half of each.
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
    bool create_decoder();
    void destroy_swapchain();
    bool upload(const uint8_t *luma, const uint8_t *cb, const uint8_t *cr);
    uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags want) const;

    /**
     * Acquire, record, draw, submit, present.
     *
     * Everything both paths share. `fill_planes` records whatever puts this frame into the plane
     * images, a buffer copy or a decode, and leaves them readable by a fragment shader.
     */
    bool present_recorded(const std::function<bool(VkCommandBuffer)> &fill_planes);

    /** Record the decode of the pushed frame into the plane images. */
    bool record_decode(VkCommandBuffer cmd);

    /** Wait for the last submitted frame, if there was one. */
    void await_last_frame();

    /** The barriers around whatever writes the planes, both ways, in their resting layout. */
    void barrier_planes(VkCommandBuffer cmd, VkPipelineStageFlags from, VkAccessFlags from_access,
                        VkPipelineStageFlags to, VkAccessFlags to_access);

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

    pyrowave_decoder decoder = nullptr;
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

    /**
     * Whether in_flight has work behind it.
     *
     * A fence is reset the moment before a submit and never before, because every path between the
     * two can refuse the frame: a decoder that will not take it, a frame that is not whole, a
     * swapchain image that cannot be acquired, a submit that fails. Resetting early left the fence
     * unsignalled with nothing coming to signal it, and the next frame waited on it forever.
     */
    bool frame_submitted = false;

    /**
     * Whether a frame has ever come out of this decoder ready.
     *
     * Gates the one retry below. Once a frame has decoded, a later one that is not ready is a frame
     * that arrived wrong, and pushing it again would waste a parse on bytes that will not improve.
     */
    bool decoder_warmed = false;
  };

}  // namespace nova_vk
