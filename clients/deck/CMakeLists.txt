cmake_minimum_required(VERSION 3.24)

project(NovaDeck LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
pkg_check_modules(NOVA_DECK_FFMPEG_VAAPI REQUIRED IMPORTED_TARGET
    libavcodec
    libavutil
    libva
    libva-drm
    # deck_stream_media_adapters.cpp includes <libdrm/drm_fourcc.h> for the
    # DMA-BUF format codes. Fedora pulls those headers in transitively through
    # mesa, so the omission was invisible on the development host and only
    # appeared once the client was built on a distribution that does not.
    libdrm
    egl
    glesv2
)
pkg_check_modules(NOVA_DECK_LINUX_AUDIO REQUIRED IMPORTED_TARGET
    libpipewire-0.3
    libpulse
    opus
)
find_package(Qt6 REQUIRED COMPONENTS Quick Network DBus)
find_package(OpenSSL 1.1.1 REQUIRED COMPONENTS Crypto)

add_library(nova_deck_core STATIC
    src/deck_gamepad.cpp
    src/stream/deck_players.cpp
    src/stream/deck_controller_input.cpp
    src/stream/deck_desktop_input.cpp
    src/deck_layout.cpp
    src/polaris_game_fixture.cpp
    src/backend/deck_backend_interfaces.cpp
    src/stream/deck_stream_core.cpp
    src/stream/deck_gamestream_launch.cpp
    src/stream/deck_gamestream_library.cpp
    src/stream/deck_gamestream_session_builder.cpp
    src/stream/deck_stream_media_adapters.cpp
    src/stream/deck_video_color.cpp
    src/stream/deck_video_capabilities.cpp
    src/stream/deck_audio_output.cpp
    src/stream/deck_audio_buffer.cpp
    src/stream/deck_audio_recovery.cpp
    src/stream/deck_opus_audio.cpp
    src/stream/deck_moonlight_handoff_preflight.cpp
    src/identity/deck_moonlight_identity.cpp
    src/identity/deck_pairing_crypto.cpp
    src/identity/deck_native_identity.cpp
    src/identity/deck_pairing.cpp
    src/polaris/deck_polaris_client.cpp
    src/polaris/deck_spaces.cpp
    src/backend/deck_live_read_only_state.cpp
    src/runtime/deck_moonlight_launcher.cpp
    src/runtime/deck_steam_shortcuts.cpp
    src/runtime/deck_native_session.cpp
    src/runtime/deck_input_hub.cpp
    src/runtime/deck_desktop_input_bridge.cpp
    src/runtime/deck_rumble.cpp
    src/runtime/deck_rumble_linux.cpp
    src/runtime/deck_sleep_monitor.cpp
    src/runtime/deck_native_target.cpp
    src/polaris/deck_game_tools.cpp
    src/runtime/deck_game_shortcuts.cpp
    src/runtime/deck_game_tools.cpp
    src/runtime/deck_play_settings.cpp
    src/runtime/deck_display_capabilities.cpp
    src/runtime/deck_frame_delivery.cpp
    src/runtime/deck_host_power.cpp
    src/runtime/deck_host_settings.cpp
    src/polaris/deck_host_settings.cpp
    src/runtime/deck_hud_metrics.cpp
    src/runtime/deck_hud_host.cpp
    src/polaris/deck_host_telemetry.cpp
    src/polaris/deck_doctor.cpp
    src/polaris/deck_doctor_action.cpp
    src/runtime/deck_doctor_actions.cpp
    src/runtime/deck_doctor_receipts.cpp
    src/runtime/deck_support_report.cpp
    src/polaris/deck_session_events.cpp
    src/runtime/deck_library_controller.cpp
    src/runtime/deck_library_artwork.cpp
    src/runtime/deck_pairing_controller.cpp
)
set_target_properties(nova_deck_core PROPERTIES AUTOMOC ON)

target_compile_definitions(nova_deck_core PRIVATE NOVA_DECK_NATIVE_MEDIA=1)

set(NOVA_DECK_MOONLIGHT_COMMON_C_DIR
    ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/jni/moonlight-core/moonlight-common-c
    CACHE PATH
    "Path to the checked-out moonlight-common-c source tree used by the Deck stream skeleton"
)
if(EXISTS "${NOVA_DECK_MOONLIGHT_COMMON_C_DIR}/src/Limelight.h")
    # moonlight-common-c defaults to a shared library when BUILD_SHARED_LIBS is
    # unset; the Deck shell links it statically so an installed binary (the
    # Flatpak, a Deck copy) carries it instead of hunting for the .so. The
    # function scope keeps the override away from the caller's own setting.
    function(nova_deck_add_moonlight_common_c source_dir binary_dir)
        set(BUILD_SHARED_LIBS OFF)
        add_subdirectory("${source_dir}" "${binary_dir}")
    endfunction()
    nova_deck_add_moonlight_common_c("${NOVA_DECK_MOONLIGHT_COMMON_C_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/moonlight-common-c")
    target_link_libraries(nova_deck_core PUBLIC moonlight-common-c)
else()
    message(FATAL_ERROR "moonlight-common-c is required for the Deck stream skeleton; expected ${NOVA_DECK_MOONLIGHT_COMMON_C_DIR}/src/Limelight.h")
endif()

target_include_directories(nova_deck_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_link_libraries(nova_deck_core
    PUBLIC
        PkgConfig::NOVA_DECK_FFMPEG_VAAPI
        PkgConfig::NOVA_DECK_LINUX_AUDIO
        Qt6::Quick
        Qt6::Network
        Qt6::DBus
        OpenSSL::Crypto
)
include(GNUInstallDirs)
target_compile_definitions(nova_deck_core
    PUBLIC
        NOVA_DECK_SAMPLE_GAME_FIXTURE=\"${CMAKE_CURRENT_SOURCE_DIR}/fixtures/sample_polaris_game.json\"
        NOVA_DECK_SAMPLE_LIBRARY_FIXTURE=\"${CMAKE_CURRENT_SOURCE_DIR}/fixtures/sample_polaris_library.json\"
        NOVA_DECK_INSTALL_FIXTURE_DIR=\"${CMAKE_INSTALL_FULL_DATADIR}/nova-deck/fixtures\"
        # Recorded real-writer fixtures (a Moonlight-Qt pairing file, Polaris API bodies) for the tests.
        NOVA_DECK_FIXTURE_SOURCE_DIR=\"${CMAKE_CURRENT_SOURCE_DIR}/fixtures\"
)

include(CTest)
option(NOVA_DECK_BUILD_HDR_VALIDATION "Build local Main10 color and Vulkan window validation" OFF)
option(NOVA_DECK_BUILD_VULKAN_STREAM "Build the opt-in Vulkan native stream UI" OFF)
if(NOVA_DECK_BUILD_HDR_VALIDATION OR NOVA_DECK_BUILD_VULKAN_STREAM)
    pkg_check_modules(NOVA_DECK_PLACEBO REQUIRED IMPORTED_TARGET libplacebo>=7.349 vulkan libavformat)
    add_library(nova_deck_hdr_render STATIC
        src/stream/deck_placebo_color_renderer.cpp
        src/stream/deck_vulkan_video_window.cpp
        src/stream/deck_vulkan_quick_overlay.cpp
        src/runtime/deck_vulkan_session_view.cpp
        src/stream/deck_placebo_libav.c)
    set_target_properties(nova_deck_hdr_render PROPERTIES AUTOMOC ON)
    target_link_libraries(nova_deck_hdr_render PUBLIC nova_deck_core PkgConfig::NOVA_DECK_PLACEBO)
endif()
if(BUILD_TESTING)
    add_executable(nova_deck_players_test tests/deck_players_test.cpp src/stream/deck_players.cpp)
    target_include_directories(nova_deck_players_test PRIVATE src)
    add_test(NAME nova_deck_players_test COMMAND nova_deck_players_test)
    add_executable(nova_deck_video_color_test tests/deck_video_color_test.cpp)
    target_link_libraries(nova_deck_video_color_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_video_color_test COMMAND nova_deck_video_color_test)
    set_tests_properties(nova_deck_video_color_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen" TIMEOUT 20)
    add_executable(nova_deck_video_capabilities_test tests/deck_video_capabilities_test.cpp)
    target_link_libraries(nova_deck_video_capabilities_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_video_capabilities_test PRIVATE
        NOVA_DECK_HEVC_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/hevc-main-sdr.b64")
    add_test(NAME nova_deck_video_capabilities_test COMMAND nova_deck_video_capabilities_test)
    set_tests_properties(nova_deck_video_capabilities_test PROPERTIES TIMEOUT 20)
    if(NOVA_DECK_BUILD_HDR_VALIDATION)
        add_executable(nova_deck_video_scale_test tests/deck_video_scale_test.cpp)
        target_link_libraries(nova_deck_video_scale_test PRIVATE nova_deck_hdr_render)
        add_test(NAME nova_deck_video_scale_test COMMAND nova_deck_video_scale_test)
        set_tests_properties(nova_deck_video_scale_test PROPERTIES TIMEOUT 30)
        add_executable(nova_deck_hdr_color_test tests/deck_hdr_color_test.cpp)
        target_link_libraries(nova_deck_hdr_color_test PRIVATE nova_deck_hdr_render)
        add_test(NAME nova_deck_hdr_color_test COMMAND nova_deck_hdr_color_test)
        set_tests_properties(nova_deck_hdr_color_test PROPERTIES TIMEOUT 45)
        add_executable(nova_deck_drm_import_layout_test tests/deck_drm_import_layout_test.cpp)
        target_link_libraries(nova_deck_drm_import_layout_test PRIVATE nova_deck_hdr_render)
        add_test(NAME nova_deck_drm_import_layout_test COMMAND nova_deck_drm_import_layout_test)
        add_executable(nova_deck_vulkan_import_test tests/deck_vulkan_import_test.cpp)
        target_link_libraries(nova_deck_vulkan_import_test PRIVATE nova_deck_hdr_render)
        target_compile_definitions(nova_deck_vulkan_import_test PRIVATE
            NOVA_DECK_IMPORT_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
        add_test(NAME nova_deck_vulkan_import_reference_test COMMAND nova_deck_vulkan_import_test --reference)
        set_tests_properties(nova_deck_vulkan_import_reference_test PROPERTIES TIMEOUT 30)
        # Missing VAAPI is a skip, never hardware acceptance. Required hardware
        # runs must use --require-hardware, which exits nonzero on unavailable.
        add_test(NAME nova_deck_vulkan_import_hardware_test COMMAND nova_deck_vulkan_import_test)
        set_tests_properties(nova_deck_vulkan_import_hardware_test PROPERTIES TIMEOUT 45 SKIP_RETURN_CODE 77)

    endif()
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    if(NOVA_DECK_BUILD_HDR_VALIDATION)
        add_test(NAME nova_deck_vulkan_import_contract_test
            COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_vulkan_import_contract_test.py
                $<TARGET_FILE:nova_deck_vulkan_import_test>)
        set_tests_properties(nova_deck_vulkan_import_contract_test PROPERTIES TIMEOUT 30)
    endif()
    add_executable(nova_deck_spaces_test tests/deck_spaces_test.cpp)
    target_link_libraries(nova_deck_spaces_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_spaces_test PRIVATE
        NOVA_DECK_LAUNCHER_TARGET_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/launcher-targets.json")
    add_test(NAME nova_deck_spaces_test COMMAND nova_deck_spaces_test)
    set_tests_properties(nova_deck_spaces_test PROPERTIES TIMEOUT 15)
    add_executable(nova_deck_library_query_test tests/deck_library_query_test.cpp)
    target_link_libraries(nova_deck_library_query_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_library_query_test PRIVATE
        NOVA_DECK_LIBRARY_QUERY_SOURCE="${CMAKE_CURRENT_SOURCE_DIR}/qml/LibraryQuery.js")
    add_test(NAME nova_deck_library_query_test COMMAND nova_deck_library_query_test)
    add_executable(nova_deck_library_artwork_test tests/deck_library_artwork_test.cpp)
    target_link_libraries(nova_deck_library_artwork_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_library_artwork_test COMMAND nova_deck_library_artwork_test)
    add_executable(nova_deck_host_power_test tests/deck_host_power_test.cpp)
    add_executable(nova_deck_game_tools_test tests/deck_game_tools_test.cpp)
    target_link_libraries(nova_deck_game_tools_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_game_tools_test COMMAND nova_deck_game_tools_test)
    set_tests_properties(nova_deck_game_tools_test PROPERTIES TIMEOUT 30)
    add_executable(nova_deck_game_shortcuts_test tests/deck_game_shortcuts_test.cpp)
    target_link_libraries(nova_deck_game_shortcuts_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_game_shortcuts_test COMMAND nova_deck_game_shortcuts_test)
    add_executable(nova_deck_host_settings_test tests/deck_host_settings_test.cpp)
    target_link_libraries(nova_deck_host_settings_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_host_settings_test COMMAND nova_deck_host_settings_test)
    set_tests_properties(nova_deck_host_settings_test PROPERTIES TIMEOUT 45)
    add_executable(nova_deck_hud_host_test tests/deck_hud_host_test.cpp)
    add_executable(nova_deck_session_sync_test tests/deck_session_sync_test.cpp)
    target_link_libraries(nova_deck_session_sync_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_session_sync_test COMMAND nova_deck_session_sync_test)
    set_tests_properties(nova_deck_session_sync_test PROPERTIES TIMEOUT 20)
    add_executable(nova_deck_doctor_actions_test tests/deck_doctor_actions_test.cpp)
    add_executable(nova_deck_support_report_test tests/deck_support_report_test.cpp)
    target_link_libraries(nova_deck_support_report_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_support_report_test COMMAND nova_deck_support_report_test)
    add_executable(nova_deck_doctor_receipts_test tests/deck_doctor_receipts_test.cpp)
    target_link_libraries(nova_deck_doctor_receipts_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_doctor_receipts_test COMMAND nova_deck_doctor_receipts_test)
    set_tests_properties(nova_deck_doctor_receipts_test PROPERTIES TIMEOUT 15)
    target_link_libraries(nova_deck_doctor_actions_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_doctor_actions_test COMMAND nova_deck_doctor_actions_test)
    set_tests_properties(nova_deck_doctor_actions_test PROPERTIES TIMEOUT 15)
    target_link_libraries(nova_deck_hud_host_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_hud_host_test PRIVATE NOVA_DECK_TUNING_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/live-tuning-v1.json")
    target_compile_definitions(nova_deck_hud_host_test PRIVATE NOVA_DECK_DOCTOR_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/doctor-v2.json")
    add_test(NAME nova_deck_hud_host_test COMMAND nova_deck_hud_host_test)
    set_tests_properties(nova_deck_hud_host_test PROPERTIES TIMEOUT 15)
    add_executable(nova_deck_hud_metrics_test tests/deck_hud_metrics_test.cpp)
    target_link_libraries(nova_deck_hud_metrics_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_hud_metrics_test COMMAND nova_deck_hud_metrics_test)
    target_link_libraries(nova_deck_host_power_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_host_power_test COMMAND nova_deck_host_power_test)
    set_tests_properties(nova_deck_host_power_test PROPERTIES TIMEOUT 25)

    add_executable(nova_deck_library_controller_test tests/deck_library_controller_test.cpp)
    target_link_libraries(nova_deck_library_controller_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_library_controller_test COMMAND nova_deck_library_controller_test)
    set_tests_properties(nova_deck_library_controller_test PROPERTIES TIMEOUT 20)
    add_executable(nova_deck_gamestream_library_test tests/deck_gamestream_library_test.cpp)
    target_link_libraries(nova_deck_gamestream_library_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_gamestream_library_test COMMAND nova_deck_gamestream_library_test)
    add_executable(nova_deck_pairing_test tests/deck_pairing_test.cpp)
    target_link_libraries(nova_deck_pairing_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_pairing_test COMMAND nova_deck_pairing_test)
    set_tests_properties(nova_deck_pairing_test PROPERTIES TIMEOUT 30)
    add_executable(nova_deck_layout_test
        tests/deck_layout_test.cpp
    )
    target_link_libraries(nova_deck_layout_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_layout_test
        PRIVATE
            NOVA_DECK_MAIN_QML_SOURCE="${CMAKE_CURRENT_SOURCE_DIR}/qml/Main.qml"
    )
    add_test(NAME nova_deck_controller_library_smoke COMMAND nova_deck_layout_test)
    add_executable(nova_deck_gamepad_test tests/deck_gamepad_test.cpp)
    target_link_libraries(nova_deck_gamepad_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_gamepad_test COMMAND nova_deck_gamepad_test)
    add_executable(nova_deck_controller_input_test tests/deck_controller_input_test.cpp)
    target_link_libraries(nova_deck_controller_input_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_controller_input_test COMMAND nova_deck_controller_input_test)
    add_executable(nova_deck_native_session_test tests/deck_native_session_test.cpp)
    add_executable(nova_deck_desktop_input_test tests/deck_desktop_input_test.cpp)
    target_link_libraries(nova_deck_desktop_input_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_desktop_input_test COMMAND nova_deck_desktop_input_test)
    target_link_libraries(nova_deck_native_session_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_native_session_test COMMAND nova_deck_native_session_test)
    set_tests_properties(nova_deck_native_session_test PROPERTIES TIMEOUT 60)
    find_program(DBUS_RUN_SESSION dbus-run-session REQUIRED)
    add_executable(nova_deck_sleep_monitor_test tests/deck_sleep_monitor_test.cpp)
    target_link_libraries(nova_deck_sleep_monitor_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_sleep_monitor_test COMMAND ${DBUS_RUN_SESSION} --
        ${CMAKE_COMMAND} -E env NOVA_DECK_PRIVATE_SLEEP_BUS=1 $<TARGET_FILE:nova_deck_sleep_monitor_test>)
    set_tests_properties(nova_deck_sleep_monitor_test PROPERTIES TIMEOUT 20)
    foreach(name IN ITEMS display_capabilities frame_delivery)
        add_executable(nova_deck_${name}_test tests/deck_${name}_test.cpp)
        target_link_libraries(nova_deck_${name}_test PRIVATE nova_deck_core)
        add_test(NAME nova_deck_${name}_test COMMAND nova_deck_${name}_test)
        set_tests_properties(nova_deck_${name}_test PROPERTIES
            ENVIRONMENT "QT_QPA_PLATFORM=offscreen" TIMEOUT 20)
    endforeach()
    add_executable(nova_deck_play_settings_test tests/deck_play_settings_test.cpp)
    target_link_libraries(nova_deck_play_settings_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_play_settings_test COMMAND nova_deck_play_settings_test)

    add_executable(nova_deck_rumble_test tests/deck_rumble_test.cpp)
    target_link_libraries(nova_deck_rumble_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_rumble_test COMMAND nova_deck_rumble_test)
    set_tests_properties(nova_deck_rumble_test PROPERTIES TIMEOUT 20)
    add_executable(nova_deck_stream_core_test
        tests/deck_stream_core_test.cpp
    )
    target_link_libraries(nova_deck_stream_core_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_stream_core_test COMMAND nova_deck_stream_core_test)

    add_executable(nova_deck_gamestream_launch_test
        tests/deck_gamestream_launch_test.cpp
    )
    target_link_libraries(nova_deck_gamestream_launch_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_gamestream_launch_test COMMAND nova_deck_gamestream_launch_test)

    add_executable(nova_deck_gamestream_session_builder_test
        tests/deck_gamestream_session_builder_test.cpp
    )
    target_link_libraries(nova_deck_gamestream_session_builder_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_gamestream_session_builder_test COMMAND nova_deck_gamestream_session_builder_test)

    add_executable(nova_deck_stream_media_adapters_test
        tests/deck_stream_media_adapters_test.cpp
    )
    target_link_libraries(nova_deck_stream_media_adapters_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_stream_media_adapters_test COMMAND nova_deck_stream_media_adapters_test)

    add_executable(nova_deck_opus_audio_test tests/deck_opus_audio_test.cpp)
    add_executable(nova_deck_audio_recovery_test tests/deck_audio_recovery_test.cpp)
    target_link_libraries(nova_deck_audio_recovery_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_audio_recovery_test COMMAND nova_deck_audio_recovery_test)
    set_tests_properties(nova_deck_audio_recovery_test PROPERTIES TIMEOUT 15)
    target_link_libraries(nova_deck_opus_audio_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_opus_audio_test COMMAND nova_deck_opus_audio_test)
    add_test(NAME nova_deck_audio_unavailable_test COMMAND nova_deck_opus_audio_test --unavailable)
    set_tests_properties(nova_deck_opus_audio_test nova_deck_audio_unavailable_test PROPERTIES TIMEOUT 15)
    find_program(NOVA_DECK_TEST_PIPEWIRE pipewire)
    find_program(NOVA_DECK_TEST_PW_LINK pw-link)
    find_program(NOVA_DECK_TEST_PW_CLI pw-cli)
    find_program(NOVA_DECK_TEST_PW_DUMP pw-dump)
    if(NOVA_DECK_TEST_PIPEWIRE AND NOVA_DECK_TEST_PW_LINK)
        foreach(mode IN ITEMS playback disconnect)
            set(audio_test_args)
            if(mode STREQUAL "disconnect")
                list(APPEND audio_test_args --disconnect)
            endif()
            add_test(NAME nova_deck_pipewire_${mode}_test
                COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_pipewire_audio_test.py
                    $<TARGET_FILE:nova_deck_opus_audio_test>
                    ${NOVA_DECK_TEST_PIPEWIRE} ${NOVA_DECK_TEST_PW_LINK} ${audio_test_args})
            set_tests_properties(nova_deck_pipewire_${mode}_test PROPERTIES TIMEOUT 20)
        endforeach()
        foreach(channels IN ITEMS 6 8)
            foreach(mode IN ITEMS playback disconnect)
                set(audio_test_args --channels ${channels})
                if(mode STREQUAL "disconnect")
                    list(APPEND audio_test_args --disconnect)
                endif()
                add_test(NAME nova_deck_pipewire_${channels}_${mode}_test
                    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_pipewire_audio_test.py
                        $<TARGET_FILE:nova_deck_opus_audio_test>
                        ${NOVA_DECK_TEST_PIPEWIRE} ${NOVA_DECK_TEST_PW_LINK} ${audio_test_args})
                set_tests_properties(nova_deck_pipewire_${channels}_${mode}_test PROPERTIES TIMEOUT 20)
            endforeach()
        endforeach()
    endif()

    if(NOVA_DECK_TEST_PIPEWIRE AND NOVA_DECK_TEST_PW_LINK AND NOVA_DECK_TEST_PW_CLI AND NOVA_DECK_TEST_PW_DUMP)
        foreach(channels IN ITEMS 2 6 8)
            foreach(mode IN ITEMS restart route)
                add_test(NAME nova_deck_audio_${channels}_${mode}_test
                    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_pipewire_recovery_test.py
                        $<TARGET_FILE:nova_deck_opus_audio_test> ${NOVA_DECK_TEST_PIPEWIRE} ${NOVA_DECK_TEST_PW_LINK}
                        ${NOVA_DECK_TEST_PW_CLI} ${NOVA_DECK_TEST_PW_DUMP} ${mode} ${channels})
                set_tests_properties(nova_deck_audio_${channels}_${mode}_test PROPERTIES TIMEOUT 30)
            endforeach()
        endforeach()
    endif()

    add_executable(nova_deck_moonlight_handoff_preflight_test
        tests/deck_moonlight_handoff_preflight_test.cpp
    )
    target_link_libraries(nova_deck_moonlight_handoff_preflight_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_moonlight_handoff_preflight_test COMMAND nova_deck_moonlight_handoff_preflight_test)

    add_executable(nova_deck_backend_interfaces_test
        tests/deck_backend_interfaces_test.cpp
    )
    target_link_libraries(nova_deck_backend_interfaces_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_backend_interfaces_test COMMAND nova_deck_backend_interfaces_test)

    add_executable(nova_deck_moonlight_identity_test
        tests/deck_moonlight_identity_test.cpp
    )
    target_link_libraries(nova_deck_moonlight_identity_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_moonlight_identity_test COMMAND nova_deck_moonlight_identity_test)

    add_executable(nova_deck_polaris_client_test
        tests/deck_polaris_client_test.cpp
    )
    target_link_libraries(nova_deck_polaris_client_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_polaris_client_test COMMAND nova_deck_polaris_client_test)

    add_executable(nova_deck_live_read_only_state_test
        tests/deck_live_read_only_state_test.cpp
    )
    target_link_libraries(nova_deck_live_read_only_state_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_live_read_only_state_test COMMAND nova_deck_live_read_only_state_test)

    find_program(NOVA_DECK_TEST_OPENSSL openssl REQUIRED)
    add_executable(nova_deck_live_network_test tests/deck_live_network_test.cpp)
    target_link_libraries(nova_deck_live_network_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_live_network_test PRIVATE
        NOVA_DECK_TEST_OPENSSL="${NOVA_DECK_TEST_OPENSSL}")
    add_test(NAME nova_deck_live_network_test COMMAND nova_deck_live_network_test)
    set_tests_properties(nova_deck_live_network_test PROPERTIES TIMEOUT 45)

    add_executable(nova_deck_moonlight_launcher_test
        tests/deck_moonlight_launcher_test.cpp
    )
    target_link_libraries(nova_deck_moonlight_launcher_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_moonlight_launcher_test COMMAND nova_deck_moonlight_launcher_test)
    set_tests_properties(nova_deck_moonlight_launcher_test PROPERTIES TIMEOUT 60)

    add_executable(nova_deck_steam_shortcuts_test
        tests/deck_steam_shortcuts_test.cpp
    )
    target_link_libraries(nova_deck_steam_shortcuts_test PRIVATE nova_deck_core)
    add_test(NAME nova_deck_steam_shortcuts_test COMMAND nova_deck_steam_shortcuts_test)

    add_executable(nova_deck_qsg_render_node_scenegraph_smoke
        tests/deck_qsg_render_node_scenegraph_smoke.cpp
    )
    target_link_libraries(nova_deck_qsg_render_node_scenegraph_smoke PRIVATE nova_deck_core)
    add_test(NAME nova_deck_qsg_render_node_scenegraph_smoke COMMAND nova_deck_qsg_render_node_scenegraph_smoke)
    set_tests_properties(nova_deck_qsg_render_node_scenegraph_smoke PROPERTIES
        ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QSG_RHI_BACKEND=opengl"
        SKIP_RETURN_CODE 77
        TIMEOUT 15
    )

    # Software Mesa under Xvfb validates actual shader pixels without VAAPI or a
    # live host. Never substitute this test for physical decode/presentation.
    add_executable(nova_deck_video_composition_test tests/deck_video_composition_test.cpp)
    set_target_properties(nova_deck_video_composition_test PROPERTIES AUTOMOC ON)
    target_link_libraries(nova_deck_video_composition_test PRIVATE nova_deck_core)
    find_program(NOVA_DECK_TEST_XVFB_RUN xvfb-run)
    if(NOVA_DECK_BUILD_HDR_VALIDATION AND NOVA_DECK_TEST_XVFB_RUN)
        find_package(Qt6 REQUIRED COMPONENTS Test QuickControls2)
        add_executable(nova_deck_vulkan_pacing_test tests/deck_vulkan_pacing_test.cpp)
        target_link_libraries(nova_deck_vulkan_pacing_test PRIVATE nova_deck_hdr_render Qt6::Test)
        add_test(NAME nova_deck_vulkan_pacing_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                $<TARGET_FILE:nova_deck_vulkan_pacing_test>)
        set_tests_properties(nova_deck_vulkan_pacing_test PROPERTIES
            TIMEOUT 30 RESOURCE_LOCK nova_deck_x11 ENVIRONMENT "QT_QPA_PLATFORM=xcb;QSG_RHI_BACKEND=vulkan;QT_QUICK_BACKEND=rhi;QT_SCALE_FACTOR=1")
        add_executable(nova_deck_vulkan_overlay_test tests/deck_vulkan_overlay_test.cpp tests/deck_preview_fixture.h)
        set_target_properties(nova_deck_vulkan_overlay_test PROPERTIES AUTOMOC ON)
        target_link_libraries(nova_deck_vulkan_overlay_test PRIVATE nova_deck_hdr_render Qt6::Test Qt6::QuickControls2)
        target_compile_definitions(nova_deck_vulkan_overlay_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
        add_test(NAME nova_deck_vulkan_overlay_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                $<TARGET_FILE:nova_deck_vulkan_overlay_test>)
        set_tests_properties(nova_deck_vulkan_overlay_test PROPERTIES
            TIMEOUT 90 RESOURCE_LOCK nova_deck_x11 ENVIRONMENT "QT_QPA_PLATFORM=xcb;QSG_RHI_BACKEND=vulkan;QT_QUICK_BACKEND=rhi;QT_SCALE_FACTOR=1")
        add_test(NAME nova_deck_vulkan_overlay_refusal_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                $<TARGET_FILE:nova_deck_vulkan_overlay_test> --reject-overlay)
        set_tests_properties(nova_deck_vulkan_overlay_refusal_test PROPERTIES
            TIMEOUT 30 RESOURCE_LOCK nova_deck_x11 ENVIRONMENT "QT_QPA_PLATFORM=xcb;QT_QUICK_BACKEND=rhi;QT_SCALE_FACTOR=1;LIBGL_ALWAYS_SOFTWARE=1")
        add_test(NAME nova_deck_vulkan_thread_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                $<TARGET_FILE:nova_deck_vulkan_overlay_test> --thread-pressure)
        set_tests_properties(nova_deck_vulkan_thread_test PROPERTIES
            TIMEOUT 45 RESOURCE_LOCK nova_deck_x11 ENVIRONMENT "QT_QPA_PLATFORM=xcb;QSG_RHI_BACKEND=vulkan;QT_QUICK_BACKEND=rhi;QT_SCALE_FACTOR=1")
        add_executable(nova_deck_vulkan_window_test tests/deck_vulkan_window_test.cpp)
        target_link_libraries(nova_deck_vulkan_window_test PRIVATE nova_deck_hdr_render)
        add_test(NAME nova_deck_vulkan_window_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                $<TARGET_FILE:nova_deck_vulkan_window_test>)
        set_tests_properties(nova_deck_vulkan_window_test PROPERTIES
            TIMEOUT 45 RESOURCE_LOCK nova_deck_x11 ENVIRONMENT "QT_QPA_PLATFORM=xcb;QT_SCALE_FACTOR=1")
    endif()
    if(NOVA_DECK_TEST_XVFB_RUN)
        add_test(NAME nova_deck_video_composition_test
            COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 800x600x24"
                $<TARGET_FILE:nova_deck_video_composition_test>)
        set_tests_properties(nova_deck_video_composition_test PROPERTIES
            ENVIRONMENT "QT_QPA_PLATFORM=xcb;QSG_RHI_BACKEND=opengl;QT_QUICK_BACKEND=rhi;LIBGL_ALWAYS_SOFTWARE=1"
            TIMEOUT 20)
    else()
        message(STATUS "Deck GL pixel test built but not registered: xvfb-run is unavailable")
    endif()

    add_test(NAME nova_deck_gamemode_capture_harness_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_gamemode_capture_test.py
    )
    add_test(NAME nova_deck_media_assert_guard_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_media_assert_guard_test.py
    )
    add_test(NAME nova_deck_moonlight_handoff_source_guard_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_moonlight_handoff_source_guard_test.py
    )
    add_test(NAME nova_deck_t31_podman_validation_route_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_t31_podman_validation_test.py
    )
    add_test(NAME nova_deck_t32_preview_pump_oracle_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_t32_preview_pump_oracle_test.py
    )
    add_test(NAME nova_deck_frontend_smoke_route_test
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_frontend_smoke_test.py
    )
endif()

option(NOVA_DECK_BUILD_QT_SHELL "Build the experimental Qt/QML Steam Deck shell" ON)

if(NOVA_DECK_BUILD_QT_SHELL)
    find_package(Qt6 QUIET COMPONENTS Core Gui Qml Quick QuickControls2)

    if(Qt6_FOUND)
        qt_standard_project_setup(REQUIRES 6.5)
        if(COMMAND qt_policy AND Qt6_VERSION VERSION_GREATER_EQUAL 6.8)
            qt_policy(SET QTP0004 NEW)
        endif()

        qt_add_executable(nova-deck
            src/main.cpp
        )
        target_compile_definitions(nova-deck PRIVATE $<$<BOOL:${BUILD_TESTING}>:NOVA_DECK_TESTING>)
        if(NOVA_DECK_BUILD_VULKAN_STREAM)
            target_compile_definitions(nova-deck PRIVATE NOVA_DECK_VULKAN_STREAM=1)
            target_link_libraries(nova-deck PRIVATE nova_deck_hdr_render)
        endif()

        set_source_files_properties(qml/NovaTheme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
        set_source_files_properties(qml/NovaHudPreferences.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
        set_source_files_properties(qml/NovaStreamPreferences.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
        qt_add_qml_module(nova-deck
            URI Nova.Deck
            VERSION 0.1
            QML_FILES
                qml/NovaTheme.qml
                qml/NovaHudPreferences.qml
                qml/NovaStreamPreferences.qml
                qml/DeckButtonGlyph.qml
                qml/DeckMenuShortcut.qml
                qml/NovaHud.qml
                qml/HudSettings.qml
                qml/LiveBitrate.qml
                qml/Doctor.qml
                qml/NovaButton.qml
                qml/NovaScrollColumn.qml
                qml/HostPower.qml
                qml/HostDefaults.qml
                qml/SessionSyncActions.qml
                qml/StreamProfileEditor.qml
                qml/AppearanceSettings.qml
                qml/SettingsHub.qml
                qml/VideoScaleSettings.qml
                qml/DeadzoneSettings.qml
                qml/LibraryLayout.js
                qml/Main.qml
                qml/LibraryBrowser.qml
                qml/DestinationPicker.qml
                qml/AudioSettings.qml
                qml/RumbleSettings.qml
                qml/LibraryQuery.js
                qml/NativeStreamPreview.qml
                qml/GameShortcut.qml
                qml/ArtworkStudio.qml
                qml/SpaceArtwork.qml
                qml/LogoPlacement.qml
                qml/LogoArtwork.qml
                qml/PlaySetup.qml
                qml/PairHost.qml
                qml/EndpointKeyboard.qml
                qml/SavedPcs.qml
        )

        target_link_libraries(nova-deck
            PRIVATE
                nova_deck_core
                Qt6::Core
                Qt6::Gui
                Qt6::Qml
                Qt6::Quick
                Qt6::QuickControls2
        )

        install(TARGETS nova-deck RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
        install(FILES fixtures/sample_polaris_game.json fixtures/sample_polaris_library.json
            DESTINATION ${CMAKE_INSTALL_DATADIR}/nova-deck/fixtures)
        install(FILES packaging/flatpak/com.papi_ux.Nova.desktop
            DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
        install(FILES packaging/flatpak/com.papi_ux.Nova.svg
            DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/scalable/apps)
        install(FILES packaging/flatpak/com.papi_ux.Nova.metainfo.xml
            DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo)

        if(BUILD_TESTING)
            add_executable(nova_deck_pairing_qml_test tests/deck_pairing_qml_test.cpp)
            target_link_libraries(nova_deck_pairing_qml_test PRIVATE nova_deck_core Qt6::QuickControls2)
            target_compile_definitions(nova_deck_pairing_qml_test PRIVATE
                NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_pairing_qml_test COMMAND nova_deck_pairing_qml_test)
            set_tests_properties(nova_deck_pairing_qml_test PROPERTIES
                ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 20)
            add_test(NAME nova_deck_standalone_route_test COMMAND ${Python3_EXECUTABLE}
                ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_standalone_route_test.py $<TARGET_FILE:nova-deck>)
            set_tests_properties(nova_deck_standalone_route_test PROPERTIES TIMEOUT 35)
            add_test(NAME nova_deck_standard_library_route_test COMMAND ${Python3_EXECUTABLE}
                ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_standard_library_route_test.py $<TARGET_FILE:nova-deck>)
            set_tests_properties(nova_deck_standard_library_route_test PROPERTIES TIMEOUT 55)
            find_program(NOVA_DECK_TEST_XDOTOOL xdotool)
            if(NOVA_DECK_TEST_XVFB_RUN AND NOVA_DECK_TEST_XDOTOOL)
                add_test(NAME nova_deck_library_navigation_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck>)
                set_tests_properties(nova_deck_library_navigation_test PROPERTIES TIMEOUT 55)
                add_test(NAME nova_deck_host_power_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --host-power)
                set_tests_properties(nova_deck_host_power_route_test PROPERTIES TIMEOUT 70 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_appearance_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --appearance)
                set_tests_properties(nova_deck_appearance_route_test PROPERTIES TIMEOUT 65)
                add_test(NAME nova_deck_library_experience_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --experience)
                set_tests_properties(nova_deck_library_experience_test PROPERTIES TIMEOUT 55)
                add_test(NAME nova_deck_library_filters_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --filters)
                set_tests_properties(nova_deck_library_filters_test PROPERTIES TIMEOUT 70)
                add_test(NAME nova_deck_library_stage_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --stage)
                set_tests_properties(nova_deck_library_stage_test PROPERTIES TIMEOUT 70)
                add_test(NAME nova_deck_play_setup_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --play-setup)
                set_tests_properties(nova_deck_play_setup_route_test PROPERTIES TIMEOUT 80)
                add_test(NAME nova_deck_launch_modes_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --launch-modes)
                set_tests_properties(nova_deck_launch_modes_route_test PROPERTIES TIMEOUT 80)
                add_test(NAME nova_deck_sync_surface_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_game_tools_route_test.py
                        $<TARGET_FILE:nova-deck> --sync-surface)
                add_test(NAME nova_deck_settings_hub_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_game_tools_route_test.py
                        $<TARGET_FILE:nova-deck> --settings-hub)
                set_tests_properties(nova_deck_settings_hub_route_test PROPERTIES TIMEOUT 60 RESOURCE_LOCK nova_deck_x11)
                set_tests_properties(nova_deck_sync_surface_route_test PROPERTIES TIMEOUT 60 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_display_settings_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_game_tools_route_test.py
                        $<TARGET_FILE:nova-deck> --display-settings)
                set_tests_properties(nova_deck_display_settings_route_test PROPERTIES TIMEOUT 80 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_space_artwork_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_game_tools_route_test.py
                        $<TARGET_FILE:nova-deck> --space-artwork)
                set_tests_properties(nova_deck_space_artwork_route_test PROPERTIES TIMEOUT 60 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_stream_plan_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --stream-plan)
                set_tests_properties(nova_deck_stream_plan_route_test PROPERTIES TIMEOUT 80)
                add_test(NAME nova_deck_audio_settings_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --audio-settings)
                set_tests_properties(nova_deck_audio_settings_route_test PROPERTIES TIMEOUT 65)
                add_test(NAME nova_deck_spaces_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --spaces)
                set_tests_properties(nova_deck_spaces_route_test PROPERTIES TIMEOUT 125 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_setup_parity_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --setup-parity)
                set_tests_properties(nova_deck_setup_parity_route_test PROPERTIES TIMEOUT 135 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_host_scope_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --host-scope)
                set_tests_properties(nova_deck_host_scope_route_test PROPERTIES TIMEOUT 135 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_profile_sync_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --profile-sync)
                set_tests_properties(nova_deck_profile_sync_route_test PROPERTIES TIMEOUT 135 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_keep_in_step_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --keep-in-step)
                set_tests_properties(nova_deck_keep_in_step_route_test PROPERTIES TIMEOUT 95 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_library_polish_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --polish)
                set_tests_properties(nova_deck_library_polish_test PROPERTIES TIMEOUT 95 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_game_tools_route_test COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                    ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_game_tools_route_test.py $<TARGET_FILE:nova-deck>)
                set_tests_properties(nova_deck_game_tools_route_test PROPERTIES TIMEOUT 70 RESOURCE_LOCK nova_deck_x11)
                add_test(NAME nova_deck_artwork_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --artwork)
                set_tests_properties(nova_deck_artwork_route_test PROPERTIES TIMEOUT 75)
                add_test(NAME nova_deck_library_auto_refresh_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_library_navigation_test.py
                        $<TARGET_FILE:nova-deck> --automatic)
                set_tests_properties(nova_deck_library_auto_refresh_test PROPERTIES TIMEOUT 65)
                add_test(NAME nova_deck_saved_pcs_route_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_saved_pcs_route_test.py
                        $<TARGET_FILE:nova-deck>)
                set_tests_properties(nova_deck_saved_pcs_route_test PROPERTIES TIMEOUT 45)
                add_test(NAME nova_deck_graphics_startup_test
                    COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                        ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_graphics_startup_test.py
                        $<TARGET_FILE:nova-deck>)
                set_tests_properties(nova_deck_graphics_startup_test PROPERTIES TIMEOUT 20)
                if(NOVA_DECK_BUILD_VULKAN_STREAM)
                    add_test(NAME nova_deck_vulkan_startup_test
                        COMMAND ${NOVA_DECK_TEST_XVFB_RUN} -a -s "-screen 0 1280x800x24"
                            ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/deck_graphics_startup_test.py
                            $<TARGET_FILE:nova-deck> --vulkan)
                    set_tests_properties(nova_deck_vulkan_startup_test PROPERTIES TIMEOUT 20 RESOURCE_LOCK nova_deck_x11)
                endif()
            else()
                message(STATUS "Deck saved-PC navigation test not registered: X11 test tools are unavailable")
            endif()
            add_executable(nova_deck_host_power_qml_test tests/deck_host_power_qml_test.cpp)
            add_executable(nova_deck_hud_qml_test tests/deck_hud_qml_test.cpp)
            target_link_libraries(nova_deck_hud_qml_test PRIVATE nova_deck_core Qt6::QuickControls2)
            target_compile_definitions(nova_deck_hud_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_hud_qml_test COMMAND nova_deck_hud_qml_test)
            set_tests_properties(nova_deck_hud_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 20)
            target_link_libraries(nova_deck_host_power_qml_test PRIVATE nova_deck_core Qt6::QuickControls2)
            target_compile_definitions(nova_deck_host_power_qml_test PRIVATE
                NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_host_power_qml_test COMMAND nova_deck_host_power_qml_test)
            set_tests_properties(nova_deck_host_power_qml_test PROPERTIES
                ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 15)
            find_package(Qt6 REQUIRED COMPONENTS Test)
            add_executable(nova_deck_space_artwork_qml_test tests/deck_space_artwork_qml_test.cpp)
            target_link_libraries(nova_deck_space_artwork_qml_test PRIVATE nova_deck_core Qt6::QuickControls2 Qt6::Test)
            target_compile_definitions(nova_deck_space_artwork_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_space_artwork_qml_test COMMAND nova_deck_space_artwork_qml_test)
            set_tests_properties(nova_deck_space_artwork_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 40)
            add_executable(nova_deck_logo_placement_qml_test tests/deck_logo_placement_qml_test.cpp)
            target_link_libraries(nova_deck_logo_placement_qml_test PRIVATE nova_deck_core Qt6::QuickControls2 Qt6::Test)
            target_compile_definitions(nova_deck_logo_placement_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_logo_placement_qml_test COMMAND nova_deck_logo_placement_qml_test)
            set_tests_properties(nova_deck_logo_placement_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 40)
            add_executable(nova_deck_display_settings_qml_test tests/deck_display_settings_qml_test.cpp)
            add_executable(nova_deck_settings_hub_qml_test tests/deck_settings_hub_qml_test.cpp)
            target_link_libraries(nova_deck_settings_hub_qml_test PRIVATE nova_deck_core Qt6::QuickControls2 Qt6::Test)
            target_compile_definitions(nova_deck_settings_hub_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_settings_hub_qml_test COMMAND nova_deck_settings_hub_qml_test)
            set_tests_properties(nova_deck_settings_hub_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 45)
            target_link_libraries(nova_deck_display_settings_qml_test PRIVATE nova_deck_core Qt6::QuickControls2 Qt6::Test)
            target_compile_definitions(nova_deck_display_settings_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_display_settings_qml_test COMMAND nova_deck_display_settings_qml_test)
            set_tests_properties(nova_deck_display_settings_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 40)
            add_executable(nova_deck_game_tools_qml_test tests/deck_game_tools_qml_test.cpp)
            target_link_libraries(nova_deck_game_tools_qml_test PRIVATE nova_deck_core Qt6::QuickControls2 Qt6::Test)
            target_compile_definitions(nova_deck_game_tools_qml_test PRIVATE NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml")
            add_test(NAME nova_deck_game_tools_qml_test COMMAND nova_deck_game_tools_qml_test)
            set_tests_properties(nova_deck_game_tools_qml_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT 30)
            add_executable(nova_deck_native_preview_qml_test tests/deck_native_preview_qml_test.cpp tests/deck_preview_fixture.h)
            target_link_libraries(nova_deck_native_preview_qml_test PRIVATE nova_deck_core Qt6::QuickControls2)
            target_compile_definitions(nova_deck_native_preview_qml_test PRIVATE
                NOVA_DECK_QML_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/qml"
                NOVA_DECK_DOCTOR_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/doctor-v2.json")
            add_test(NAME nova_deck_native_preview_qml_test COMMAND nova_deck_native_preview_qml_test)
            set_tests_properties(nova_deck_native_preview_qml_test PROPERTIES
                ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software"
                TIMEOUT 45)
            add_test(NAME nova_deck_qt_shell_smoke COMMAND nova-deck --smoke-exit)
            set_tests_properties(nova_deck_qt_shell_smoke PROPERTIES
                ENVIRONMENT "QT_QPA_PLATFORM=offscreen"
                TIMEOUT 10
            )
        endif()
    else()
        message(WARNING "Qt6 Quick/QuickControls2 not found; building nova_deck_core and tests only. On Fedora install cmake gcc-c++ qt6-qtbase-devel qt6-qtdeclarative-devel; qt6-qtdeclarative-devel provides cmake(Qt6QuickControls2).")
    endif()
endif()

# Some xvfb-run packages reuse :99 during simultaneous startup despite -a.
# Serialize X11 fixtures while allowing backend and offscreen tests in parallel.
if(BUILD_TESTING)
    foreach(deck_x11_test IN ITEMS
        nova_deck_video_composition_test
        nova_deck_library_navigation_test
        nova_deck_appearance_route_test
        nova_deck_library_experience_test
        nova_deck_library_filters_test
        nova_deck_library_stage_test
        nova_deck_play_setup_route_test
        nova_deck_launch_modes_route_test
        nova_deck_stream_plan_route_test
        nova_deck_audio_settings_route_test
        nova_deck_artwork_route_test
        nova_deck_library_auto_refresh_test
        nova_deck_saved_pcs_route_test
        nova_deck_graphics_startup_test
    )
        if(TEST ${deck_x11_test})
            set_tests_properties(${deck_x11_test} PROPERTIES RESOURCE_LOCK nova_deck_x11)
        endif()
    endforeach()
endif()
