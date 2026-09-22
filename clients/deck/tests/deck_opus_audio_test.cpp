#include "stream/deck_stream_media_adapters.h"
#include "deck_audio_test_support.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <fcntl.h>
#include <unistd.h>

using namespace nova::deck::stream;
using namespace nova::deck::test;

#define CHECK(...) do { if (!(__VA_ARGS__)) { \
    std::cerr << "line " << __LINE__ << ": " #__VA_ARGS__ "\n"; return 1; \
} } while (false)

int main(int argc, char** argv) {
    const bool disconnect = argc == 2 && std::string_view(argv[1]) == "--private-server-disconnect";
    const bool controlled = argc == 2 && std::string_view(argv[1]) == "--private-recovery";
    if (argc == 2 && (std::string_view(argv[1]) == "--private-server" || disconnect || controlled)) {
        // The Python harness supplies its own daemon and links only a null sink.
        CHECK(std::getenv("NOVA_AUDIO_PRIVATE_TEST") != nullptr);
        DeckPipeWireAudio audio;
        auto config = stereoConfig();
        const int channels = qEnvironmentVariableIntValue("NOVA_AUDIO_TEST_CHANNELS");
        CHECK(channels == 2 || channels == 6 || channels == 8);
        if (channels != 2) {
            config.channelCount = config.streams = channels;
            config.coupledStreams = 0;
            for (int i = 0; i < channels; ++i) config.mapping[i] = i;
        }
        const int audioConfiguration = channels == 8 ? AUDIO_CONFIGURATION_71_SURROUND
            : channels == 6 ? AUDIO_CONFIGURATION_51_SURROUND : AUDIO_CONFIGURATION_STEREO;
        auto packet = encodePacket(config, 240);
        CHECK(audio.init(audioConfiguration, &config, nullptr, 0) == 0);
        audio.start();
        CHECK(audio.lifecycle().active);
        std::cout << "ready" << std::endl;
        if (controlled) CHECK(fcntl(STDIN_FILENO, F_SETFL, O_NONBLOCK) == 0);
        bool quit = false;
        for (int i = 0; i < (controlled ? 3600 : 400); ++i) {
            char command = 0;
            if (controlled && read(STDIN_FILENO, &command, 1) == 1 && command == 'q') { quit = true; break; }
            const auto began = std::chrono::steady_clock::now();
            audio.decodeAndPlaySample(packet.data(), packet.size());
            if (controlled) {
                CHECK(std::chrono::steady_clock::now() - began < std::chrono::milliseconds(250));
                const auto s = audio.lifecycle();
                CHECK(s.active && !s.decodeErrors && s.submittedFrames <= s.queuedFrames);
                if (i % 10 == 0) std::cout << "status " << s.decodedFrames << ' ' << s.submittedFrames << ' '
                    << s.droppedFrames << ' ' << s.audioRecoveries << ' ' << s.audioRecoveryAttempts << ' '
                    << s.outputReady << ' ' << s.outputStreaming << ' ' << s.outputRecovering << ' '
                    << s.active << ' ' << s.discardedFrames << std::endl;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        const auto beforeStop = audio.lifecycle();
        audio.stop();
        audio.cleanup();
        const auto stats = audio.lifecycle();
        if (controlled) {
            CHECK(quit && !stats.active && !stats.outputReady && !stats.outputRecovering);
            const auto attempts = stats.audioRecoveryAttempts;
            std::this_thread::sleep_for(std::chrono::milliseconds(350));
            CHECK(audio.lifecycle().audioRecoveryAttempts == attempts);
            return 0;
        } else if (disconnect) {
            CHECK(stats.decodedFrames == 96000);
            CHECK(stats.submittedFrames > 240);
            CHECK(beforeStop.active && beforeStop.outputRecovering && !beforeStop.outputReady);
            CHECK(beforeStop.audioRecoveryAttempts > 0);
            CHECK(stats.droppedFrames > 0 && !stats.outputRecovering);
        } else {
            CHECK(stats.decodedFrames == 96000);
            CHECK(stats.submittedFrames > 48000);
            CHECK(stats.queuedFrames + stats.droppedFrames == stats.decodedFrames);
        }
        CHECK(stats.submittedFrames <= stats.queuedFrames);
        CHECK(stats.decodeErrors == 0);
        CHECK(!stats.active && !stats.outputReady);
        std::cout << "submittedFrames=" << stats.submittedFrames << '\n';
        // A failed next run cannot inherit the previous run's media counters.
        CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, nullptr, nullptr, 0) < 0);
        CHECK(audio.lifecycle().submittedFrames == 0);
        return 0;
    }
    if (argc == 2 && std::string_view(argv[1]) == "--unavailable") {
        // Never connect this negative test to the user's audio server.
        setenv("PIPEWIRE_REMOTE", "nova-test-server-that-does-not-exist", 1);
        DeckPipeWireAudio audio;
        auto config = stereoConfig();
        const auto before = std::chrono::steady_clock::now();
        CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &config, nullptr, 0) < 0);
        CHECK(std::chrono::steady_clock::now() - before < std::chrono::seconds(5));
        CHECK(!audio.lifecycle().outputReady);
        CHECK(audio.lifecycle().lastError == "Audio output is unavailable");
        audio.stop();
        audio.cleanup();
        return 0;
    }

    // Complete writes are rejected on overflow; consumed data wraps without loss.
    DeckPcmRingBuffer ring;
    std::array<float, 4> output{};
    CHECK(ring.pop(output) == 0);
    CHECK(!ring.push(std::array{1.0f}));
    ring.configure(4);
    CHECK(ring.capacity() == 4);
    CHECK(ring.push(std::array{1.0f, 2.0f, 3.0f}));
    CHECK(!ring.push(std::array{8.0f, 9.0f}));
    CHECK(ring.pop(std::span(output).first(2)) == 2);
    CHECK(output[0] == 1.0f && output[1] == 2.0f);
    CHECK(ring.push(std::array{4.0f, 5.0f, 6.0f}));
    CHECK(ring.pop(output) == 4);
    CHECK((output == std::array{3.0f, 4.0f, 5.0f, 6.0f}));
    CHECK(ring.pop(output) == 0);
    ring.configure(257);
    std::atomic<bool> orderCorrect{true};
    std::thread producer([&] {
        for (int i = 0; i < 100000; ++i) {
            const std::array value{static_cast<float>(i)};
            while (!ring.push(value)) std::this_thread::yield();
        }
    });
    std::thread consumer([&] {
        std::array<float, 19> values{};
        int expected = 0;
        while (expected < 100000) {
            const auto count = ring.pop(values);
            for (std::size_t i = 0; i < count; ++i) {
                if (values[i] != static_cast<float>(expected++)) orderCorrect = false;
            }
            if (!count) std::this_thread::yield();
        }
    });
    producer.join();
    consumer.join();
    CHECK(orderCorrect.load());

    // Exercise real packets at every ordinary Opus duration, stereo and surround.
    for (const int channels : {2, 6, 8}) {
        auto config = stereoConfig();
        const int audioConfiguration = channels == 2 ? AUDIO_CONFIGURATION_STEREO
            : channels == 6 ? AUDIO_CONFIGURATION_51_SURROUND : AUDIO_CONFIGURATION_71_SURROUND;
        if (channels != 2) {
            config.channelCount = channels;
            config.streams = channels;
            config.coupledStreams = 0;
            for (int i = 0; i < channels; ++i) config.mapping[i] = channels - i - 1;
        }
        for (const int frames : {120, 240, 480, 960, 1920, 2880}) {
            config.samplesPerFrame = frames;
            RecordingPcmOutput sink;
            DeckPipeWireAudio audio(sink);
            auto packet = encodePacket(config, frames);
            CHECK(audio.init(audioConfiguration, &config, nullptr, 0) == 0);
            CHECK(sink.format.channels == channels);
            CHECK(sink.format.channelMask == CHANNEL_MASK_FROM_AUDIO_CONFIGURATION(audioConfiguration));
            audio.decodeAndPlaySample(packet.data(), packet.size());
            CHECK(sink.pcm.empty());
            audio.start();
            audio.decodeAndPlaySample(packet.data(), packet.size());
            CHECK(sink.pcm.size() == static_cast<std::size_t>(frames * channels));
            // Short first packets can contain only encoder lookahead silence.
            if (frames >= 480) {
                CHECK(std::any_of(sink.pcm.begin(), sink.pcm.end(), [](float value) { return std::abs(value) > 0.001f; }));
            }

            // A fresh reference decoder must produce exactly the PCM sent to the sink,
            // including multistream mapping and every channel, with no format coercion.
            int error = OPUS_OK;
            auto* reference = opus_multistream_decoder_create(48000, channels,
                config.streams, config.coupledStreams, config.mapping, &error);
            CHECK(reference && error == OPUS_OK);
            std::vector<float> expected(frames * channels);
            CHECK(opus_multistream_decode_float(reference,
                reinterpret_cast<unsigned char*>(packet.data()), packet.size(), expected.data(), frames, 0) == frames);
            CHECK(sink.pcm == expected);
            CHECK(audio.lifecycle().decodedFrames == frames);
            CHECK(audio.lifecycle().queuedFrames == frames);
            CHECK(audio.lifecycle().submittedFrames == 0); // The recorder has no playback hardware.
            // The real transport requests PLC with (nullptr, 0) on packet loss.
            CHECK(opus_multistream_decode_float(reference, nullptr, 0, expected.data(), frames, 0) == frames);
            opus_multistream_decoder_destroy(reference);
            audio.decodeAndPlaySample(nullptr, 0);
            CHECK(sink.pcm.size() == static_cast<std::size_t>(frames * channels * 2));
            CHECK(std::equal(expected.begin(), expected.end(), sink.pcm.begin() + frames * channels));
            CHECK(audio.lifecycle().decodedFrames == frames * 2);
            CHECK(audio.lifecycle().concealedFrames == frames);
            CHECK(audio.lifecycle().queuedFrames == frames * 2);
            audio.stop();
            audio.decodeAndPlaySample(packet.data(), packet.size());
            audio.decodeAndPlaySample(nullptr, 0);
            CHECK(audio.lifecycle().sampleCalls == 2);
            audio.cleanup();
            audio.cleanup();
            CHECK(!audio.lifecycle().active && !audio.lifecycle().outputReady);
            CHECK(audio.init(audioConfiguration, &config, nullptr, 0) == 0);
            CHECK(audio.lifecycle().decodedFrames == 0 && audio.lifecycle().queuedFrames == 0);
            CHECK(sink.pcm.empty());
        }
    }

    RecordingPcmOutput sink;
    DeckPipeWireAudio audio(sink);
    auto config = stereoConfig();
    CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, nullptr, nullptr, 0) < 0);
    for (int bad = 0; bad < 11; ++bad) {
        auto invalid = config;
        switch (bad) {
        case 0: invalid.sampleRate = 44100; break;
        case 1: invalid.channelCount = 9; break;
        case 2: invalid.channelCount = 0; break;
        case 3: invalid.streams = 0; break;
        case 4: invalid.coupledStreams = 2; break;
        case 5: invalid.samplesPerFrame = 0; break;
        case 6: invalid.samplesPerFrame = 5761; break;
        case 7: invalid.mapping[0] = 4; break;
        case 8: invalid.streams = 3; break;
        case 9: invalid.coupledStreams = -1; break;
        case 10: invalid.samplesPerFrame = 121; break;
        }
        CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &invalid, nullptr, 0) < 0);
        CHECK(!audio.lifecycle().outputReady);
    }
    CHECK(audio.init(MAKE_AUDIO_CONFIGURATION(2, 0x1001), &config, nullptr, 0) < 0);
    CHECK(sink.openCalls == 0);
    sink.allowOpen = false;
    CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &config, nullptr, 0) < 0);
    CHECK(audio.lifecycle().lastError == "Audio output is unavailable");
    sink.allowOpen = true;
    sink.allowStart = false;
    CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &config, nullptr, 0) == 0);
    audio.start();
    CHECK(!audio.lifecycle().active && !audio.lifecycle().outputReady);
    CHECK(audio.lifecycle().lastError == "Audio output could not start");
    sink.allowStart = true;
    CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &config, nullptr, 0) == 0);
    audio.start();
    char invalidPacket = 3; // Code 3 requires a frame-count byte, absent here.
    audio.decodeAndPlaySample(&invalidPacket, 1);
    audio.decodeAndPlaySample(nullptr, 12);
    audio.decodeAndPlaySample(&invalidPacket, -1);
    CHECK(audio.lifecycle().decodeErrors == 1);
    CHECK(audio.lifecycle().decodedFrames == 0 && sink.pcm.empty());
    auto packet = encodePacket(config, 240);
    sink.rejectWrites = true;
    audio.decodeAndPlaySample(packet.data(), packet.size());
    CHECK(audio.lifecycle().decodedFrames == 240 && audio.lifecycle().queuedFrames == 0);
    CHECK(audio.lifecycle().droppedFrames == 240 && audio.lifecycle().active);
    sink.outputStats.available = false;
    CHECK(!audio.lifecycle().active && !audio.lifecycle().outputReady);
    CHECK(audio.lifecycle().lastError == "Audio output disconnected");
    audio.decodeAndPlaySample(packet.data(), packet.size());
    CHECK(!audio.lifecycle().active);
    audio.stop();
    audio.cleanup();
    // A recoverable output outage keeps decoding so the Opus state remains
    // current. Fresh PCM resumes when the output worker makes a route usable.
    CHECK(audio.init(AUDIO_CONFIGURATION_STEREO, &config, nullptr, 0) == 0);
    audio.start();
    sink.outputStats.available = false;
    sink.outputStats.recovering = true;
    sink.rejectWrites = true;
    audio.decodeAndPlaySample(packet.data(), packet.size());
    CHECK(audio.lifecycle().active && audio.lifecycle().outputRecovering && audio.lifecycle().decodedFrames == 240);
    sink.outputStats.available = sink.outputStats.flowing = true;
    sink.outputStats.recovering = sink.rejectWrites = false;
    audio.decodeAndPlaySample(packet.data(), packet.size());
    CHECK(audio.lifecycle().decodedFrames == 480 && audio.lifecycle().queuedFrames == 240 &&
        audio.lifecycle().outputStreaming && !audio.lifecycle().outputRecovering && audio.lifecycle().lastError.empty());
    audio.stop();
    audio.cleanup();
    return 0;
}
