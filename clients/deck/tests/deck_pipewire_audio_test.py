#!/usr/bin/env python3
"""Run real playback against a private PipeWire daemon with only a null sink.

No session manager, physical devices, host services, or game host are involved.
"""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


CONFIG = """
context.properties = {
    core.daemon = true
    core.name = nova-audio-test
    default.clock.rate = 48000
    default.clock.quantum = 240
}
context.spa-libs = {
    audio.convert.* = audioconvert/libspa-audioconvert
    support.* = support/libspa-support
}
context.modules = [
    { name = libpipewire-module-protocol-native }
    { name = libpipewire-module-spa-node-factory }
    { name = libpipewire-module-client-node }
    { name = libpipewire-module-metadata }
    { name = libpipewire-module-adapter }
    { name = libpipewire-module-link-factory }
    { name = libpipewire-module-access }
]
context.objects = [
    { factory = spa-node-factory args = {
        factory.name = support.node.driver
        node.name = Test-Driver
        priority.driver = 20000
    } }
    { factory = adapter args = {
        factory.name = support.null-audio-sink
        node.name = nova-test-sink
        media.class = Audio/Sink
        audio.position = [ FL FR ]
        adapter.auto-port-config = { mode = dsp monitor = true position = preserve }
    } }
]
"""

CLIENT_CONFIG = """
context.spa-libs = {
    audio.convert.* = audioconvert/libspa-audioconvert
    support.* = support/libspa-support
}
context.modules = [
    { name = libpipewire-module-protocol-native }
    { name = libpipewire-module-client-node }
    { name = libpipewire-module-adapter }
    { name = libpipewire-module-metadata }
]
stream.properties = {
    adapter.auto-port-config = { mode = dsp position = preserve }
}
"""


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def main():
    binary, pipewire, pw_link = sys.argv[1:4]
    disconnect = "--disconnect" in sys.argv[4:]
    channels = int(sys.argv[sys.argv.index("--channels") + 1]) if "--channels" in sys.argv else 2
    positions = {2: ("FL", "FR"), 6: ("FL", "FR", "FC", "LFE", "RL", "RR"),
                 8: ("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR")}[channels]
    with tempfile.TemporaryDirectory(prefix="nova-audio-test-") as directory:
        root = Path(directory)
        config = root / "pipewire.conf"
        config.write_text(CONFIG.replace("[ FL FR ]", "[ " + " ".join(positions) + " ]"), encoding="utf-8")
        (root / "client.conf").write_text(CLIENT_CONFIG, encoding="utf-8")
        env = dict(os.environ, XDG_RUNTIME_DIR=directory, PIPEWIRE_RUNTIME_DIR=directory,
                   PIPEWIRE_CONFIG_DIR=directory,
                   PIPEWIRE_REMOTE="nova-audio-test", NOVA_AUDIO_PRIVATE_TEST="1", NOVA_AUDIO_TEST_CHANNELS=str(channels))
        server = player = None
        with (root / "server.log").open("w+") as server_log, (root / "player.log").open("w+") as player_log:
            try:
                server = subprocess.Popen([pipewire, "-c", str(config)], env=env,
                                          stdout=server_log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 5
                while not (root / "nova-audio-test").exists():
                    if server.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("private PipeWire daemon did not start")
                    time.sleep(0.02)
                mode = "--private-server-disconnect" if disconnect else "--private-server"
                player = subprocess.Popen([binary, mode], env=env,
                                          stdout=player_log, stderr=subprocess.STDOUT)
                # PipeWire client adapters expose DSP ports once the stream is active.
                for channel in positions:
                    deadline = time.monotonic() + 3
                    while True:
                        linked = subprocess.run([pw_link, f"nova-audio:output_{channel}",
                                                 f"nova-test-sink:playback_{channel}"],
                                                env=env, capture_output=True, timeout=3)
                        if linked.returncode == 0:
                            break
                        if player.poll() is not None or time.monotonic() > deadline:
                            for flag in ("-o", "-i"):
                                ports = subprocess.run([pw_link, flag], env=env, capture_output=True, timeout=3)
                                print(ports.stdout.decode(), file=sys.stderr)
                            raise RuntimeError("private audio ports did not link: " + linked.stderr.decode())
                        time.sleep(0.02)
                if disconnect:
                    time.sleep(0.5)
                    stop(server)
                if player.wait(timeout=8) != 0:
                    raise RuntimeError("private PipeWire playback assertions failed")
                player_log.seek(0)
                print(f"channels={channels}, linked positions={','.join(positions)}\n" + player_log.read())
            except Exception:
                for label, log in (("server", server_log), ("player", player_log)):
                    log.flush()
                    log.seek(0)
                    print(f"{label}:\n{log.read()}", file=sys.stderr)
                raise
            finally:
                stop(player)
                stop(server)


if __name__ == "__main__":
    main()
