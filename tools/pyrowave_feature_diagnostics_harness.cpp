#include "pyrowave_feature_diagnostics.h"
#include <cassert>
#include <cstdio>

static uint32_t api_version;
static uint32_t disabled;
static bool enumerate_fails;
static std::vector<const char *> extension_names;
static uint32_t queried;

static void VKAPI_CALL properties(VkPhysicalDevice, VkPhysicalDeviceProperties *out) {
  out->apiVersion = api_version;
}

static VkResult VKAPI_CALL extensions(VkPhysicalDevice, const char *, uint32_t *count,
                                      VkExtensionProperties *out) {
  if (enumerate_fails) return VK_ERROR_INITIALIZATION_FAILED;
  if (out) {
    assert(*count >= extension_names.size());
    for (size_t i = 0; i < extension_names.size(); i++) {
      std::strcpy(out[i].extensionName, extension_names[i]);
    }
  }
  *count = static_cast<uint32_t>(extension_names.size());
  return VK_SUCCESS;
}

static VkBool32 enabled(uint32_t bit) { return (disabled & bit) ? VK_FALSE : VK_TRUE; }

static void VKAPI_CALL features(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *out) {
  out->features.shaderInt16 = enabled(PYROWAVE_MISSING_SHADER_INT16);
  for (auto *node = static_cast<VkBaseOutStructure *>(out->pNext); node; node = node->pNext) {
    switch (node->sType) {
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES:
        reinterpret_cast<VkPhysicalDevice16BitStorageFeatures *>(node)->storageBuffer16BitAccess =
          enabled(PYROWAVE_MISSING_STORAGE_16BIT);
        queried |= PYROWAVE_MISSING_STORAGE_16BIT;
        break;
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES:
        reinterpret_cast<VkPhysicalDevice8BitStorageFeatures *>(node)->storageBuffer8BitAccess =
          enabled(PYROWAVE_MISSING_STORAGE_8BIT);
        queried |= PYROWAVE_MISSING_STORAGE_8BIT;
        break;
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
        reinterpret_cast<VkPhysicalDeviceTimelineSemaphoreFeatures *>(node)->timelineSemaphore =
          enabled(PYROWAVE_MISSING_TIMELINE_SEMAPHORE);
        queried |= PYROWAVE_MISSING_TIMELINE_SEMAPHORE;
        break;
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES: {
        auto *subgroup = reinterpret_cast<VkPhysicalDeviceSubgroupSizeControlFeatures *>(node);
        subgroup->subgroupSizeControl = enabled(PYROWAVE_MISSING_SUBGROUP_SIZE_CONTROL);
        subgroup->computeFullSubgroups = enabled(PYROWAVE_MISSING_COMPUTE_FULL_SUBGROUPS);
        queried |= PYROWAVE_MISSING_SUBGROUP_SIZE_CONTROL | PYROWAVE_MISSING_COMPUTE_FULL_SUBGROUPS;
        break;
      }
      default: assert(false && "unexpected feature query on an older driver");
    }
  }
}

static uint32_t diagnose() {
  queried = 0;
  return nova_vk::missing_required_features(VK_NULL_HANDLE, properties, extensions, features);
}

int main() {
  // Shield's 1.1 driver: 16-bit storage is core; subgroup size control is an extension.
  api_version = VK_MAKE_VERSION(1, 1, 178);
  extension_names = {VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME};
  assert(diagnose() == (PYROWAVE_MISSING_STORAGE_8BIT | PYROWAVE_MISSING_TIMELINE_SEMAPHORE));
  assert(!(queried & (PYROWAVE_MISSING_STORAGE_8BIT | PYROWAVE_MISSING_TIMELINE_SEMAPHORE)));
  assert(queried & PYROWAVE_MISSING_STORAGE_16BIT);

  // An older driver offering the promoted extensions must not be called feature-deficient.
  extension_names.push_back(VK_KHR_8BIT_STORAGE_EXTENSION_NAME);
  extension_names.push_back(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
  assert(diagnose() == 0);

  // A core 1.3 implementation needs no extension names for these features.
  api_version = VK_API_VERSION_1_3;
  extension_names.clear();
  assert(diagnose() == 0);
  for (uint32_t bit = 1; bit <= PYROWAVE_MISSING_COMPUTE_FULL_SUBGROUPS; bit <<= 1) {
    disabled = bit;
    assert(diagnose() == bit);
  }
  disabled = 63;
  assert(diagnose() == 63);
  assert(-(PYROWAVE_PROBE_FEATURE_FAILURE_BASE + 12) == -268);

  // Failure to inspect is unknown, not a claim that the hardware lacks all features.
  enumerate_fails = true;
  assert(diagnose() == 0);
  assert(nova_vk::missing_required_features(VK_NULL_HANDLE, nullptr, extensions, features) == 0);
  std::puts("all feature diagnostic cases passed");
}
