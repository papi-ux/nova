/* Compile the actual selected transport source. ENet events and the audio
 * notification are fixtures; TCP uses production sockets against loopback. */
#include "Limelight-internal.h"
#include <stdarg.h>

static size_t maximumAllocation;
static ENetPacket* incoming[2];
static int delivered;
static int destroyed;
static void* fixtureMalloc(size_t size);
static void* fixtureExtendBuffer(void* pointer, size_t size);
static int fixtureService(ENetHost* host, ENetEvent* event, enet_uint32 timeout);
static int fixtureSend(ENetPeer* peer, enet_uint8 channel, ENetPacket* packet);
static void fixtureFlush(ENetHost* host);
static int fixtureAudioPort(void);

#define malloc fixtureMalloc
#define extendBuffer fixtureExtendBuffer
#define serviceEnetHost fixtureService
#define enet_peer_send fixtureSend
#define enet_host_flush fixtureFlush
#define notifyAudioPortNegotiationComplete fixtureAudioPort
#include NOVA_RTSP_SOURCE
#undef malloc
#undef extendBuffer
#undef serviceEnetHost
#undef enet_peer_send
#undef enet_host_flush
#undef notifyAudioPortNegotiationComplete

static void* fixtureMalloc(size_t size) {
    if (size > maximumAllocation) maximumAllocation = size;
    return malloc(size);
}
static void* fixtureExtendBuffer(void* pointer, size_t size) {
    if (size > maximumAllocation) maximumAllocation = size;
    return extendBuffer(pointer, size);
}
static void packetFreed(ENetPacket* packet) { (void)packet; destroyed++; }
static int fixtureService(ENetHost* host, ENetEvent* event, enet_uint32 timeout) {
    (void)host; (void)timeout;
    if (delivered == 2 || incoming[delivered] == NULL) return 0;
    memset(event, 0, sizeof(*event));
    event->type = ENET_EVENT_TYPE_RECEIVE;
    event->packet = incoming[delivered];
    incoming[delivered++] = NULL;
    return 1;
}
static int fixtureSend(ENetPeer* destination, enet_uint8 channel, ENetPacket* packet) {
    (void)destination; (void)channel;
    enet_packet_destroy(packet);
    return 0;
}
static void fixtureFlush(ENetHost* host) { (void)host; }
static int fixtureAudioPort(void) { return 0; }
static void fixtureLog(const char* format, ...) {
    va_list arguments;
    va_start(arguments, format);
    vfprintf(stderr, format, arguments);
    va_end(arguments);
}

static bool packetInput(int index, size_t size, bool header) {
    char* bytes = malloc(size);
    if (bytes == NULL) return false;
    memset(bytes, 'x', size);
    if (header) {
        const char response[] = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n";
        if (size < sizeof(response) - 1) { free(bytes); return false; }
        memcpy(bytes, response, sizeof(response) - 1);
    }
    incoming[index] = enet_packet_create(bytes, size, ENET_PACKET_FLAG_RELIABLE);
    free(bytes);
    if (incoming[index] == NULL) return false;
    incoming[index]->freeCallback = packetFreed;
    return true;
}

int main(int argc, char** argv) {
    if (argc < 3) return 2;
    const char* mode = argv[1];
    ListenerCallbacks.logMessage = fixtureLog;
    if (initializePlatform() != 0) return 3;
    int result;
    if (strcmp(mode, "enet") == 0) {
        if (argc != 4) return 2;
        size_t first = strtoull(argv[2], NULL, 10);
        size_t second = strtoull(argv[3], NULL, 10);
        if (first < 32 || first > 2 * 1024 * 1024 || second > 2 * 1024 * 1024) return 2;
        if (!packetInput(0, first, true) || (second && !packetInput(1, second, false))) return 3;
        RTSP_MESSAGE request, response;
        createRtspRequest(&request, NULL, 0, "DESCRIBE", "rtsp://127.0.0.1", "RTSP/1.0", 0, NULL, NULL, 0);
        int error;
        result = transactRtspMessageEnet(&request, &response, second != 0, &error);
        if (result) freeMessage(&response);
        freeMessage(&request);
        printf("ret=%d error=%d delivered=%d destroyed=%d maxAllocation=%zu\n", result, error, delivered, destroyed, maximumAllocation);
        for (int i = 0; i < 2; i++) if (incoming[i]) enet_packet_destroy(incoming[i]);
    }
    else {
        unsigned long port = strtoul(argv[2], NULL, 10);
        if (port == 0 || port > 65535) return 2;
        struct sockaddr_in* address = (struct sockaddr_in*)&RemoteAddr;
        address->sin_family = AF_INET;
        address->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        AddrLen = sizeof(*address);
        RtspPortNumber = (uint16_t)port;
        if (strcmp(mode, "tcp") == 0) {
            RTSP_MESSAGE request, response;
            createRtspRequest(&request, NULL, 0, "OPTIONS", "rtsp://127.0.0.1", "RTSP/1.0", 0, NULL, NULL, 0);
            int error;
            result = transactRtspMessageTcp(&request, &response, &error);
            if (result) freeMessage(&response);
            freeMessage(&request);
            printf("ret=%d error=%d maxAllocation=%zu\n", result, error, maximumAllocation);
        }
        else if (strcmp(mode, "handshake") == 0) {
            SERVER_INFORMATION server;
            LiInitializeServerInformation(&server);
            server.address = "127.0.0.1";
            server.serverCodecModeSupport = SCM_H264;
            AppVersionQuad[0] = 7; AppVersionQuad[1] = 1;
            AppVersionQuad[2] = 500; AppVersionQuad[3] = -1;
            LiInitializeStreamConfiguration(&StreamConfig);
            StreamConfig.width = 1920; StreamConfig.height = 1080;
            StreamConfig.fps = 60; StreamConfig.bitrate = 10000;
            StreamConfig.packetSize = 1024;
            StreamConfig.streamingRemotely = STREAM_CFG_LOCAL;
            StreamConfig.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
            StreamConfig.supportedVideoFormats = VIDEO_FORMAT_H264;
            result = performRtspHandshake(&server);
            printf("ret=%d maxAllocation=%zu\n", result, maximumAllocation);
        }
        else return 2;
    }
    cleanupPlatform();
    return 0;
}
