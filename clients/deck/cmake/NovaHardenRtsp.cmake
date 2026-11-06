# Prepare a build-owned core copy. Android's checkout may already carry the
# attributed patch series; exact reverse applicability verifies that case.
function(nova_deck_prepare_rtsp source destination)
    find_package(Git REQUIRED)
    file(COPY "${source}/" DESTINATION "${destination}" PATTERN ".git" EXCLUDE)
    # COPY can skip different bytes with an equal modification time. These
    # files carry the patch chain and must start from the actual input bytes.
    # A file the chain creates must not survive from an earlier configure.
    foreach(source_file IN ITEMS RtspConnection.c PlatformSockets.h
            ControlStream.c PacketReception.c PacketReception.h Platform.c Platform.h
            RtpVideoQueue.c RtpVideoQueue.h SdpGenerator.c VideoStream.c Limelight.h VideoDepacketizer.c)
        if(EXISTS "${source}/src/${source_file}")
            file(COPY_FILE "${source}/src/${source_file}" "${destination}/src/${source_file}" ONLY_IF_DIFFERENT)
        else()
            file(REMOVE "${destination}/src/${source_file}")
        endif()
    endforeach()
    # Work on plain files. Repository discovery above this generated copy can
    # otherwise make git apply silently skip paths below a checkout prefix.
    file(REAL_PATH "${destination}" patch_directory)
    get_filename_component(patch_ceiling "${patch_directory}" DIRECTORY)
    set(git_patch_command "${CMAKE_COMMAND}" -E env
        --unset=GIT_DIR --unset=GIT_WORK_TREE --unset=GIT_INDEX_FILE
        "GIT_CEILING_DIRECTORIES=${patch_ceiling}" "${GIT_EXECUTABLE}" apply --whitespace=error)
    # The RTSP hardening pair, then the packet reception observer, raw source
    # timing and reception transport fix shared with Android. The optional
    # PyroWave patch applies afterward.
    set(rtsp_patches
        0009-upstream-harden-RTSP-responses-and-Session.patch
        0010-clean-Session-errors-and-map-Windows-size-error.patch
        0011-feat-video-report-bounded-native-packet-reception.patch
        0012-feat-video-carry-source-presentation-time.patch
        0013-fix-video-keep-ENet-s-throttle-from-discarding-recep.patch)
    # Later patches change earlier context. Unwind verified patches in reverse
    # order on this generated copy, then apply the complete chain in order.
    set(reverse_patches ${rtsp_patches})
    list(REVERSE reverse_patches)
    foreach(native_patch IN LISTS reverse_patches)
        set(patch_path "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../../app/src/main/jni/moonlight-core/patches/${native_patch}")
        execute_process(COMMAND ${git_patch_command} --reverse --check "${patch_path}"
            WORKING_DIRECTORY "${destination}" RESULT_VARIABLE reverse_result OUTPUT_QUIET ERROR_QUIET)
        if(reverse_result EQUAL 0)
            execute_process(COMMAND ${git_patch_command} --reverse "${patch_path}"
                WORKING_DIRECTORY "${destination}" RESULT_VARIABLE undo_result OUTPUT_QUIET)
            if(NOT undo_result EQUAL 0)
                message(FATAL_ERROR "Could not verify the existing Deck native patch chain: ${native_patch}")
            endif()
        endif()
    endforeach()
    foreach(native_patch IN LISTS rtsp_patches)
        set(patch_path "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../../app/src/main/jni/moonlight-core/patches/${native_patch}")
        execute_process(COMMAND ${git_patch_command} "${patch_path}"
            WORKING_DIRECTORY "${destination}" RESULT_VARIABLE apply_result)
        if(NOT apply_result EQUAL 0)
            message(FATAL_ERROR "Deck native patch failed: ${native_patch}")
        endif()
    endforeach()
endfunction()
