#pragma once

#include <cmath>
#include <cstdint>

namespace nova_vk {

  // Matches the interleaved 64-bit value/availability layout of vkGetQueryPoolResults.
  struct timestamp_result_t {
    uint64_t ticks;
    uint64_t available;
  };

  struct gpu_intervals_t {
    double planes_ms;
    double draw_ms;
  };

  inline bool timestamp_intervals(const timestamp_result_t (&queries)[3], uint32_t valid_bits,
                                  double period_ns, gpu_intervals_t &out) {
    if (valid_bits == 0 || valid_bits > 64 || !std::isfinite(period_ns) || period_ns <= 0 ||
        !queries[0].available || !queries[1].available || !queries[2].available) {
      return false;
    }
    // Unsigned subtraction handles wrap, including 64-bit counters. The unused high bits must not
    // enter the delta. Like all timestamp intervals, this assumes less than one full counter wrap.
    const uint64_t mask = valid_bits == 64 ? UINT64_MAX : (uint64_t{1} << valid_bits) - 1;
    out.planes_ms = ((queries[1].ticks - queries[0].ticks) & mask) * period_ns / 1e6;
    out.draw_ms = ((queries[2].ticks - queries[1].ticks) & mask) * period_ns / 1e6;
    return std::isfinite(out.planes_ms) && std::isfinite(out.draw_ms);
  }

  struct timing_metric_t {
    uint64_t count = 0;
    double sum_ms = 0;
    double max_ms = 0;

    void add(double ms) {
      ++count;
      sum_ms += ms;
      if (ms > max_ms) max_ms = ms;
    }

    // An unavailable measurement is not a zero-duration operation.
    double mean() const { return count ? sum_ms / count : -1; }
    double maximum() const { return count ? max_ms : -1; }
  };

}  // namespace nova_vk
