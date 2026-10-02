#include <atomic>
#include <cassert>
#include <iostream>

// Detect Release silently removing both a test check and setup inside assert.
// This counter check stays unconditional, so -DNDEBUG makes this test fail.
int main() {
    std::atomic<unsigned> setupCalls{0};
    assert(setupCalls.fetch_add(1, std::memory_order_relaxed) == 0);
    if (setupCalls.load(std::memory_order_relaxed) != 1) {
        std::cerr << "Release removed the test assertion and its setup call\n";
        return 1;
    }
    std::cout << "Test assertion and setup expression executed\n";
    return 0;
}
