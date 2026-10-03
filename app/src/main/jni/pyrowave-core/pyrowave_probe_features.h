#pragma once

// Kept in step with PyroWaveGpuFeatures.kt. A missing-feature result is
// -(PYROWAVE_PROBE_FEATURE_FAILURE_BASE + mask); -1 remains an unspecified failure.
enum pyrowave_missing_feature {
  PYROWAVE_MISSING_SHADER_INT16 = 1 << 0,
  PYROWAVE_MISSING_STORAGE_16BIT = 1 << 1,
  PYROWAVE_MISSING_STORAGE_8BIT = 1 << 2,
  PYROWAVE_MISSING_TIMELINE_SEMAPHORE = 1 << 3,
  PYROWAVE_MISSING_SUBGROUP_SIZE_CONTROL = 1 << 4,
  PYROWAVE_MISSING_COMPUTE_FULL_SUBGROUPS = 1 << 5,
};

#define PYROWAVE_PROBE_FEATURE_FAILURE_BASE 256
