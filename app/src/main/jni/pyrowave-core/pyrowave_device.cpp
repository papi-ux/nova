// A Vulkan device Nova owns, that PyroWave can use and that can also present.
//
// PyroWave will happily make its own device, and that device can never draw: it is created with no
// instance extensions, so it has no VK_KHR_surface and its device has no VK_KHR_swapchain. A client
// has to put the picture on screen, so the ownership goes the other way round. The codec's C API is
// built for exactly this, taking an instance, a physical device and a device somebody else made.
//
// Vulkan is loaded by hand rather than linked. minSdk here is 21 and libvulkan.so first appears on
// 24, so linking it would stop the APK loading on devices the manifest still promises.

#include "pyrowave_device.h"

#include "pyrowave_device_c.h"

#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <vector>

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace nova_vk {

  namespace {

    /** The handful of entry points needed to stand a device up. Resolved, never linked. */
    struct loader_t {
      void *library = nullptr;
      PFN_vkGetInstanceProcAddr get_instance_proc_addr = nullptr;

      PFN_vkCreateInstance create_instance = nullptr;
      PFN_vkDestroyInstance destroy_instance = nullptr;
      PFN_vkEnumeratePhysicalDevices enumerate_physical_devices = nullptr;
      PFN_vkGetPhysicalDeviceProperties2 get_physical_device_properties2 = nullptr;
      PFN_vkGetPhysicalDeviceFeatures2 get_physical_device_features2 = nullptr;
      PFN_vkGetPhysicalDeviceQueueFamilyProperties get_queue_family_properties = nullptr;
      PFN_vkCreateDevice create_device = nullptr;
      PFN_vkDestroyDevice destroy_device = nullptr;
      PFN_vkGetDeviceQueue get_device_queue = nullptr;

      bool open() {
        if (get_instance_proc_addr) {
          return true;
        }
        library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
          LOGW("no libvulkan.so on this device");
          return false;
        }
        get_instance_proc_addr =
          reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
        if (!get_instance_proc_addr) {
          LOGW("libvulkan.so has no vkGetInstanceProcAddr");
          return false;
        }
        create_instance = reinterpret_cast<PFN_vkCreateInstance>(
          get_instance_proc_addr(VK_NULL_HANDLE, "vkCreateInstance"));
        return create_instance != nullptr;
      }

      void resolve_instance(VkInstance instance) {
        const auto get = [&](const char *name) { return get_instance_proc_addr(instance, name); };
        destroy_instance = reinterpret_cast<PFN_vkDestroyInstance>(get("vkDestroyInstance"));
        enumerate_physical_devices =
          reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(get("vkEnumeratePhysicalDevices"));
        get_physical_device_properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
          get("vkGetPhysicalDeviceProperties2"));
        get_physical_device_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
          get("vkGetPhysicalDeviceFeatures2"));
        get_queue_family_properties = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
          get("vkGetPhysicalDeviceQueueFamilyProperties"));
        create_device = reinterpret_cast<PFN_vkCreateDevice>(get("vkCreateDevice"));
        destroy_device = reinterpret_cast<PFN_vkDestroyDevice>(get("vkDestroyDevice"));
        get_device_queue = reinterpret_cast<PFN_vkGetDeviceQueue>(get("vkGetDeviceQueue"));
      }
    };

    loader_t loader;

  }  // namespace

  PFN_vkGetInstanceProcAddr device_t::get_instance_proc_addr() const {
    return loader.get_instance_proc_addr;
  }

  void device_t::destroy() {
    if (codec) {
      pyrowave_device_destroy(codec);
      codec = nullptr;
    }
    if (device && loader.destroy_device) {
      loader.destroy_device(device, nullptr);
      device = VK_NULL_HANDLE;
    }
    if (instance && loader.destroy_instance) {
      loader.destroy_instance(instance, nullptr);
      instance = VK_NULL_HANDLE;
    }
    physical_device = VK_NULL_HANDLE;
    graphics_queue = VK_NULL_HANDLE;
    graphics_family = VK_QUEUE_FAMILY_IGNORED;
  }

  bool device_t::create(bool want_presentation) {
    destroy();

    if (!loader.open()) {
      return false;
    }

    // A member rather than a local. The codec's C API says the create infos and everything inside
    // them have to outlive the device it makes from them, and instance_info points at this one.
    application_info = {};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "nova";
    application_info.pEngineName = "nova";
    // The codec asks for 1.3: it needs subgroup size control, which is core there.
    application_info.apiVersion = VK_API_VERSION_1_3;

    std::vector<const char *> instance_extensions;
    if (want_presentation) {
      instance_extensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
      instance_extensions.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    }

    instance_info = {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &application_info;
    instance_info.enabledExtensionCount = static_cast<uint32_t>(instance_extensions.size());
    instance_info.ppEnabledExtensionNames = instance_extensions.empty() ? nullptr : instance_extensions.data();

    if (loader.create_instance(&instance_info, nullptr, &instance) != VK_SUCCESS) {
      LOGW("could not create a Vulkan instance");
      return false;
    }
    loader.resolve_instance(instance);

    uint32_t count = 0;
    loader.enumerate_physical_devices(instance, &count, nullptr);
    if (count == 0) {
      LOGW("no Vulkan physical devices");
      destroy();
      return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    loader.enumerate_physical_devices(instance, &count, devices.data());
    physical_device = devices.front();

    loader.get_queue_family_properties(physical_device, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    loader.get_queue_family_properties(physical_device, &count, families.data());
    for (uint32_t i = 0; i < count; i++) {
      // One graphics capable queue is what the codec asks for, and what presentation needs.
      // Compute as well as graphics, because the decoder records compute work into this family's
      // command buffers and presenting needs the graphics half. Vulkan guarantees at least one family
      // with both, so requiring it costs nothing and asserting it here beats a decoder that records
      // into a command buffer its queue cannot execute.
      constexpr VkQueueFlags wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
      if ((families[i].queueFlags & wanted) == wanted) {
        graphics_family = i;
        break;
      }
    }
    if (graphics_family == VK_QUEUE_FAMILY_IGNORED) {
      LOGW("no graphics capable queue family");
      destroy();
      return false;
    }

    static const float priority = 1.0f;
    queue_info = {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = graphics_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;

    // Everything this GPU reports, handed back as what to enable, chained through a features2
    // struct because the C API says the device create info's pNext must carry one. The same structs
    // serve both ways round in Vulkan, and asking is better than listing: a device built from a hand
    // written minimum is a shape nobody upstream runs, and the codec's Granite core falls back when a
    // feature is missing. One of those fallbacks deadlocks. A device with no timeline semaphore sends
    // Granite to ask for a legacy one while it already holds the device lock, and it hangs on itself,
    // which is why that one is required below rather than merely enabled.
    vulkan13 = {};
    vulkan13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

    vulkan12 = {};
    vulkan12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    vulkan12.pNext = &vulkan13;

    vulkan11 = {};
    vulkan11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    vulkan11.pNext = &vulkan12;

    features = {};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &vulkan11;

    if (!loader.get_physical_device_features2) {
      LOGW("this Vulkan loader cannot report device features");
      destroy();
      return false;
    }
    loader.get_physical_device_features2(physical_device, &features);

    if (!features.features.shaderInt16 || !vulkan11.storageBuffer16BitAccess ||
        !vulkan12.storageBuffer8BitAccess || !vulkan12.timelineSemaphore ||
        !vulkan13.subgroupSizeControl || !vulkan13.computeFullSubgroups) {
      LOGW("this GPU lacks a feature the codec's shaders need");
      destroy();
      return false;
    }

    // Bounds checking every buffer access costs throughput and buys a decoder nothing; the rest is
    // for capture and replay tooling, or is something Granite itself switches off.
    features.features.robustBufferAccess = VK_FALSE;
    vulkan11.protectedMemory = VK_FALSE;
    vulkan11.multiviewGeometryShader = VK_FALSE;
    vulkan11.multiviewTessellationShader = VK_FALSE;
    vulkan12.bufferDeviceAddressCaptureReplay = VK_FALSE;
    vulkan12.bufferDeviceAddressMultiDevice = VK_FALSE;
    vulkan13.privateData = VK_FALSE;

    device_extensions.clear();
    if (want_presentation) {
      device_extensions.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    }

    device_info = {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.pNext = &features;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = static_cast<uint32_t>(device_extensions.size());
    device_info.ppEnabledExtensionNames = device_extensions.empty() ? nullptr : device_extensions.data();

    if (loader.create_device(physical_device, &device_info, nullptr, &device) != VK_SUCCESS) {
      LOGW("could not create a Vulkan device with the features this codec needs");
      destroy();
      return false;
    }
    loader.get_device_queue(device, graphics_family, 0, &graphics_queue);

    pyrowave_device_create_queue_info queue = {};
    queue.queue = graphics_queue;
    queue.familyIndex = graphics_family;
    queue.index = 0;

    pyrowave_device_create_info info = {};
    info.GetInstanceProcAddr = loader.get_instance_proc_addr;
    info.instance = instance;
    info.physical_device = physical_device;
    info.device = device;
    info.instance_create_info = &instance_info;
    info.device_create_info = &device_info;
    info.queue_info = &queue;
    info.queue_info_count = 1;

    const auto result = pyrowave_create_device(&info, &codec);
    if (result != PYROWAVE_SUCCESS || !codec) {
      LOGW("the codec refused a borrowed device (result %d)", static_cast<int>(result));
      destroy();
      return false;
    }

    LOGI("borrowed device ready%s", want_presentation ? " with presentation" : "");
    return true;
  }

}  // namespace nova_vk

extern "C" void *pyrowave_device_acquire(bool want_presentation, pyrowave_device *out_codec_device) {
  auto *owned = new nova_vk::device_t();
  if (!owned->create(want_presentation)) {
    delete owned;
    return nullptr;
  }
  if (out_codec_device) {
    *out_codec_device = owned->codec;
  }
  return owned;
}

extern "C" void pyrowave_device_release(void *handle) {
  delete static_cast<nova_vk::device_t *>(handle);
}
