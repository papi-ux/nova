# Prepare a build-owned core copy. Android's checkout may already carry the
# attributed patch series; exact reverse applicability verifies that case.
function(nova_deck_prepare_rtsp source destination)
    find_program(NOVA_RTSP_PATCH_EXECUTABLE patch REQUIRED)
    file(COPY "${source}/" DESTINATION "${destination}" PATTERN ".git" EXCLUDE)
    # COPY can skip different bytes with an equal modification time. These two
    # files carry the patch chain and must start from the actual input bytes.
    foreach(source_file IN ITEMS RtspConnection.c PlatformSockets.h)
        file(COPY_FILE "${source}/src/${source_file}" "${destination}/src/${source_file}" ONLY_IF_DIFFERENT)
    endforeach()
    set(rtsp_patches
        0009-upstream-harden-RTSP-responses-and-Session.patch
        0010-clean-Session-errors-and-map-Windows-size-error.patch)
    # Later patches change earlier context. Unwind verified patches in reverse
    # order on this generated copy, then apply the complete chain in order.
    set(reverse_patches ${rtsp_patches})
    list(REVERSE reverse_patches)
    foreach(native_patch IN LISTS reverse_patches)
        set(patch_path "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../../app/src/main/jni/moonlight-core/patches/${native_patch}")
        execute_process(COMMAND "${NOVA_RTSP_PATCH_EXECUTABLE}" --batch --force --reverse --dry-run -p1 -i "${patch_path}"
            WORKING_DIRECTORY "${destination}" RESULT_VARIABLE reverse_result OUTPUT_QUIET ERROR_QUIET)
        if(reverse_result EQUAL 0)
            execute_process(COMMAND "${NOVA_RTSP_PATCH_EXECUTABLE}" --batch --force --reverse -p1 -i "${patch_path}"
                WORKING_DIRECTORY "${destination}" RESULT_VARIABLE undo_result OUTPUT_QUIET)
            if(NOT undo_result EQUAL 0)
                message(FATAL_ERROR "Could not verify the existing RTSP patch chain: ${native_patch}")
            endif()
        endif()
    endforeach()
    foreach(native_patch IN LISTS rtsp_patches)
        set(patch_path "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../../app/src/main/jni/moonlight-core/patches/${native_patch}")
        execute_process(COMMAND "${NOVA_RTSP_PATCH_EXECUTABLE}" --batch --forward -p1 -i "${patch_path}"
            WORKING_DIRECTORY "${destination}" RESULT_VARIABLE apply_result)
        if(NOT apply_result EQUAL 0)
            message(FATAL_ERROR "RTSP hardening patch failed: ${native_patch}")
        endif()
    endforeach()
endfunction()
