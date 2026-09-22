"""Device removal/route return and daemon restart on an isolated null-sink graph."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from deck_pipewire_audio_test import CONFIG, CLIENT_CONFIG, stop


def main():
    binary, pipewire, pw_link, pw_cli, pw_dump, mode, count = sys.argv[1:]
    channels = int(count)
    positions = {2: ("FL", "FR"), 6: ("FL", "FR", "FC", "LFE", "RL", "RR"),
                 8: ("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR")}[channels]
    with tempfile.TemporaryDirectory(prefix="nova-audio-recovery-") as directory:
        root = Path(directory)
        config = root / "pipewire.conf"
        config_text = CONFIG.replace("[ FL FR ]", "[ " + " ".join(positions) + " ]")
        # A second synthetic device; never enumerate or touch physical hardware.
        sink = config_text[config_text.index("    { factory = adapter args"):config_text.rindex("\n]")]
        config_text = config_text[:config_text.rindex("\n]")] + sink.replace("nova-test-sink", "nova-second-sink") + "\n]\n"
        config.write_text(config_text)
        (root / "client.conf").write_text(CLIENT_CONFIG)
        env = dict(os.environ, XDG_RUNTIME_DIR=directory, PIPEWIRE_RUNTIME_DIR=directory,
                   PIPEWIRE_CONFIG_DIR=directory, PIPEWIRE_REMOTE="nova-audio-test",
                   NOVA_AUDIO_PRIVATE_TEST="1", NOVA_AUDIO_TEST_CHANNELS=str(channels))
        server = player = None
        with (root / "server.log").open("w+") as server_log, (root / "player.log").open("w+") as player_log:
            def command(*args):
                return subprocess.run(args, env=env, check=True, text=True, capture_output=True, timeout=4).stdout

            def status():
                lines = (root / "player.log").read_text().splitlines()
                reports = [line.split()[1:] for line in lines if line.startswith("status ")]
                if not reports:
                    return {}
                return dict(zip(("decoded", "submitted", "dropped", "recoveries", "attempts", "ready",
                                 "flowing", "recovering", "active", "discarded"), map(int, reports[-1]), strict=True))

            def wait(predicate, timeout=6):
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    if player is not None and player.poll() is not None:
                        raise AssertionError("audio fixture exited during recovery")
                    current = status()
                    if predicate(current):
                        return current
                    time.sleep(.02)
                raise AssertionError(f"audio recovery timed out: {status()}")

            def start_server():
                process = subprocess.Popen([pipewire, "-c", str(config)], env=env,
                                           stdout=server_log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 4
                while not (root / "nova-audio-test").exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        stop(process)
                        raise AssertionError("private audio server failed to start")
                    time.sleep(.02)
                return process

            def link(sink):
                for channel in positions:
                    deadline = time.monotonic() + 6
                    while True:
                        result = subprocess.run([pw_link, f"nova-audio:output_{channel}", f"{sink}:playback_{channel}"],
                                                env=env, capture_output=True, timeout=3)
                        if result.returncode == 0:
                            break
                        if player.poll() is not None or time.monotonic() > deadline:
                            raise AssertionError("private recovery ports did not link")
                        time.sleep(.03)

            try:
                server = start_server()
                player = subprocess.Popen([binary, "--private-recovery"], env=env, stdin=subprocess.PIPE,
                                          stdout=player_log, stderr=subprocess.STDOUT)
                link("nova-test-sink")
                baseline = wait(lambda s: s.get("submitted", 0) > 4000 and s.get("flowing"))
                if mode == "restart":
                    stop(server)
                    outage = wait(lambda s: s.get("recovering") and not s.get("ready") and s.get("attempts", 0) >= 2)
                    assert outage["active"] and outage["decoded"] > baseline["decoded"]
                    server = start_server()
                    link("nova-second-sink")
                    recovered = wait(lambda s: s.get("recoveries", 0) > baseline["recoveries"]
                                     and s.get("submitted", 0) > outage["submitted"] + 4000 and s.get("flowing"))
                    assert recovered["dropped"] > baseline["dropped"]
                    # End during another outage: retries must stop and stay stopped.
                    stop(server)
                    wait(lambda s: s.get("recovering") and not s.get("ready"))
                else:
                    graph = json.loads(command(pw_dump))
                    sink_id = next(node["id"] for node in graph if node.get("info", {}).get("props", {}).get("node.name") == "nova-test-sink")
                    command(pw_cli, "destroy", str(sink_id))
                    outage = wait(lambda s: s.get("recovering") and not s.get("flowing"))
                    later = wait(lambda s: s.get("decoded", 0) >= outage["decoded"] + 12000)
                    assert later["dropped"] > outage["dropped"] and later["submitted"] == outage["submitted"]
                    assert later["attempts"] == baseline["attempts"], "paused graph was mistaken for a lost daemon"
                    link("nova-second-sink")
                    recovered = wait(lambda s: s.get("flowing") and not s.get("recovering")
                                     and s.get("submitted", 0) > later["submitted"] + 4000)
                    assert recovered["recoveries"] == baseline["recoveries"], "route return recreated the stream"
                player.stdin.write(b"q")
                player.stdin.flush()
                assert player.wait(timeout=5) == 0, "audio stop/cleanup assertions failed"
                print(f"Private {mode}, channels={channels}: {json.dumps(recovered, sort_keys=True)}")
            except Exception:
                print((root / "player.log").read_text(), file=sys.stderr)
                print((root / "server.log").read_text(), file=sys.stderr)
                raise
            finally:
                stop(player)
                stop(server)


if __name__ == "__main__":
    main()
