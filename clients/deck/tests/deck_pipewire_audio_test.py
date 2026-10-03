#!/usr/bin/env python3
"""Run real playback against a private PipeWire daemon with only a null sink.

No session manager, physical devices, host services, or game host are involved.
"""

import json
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


def process_identity(pid):
    proc = Path('/proc') / str(pid)
    fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': pid, 'parent': int(fields[1]), 'birth': int(fields[19]),
            'uid': proc.stat().st_uid}


def emit_owned_player_diagnostics(pid, birth):
    """Read only the fixture's own child; never environment or arbitrary peers."""
    report = {'pid': pid, 'expected_birth': birth, 'threads': [],
              'deadline_seconds': 1, 'thread_limit': 32}
    began = time.monotonic()
    try:
        identity = process_identity(pid)
        report['identity_before'] = identity
        if (identity['birth'] != birth or identity['parent'] != os.getppid()
                or identity['uid'] != os.getuid()):
            report['status'] = 'OWNED_CHILD_IDENTITY_REFUSED'
        else:
            tasks = sorted((Path('/proc') / str(pid) / 'task').iterdir(),
                           key=lambda p: int(p.name))
            report['observed_thread_count'] = len(tasks)
            report['thread_limit_reached'] = len(tasks) > 32
            for task in tasks[:32]:
                if time.monotonic() - began >= 1:
                    report['deadline_reached'] = True
                    break
                thread = {'tid': int(task.name), 'reads': {}}
                for name in ('comm', 'status', 'wchan', 'stack', 'syscall'):
                    if time.monotonic() - began >= 1:
                        report['deadline_reached'] = True
                        break
                    try:
                        with (task / name).open('rb') as source:
                            raw = source.read(4097)
                        thread['reads'][name] = {
                            'bytes': raw[:4096].decode('utf-8', errors='replace'),
                            'truncated': len(raw) > 4096}
                    except OSError as exc:
                        thread['reads'][name] = {'error': type(exc).__name__,
                                                 'errno': exc.errno}
                report['threads'].append(thread)
            report['identity_after'] = process_identity(pid)
            report['status'] = ('CAPTURED_OWNED_CHILD_THREADS' if
                                report['identity_after'] == identity else
                                'OWNED_CHILD_IDENTITY_CHANGED_CAPTURE_UNQUALIFIED')
    except (OSError, ValueError, IndexError) as exc:
        report['status'] = 'OWNED_CHILD_DIAGNOSTICS_UNAVAILABLE'
        report['error'] = type(exc).__name__
    report['elapsed_seconds'] = time.monotonic() - began
    print(json.dumps(report, sort_keys=True))


def capture_owned_player_diagnostics(player, birth):
    if birth is None or player.poll() is not None:
        print('owned-player-diagnostics: unavailable identity or child already exited',
              file=sys.stderr)
        return
    # The helper has a one-second read budget and a two-second parent watchdog.
    # Neither changes the existing eight-second player deadline or its result.
    try:
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()),
                                 '--owned-player-diagnostics', str(player.pid), str(birth)],
                                capture_output=True, timeout=2)
        print('owned-player-diagnostics returncode=' + str(result.returncode),
              file=sys.stderr)
        print(result.stdout.decode('utf-8', errors='replace'), file=sys.stderr)
        if result.stderr:
            print(result.stderr.decode('utf-8', errors='replace'), file=sys.stderr)
    except subprocess.TimeoutExpired as exc:
        print('owned-player-diagnostics: two-second watchdog expired', file=sys.stderr)
        for raw in (exc.stdout, exc.stderr):
            if raw:
                print(raw.decode('utf-8', errors='replace'), file=sys.stderr)
    except OSError as exc:
        print('owned-player-diagnostics: ' + type(exc).__name__, file=sys.stderr)



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
        player_birth = None
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
                if disconnect:
                    try:
                        player_birth = process_identity(player.pid)['birth']
                    except (OSError, ValueError, IndexError):
                        pass  # A missing diagnostic identity never changes playback assertions.
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
                try:
                    returncode = player.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    if disconnect:
                        capture_owned_player_diagnostics(player, player_birth)
                    raise
                if returncode != 0:
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
    if len(sys.argv) == 4 and sys.argv[1] == '--owned-player-diagnostics':
        emit_owned_player_diagnostics(int(sys.argv[2]), int(sys.argv[3]))
    else:
        main()
