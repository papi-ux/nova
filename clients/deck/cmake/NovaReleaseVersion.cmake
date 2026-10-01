# Keep CMake's numeric project version while reporting the exact prerelease in Nova.
set(NOVA_DECK_VERSION_SUFFIX "" CACHE STRING "Optional numbered prerelease suffix: -beta.N or -rc.N")
if(NOT NOVA_DECK_VERSION_SUFFIX MATCHES "^(|-(beta|rc)\\.[1-9][0-9]*)$")
    message(FATAL_ERROR "Nova version suffix must be empty, -beta.N or -rc.N with N greater than zero")
endif()
set(NOVA_DECK_FULL_VERSION "${PROJECT_VERSION}${NOVA_DECK_VERSION_SUFFIX}")
