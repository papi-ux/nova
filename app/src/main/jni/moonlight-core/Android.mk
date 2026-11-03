# Android.mk for moonlight-core and binding
MY_LOCAL_PATH := $(call my-dir)

include $(call all-subdir-makefiles)

LOCAL_PATH := $(MY_LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE    := moonlight-core

ifeq ($(NOVA_AUDIO_RECEIVE_DIAGNOSTICS),1)
NOVA_AUDIO_STREAM_SOURCE := nova_audio_stream.c
else
NOVA_AUDIO_STREAM_SOURCE := moonlight-common-c/src/AudioStream.c
endif

LOCAL_SRC_FILES := $(NOVA_AUDIO_STREAM_SOURCE) \
                   moonlight-common-c/src/ByteBuffer.c \
                   moonlight-common-c/src/Connection.c \
                   moonlight-common-c/src/ConnectionTester.c \
                   moonlight-common-c/src/ControlStream.c \
                   moonlight-common-c/src/FakeCallbacks.c \
                   moonlight-common-c/src/InputStream.c \
                   moonlight-common-c/src/LinkedBlockingQueue.c \
                   moonlight-common-c/src/Misc.c \
                   moonlight-common-c/src/PacketReception.c \
                   moonlight-common-c/src/Platform.c \
                   moonlight-common-c/src/PlatformCrypto.c \
                   moonlight-common-c/src/PlatformSockets.c \
                   moonlight-common-c/src/RtpAudioQueue.c \
                   moonlight-common-c/src/RtpVideoQueue.c \
                   moonlight-common-c/src/RtspConnection.c \
                   moonlight-common-c/src/RtspParser.c \
                   moonlight-common-c/src/SdpGenerator.c \
                   moonlight-common-c/src/SimpleStun.c \
                   moonlight-common-c/src/VideoDepacketizer.c \
                   moonlight-common-c/src/VideoStream.c \
                   moonlight-common-c/reedsolomon/rs.c \
                   moonlight-common-c/enet/callbacks.c \
                   moonlight-common-c/enet/compress.c \
                   moonlight-common-c/enet/host.c \
                   moonlight-common-c/enet/list.c \
                   moonlight-common-c/enet/packet.c \
                   moonlight-common-c/enet/peer.c \
                   moonlight-common-c/enet/protocol.c \
                   moonlight-common-c/enet/unix.c \
                   simplejni.c \
                   callbacks.c \
                   video_payload_stats.c \
                   minisdl.c \


LOCAL_C_INCLUDES := $(LOCAL_PATH)/moonlight-common-c/enet/include \
                    $(LOCAL_PATH)/moonlight-common-c/reedsolomon \
                    $(LOCAL_PATH)/moonlight-common-c/src \

LOCAL_CFLAGS := -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1 \
                -ffunction-sections -fdata-sections

ifeq ($(NDK_DEBUG),1)
ifneq ($(NOVA_NATIVE_DEBUG_CHECKS),0)
LOCAL_CFLAGS += -DLC_DEBUG
endif
endif

LOCAL_LDLIBS := -llog

LOCAL_STATIC_LIBRARIES := libopus libssl libcrypto cpufeatures
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL -Wl,--gc-sections

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)

$(call import-module,android/cpufeatures)
