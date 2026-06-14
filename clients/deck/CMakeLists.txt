cmake_minimum_required(VERSION 3.24)

project(NovaDeck LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(nova_deck_core
    src/deck_gamepad.cpp
    src/deck_layout.cpp
    src/polaris_game_fixture.cpp
)
target_include_directories(nova_deck_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_compile_definitions(nova_deck_core
    PUBLIC
        NOVA_DECK_SAMPLE_GAME_FIXTURE=\"${CMAKE_CURRENT_SOURCE_DIR}/fixtures/sample_polaris_game.json\"
        NOVA_DECK_SAMPLE_LIBRARY_FIXTURE=\"${CMAKE_CURRENT_SOURCE_DIR}/fixtures/sample_polaris_library.json\"
)

include(CTest)
if(BUILD_TESTING)
    add_executable(nova_deck_layout_test
        tests/deck_layout_test.cpp
    )
    target_link_libraries(nova_deck_layout_test PRIVATE nova_deck_core)
    target_compile_definitions(nova_deck_layout_test
        PRIVATE
            NOVA_DECK_MAIN_QML_SOURCE="${CMAKE_CURRENT_SOURCE_DIR}/qml/Main.qml"
    )
    add_test(NAME nova_deck_controller_library_smoke COMMAND nova_deck_layout_test)
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

        qt_add_qml_module(nova-deck
            URI Nova.Deck
            VERSION 0.1
            QML_FILES
                qml/Main.qml
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

        if(BUILD_TESTING)
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
