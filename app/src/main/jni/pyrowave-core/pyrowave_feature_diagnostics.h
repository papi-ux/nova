#pragma once

#include "pyrowave_probe_features.h"
#include <vulkan/vulkan_core.h>
#include <cstring>
#include <vector>

namespace nova_vk {

// Diagnostic only: actual device creation and the decode self-test still decide eligibility.
// Query the individual feature structs on older drivers. Vulkan 1.1 drivers need not
// populate the aggregate Vulkan 1.2/1.3 structs, even for features offered as extensions.
inline uint32_t missing_required_features(
    VkPhysicalDevice physical_device,
    PFN_vkGetPhysicalDeviceProperties get_properties,
    PFN_vkEnumerateDeviceExtensionProperties enumerate_extensions,
    PFN_vkGetPhysicalDeviceFeatures2 get_features) {
  if (!get_properties || !enumerate_extensions || !get_features) {
    return 0; // Unknown is not evidence of a particular missing feature.
  }
  VkPhysicalDeviceProperties properties = {};
  get_properties(physical_device, &properties);
  uint32_t count = 0;
  if (enumerate_extensions(physical_device, nullptr, &count, nullptr) != VK_SUCCESS) {
    return 0;
  }
  std::vector<VkExtensionProperties> extensions(count);
  if (count && enumerate_extensions(physical_device, nullptr, &count, extensions.data()) != VK_SUCCESS) {
    return 0;
  }
  const auto supports = [&](uint32_t core_version, const char *extension_name) {
    if (properties.apiVersion >= core_version) {
      return true;
    }
    for (const auto &extension : extensions) {
      if (std::strcmp(extension.extensionName, extension_name) == 0) {
        return true;
      }
    }
    return false;
  };

  VkPhysicalDeviceFeatures2 features = {};
  features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
  VkPhysicalDevice16BitStorageFeatures storage16 = {};
  storage16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
  VkPhysicalDevice8BitStorageFeatures storage8 = {};
  storage8.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES;
  VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {};
  timeline.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
  VkPhysicalDeviceSubgroupSizeControlFeatures subgroup = {};
  subgroup.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES;
  if (supports(VK_API_VERSION_1_1, VK_KHR_16BIT_STORAGE_EXTENSION_NAME)) {
    storage16.pNext = features.pNext;
    features.pNext = &storage16;
  }
  if (supports(VK_API_VERSION_1_2, VK_KHR_8BIT_STORAGE_EXTENSION_NAME)) {
    storage8.pNext = features.pNext;
    features.pNext = &storage8;
  }
  if (supports(VK_API_VERSION_1_2, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
    timeline.pNext = features.pNext;
    features.pNext = &timeline;
  }
  if (supports(VK_API_VERSION_1_3, VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME)) {
    subgroup.pNext = features.pNext;
    features.pNext = &subgroup;
  }
  get_features(physical_device, &features);
  uint32_t missing = 0;
  if (!features.features.shaderInt16) missing |= PYROWAVE_MISSING_SHADER_INT16;
  if (!storage16.storageBuffer16BitAccess) missing |= PYROWAVE_MISSING_STORAGE_16BIT;
  if (!storage8.storageBuffer8BitAccess) missing |= PYROWAVE_MISSING_STORAGE_8BIT;
  if (!timeline.timelineSemaphore) missing |= PYROWAVE_MISSING_TIMELINE_SEMAPHORE;
  if (!subgroup.subgroupSizeControl) missing |= PYROWAVE_MISSING_SUBGROUP_SIZE_CONTROL;
  if (!subgroup.computeFullSubgroups) missing |= PYROWAVE_MISSING_COMPUTE_FULL_SUBGROUPS;
  return missing;
}

} // namespace nova_vk
