#include "pyrowave_timing.h"

#include <cassert>
#include <cstdio>
#include <limits>

using namespace nova_vk;

static void near(double actual, double expected) {
  assert(std::abs(actual - expected) < 1e-9);
}

int main() {
  gpu_intervals_t intervals = {};
  timestamp_result_t normal[3] = {{100, 1}, {250, 1}, {400, 1}};
  assert(timestamp_intervals(normal, 64, 1000, intervals));
  near(intervals.planes_ms, 0.15);
  near(intervals.draw_ms, 0.15);

  // RP6 and other mobile queues need not have full-width counters. Wrap can occur in either
  // interval, and unused high bits are not part of the timestamp.
  timestamp_result_t wrap36[3] = {{(uint64_t{1} << 36) - 10, 1}, {10, 1}, {35, 1}};
  assert(timestamp_intervals(wrap36, 36, 1000, intervals));
  near(intervals.planes_ms, 0.02);
  near(intervals.draw_ms, 0.025);
  wrap36[0].ticks |= uint64_t{3} << 62;
  wrap36[1].ticks |= uint64_t{1} << 60;
  assert(timestamp_intervals(wrap36, 36, 1000, intervals));
  near(intervals.planes_ms, 0.02);
  near(intervals.draw_ms, 0.025);

  timestamp_result_t wrap64[3] = {{UINT64_MAX - 15, 1}, {UINT64_MAX - 5, 1}, {10, 1}};
  assert(timestamp_intervals(wrap64, 64, 1000, intervals));
  near(intervals.planes_ms, 0.01);
  near(intervals.draw_ms, 0.016);

  for (int missing = 0; missing < 3; ++missing) {
    normal[missing].available = 0;
    assert(!timestamp_intervals(normal, 64, 1000, intervals));
    normal[missing].available = 1;
  }
  assert(!timestamp_intervals(normal, 0, 1000, intervals));
  assert(!timestamp_intervals(normal, 65, 1000, intervals));
  assert(!timestamp_intervals(normal, 64, 0, intervals));
  assert(!timestamp_intervals(normal, 64, -1, intervals));
  assert(!timestamp_intervals(normal, 64, std::numeric_limits<double>::infinity(), intervals));
  assert(!timestamp_intervals(normal, 64, std::numeric_limits<double>::quiet_NaN(), intervals));

  // Identical timestamps are a valid zero; missing observations remain distinguishable from that.
  timestamp_result_t zero[3] = {{10, 1}, {10, 1}, {10, 1}};
  assert(timestamp_intervals(zero, 64, 1000, intervals));
  timing_metric_t metric;
  assert(metric.mean() == -1 && metric.maximum() == -1 && metric.count == 0);
  metric.add(intervals.planes_ms);
  assert(metric.mean() == 0 && metric.maximum() == 0 && metric.count == 1);
  metric.add(2);
  assert(metric.mean() == 1 && metric.maximum() == 2 && metric.count == 2);
  metric = {};
  assert(metric.mean() == -1 && metric.maximum() == -1 && metric.count == 0);

  std::puts("all cases passed");
}
