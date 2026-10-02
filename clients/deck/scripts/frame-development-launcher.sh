#!/bin/sh
set -eu
test "$(uname -m)" = aarch64 || { echo 'This launcher requires the ARM64 Frame development build.' >&2; exit 1; }
flatpak info --user --arch=aarch64 com.papi_ux.Nova//frame-dev >/dev/null
# Flatpak has no video-only device permission. This process-only development
# option exposes video nodes; it never writes a persistent permission override.
# Separate configuration/data keep the regular Nova settings and pairing intact.
exec flatpak run --user --arch=aarch64 --branch=frame-dev --device=all \
    --env=NOVA_DECK_FRAME_V4L2=1 \
    --env=XDG_CONFIG_HOME=/var/config/frame-dev \
    --env=XDG_DATA_HOME=/var/data/frame-dev \
    --env=XDG_CACHE_HOME=/var/cache/frame-dev \
    --env=NOVA_DECK_IDENTITY_DIR=/var/config/frame-dev/nova-deck \
    com.papi_ux.Nova --standalone --experimental-vulkan-stream "$@"
