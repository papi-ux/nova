#pragma once

// The Android surface extension lives behind this, and the header only declares it when asked.
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <pyrowave/pyrowave.h>

#include <vector>

namespace nova_vk {

  /**
   * A Vulkan device Nova owns and the codec borrows.
   *
   * The create infos are kept alive as members on purpose: the codec's C API says the pointers it is
   * handed, and everything inside them, must outlive the device it makes from them.
   */
  struct device_t {
    bool create(bool want_presentation);
    void destroy();

    ~device_t() {
      destroy();
    }

    /** The loader entry point this device was built with, for anything else that needs to resolve. */
    PFN_vkGetInstanceProcAddr get_instance_proc_addr() const;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphics_queue = VK_NULL_HANDLE;
    uint32_t graphics_family = VK_QUEUE_FAMILY_IGNORED;
    pyrowave_device codec = nullptr;

  private:
    VkApplicationInfo application_info = {};
    VkInstanceCreateInfo instance_info = {};
    VkDeviceCreateInfo device_info = {};
    VkDeviceQueueCreateInfo queue_info = {};
    VkPhysicalDeviceFeatures2 features = {};
    VkPhysicalDeviceVulkan11Features vulkan11 = {};
    VkPhysicalDeviceVulkan12Features vulkan12 = {};
    VkPhysicalDeviceVulkan13Features vulkan13 = {};
    std::vector<const char *> device_extensions;
  };

}  // namespace nova_vk
