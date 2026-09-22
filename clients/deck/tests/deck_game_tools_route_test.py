"""Exercise production game tools and canonical launch routing against an isolated paired PC."""
import base64
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit, parse_qs
from deck_artwork_fixture import artwork_png
from deck_ui_input import wait_for_ui_observations


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=15).stdout.strip()


def main():
    binary = str(Path(sys.argv[1]).resolve())
    display_only = "--display-settings" in sys.argv
    space_only = "--space-artwork" in sys.argv
    sync_only = "--sync-surface" in sys.argv
    hub_only = "--settings-hub" in sys.argv
    settings_offline = [False]
    with tempfile.TemporaryDirectory(prefix="nova-game-tools-") as temp, ExitStack() as stack:
        root = Path(temp)
        for name in ("client", "host"):
            command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=Nova fixture",
                    "-out", str(root/f"{name}.crt"), "-keyout", str(root/f"{name}.key"))
        profile = {"stream_display_mode": "headless_stream", "display_mode": "1280x800x60", "target_bitrate_kbps": 20000, "disconnect_resume_timeout_seconds": 300}
        settings = {"version": 1, "revision": "one", "desired": dict(profile), "effective": dict(profile), "relaunch_required": False,
                    "capabilities": {"disconnect_resume_timeout_control": True, "display_mode_override": True, "target_bitrate_override": True,
                        "session_encoder_override": True, "encoders": [{"value": "vaapi", "label": "AMD / VA-API", "available": True}],
                        "modes": [{"value": "headless_stream", "label": "Private Stream", "available": True, "session_overridable": True}]}}
        game = {"id": "game-7", "app_id": 7, "name": "Moonlit Harbor", "source": "steam", "installed": True,
                "steam_launch": {"available": True, "mode": "direct", "allowed_modes": ["direct", "big-picture"]},
                "artwork": {"version": 1, "revision": "one", "override": {"logo_transform": {"scale": 1.2, "x": .4, "y": .6}}, "assets": {kind: {"cached": True,
                    "url": f"/polaris/v1/games/game-7/artwork/{kind}"} for kind in ("poster", "hero", "logo", "icon")}}}
        requests = []
        writes = []
        settings_writes = []
        drop_settings_reply = [False]
        space_selected = ["room"]
        space_allowed = [True]
        space_reply = ["partial_failure"]
        if space_only:
            game.update(id="space.room.42", app_id=1347244801, space={"id":"room","name":"Game room","target":"42"})
            for kind, asset in game["artwork"]["assets"].items():
                asset["url"] = f"/polaris/v1/games/space.room.42/space-artwork/{kind}"
        def spaces():
            return {"schema":1,"status":True,"enabled":True,"available":True,"can_switch":True,"desktop_allowed":True,
                    "selected_space_id":space_selected[0],"spaces":[{"id":"room","name":"Game room","state":"ready","selected":space_selected[0]=="room","library_enabled":True,"can_open":True}] if space_allowed[0] else []}
        candidate = {"provider": "steamgriddb", "provider_game_id": "42", "title": "Moonlit Harbor"}
        token = "a"*32

        class Host(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, value, status=200):
                body = value if isinstance(value, bytes) else json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                path = urlsplit(self.path).path
                requests.append(("GET", self.path))
                if not isinstance(self.connection, ssl.SSLSocket):
                    return self.reply(f'<root status_code="200"><HttpsPort>{tls.server_port}</HttpsPort></root>'.encode())
                if path == "/polaris/v1/capabilities":
                    return self.reply({"server": "polaris", "features": {"game_library": True, "client_settings_v1": True,
                        "resolved_profile_provenance_v1": True,"spaces_v1":space_only}, "capture": {"codecs": ["h264"], "max_fps": 60}})
                if path == "/polaris/v1/spaces":
                    if space_only: return self.reply(spaces())
                    return self.reply({}, 404)
                if path == "/polaris/v1/spaces/library" and space_only:
                    assert parse_qs(urlsplit(self.path).query)=={"space_id":["room"]}
                    return self.reply({"schema":1,"status":True,"space_id":"room","library_available":True,"games":[game] if space_allowed[0] else []})
                if path == "/polaris/v1/games":
                    return self.reply({"games": [game], "total": 1})
                if path == "/polaris/v1/session/status":
                    return self.reply({"state": "idle", "streaming_active": False, "game_uuid": ""})
                if path == "/polaris/v1/client-settings":
                    return self.reply({} if settings_offline[0] else settings, 503 if settings_offline[0] else 200)
                if path == "/polaris/v1/optimize":
                    values = {"display_mode": "1280x800x60", "display_width": 1280, "display_height": 800,
                              "target_fps": 60, "target_bitrate_kbps": 20000, "preferred_codec": "h264", "hdr": False}
                    fields = {key: {"value": value, "source": "paired_client", "reason_code": "paired_setting", "locked": False,
                                    "normalized": False} for key, value in values.items()}
                    return self.reply({"source": "deterministic_preset_v1", "resolved_profile": {"policy_version": 1,
                        "preset": "auto", "preset_label": "Auto", "fields": fields}})
                if path.endswith("/artwork/candidates"):
                    return self.reply({"status": True, "candidates": [dict(candidate, preview={"poster": f"/polaris/v1/games/game-7/artwork/candidate/{token}/poster"})]})
                if "/artwork/" in path or "/space-artwork/" in path or path.endswith("/cover"):
                    kind = path.split("/")[-1]
                    return self.reply(artwork_png(kind if kind != "cover" else "poster", "game-7", game["artwork"]["revision"]))
                if path == "/serverinfo":
                    return self.reply(b'<root status_code="200"><appversion>7.1.431.0</appversion><GfeVersion>3.23.0.74</GfeVersion><ServerCodecModeSupport>1</ServerCodecModeSupport><currentgame>0</currentgame><PairStatus>1</PairStatus></root>')
                if path == "/launch":
                    # Reaching this paired canonical request proves routing. Never
                    # start real media, input, Steam, or a game in this fixture.
                    return self.reply(b'<root status_code="503"><gamesession>0</gamesession></root>')
                return self.reply({}, 404)

            def do_POST(self):
                path = urlsplit(self.path).path
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                requests.append(("POST", path))
                if space_only and path=="/polaris/v1/games/space.room.42/space-artwork/resolve":
                    assert body=={"policy":"missing_or_stale"} and space_allowed[0] and space_selected[0]=="room"
                    writes.append(path)
                    if space_reply[0]=="lost":
                        self.close_connection=True
                        return
                    manifest=dict(game["artwork"])
                    manifest["resolution"]={"status":space_reply[0],"requested_kinds":["hero"],"remaining_kinds":["hero"] if space_reply[0]=="partial_failure" else []}
                    return self.reply(manifest)
                if path == "/polaris/v1/client-settings" and (display_only or sync_only):
                    assert body == {"display_mode":"1280x800x60","target_bitrate_kbps":20000} if sync_only else body in ({"disconnect_resume_timeout_seconds": 600}, {"disconnect_resume_timeout_seconds": 1800}), body
                    settings_writes.append(body)
                    settings["desired"].update(body)
                    settings["revision"] = str(len(settings_writes))
                    if drop_settings_reply[0]:
                        self.connection.shutdown(2)
                        self.connection.close()
                        return
                    return self.reply(settings)
                if "/artwork/choices/" in path:
                    kind = path.split("/")[-1]
                    return self.reply({"status": True, "kind": kind, "choices": [{"selection_token": token,
                        "preview": f"/polaris/v1/games/game-7/artwork/candidate/{token}/{kind}", "expires_at": int(time.time())+600}]})
                if path.endswith("/artwork/match"):
                    assert body["provider_game_id"] == "42" and body["selections"] == {"poster": token}
                    writes.append(path); game["artwork"]["revision"] = "two"
                    return self.reply({"status": True, "artwork": game["artwork"]})
                if path.endswith("/steam-launch-mode"):
                    writes.append(path); game["steam_launch"]["mode"] = body["mode"]
                    return self.reply({"status": True})
                return self.reply({}, 404)

        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(root/"host.crt", root/"host.key")
        ctx.load_verify_locations(root/"client.crt"); ctx.verify_mode = ssl.CERT_REQUIRED
        http = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Host))
        tls = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Host)); tls.socket = ctx.wrap_socket(tls.socket, server_side=True)
        for server in (http, tls):
            threading.Thread(target=server.serve_forever, daemon=True).start(); stack.callback(server.shutdown)
        identity = root/"identity.json"
        identity.write_text(json.dumps({"version": 1, "certificate": (root/"client.crt").read_text(), "private_key": (root/"client.key").read_text(),
            "hosts": [{"uuid": "host", "name": "Living Room PC", "address": "127.0.0.1", "http_port": http.server_port,
                "https_port": tls.server_port, "server_certificate": (root/"host.crt").read_text()}]})); identity.chmod(0o600)
        original_identity = identity.read_bytes()
        env = dict(os.environ, NOVA_DECK_IDENTITY_DIR=str(root), NOVA_DECK_GAMEPAD_DEVICE="/dev/null", XDG_CONFIG_HOME=str(root/"config"),
                   QT_QPA_PLATFORM="xcb", QT_QUICK_BACKEND="software", QT_FORCE_STDERR_LOGGING="1")
        observation = root/"state.json"; log = root/"app.log"
        output = stack.enter_context(log.open("w"))
        def start(extra=()):
            observation.unlink(missing_ok=True)
            return subprocess.Popen([binary, "--standalone", "--frontend-smoke-codecs", "--frontend-smoke-library-state", str(observation),
                "--frontend-smoke-exit-after-ms", "35000", *extra], env=env, stdout=output, stderr=output)
        app = start()
        def state():
            try: return json.loads(observation.read_text())
            except (FileNotFoundError, json.JSONDecodeError): return {}
        def wait(predicate, timeout=10):
            deadline = time.monotonic()+timeout
            while time.monotonic() < deadline:
                if app.poll() is not None: raise AssertionError(f"Nova exited {app.returncode}: {log.read_text()}")
                if predicate(state()): return state()
                time.sleep(.03)
            raise AssertionError(f"UI did not settle: {state()}\n{log.read_text()}")
        def keys(*values):
            for value in values:
                command("xdotool", "key", "--clearmodifiers", value)
                wait_for_ui_observations(observation, app)
        try:
            wait(lambda s: s.get("game") == game["id"])
            window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
            command("xdotool", "windowfocus", "--sync", window)
            if hub_only:
                def hub(s=None): return (state() if s is None else s).get("settingsHub", {})
                def click(point): command("xdotool", "mousemove", str(round(point["x"])), str(round(point["y"])), "click", "1")
                wait(lambda s: not s.get("busy") and not s.get("playSetup", {}).get("hostPlan", {}).get("busy", False))
                def settings_read_count(): return sum(method == "GET" and urlsplit(path).path == "/polaris/v1/client-settings" for method, path in requests)
                initial_settings_reads = settings_read_count()
                saved_game = state()["game"]
                click(state()["settingsCenter"])
                wait(lambda s: hub(s).get("opened") and s.get("focus") == "settings-category-all")
                # Enter Audio from the category rail. Selecting a category never changes a preference.
                keys("Down", "Down", "Return", "Right", "Return", "Down", "Down", "Return")
                wait(lambda s: hub(s).get("values", {}).get("channels") == 8 and s.get("focus") == "settings-row-channels")
                keys("Down", "Return")
                wait(lambda s: hub(s).get("values", {}).get("hostAudio") is True)
                keys("Up", "Right", "Return")
                wait(lambda s: hub(s).get("values", {}).get("channels") == 2)
                assert hub()["values"]["hostAudio"], "one audio reset erased another choice"
                # Navigate to global search; typed search and touch keyboard share one query.
                keys("Up"); wait(lambda s: s.get("focus") == "settings-search")
                command("xdotool", "type", "--clearmodifiers", "text size")
                wait(lambda s: hub(s).get("keys") == ["text"])
                keys("Down", "Return", "Down", "Down", "Return")
                wait(lambda s: s.get("fontScale") == 1.3 and s.get("focus") == "settings-row-text")
                command("xdotool", "windowsize", window, "960", "600")
                wait(lambda s: s.get("focusVisible") and hub(s).get("width") == 960 and hub(s).get("height") == 600)
                assert hub()["back"]["x"] < 960 and hub()["back"]["y"] < 600
                capture_dir = os.environ.get("NOVA_DECK_SETTINGS_CAPTURE_DIR")
                if capture_dir:
                    Path(capture_dir).mkdir(parents=True, exist_ok=True)
                    command("import", "-window", window, str(Path(capture_dir)/"settings-search-app-960-large.png"))
                assert not settings_writes and not writes
                assert settings_read_count() == initial_settings_reads, "browsing Settings fetched host settings"
                keys("Escape"); wait(lambda s: not hub(s).get("opened") and s.get("focus") == "library-settings")
                assert state()["game"] == saved_game
                # Open the same guarded Polaris Sync view from the hub, then return to the exact row.
                keys("Return", *(["Down"] * 4), "Return", "Right", "Return")
                wait(lambda s: hub(s).get("host", {}).get("opened") and hub(s)["host"]["status"].get("canChange"))
                keys("Escape"); wait(lambda s: s.get("focus") == "settings-row-sync")
                keys("Escape"); wait(lambda s: not hub(s).get("opened"))
                assert not settings_writes and identity.read_bytes() == original_identity
                assert not any(method == "POST" or urlsplit(path).path == "/launch" for method, path in requests)
                app.terminate(); app.wait(timeout=8); app = start()
                wait(lambda s: s.get("game") == saved_game)
                assert state()["fontScale"] == 1.3 and hub()["values"]["channels"] == 2 and hub()["values"]["hostAudio"]
                assert not any(v in log.read_text() for v in ("ReferenceError", "TypeError", "Cannot assign", "is not a type")), log.read_text()
                print("Production Settings entry, category/search, reset scope, persistence, Sync routing, unchanged pairing and zero POSTs passed.")
                return
            if sync_only:
                def sync(s=None): return (state() if s is None else s).get("polarisSync", {})
                def click(point): command("xdotool", "mousemove", str(round(point["x"])), str(round(point["y"])), "click", "1")
                click(state()["systemCenter"]); wait(lambda s: s.get("systemOpen"))
                keys("Down", "Down"); wait(lambda s: s.get("focus") == "library-polaris-sync")
                keys("Return"); wait(lambda s: sync(s).get("opened") and sync(s).get("status", {}).get("canChange"))
                assert sync()["syncView"] and not sync()["readOnly"] and not state().get("nativePreviewOpen")
                capture_dir=os.environ.get("NOVA_DECK_SYNC_CAPTURE_DIR")
                if capture_dir:
                    Path(capture_dir).mkdir(parents=True,exist_ok=True)
                    command("import","-window",window,str(Path(capture_dir)/"polaris-sync-library-1280.png"))
                assert not settings_writes
                # A host-side profile edit is observed without selecting a game or enabling automatic writes.
                settings["desired"]["display_mode"]="1920x1080x60"; settings["revision"]="two"
                wait(lambda s: sync(s).get("status", {}).get("settings", {}).get("desiredDisplay")=="1920x1080x60",timeout=5)
                assert sync()["status"]["profileState"]=="Different from Nova" and not settings_writes
                # Back -> display -> edit defaults -> Match -> Send, using real controller navigation.
                click(sync()["controls"]["back"]); wait(lambda s: not sync(s).get("opened"))
                wait(lambda s: s.get("focus")=="library-polaris-sync")
                keys("Return"); wait(lambda s: sync(s).get("status", {}).get("canChange"))
                keys("Up","Down","Down","Down")
                wait(lambda s: s.get("focus")=="host-profile-send")
                keys("Return"); wait(lambda s: len(settings_writes)==1 and not sync(s).get("status",{}).get("busy",True))
                assert settings_writes==[{"display_mode":"1280x800x60","target_bitrate_kbps":20000}]
                settings_offline[0]=True; click(sync()["controls"]["refresh"])
                wait(lambda s: sync(s).get("status",{}).get("phase")=="unavailable")
                assert not sync()["status"]["settings"] and not sync()["status"]["canChange"]
                settings_offline[0]=False; click(sync()["controls"]["refresh"])
                wait(lambda s: sync(s).get("status",{}).get("canChange"))
                assert len(settings_writes)==1 and identity.read_bytes()==original_identity
                assert not any(urlsplit(path).path=="/launch" for method,path in requests)
                assert [(method,urlsplit(path).path) for method,path in requests if method=="POST"]==[("POST","/polaris/v1/client-settings")]
                keys("Escape"); wait(lambda s: not sync(s).get("opened"))
                wait(lambda s: s.get("focus")=="library-polaris-sync")
                keys("Escape"); wait(lambda s: not s.get("systemOpen"))
                assert state().get("game")==game["id"]
                assert not any(v in log.read_text() for v in ("ReferenceError","TypeError","Cannot assign","is not a type")),log.read_text()
                print("Production library Polaris Sync, scoped profile POST, polling, offline recovery and focus return passed.")
                return
            if space_only:
                def artwork(s=None): return (state() if s is None else s).get("spaceArtwork", {})
                def status(s=None): return artwork(s).get("status", {})
                keys("Return", "Right", "Return")
                wait(lambda s: artwork(s).get("opened") and status(s).get("canRefreshArtwork"))
                keys("Down", "Down", "Down")
                wait(lambda s: s.get("focus")=="space-artwork-refresh")
                keys("Return")
                wait(lambda s: len(writes)==1 and status(s).get("artworkResolution",{}).get("resolution")=="partial_failure" and status(s).get("canRefreshArtwork"))
                assert "backdrop" in status()["copy"]
                # A confirmed refresh also replaces the library snapshot and
                # checks this game's access again before the next interaction.
                time.sleep(.3)
                wait(lambda s: not s.get("busy") and status(s).get("canRefreshArtwork"))
                # A reply can be lost after a cache update. Do not POST again.
                space_reply[0]="lost"
                wait(lambda s: s.get("focus")=="space-artwork-refresh")
                keys("Return")
                wait(lambda s: status(s).get("uncertain") and s.get("focus")=="space-artwork-check")
                assert len(writes)==2 and not status()["canRefreshArtwork"]
                keys("Return")
                wait(lambda s: status(s).get("canRefreshArtwork") and not status(s).get("uncertain"))
                assert len(writes)==2
                time.sleep(.3)
                wait(lambda s: not s.get("busy") and status(s).get("canRefreshArtwork"))
                # A changed selected destination revokes this view before POST.
                space_selected[0]="desktop"
                wait(lambda s: s.get("focus")=="space-artwork-refresh")
                keys("Return")
                wait(lambda s: not status(s).get("busy") and not status(s).get("canRefreshArtwork"))
                assert len(writes)==2
                space_selected[0]="room"
                # Use the fixed Check again control through its published center.
                point=artwork()["controls"]["check"]
                command("xdotool","mousemove",str(point["x"]),str(point["y"]),"click","1")
                wait(lambda s: status(s).get("canRefreshArtwork"))
                assert len(writes)==2 and identity.read_bytes()==original_identity
                # The ordinary library snapshot reads client settings as metadata;
                # the only writes in this entire scenario are the two refreshes.
                assert [(method,path) for method,path in requests if method=="POST"]==[("POST","/polaris/v1/games/space.room.42/space-artwork/resolve")]*2
                unexpected=[(method,path) for method,path in requests if "/artwork/" in path or path.startswith("/polaris/v1/optimize") or path=="/launch"]
                assert not unexpected,unexpected
                assert not any(v in log.read_text() for v in ("ReferenceError","TypeError","Cannot assign","is not a type")),log.read_text()
                print("Production Space preview, partial receipt, no replay, GET reconciliation and changed-destination rejection passed.")
                return
            keys("Return", "Return")
            wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("hostPlan", {}).get("plan", {}).get("fields"))
            if display_only:
                def type_number(value):
                    keys("ctrl+a")
                    command("xdotool", "type", "--clearmodifiers", "--delay", "1", value)
                def focus(name):
                    return wait(lambda s: s.get("focus") == name)
                def host_state(s=None):
                    return (state() if s is None else s).get("playSetup", {}).get("hostDefaults", {})
                def click(point):
                    command("xdotool", "mousemove", str(point["x"]), str(point["y"]), "click", "1")
                keys("Down", "Return", "Down", "Down", "Down", "Down", "Return")
                focus("stream-profile-width"); type_number("2560"); keys("Down"); type_number("1440"); keys("Down", "Return")
                wait(lambda s: s["playSetup"]["configuration"]["width"] == 2560 and not s["playSetup"]["choicesOpen"])
                keys("Down", "Return", "Down", "Return")
                focus("stream-profile-fps"); type_number("45"); keys("Down", "Return")
                wait(lambda s: s["playSetup"]["configuration"]["fps"] == 45 and not s["playSetup"]["choicesOpen"])
                keys("Down", "Return", "Down", "Down", "Down", "Return")
                focus("stream-profile-bitrate"); type_number("27.5"); keys("Down", "Return")
                wait(lambda s: s["playSetup"]["configuration"]["bitrateKbps"] == 27500 and not s["playSetup"]["choicesOpen"])
                assert not settings_writes
                click(state()["playSetup"]["controls"]["everyGame"])
                wait(lambda s: host_state(s).get("opened") and not host_state(s)["status"]["busy"])
                click(host_state()["controls"]["editDefaults"]); focus("stream-profile-width")
                for value in ("1920", "1200", "50", "22.5"):
                    type_number(value); keys("Down")
                keys("Return")
                wait(lambda s: host_state(s)["status"]["novaDefaults"].get("bitrateKbps") == 22500)
                assert not settings_writes and state()["playSetup"]["configuration"]["fps"] == 45
                focus("host-edit-defaults"); keys(*(["Down"] * 6)); focus("host-resume-timeout")
                keys("Return", "Down", "Return")
                wait(lambda s: len(settings_writes) == 1 and host_state(s)["status"].get("phase") == "ready" and not host_state(s)["status"]["busy"])
                assert host_state()["status"]["settings"]["desiredResumeTimeout"] == 600
                assert host_state()["status"]["settings"]["effectiveResumeTimeout"] == 300
                drop_settings_reply[0] = True
                focus("host-resume-timeout"); keys("Return", "Down", "Return")
                wait(lambda s: host_state(s)["status"].get("phase") == "unconfirmed" and not host_state(s)["status"]["busy"])
                assert len(settings_writes) == 2
                drop_settings_reply[0] = False
                click(host_state()["controls"]["refresh"])
                wait(lambda s: host_state(s)["status"].get("phase") == "ready" and not host_state(s)["status"]["busy"])
                assert len(settings_writes) == 2 and host_state()["status"]["settings"]["desiredResumeTimeout"] == 1800
                app.terminate(); app.wait(timeout=8); app = start()
                wait(lambda s: s.get("game") == "game-7")
                window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
                command("xdotool", "windowfocus", "--sync", window); keys("Return", "Return")
                wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("configuration", {}).get("fps") == 45)
                assert state()["playSetup"]["configuration"]["width"] == 2560 and state()["playSetup"]["configuration"]["bitrateKbps"] == 27500
                wait(lambda s: not s.get("playSetup", {}).get("hostPlan", {}).get("busy", True) and s.get("playSetup", {}).get("hostPlan", {}).get("plan", {}).get("fields"))
                click(state()["playSetup"]["controls"]["everyGame"])
                wait(lambda s: host_state(s).get("opened") and not host_state(s)["status"]["busy"])
                assert host_state()["status"]["novaDefaults"] == {"width": 1920, "height": 1200, "fps": 50, "bitrateKbps": 22500}
                assert len(settings_writes) == 2 and identity.read_bytes() == original_identity
                assert not any(urlsplit(path).path == "/launch" for method, path in requests)
                assert not any(v in log.read_text() for v in ("ReferenceError", "TypeError", "Cannot assign", "is not a type")), log.read_text()
                print("Production custom profile, scoped device defaults, paired timeout/lost reply, read-back and restart passed.")
                return
            keys("Escape"); wait(lambda s: not s.get("nativePreviewOpen"))
            keys("Right", "Return")
            wait(lambda s: s.get("artworkOpen") and not s.get("artworkStudioState", {}).get("busy"))
            keys("Up", "Left", "Return")
            wait(lambda s: s.get("focus") == "logo-smaller")
            keys("Right", "Right", "Return", "Down", "Return", "Down", "Left", "Return")
            expected_logo = {"scale": 1.3, "x": .45, "y": .6}
            wait(lambda s: s.get("focus") == "artwork-logo-placement" and s.get("artwork", {}).get("logoPlacement") == expected_logo)
            assert not writes and not settings_writes, "Device logo placement must not write to the PC"
            keys("Down")
            keys("Return"); wait(lambda s: s.get("artworkStudioState", {}).get("candidates"))
            keys("Down", "Return"); wait(lambda s: s.get("artworkStudioState", {}).get("choices"))
            wait(lambda s: s.get("focus") == "artwork-result-0")
            keys("Return"); wait(lambda s: s.get("artworkStudioState", {}).get("canApply"))
            keys("Down", "Return"); wait(lambda s: len(writes) == 1 and not s.get("busy"))
            wait(lambda s: s.get("artwork", {}).get("key"))
            keys("Escape"); wait(lambda s: not s.get("artworkOpen"))
            assert len(writes) == 1 and game["artwork"]["revision"] == "two"
            keys("Right", "Return"); wait(lambda s: s.get("shortcutOpen")); keys("Escape")
            app.terminate(); app.wait(timeout=8)
            assert not any(v in log.read_text() for v in ("ReferenceError", "TypeError", "Cannot assign", "is not a type")), log.read_text()
            # Canonical shortcut launches are checked against the fresh paired catalog.
            token_link = base64.urlsafe_b64encode(json.dumps({"destination": "desktop", "game": "game-7", "host": "host", "v": 1}, separators=(",", ":")).encode()).decode().rstrip("=")
            app = start(["--game-link", token_link])
            launched = wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("autoStartAttempted"), 15)["playSetup"]
            assert launched["hostId"] == "host" and launched["gameId"] == "game-7"
            assert state()["artwork"]["logoPlacement"] == expected_logo, "Saved logo placement lost after app restart"
            # This smoke flag deliberately disables native media; exact wire
            # parameters and resumed-session exclusion are covered in C++.
            assert not any(urlsplit(path).path == "/launch" for method, path in requests)
            assert identity.read_bytes() == original_identity
        finally:
            if app.poll() is None: app.terminate()
            app.wait(timeout=10)
        assert not any(v in log.read_text() for v in ("ReferenceError", "TypeError", "Cannot assign", "is not a type")), log.read_text()
        print("Production paired Play Setup, local logo save/restart, artwork search/apply/refresh, Steam entry sheet and canonical launch routing passed.")


if __name__ == "__main__":
    main()
