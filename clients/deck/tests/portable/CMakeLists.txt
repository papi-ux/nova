cmake_minimum_required(VERSION 3.22)
# Protocol/transport validation only. This target does not build the Deck app
# or validate Linux media, hardware presentation, audio or physical input.
project(NovaPortableLibraryValidation LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
find_package(Qt6 REQUIRED COMPONENTS Core Network)
find_program(TEST_OPENSSL openssl HINTS /opt/homebrew/opt/openssl@3/bin REQUIRED)
get_filename_component(NOVA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(DECK "${NOVA_ROOT}/clients/deck")
add_library(portable_library STATIC
    ${DECK}/src/deck_layout.cpp
    ${DECK}/src/polaris_game_fixture.cpp
    ${DECK}/src/backend/deck_backend_interfaces.cpp
    ${DECK}/src/backend/deck_live_read_only_state.cpp
    ${DECK}/src/identity/deck_moonlight_identity.cpp
    ${DECK}/src/polaris/deck_polaris_client.cpp
    ${DECK}/src/polaris/deck_spaces.cpp
    ${DECK}/src/polaris/deck_host_settings.cpp
    ${DECK}/src/polaris/deck_host_telemetry.cpp
    ${DECK}/src/polaris/deck_doctor.cpp
    ${DECK}/src/polaris/deck_doctor_action.cpp
    ${DECK}/src/polaris/deck_session_events.cpp
    ${DECK}/src/stream/deck_gamestream_library.cpp
    ${DECK}/src/stream/deck_gamestream_launch.cpp
    ${DECK}/src/stream/deck_stream_core.cpp
    ${DECK}/src/runtime/deck_hud_host.cpp
    ${DECK}/src/runtime/deck_doctor_actions.cpp
    ${DECK}/src/stream/deck_gamestream_session_builder.cpp
    ${DECK}/src/runtime/deck_native_target.cpp
    ${DECK}/src/runtime/deck_library_controller.cpp)
set_target_properties(portable_library PROPERTIES AUTOMOC ON)
target_include_directories(portable_library PUBLIC "${DECK}/src" "${NOVA_ROOT}/app/src/main/jni/moonlight-core/moonlight-common-c/src")
set(BUILD_SHARED_LIBS OFF)
add_subdirectory("${NOVA_ROOT}/app/src/main/jni/moonlight-core/moonlight-common-c"
    "${CMAKE_CURRENT_BINARY_DIR}/moonlight-common-c")
target_link_libraries(portable_library PUBLIC Qt6::Core Qt6::Network moonlight-common-c)
target_compile_definitions(portable_library PUBLIC
    NOVA_DECK_FIXTURE_SOURCE_DIR="${DECK}/fixtures"
    NOVA_DECK_SAMPLE_GAME_FIXTURE="${DECK}/fixtures/sample_polaris_game.json"
    NOVA_DECK_SAMPLE_LIBRARY_FIXTURE="${DECK}/fixtures/sample_polaris_library.json")
enable_testing()
foreach(name IN ITEMS gamestream_library library_controller live_read_only_state live_network polaris_client moonlight_identity backend_interfaces gamestream_launch gamestream_session_builder)
    add_executable(nova_deck_${name}_test "${DECK}/tests/deck_${name}_test.cpp")
    target_compile_definitions(nova_deck_${name}_test PRIVATE NOVA_DECK_TEST_OPENSSL="${TEST_OPENSSL}")
    target_link_libraries(nova_deck_${name}_test PRIVATE portable_library)
    add_test(NAME nova_deck_${name}_test COMMAND nova_deck_${name}_test)
    set_tests_properties(nova_deck_${name}_test PROPERTIES TIMEOUT 45)
endforeach()
