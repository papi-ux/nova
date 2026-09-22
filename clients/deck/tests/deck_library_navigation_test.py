"""Drive the real library with keyboard input and two isolated mTLS PCs."""
import argparse
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import socket
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit, parse_qs
from xml.sax.saxutils import escape
from deck_artwork_fixture import artwork_png
from deck_ui_input import wait_for_ui_observations


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=15).stdout.strip()


def experience_navigation(wait, keys, state, save_capture, window):
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    save_capture("android-game-details.png")
    keys("Return")
    wait(lambda s: s.get("nativePreviewOpen"))
    keys("Escape")
    wait(lambda s: not s.get("nativePreviewOpen") and s.get("detailOpen") and s.get("focus") == "game-detail-play")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "gamestream-app-42")

    # A touch/click enters the same details flow as A/Enter.
    command("xdotool", "mousemove", "--window", window, "100", "360", "click", "1")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "gamestream-app-7")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == s.get("game"))
    keys("Up", "Up")
    wait(lambda s: s.get("focus") == "library-search")
    command("xdotool", "type", "--clearmodifiers", "orbit")
    wait(lambda s: s.get("visibleGames") == ["gamestream-app-42"] and s.get("focus") == "library-search")
    save_capture("android-library-search.png")
    keys("Return", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "gamestream-app-42")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "gamestream-app-42")
    keys("Up", "Up")
    wait(lambda s: s.get("focus") == "library-search")
    keys("ctrl+a")
    command("xdotool", "type", "--clearmodifiers", "no matching title")
    wait(lambda s: s.get("visibleGames") == [] and not s.get("launchEnabled") and s.get("focus") == "library-search")
    keys("Escape")
    wait(lambda s: s.get("query") == "" and s.get("focus") == "gamestream-app-7")

    keys("Up", "Up", "Up", "Return")
    wait(lambda s: s.get("optionsOpen") and s.get("focus") == "library-layout-option")
    keys("Return", "Down", "Return")
    wait(lambda s: s.get("sortChoicesOpen") and s.get("focus") == "library-choice-sort-0")
    keys("Down", "Down", "Return")
    wait(lambda s: s.get("layout") == "compact" and s.get("sort") == "name")
    save_capture("android-library-options.png")
    keys("Escape", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "gamestream-app-7")
    save_capture("android-library-compact.png")
    # Compact is now a real poster grid: two rows, more columns than Grid,
    # with controller movement following those columns rather than list indices.
    assert state()["columns"] >= 6 and state()["rowsVisible"] >= 2
    command("xdotool", "windowsize", window, "960", "600")
    keys(*(["Down"] * 8))
    wait(lambda s: s.get("scroll", 0) > 0 and s.get("focus") == s.get("game") and s.get("selectionVisible"))
    saved = state()["game"]
    keys("Return", "Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == saved and s.get("scroll", 0) > 0)
    save_capture("android-library-small.png")


def polish_navigation(wait, keys, state, fixtures, save_capture, window):
    # A late logo cannot replace the settled title or move the launch action.
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("overview", {}).get("played") == "8 h 20 min")
    before = state()["overview"]
    assert before["estimates"] == ["Main story 10 h", "Main + extras 15 h", "Completionist 20 h"]
    assert not before["usesLogo"] and before["titleVisible"]
    fixtures["a"]["logo_release"].set()
    wait(lambda s: s.get("artwork", {}).get("logoReady"))
    assert not state()["overview"]["usesLogo"] and state()["overview"]["playY"] == before["playY"]
    save_capture("overview-title-duration-1280.png")
    keys("Return")
    wait(lambda s: s.get("nativePreviewOpen"))
    keys("Escape")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and not s.get("nativePreviewOpen") and s.get("focus") == s.get("game"))
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("overview", {}).get("usesLogo"))
    keys("Down")
    wait(lambda s: s.get("focus") == "game-detail-play" and s.get("launchEnabled"))
    save_capture("overview-logo-duration-1280.png")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "game-42")
    time.sleep(.25)
    assert state()["focusOutlineVisible"]
    save_capture("library-posters-1280.png")

    # Desktop/older hosts omit durations; a known zero is shown, never inferred.
    keys("Left", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("overview", {}).get("played") == "0 min")
    assert state()["overview"]["estimates"] == []
    keys("Escape", "Right", "Right", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "game-103")
    assert state()["overview"]["played"] == "" and state()["overview"]["estimates"] == []
    assert state()["overview"]["titleVisible"]
    save_capture("overview-missing-artwork-1280.png")
    keys("Escape", "Right", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "game-110")
    assert state()["overview"]["played"] == "" and state()["overview"]["estimates"] == ["Main story 1 h"]
    keys("Escape", "Left")
    appearance_navigation(wait, keys, state, save_capture, window)
    # appearance_navigation leaves the large-text library in Portable Chrome.
    wait(lambda s: not s.get("systemOpen") and s.get("fontScale") == 1.3)
    keys("Down", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("focusVisible"))
    assert state()["overview"]["playY"] + state()["overview"]["playHeight"] < 600
    save_capture("overview-long-title-large-960.png")
    keys("Escape")
    # At large text, dense details scroll independently while Play stays fixed.
    keys("Left", "Up", "Up", "Up", "Right", "Return", *(["Down"] * 6), "Return")
    wait(lambda s: s.get("appearanceOpen") and s.get("focus") == "appearance-theme-portable_chrome")
    keys("Down", "Down", "Down", "Return", "Escape", "Escape", "Down")
    wait(lambda s: s.get("theme") == "high_contrast" and s.get("focus") == "game-42" and s.get("launchEnabled"))
    save_capture("library-high-contrast-large-960.png")
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    action_y = state()["overview"]["playY"]
    save_capture("overview-high-contrast-large-960.png")
    assert not state()["overview"]["contentFits"]
    keys("Up")
    wait(lambda s: s.get("focus") == "game-detail-reading" and s["overview"]["scroll"] > 0)
    assert state()["overview"]["playY"] == action_y
    save_capture("overview-scrolled-large-960.png")
    keys("Down")
    wait(lambda s: s.get("focus") == "game-detail-play")
    command("xdotool", "mousemove", "--window", window, "400", "250", "click", "--repeat", "2", "4")
    wait(lambda s: s["overview"]["scroll"] <= 0)
    command("xdotool", "click", "--repeat", "2", "5")
    wait(lambda s: s["overview"]["scroll"] > 0)
    assert state()["overview"]["playY"] == action_y
    keys("Escape", "Up", "Up", "Up", "Right", "Return", *(["Down"] * 6), "Return")
    wait(lambda s: s.get("appearanceOpen"))
    keys("Up", "Up", "Up", "Return", "Escape", "Escape", "Down")
    wait(lambda s: s.get("theme") == "portable_chrome" and s.get("focus") == "game-42")
    # Explicit pointer activation must return the controller to that same card.
    card = state()["cards"][0]
    command("xdotool", "mousemove", "--window", window, str(int(card["x"])), str(int(card["y"])), "click", "1")
    wait(lambda s: s.get("detailOpen") and s.get("game") == card["id"])
    keys("Escape")
    wait(lambda s: s.get("focus") == card["id"])
    for layout in ("compact", "stage", "grid"):
        keys(*(["Up"] * (4 if state()["layout"] == "stage" else 3)), "Return", "Return", "Escape", "Down", "Down", "Down")
        wait(lambda s: s.get("layout") == layout and s.get("focus") == s.get("game") and s.get("selectionVisible"))
        time.sleep(.25)  # Check the final 180 ms scale/lift, not its starting frame.
        assert state()["focusOutlineVisible"], (layout, state())
        save_capture("library-" + layout + "-large-960.png")
        if layout == "stage":
            command("xdotool", "windowsize", window, "1280", "800")
            wait(lambda s: s.get("selectionVisible"))
            time.sleep(.25)
            assert state()["focusOutlineVisible"], "Stage clips the first focused poster at 1280x800"
            save_capture("library-stage-large-1280.png")
            keys(*(["Right"] * 20))
            wait(lambda s: s.get("game") == "game-122" and s.get("selectionVisible"))
            time.sleep(.25)
            assert state()["focusOutlineVisible"], "Stage clips the last focused poster at 1280x800"
            keys(*(["Left"] * 20))
            wait(lambda s: s.get("game") == "game-7")
            command("xdotool", "windowsize", window, "960", "600")
            wait(lambda s: s.get("selectionVisible"))
    keys(*(["Down"] * 5))
    wait(lambda s: s.get("scroll", 0) > 0 and s.get("selectionVisible"))
    saved = state()["game"]
    keys("Return", "Escape")
    wait(lambda s: s.get("focus") == saved and s.get("scroll", 0) > 0)
    time.sleep(.25)
    assert state()["focusOutlineVisible"]
    save_capture("library-final-row-large-960.png")


def appearance_navigation(wait, keys, state, save_capture, window):
    keys("Up", "Up", "Up", "Right", "Return", *(["Down"] * 6), "Return")
    wait(lambda s: s.get("appearanceOpen") and s.get("focus") == "appearance-theme-polaris")
    for index, theme in enumerate(("polaris", "portable_chrome", "oled", "miami", "high_contrast", "material_you")):
        if index:
            keys("Down")
        keys("Return")
        wait(lambda s: s.get("theme") == theme and s.get("focus") == "appearance-theme-" + theme and s.get("focusVisible"))
        save_capture("appearance-" + theme + ".png")
    keys("Down", "Return", "Return")
    wait(lambda s: s.get("fontScale") == 1.3 and s.get("focus") == "appearance-text-size" and s.get("focusVisible"))
    command("xdotool", "windowsize", window, "960", "600")
    wait(lambda s: s.get("focusVisible"))
    save_capture("appearance-large-text-960.png")
    # Text size is followed by the two saved in-game visibility controls.
    keys("Down", "Down", "Down", "Return")
    wait(lambda s: not s.get("appearanceOpen") and s.get("focus") == "library-appearance" and s.get("focusVisible"))
    keys("Escape", "Down")
    wait(lambda s: s.get("focus") == s.get("game") and s.get("launchEnabled"))
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play" and s.get("launchEnabled"))
    keys("Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action" and s.get("focusVisible"))
    save_capture("appearance-play-setup-large-960.png")
    keys("Escape", "Escape", "Up", "Up", "Up", "Right", "Return", *(["Down"] * 6), "Return")
    wait(lambda s: s.get("appearanceOpen"))
    # Every choice remains reachable at large text. Choose Portable Chrome for
    # the restart check; switching theme must not reset the larger font.
    keys(*(["Up"] * 4), "Return")
    wait(lambda s: s.get("theme") == "portable_chrome" and s.get("fontScale") == 1.3)
    keys("Escape", "Escape")


def stage_navigation(wait, keys, state, fixtures, save_capture, window):
    keys("Up", "Up", "Up", "Return", "Return", "Return")
    wait(lambda s: s.get("layout") == "stage" and s.get("optionsOpen"))
    keys("Escape", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "gamestream-app-42" and s.get("stageVisible")
         and s.get("stageTitle") == s.get("title") and s.get("selectionVisible"))
    save_capture("android-stage-library.png")

    # The complete library remains reachable; neither edge wraps or exits the rail.
    keys(*(["Right"] * 25))
    wait(lambda s: s.get("focus") == "gamestream-app-123" and s.get("selectionVisible") and s.get("scrollX", 0) > 0)
    keys("Right", "Down")
    assert state()["game"] == "gamestream-app-123"
    command("xdotool", "windowsize", window, "960", "600")
    wait(lambda s: s.get("focus") == "gamestream-app-123" and s.get("selectionVisible"))
    save_capture("android-stage-long-title.png")
    command("xdotool", "windowsize", window, "1280", "800")
    wait(lambda s: s.get("focus") == "gamestream-app-123" and s.get("selectionVisible"))
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    keys("Escape")
    wait(lambda s: s.get("focus") == "gamestream-app-123" and s.get("selectionVisible") and s.get("scrollX", 0) > 0)
    save_capture("android-stage-scrolled.png")
    keys(*(["Left"] * 28))
    wait(lambda s: s.get("focus") == "gamestream-app-7" and s.get("selectionVisible"))

    # Review opens the existing detail/launch review route, never starts a stream.
    keys("Up")
    wait(lambda s: s.get("focus") == "library-stage-review")
    save_capture("android-stage-review.png")
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "gamestream-app-7")
    keys("Return")
    wait(lambda s: s.get("nativePreviewOpen"))
    keys("Escape", "Escape")
    wait(lambda s: s.get("focus") == "gamestream-app-7" and not s.get("detailOpen"))
    review = state()["stageReviewCenter"]
    command("xdotool", "mousemove", "--window", window, str(int(review["x"])), str(int(review["y"])), "click", "1")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "gamestream-app-7")
    keys("Escape")
    card = next(card for card in state()["cards"] if card["id"] == "gamestream-app-42")
    command("xdotool", "mousemove", "--window", window, str(int(card["x"])), str(int(card["y"])), "click", "1")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "gamestream-app-42")
    keys("Escape", "Left")
    wait(lambda s: s.get("focus") == "gamestream-app-7")

    command("xdotool", "windowsize", window, "960", "600")
    keys(*(["Right"] * 15))
    wait(lambda s: s.get("focus") == "gamestream-app-113" and s.get("selectionVisible"))
    save_capture("android-stage-small.png")
    # Reorder and remove entries while browsing; hero and Play must track the current game.
    a = fixtures["a"]
    a["games"].reverse()
    a["games"] = [(app_id, title + " refreshed") for app_id, title in a["games"]]
    wait(lambda s: s.get("title", "").endswith("refreshed") and not s.get("busy")
         and s.get("game") == s.get("focus") == "gamestream-app-113" and s.get("selectionVisible"))
    a["games"] = [(app_id, title) for app_id, title in a["games"] if app_id != 113]
    wait(lambda s: "gamestream-app-113" not in s.get("visibleGames", []) and not s.get("busy")
         and s.get("game") == s.get("focus") and s.get("stageTitle") == s.get("title") and s.get("selectionVisible"))
    keys("Up", "Up", "Up")
    wait(lambda s: s.get("focus") == "library-search")
    command("xdotool", "type", "--clearmodifiers", "no matching stage game")
    wait(lambda s: s.get("visibleGames") == [] and not s.get("stageVisible") and not s.get("launchEnabled"))
    keys("Return")
    wait(lambda s: s.get("focus") == "game-empty-state")
    save_capture("android-stage-empty.png")
    keys("Escape")
    wait(lambda s: s.get("query") == "" and s.get("selectionVisible") and s.get("stageVisible"))
    # A PC change keeps the chosen layout and replaces both the hero and rail.
    keys("Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen"))
    keys("Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy") and s.get("game") == "gamestream-app-7"
         and s.get("layout") == "stage" and s.get("stageTitle") == "Moonlit Harbor b" and s.get("selectionVisible"))


def audio_settings_navigation(wait, keys, state, save_capture, window):
    def tap(point):
        command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")

    defaults = {"channels": 2, "playHostAudio": False}
    surround = {"channels": 8, "playHostAudio": True}
    keys("Up", "Up", "Up", "Right", "Return", "Down", "Down", "Down", "Down", "Return")
    wait(lambda s: s.get("audio", {}).get("opened") and s.get("focus") == "audio-channels-2")
    assert state()["audio"]["settings"] == defaults
    keys("Down", "Return")
    wait(lambda s: s["audio"]["settings"]["channels"] == 6)
    keys("Down", "Escape")
    wait(lambda s: not s["audio"]["opened"] and s.get("focus") == "library-audio-settings")
    keys("Return")
    wait(lambda s: s["audio"]["opened"] and s.get("focus") == "audio-channels-6")
    tap(state()["audio"]["channels"][2])
    tap(state()["audio"]["host"])
    wait(lambda s: s["audio"]["settings"] == surround)
    save_capture("audio-settings-1280.png")
    command("xdotool", "windowsize", window, "960", "600")
    wait(lambda s: s.get("width") == 960 and s.get("height") == 600)
    save_capture("audio-settings-960.png")
    tap(state()["audio"]["reset"])
    wait(lambda s: s["audio"]["settings"] == defaults)
    tap(state()["audio"]["channels"][2])
    tap(state()["audio"]["host"])
    wait(lambda s: s["audio"]["settings"] == surround)
    tap(state()["audio"]["done"])
    wait(lambda s: not s["audio"]["opened"] and s.get("focus") == "library-audio-settings")
    keys("Down", "Return")
    wait(lambda s: s.get("rumble", {}).get("opened") and s.get("focus") == "rumble-enabled")
    assert state()["rumble"]["enabled"], "rumble default differs from Android"
    keys("Return", "Down", "Escape")
    wait(lambda s: not s["rumble"]["opened"] and s.get("focus") == "library-rumble-settings")
    assert not state()["rumble"]["enabled"], "focus movement changed the rumble preference"
    keys("Return")
    wait(lambda s: s["rumble"]["opened"] and s.get("focus") == "rumble-enabled")
    save_capture("rumble-settings-960.png")
    tap(state()["rumble"]["reset"])
    wait(lambda s: s["rumble"]["enabled"])
    tap(state()["rumble"]["toggle"])
    wait(lambda s: not s["rumble"]["enabled"])
    command("xdotool", "windowsize", window, "1280", "800")
    time.sleep(.2)
    save_capture("rumble-settings-1280.png")
    assert state()["audio"]["settings"] == surround, "rumble reset changed audio"
    tap(state()["rumble"]["done"])
    wait(lambda s: not s["rumble"]["opened"] and s.get("focus") == "library-rumble-settings")
    save_capture("rumble-system-1280.png")
    command("xdotool", "windowsize", window, "960", "600")
    time.sleep(.2)
    save_capture("rumble-system-960.png")
    keys("Up")
    keys("Escape", "Down", "Down", "Down", "Return", "Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("audio", {}).get("channels") == 8)
    assert state()["playSetup"]["audio"]["playHostAudio"]
    save_capture("audio-review-960.png")
    command("xdotool", "windowsize", window, "1280", "800")
    time.sleep(.2)
    save_capture("audio-review-1280.png")
    # Another saved PC/game inherits device audio without a per-game override.
    keys("Escape", "Escape", "Up", "Up", "Up", "Left", "Return", "Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy"))
    keys("Return", "Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("audio", {}).get("channels") == 8)
    assert state()["playSetup"]["audio"]["playHostAudio"]


def play_setup_navigation(wait, keys, state, save_capture, window):
    defaults = {"width": 1280, "height": 800, "fps": 60, "bitrateKbps": 20000, "faceButtonLayout": "default", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}
    chosen = {"width": 1920, "height": 1080, "fps": 30, "bitrateKbps": 30000, "faceButtonLayout": "positions", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}

    keys("Up", "Up", "Up", "Right", "Return", "Down", "Down", "Down", "Return")
    wait(lambda s: s.get("faceDefaultsOpen"))
    keys("Down", "Return")
    wait(lambda s: s.get("defaultFaceButtonLayout") == "positions" and s.get("focus") == "library-face-default")
    save_capture("play-setup-device-face-default.png")
    keys("Return", "Up", "Escape")
    wait(lambda s: not s.get("faceDefaultsOpen") and s.get("defaultFaceButtonLayout") == "positions")
    keys("Escape", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "gamestream-app-42")

    def review(expected):
        keys("Return", "Return")
        wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action"
             and s.get("playSetup", {}).get("configuration") == expected)

    def customize():
        keys("Down", "Return", "Down", "Down", "Return", "Down", "Return", "Up", "Return", "Down", "Return", "Down", "Return")
        keys("Down", "Return", "Down", "Down", "Return")
        wait(lambda s: s.get("playSetup", {}).get("configuration") == chosen and s.get("focus") == "play-setup-face-buttons")

    review(defaults)
    assert state()["playSetup"]["effectiveFaceButtonLayout"] == "positions", "device default was not inherited"
    save_capture("play-setup-library-defaults.png")
    customize()
    save_capture("play-setup-library-custom.png")
    # Escape from a picker must discard only the highlighted choice.
    keys("Return", "Up", "Escape")
    wait(lambda s: s.get("nativePreviewOpen") and not s.get("playSetup", {}).get("choicesOpen")
         and s.get("focus") == "play-setup-face-buttons" and s["playSetup"]["configuration"] == chosen)
    # A game can override the inherited swap without changing the device default.
    point = state()["playSetup"]["controls"]["faceButtons"]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
    wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
    point = state()["playSetup"]["choiceCenters"][1]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
    wait(lambda s: s.get("playSetup", {}).get("effectiveFaceButtonLayout") == "labels")
    assert state()["defaultFaceButtonLayout"] == "positions", "game choice changed the device default"
    keys("Return", "Down", "Return")
    wait(lambda s: s.get("playSetup", {}).get("configuration") == chosen)
    keys("Escape", "Escape", "Left")
    wait(lambda s: s.get("focus") == "gamestream-app-7")
    review(defaults)
    # Pointer selection follows the same persisted route, then reset only this game.
    point = state()["playSetup"]["controls"]["resolution"]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
    wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
    keys("Down", "Return")
    wait(lambda s: s.get("playSetup", {}).get("configuration", {}).get("height") == 720)
    keys("Down", "Down", "Down", "Down", "Down", "Down", "Return")
    wait(lambda s: s.get("playSetup", {}).get("configuration") == defaults and not s["playSetup"]["custom"])
    assert state()["playSetup"]["effectiveFaceButtonLayout"] == "positions", "game reset lost device inheritance"
    keys("Escape", "Escape", "Right")
    review(chosen)
    keys("Escape", "Escape", "Up", "Up", "Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen"))
    keys("Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy") and s.get("focus") == "gamestream-app-7")
    keys("Right")
    review(defaults)  # Same numeric game ID on another PC must not inherit choices.
    keys("Escape", "Escape", "Up", "Up", "Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen"))
    keys("Up", "Return")
    wait(lambda s: s.get("host") == "a" and not s.get("busy") and s.get("focus") == "gamestream-app-7")
    review(defaults)
    customize()
    command("xdotool", "windowsize", window, "960", "600")
    save_capture("play-setup-library-small.png")
    keys("Return")
    wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
    save_capture("play-setup-library-small-picker.png")
    keys("Escape", "Escape", "Escape")
    wait(lambda s: s.get("focus") == "gamestream-app-7" and not s.get("nativePreviewOpen"))


def stream_plan_navigation(wait, keys, state, fixtures, save_capture, window):
    host = fixtures["a"]

    def review():
        keys("Return", "Return")
        wait(lambda s: s.get("nativePreviewOpen"))

    def close():
        keys("Escape", "Escape")
        wait(lambda s: not s.get("nativePreviewOpen") and not s.get("detailOpen"))

    def resolution():
        point = state()["playSetup"]["controls"]["resolution"]
        command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
        wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))

    review()
    plan = state()["playSetup"]["streamPlan"]
    assert [rate["fps"] for rate in plan["rates"]] == [30, 60], "host rate became unsupported client rate"
    assert plan["videoLabel"] == "H.264 · SDR" and plan["resolutions"][-1]["recommended"], "effective format/recommendation missing"
    resolution()
    keys("Down", "Down", "Down")
    save_capture("stream-plan-resolution.png")
    keys("Return")
    wait(lambda s: s["playSetup"]["configuration"]["height"] == 1200)
    assert state()["playSetup"]["configuration"]["fps"] == 60, "resolution adopted the host planner's 90 fps"
    save_capture("stream-plan-review.png")
    close()
    host["capture"]["max_fps"] = 30
    wait(lambda s: s.get("streamCapabilities", {}).get("maxFps") == 30 and not s.get("busy"))
    review()
    plan = state()["playSetup"]["streamPlan"]
    assert plan["configuration"]["fps"] == 30 and state()["playSetup"]["configuration"]["fps"] == 60
    assert state()["playSetup"]["playEnabled"] and plan["adjustment"] and len(plan["rates"]) == 1
    save_capture("stream-plan-adjusted.png")
    command("xdotool", "windowsize", window, "960", "600")
    save_capture("stream-plan-adjusted-small.png")
    close()
    host["capture"]["codecs"] = ["hevc"]
    wait(lambda s: s.get("streamCapabilities", {}).get("h264") is False and not s.get("busy"))
    review()
    assert not state()["playSetup"]["playEnabled"] and "selected codec" in state()["playSetup"]["streamPlan"]["reason"]
    save_capture("stream-plan-codec-unavailable.png")
    close()
    host["capture"]["codecs"] = ["h264", "hevc"]
    host["capture"]["max_fps"] = "60"
    wait(lambda s: s.get("streamCapabilities", {}).get("valid") is False and not s.get("busy"))
    review()
    assert not state()["playSetup"]["playEnabled"], "malformed capabilities left Play enabled"
    close()
    host["capture"]["max_fps"] = 240
    for game in host["metadata"]:
        game["display_planner"]["choices"][0]["target_mode"] = "3840x2400x90"
    wait(lambda s: s.get("streamCapabilities", {}).get("maxFps") == 240 and not s.get("busy"))
    review()
    plan = state()["playSetup"]["streamPlan"]
    assert plan["configuration"]["fps"] == 60 and state()["playSetup"]["configuration"]["height"] == 1200
    assert any(choice["recommended"] and choice["width"] == 3840 for choice in plan["resolutions"]), "advanced host recommendation missing"
    close()
    keys("Left")
    review()
    assert state()["playSetup"]["configuration"]["width"] == 1280, "display preferences crossed games"
    resolution()
    keys("Down", "Down", "Down", "Return")
    wait(lambda s: s["playSetup"]["configuration"]["height"] == 1200)
    close()
    keys("Up", "Up", "Up", "Left", "Return", "Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy"))
    review()
    plan = state()["playSetup"]["streamPlan"]
    assert plan["playable"] and plan["configuration"]["width"] == 1280 and len(plan["rates"]) == 2
    assert not any(choice["recommended"] for choice in plan["resolutions"]), "PC recommendations crossed hosts"
    close()
    keys("Up", "Up", "Up", "Left", "Return", "Up", "Return")
    wait(lambda s: s.get("host") == "a" and not s.get("busy"))


def launch_mode_navigation(wait, keys, state, fixtures, save_capture, window):
    host = fixtures["a"]

    def review():
        keys("Return", "Return")
        wait(lambda s: s.get("nativePreviewOpen"))

    def close():
        keys("Escape", "Escape")
        wait(lambda s: not s.get("nativePreviewOpen") and not s.get("detailOpen"))

    def choose(index):
        point = state()["playSetup"]["controls"]["launchMode"]
        command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
        wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
        keys("Up", "Up", "Up")
        for _ in range(index):
            keys("Down")
        keys("Return")
        wait(lambda s: not s.get("playSetup", {}).get("choicesOpen"))

    review()
    assert state()["playSetup"]["launchChoices"] == ["default", "headless_stream", "desktop_display"], "picker ignored host/game intersection"
    choose(1)
    wait(lambda s: s.get("playSetup", {}).get("configuration", {}).get("launchMode") == "headless_stream")
    save_capture("launch-mode-private.png")
    close()
    host["catalog"]["capabilities"]["modes"][0]["available"] = False
    wait(lambda s: s.get("launchPolicy", {}).get("allowed") == ["desktop_display"] and not s.get("busy"))
    review()
    wait(lambda s: s.get("playSetup", {}).get("configuration", {}).get("launchMode") == "default"
         and s["playSetup"]["notice"])
    save_capture("launch-mode-retired.png")
    close()
    host["catalog"]["capabilities"]["modes"][0]["available"] = True
    wait(lambda s: "headless_stream" in s.get("launchPolicy", {}).get("allowed", []) and not s.get("busy"))
    review()
    assert state()["playSetup"]["configuration"]["launchMode"] == "default", "retired choice silently returned"
    choose(1)
    close()
    host["catalog_status"] = 503
    wait(lambda s: not s.get("launchPolicy", {}).get("known", True) and not s.get("busy"))
    review()
    wait(lambda s: not s.get("playSetup", {}).get("launchModeAllowed", True))
    assert not state()["playSetup"]["playEnabled"], "unverified saved mode left Play enabled"
    assert state()["playSetup"]["configuration"]["launchMode"] == "headless_stream", "temporary outage erased the saved mode"
    save_capture("launch-mode-unverified.png")
    choose(0)
    wait(lambda s: s.get("playSetup", {}).get("launchModeAllowed"))
    close()
    host["catalog_status"] = 200
    wait(lambda s: s.get("launchPolicy", {}).get("known") and not s.get("busy"))
    keys("Left")
    review()
    assert state()["playSetup"]["configuration"]["launchMode"] == "default", "mode crossed game boundary"
    choose(2)
    command("xdotool", "windowsize", window, "960", "600")
    save_capture("launch-mode-small.png")
    keys("Return")
    wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
    save_capture("launch-mode-picker-small.png")
    keys("Escape")
    close()
    for mode in host["catalog"]["capabilities"]["modes"]:
        mode["available"] = mode["session_overridable"] = True
    wait(lambda s: len(s.get("launchPolicy", {}).get("allowed", [])) == 6 and not s.get("busy"))
    review()
    assert len(state()["playSetup"]["launchChoices"]) == 7, "full catalog lost a selectable mode"
    point = state()["playSetup"]["controls"]["launchMode"]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
    wait(lambda s: s.get("playSetup", {}).get("choicesOpen"))
    keys("Down", "Down", "Down")
    save_capture("launch-mode-all-options-small.png")
    keys("Escape")
    close()
    keys("Up", "Up", "Up", "Left", "Return", "Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy"))
    review()
    assert state()["playSetup"]["launchChoices"] == ["default"] and state()["playSetup"]["configuration"]["launchMode"] == "default", "standard host inherited Polaris modes"
    close()
    keys("Up", "Up", "Up", "Left", "Return", "Up", "Return")
    wait(lambda s: s.get("host") == "a" and not s.get("busy"))


def artwork_navigation(wait, keys, state, fixtures, save_capture):
    a = fixtures["a"]
    wait(lambda s: s.get("game") == "game-42" and s.get("artwork", {}).get("heroReady"))
    save_capture("artwork-grid.png")
    keys("Up", "Up", "Up", "Return", "Return", "Return", "Escape", "Down", "Down", "Down")
    wait(lambda s: s.get("layout") == "stage" and s.get("focus") == "game-42" and s.get("artwork", {}).get("iconReady"))
    keys("Left")
    wait(lambda s: s.get("focus") == "game-7" and s.get("artwork", {}).get("heroReady") and s["artwork"]["iconReady"])
    save_capture("artwork-stage.png")
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("artwork", {}).get("logoReady") and s["artwork"]["posterReady"])
    save_capture("artwork-details.png")
    keys("Escape")
    wait(lambda s: s.get("focus") == "game-7" and s.get("artwork", {}).get("iconReady"))
    previous = state()["artwork"].copy()
    game = a["metadata"][0]
    before_requests = len([path for path in a["artwork_requests"] if "/game-7/" in path])
    game["last_launched"] = 1718200000
    wait(lambda s: s.get("metadata", {}).get("lastLaunched") == 1718200000 and not s.get("busy"))
    assert state()["artwork"]["hero"] == previous["hero"] and state()["artwork"]["poster"] == previous["poster"], "metadata refresh replaced artwork"
    assert len([path for path in a["artwork_requests"] if "/game-7/" in path]) == before_requests, "metadata refresh refetched cached art"
    game["artwork"]["revision"] = "two"
    wait(lambda s: s.get("artwork", {}).get("key") != previous["key"] and s["artwork"]["heroReady"]
         and s.get("focus") == "game-7" and not s.get("busy"))
    assert state()["artwork"]["hero"] != previous["hero"], "new manifest revision retained old hero"
    save_capture("artwork-stage-refreshed.png")
    game["artwork"]["revision"] = "three"
    for kind in ("hero", "logo", "icon"):
        game["artwork"]["assets"][kind]["cached"] = False
    wait(lambda s: s.get("artwork", {}).get("hero") == "" and not s["artwork"]["heroReady"] and not s.get("busy"))
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("artwork", {}).get("posterReady") and not s["artwork"]["logoReady"])
    save_capture("artwork-details-fallback.png")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "game-7")
    keys("Right", "Right")
    wait(lambda s: s.get("game") == "game-103" and any("/game-103/artwork/hero" in p for p in a["artwork_requests"]))
    assert not state()["artwork"]["heroReady"], "denied hero retained the previous game's pixels"
    keys("Return")
    wait(lambda s: s.get("detailOpen") and any("/game-103/artwork/logo" in p for p in a["artwork_requests"]))
    assert not state()["artwork"]["logoReady"], "denied logo suppressed the title fallback"
    save_capture("artwork-denied-fallback.png")
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "game-103")
    keys("Up")
    wait(lambda s: s.get("focus") == "library-stage-review")
    keys("Up", "Up", "Up")
    wait(lambda s: s.get("focus") == "library-options")
    keys("Left")
    wait(lambda s: s.get("focus") == "library-host-button" and not s.get("busy"))
    keys("Return")
    wait(lambda s: s.get("pickerOpen"))
    keys("Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy") and s.get("focus") == "gamestream-app-7")
    assert state()["artwork"]["hero"] == "" and state()["artwork"]["logo"] == "", "artwork crossed PC boundary"


def automatic_navigation(wait, keys, state, fixtures, save_capture, window):
    a, b = fixtures["a"], fixtures["b"]
    other_requests = len(b["requests"])
    a["entered"].clear()
    a["release"].clear()
    wait(lambda s: s.get("busy") and s.get("automatic") and not s.get("launchEnabled"))
    assert a["entered"].wait(3), "automatic check did not request the app list"
    # Navigation remains usable while the background response is pending. The
    # completion must retain the current choice, not the choice at request time.
    keys("Left")
    wait(lambda s: s.get("game") == "gamestream-app-7")
    a["games"] = [(42, "Orbit & Beyond — live"), (7, "Moonlit Harbor — live"), (103, "Northern Lights")]
    a["release"].set()
    wait(lambda s: not s.get("busy") and s.get("title") == "Moonlit Harbor — live"
         and s.get("focus") == "gamestream-app-7")
    save_capture("library-auto-updated.png")
    previous = len(a["requests"])
    wait(lambda s: len(a["requests"]) >= previous+4 and not s.get("busy"))
    assert state()["game"] == "gamestream-app-7" and state()["focus"] == "gamestream-app-7", "unchanged check moved focus"

    a["games"] = [(42, "Orbit & Beyond — live"), (103, "Northern Lights")]
    wait(lambda s: s.get("game") == "gamestream-app-103" and s.get("focus") == "gamestream-app-103"
         and not s.get("busy"))
    keys("Up", "Up", "Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen") is True)
    paused = len(a["requests"])
    a["games"] = [(42, "Orbit & Beyond — live"), (103, "Northern Lights — refreshed")]
    time.sleep(1.05)
    assert len(a["requests"]) == paused and state()["pickerOpen"], "PC picker did not pause polling"
    keys("Escape")
    wait(lambda s: s.get("title") == "Northern Lights — refreshed" and s.get("focus") == "gamestream-app-103"
         and not s.get("busy"))

    keys("Return", "Return")
    wait(lambda s: s.get("nativePreviewOpen") is True)
    paused = len(a["requests"])
    time.sleep(1.05)
    assert len(a["requests"]) == paused, "native review did not pause polling"
    keys("Escape", "Escape")
    wait(lambda s: not s.get("nativePreviewOpen") and s.get("focus") == "gamestream-app-103" and not s.get("busy"))
    command("xdotool", "windowunmap", window)
    wait(lambda s: s.get("windowActive") is False and not s.get("busy"))
    paused = len(a["requests"])
    a["games"] = [(42, "Orbit & Beyond — live"), (103, "Northern Lights — welcome back")]
    time.sleep(1.05)
    assert len(a["requests"]) == paused, "inactive window polled its PC"
    command("xdotool", "windowmap", window)
    command("xdotool", "windowfocus", "--sync", window)
    wait(lambda s: s.get("title") == "Northern Lights — welcome back" and s.get("focus") == "gamestream-app-103"
         and not s.get("busy"))

    a["unavailable"] = True
    wait(lambda s: s.get("failed") and s.get("games") == [] and not s.get("busy"))
    a["unavailable"] = False
    a["games"] = [(103, "Northern Lights — reconnected")]
    wait(lambda s: s.get("title") == "Northern Lights — reconnected" and not s.get("busy") and not s.get("failed")
         and s.get("focus") == "gamestream-app-103")
    save_capture("library-auto-recovered.png")
    a["denied"] = True
    wait(lambda s: s.get("failed") and s.get("games") == [] and not s.get("busy"))
    rejected = len(a["requests"])
    a["denied"] = False
    time.sleep(1.8)
    assert len(a["requests"]) == rejected and not state()["launchEnabled"], "auth rejection retried automatically"
    keys("Up", "Up", "Up", "Right", "Return")
    wait(lambda s: s.get("focus") == "library-refresh-button")
    keys("Return")
    wait(lambda s: s.get("title") == "Northern Lights — reconnected" and not s.get("busy") and s.get("launchEnabled"))
    assert len(b["requests"]) == other_requests, "automatic refresh contacted a different PC"


def filter_navigation(wait, keys, state, fixtures, save_capture):
    def filter_focus(index):
        for _ in range(8):
            previous = state().get("focus")
            if previous.startswith("library-filter-"):
                break
            keys("Up")
            wait(lambda s: s.get("focus") != previous)
        assert state()["focus"].startswith("library-filter-"), state()
        keys(*(["Left"] * 4 + ["Right"] * index))
        expected = ["all", "recent", "source", "hdr", "more"][index]
        wait(lambda s: s.get("focus") == "library-filter-"+expected)

    def visible(ids, **expected):
        wait(lambda s: s.get("visibleGames") == ["game-"+str(value) for value in ids]
             and all(s.get(key) == value for key, value in expected.items()))

    def choose(index, kind):
        wait(lambda s: (s.get("filterChoicesOpen") or s.get("sortChoicesOpen"))
             and s.get("focus", "").startswith("library-choice-"))
        keys(*(["Up"] * 8 + ["Down"] * index))
        wait(lambda s: s.get("focus") == f"library-choice-{kind}-{index}")
        keys("Return")
        wait(lambda s: not s.get("filterChoicesOpen") and not s.get("sortChoicesOpen"))

    def sort(index, mode):
        filter_focus(0)
        keys("Up", "Up", "Return")
        wait(lambda s: s.get("optionsOpen") and s.get("focus") == "library-layout-option")
        keys("Down", "Return")
        choose(index, "sort")
        wait(lambda s: s.get("sort") == mode)
        keys("Escape", "Down", "Down", "Down")
        wait(lambda s: s.get("focus") == s.get("game"))

    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    assert state()["metadata"]["labels"] == "Heroic · Windows · Wine"
    assert "2024" in state()["lastPlayedLabel"], "epoch seconds rendered as a 1970 date"
    save_capture("android-details-metadata.png")
    keys("Escape")
    filter_focus(1)
    keys("Return")
    visible([42, 106, 7, 103], filter="recent", focus="library-filter-recent")
    save_capture("android-recent-filter.png")
    filter_focus(2)
    keys("Return")
    choose(2, "source")
    visible([42, 106], filter="source", filterValue="heroic")
    keys("Up")
    wait(lambda s: s.get("focus") == "library-search")
    command("xdotool", "type", "--clearmodifiers", "architect")
    visible([106], filter="source", query="architect", focus="library-search")
    keys("Return", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("game") == "game-106")
    keys("Escape")
    visible([106], query="architect", focus="game-106")
    keys("Escape")
    wait(lambda s: s.get("filter") == "all" and s.get("query") == "")

    filter_focus(4)
    keys("Return")
    choose(1, "category")
    visible([7, 103, 106], filter="category", filterValue="cinematic")
    keys("Return")
    wait(lambda s: s.get("filterChoicesOpen"))
    save_capture("android-more-filters.png")
    choose(5, "genre")
    visible([7, 106], filter="genre", filterValue="Puzzle")
    filter_focus(0)
    keys("Return", "Down")
    wait(lambda s: s.get("filter") == "all" and s.get("focus") == s.get("game"))
    sort(3, "name-desc")
    visible([106, 42, 7, 105, 104, 103], sort="name-desc")
    sort(5, "hdr")
    visible([103, 105, 7, 104, 42, 106], sort="hdr")
    filter_focus(3)
    keys("Return")
    visible([103, 105, 7], filter="hdr")
    keys("Down")
    wait(lambda s: s.get("focus") == s.get("game"))
    keys("Return")
    wait(lambda s: s.get("detailOpen"))
    assert state()["metadata"]["hdrSupported"] is True
    save_capture("android-hdr-details.png")
    keys("Escape")
    filter_focus(3)

    # Change only metadata, with unchanged IDs/titles. Automatic refresh must
    # rebuild the filtered view and retire a removed selection's Play action.
    a = fixtures["a"]
    a["entered"].clear()
    a["release"].clear()
    keys("Down")
    wait(lambda s: s.get("busy") and s.get("automatic"))
    assert a["entered"].wait(3), "automatic Polaris list read never arrived"
    for game in a["metadata"]:
        if game["app_id"] in (7, 103):
            game["hdr_supported"] = False
    a["release"].set()
    visible([105], filter="hdr", busy=False, focus="game-105")
    save_capture("android-hdr-refreshed.png")

    # Switching to a standard host resets host-specific constraints. It has no
    # play history, and that empty state must offer a local way back to games.
    filter_focus(0)
    keys("Up", "Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen") and s.get("focus") == "a")
    keys("Down", "Return")
    wait(lambda s: s.get("host") == "b" and not s.get("busy") and s.get("focus") == "gamestream-app-7")
    assert state()["filter"] == "all" and state()["query"] == ""
    filter_focus(1)
    keys("Return", "Down")
    wait(lambda s: s.get("visibleGames") == [] and s.get("focus") == "game-empty-state" and not s.get("launchEnabled"))
    save_capture("android-no-recent-games.png")
    keys("Return")
    wait(lambda s: s.get("filter") == "all" and s.get("focus") == "gamestream-app-7")
    filter_focus(4)
    keys("Return")
    wait(lambda s: s.get("filterChoicesOpen") and s.get("focus") == "library-choice-back")
    keys("Escape")
    wait(lambda s: not s.get("filterChoicesOpen") and s.get("focus") == "library-filter-more")

    filter_focus(0)
    keys("Up", "Up", "Left", "Return")
    wait(lambda s: s.get("pickerOpen") and s.get("focus") == "b")
    keys("Up", "Return")
    wait(lambda s: s.get("host") == "a" and not s.get("busy") and s.get("focus") == s.get("game"))
    filter_focus(4)
    keys("Return")
    choose(5, "genre")
    visible([7, 106], filter="genre", filterValue="Puzzle", sort="hdr")


def host_power_navigation(wait, keys, state, fixtures, save_capture, window):
    def power_phase(value):
        return lambda s: s.get("hostPower", {}).get("phase") == value
    def open_power():
        keys("Return")
        wait(lambda s: s.get("hostPowerOpen") and power_phase("ready")(s) and s.get("focus") == "host-power-hold")
    def hold():
        command("xdotool", "keydown", "Return")
        wait(power_phase("countdown"))
        command("xdotool", "keyup", "Return")
    a = fixtures["a"]
    keys("Up", "Up", "Up", "Right", "Return", *(["Down"] * 7))
    wait(lambda s: s.get("focus") == "library-sleep-host")
    open_power()
    save_capture("host-sleep-ready.png")
    keys("Return")
    wait(power_phase("ready"))
    assert not a["sleeps"], "a tap sent sleep"
    hold()
    save_capture("host-sleep-countdown.png")
    keys("Return")  # Countdown focus is the safe Cancel action.
    wait(lambda s: not s.get("hostPowerOpen") and s.get("focus") == "library-sleep-host")
    assert not a["sleeps"], "cancel sent sleep"
    open_power()
    hold()
    a["power"]["sleep_permitted"] = False
    wait(power_phase("unavailable"), timeout=8)
    assert not a["sleeps"], "permission withdrawn during countdown sent sleep"
    save_capture("host-sleep-denied.png")
    keys("Escape")
    a["power"]["sleep_permitted"] = True
    open_power()
    command("xdotool", "windowsize", window, "960", "600")
    wait(lambda s: s.get("focusVisible") and s.get("hostPowerUi", {}).get("hold", {}).get("x") == 480)
    point = state()["hostPowerUi"]["hold"]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "mousedown", "1")
    wait(power_phase("countdown"))
    command("xdotool", "mouseup", "1")
    wait(power_phase("confirming"), timeout=8)
    save_capture("host-sleep-confirming-960.png")
    assert len(a["sleeps"]) == 1 and a["sleeps"][0] == b"{}"
    # Acceptance is not sleep: this fixture stays reachable and supplies a fresh
    # failed-suspend receipt. The real production confirmation loop must finish.
    wait(power_phase("awake"), timeout=15)
    assert "running task" in state()["hostPower"]["copy"] and len(a["sleeps"]) == 1
    save_capture("host-sleep-still-awake-960.png")
    keys("Escape")
    wait(lambda s: not s.get("hostPowerOpen") and s.get("focus") == "library-sleep-host")


def host_scope_navigation(wait, keys, state, fixtures, save_capture, window, settle):
    fixture = fixtures["a"]
    def host():
        return state().get("playSetup", {}).get("hostDefaults", {})
    def settled():
        return wait(lambda s: s.get("playSetup", {}).get("hostDefaults", {}).get("opened") and
                    not s["playSetup"]["hostDefaults"]["status"].get("busy"))
    def click(control):
        settle()
        p = host()["controls"][control]
        # --sync waits for motion and hangs if Refresh is already under the pointer.
        command("xdotool", "mousemove", str(p["x"]), str(p["y"]), "click", "1")
        settle()
    def refresh(phase="ready", can_change=True):
        before = len([r for r in fixture["requests"] if r[1] == "/polaris/v1/client-settings"])
        click("refresh")
        wait(lambda s: len([r for r in fixture["requests"] if r[1] == "/polaris/v1/client-settings"]) > before and
             s["playSetup"]["hostDefaults"]["status"].get("phase") == phase and
             s["playSetup"]["hostDefaults"]["status"].get("canChange") == can_change and
             not s["playSetup"]["hostDefaults"]["status"].get("busy"))
    def choose(mode):
        # Refresh recreates the mode rows. A ready status can be observed before
        # their layout and deferred focus restoration have reached the window.
        settle()
        p = next(row["center"] for row in host()["modes"] if row["id"] == mode)
        command("xdotool", "mousemove", str(p["x"]), str(p["y"]), "click", "1")
        settle()
    keys("Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-play")
    keys("Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action")
    original = state()["playSetup"]["configuration"]
    keys("Left", "Left", "Up")
    wait(lambda s: s.get("focus") == "play-setup-every-game")
    keys("Return")
    settled()
    assert host()["status"]["canChange"] and not fixture["settings_posts"]
    assert host()["status"]["settings"]["desiredLabel"] == "Private Stream"
    save_capture("every-game-defaults-1280.png")
    keys("Up")
    wait(lambda s: s.get("focus") == "host-defaults-mode-headless_stream")
    keys("Down", "Down")
    wait(lambda s: s.get("focus") == "host-defaults-mode-headless_dongle")
    keys("Return")
    assert not fixture["settings_posts"], "unavailable mode sent a write"
    save_capture("every-game-unavailable-1280.png")
    keys("Up", "Return")
    # The server records the POST before the app consumes its reply. A prior
    # idle UI snapshot must not satisfy this wait during that interval.
    wait(lambda s: len(fixture["settings_posts"]) == 1 and
         s["playSetup"]["hostDefaults"]["status"].get("copy", "").startswith("Saved on the PC.") and
         not s["playSetup"]["hostDefaults"]["status"].get("busy"))
    assert host()["status"]["settings"]["desiredMode"] == "desktop_display"
    assert host()["status"]["settings"]["effectiveMode"] == "headless_stream"
    assert host()["status"]["settings"]["relaunchRequired"]
    assert not state()["playSetup"]["playEnabled"], "changed host defaults retained old Play review"
    save_capture("every-game-saved-1280.png")
    # Fresh host changes cannot be silently replaced with a choice from stale UI.
    fixture["catalog"]["revision"] = "changed-elsewhere"
    choose("headless_stream")
    wait(lambda s: "settings changed" in s["playSetup"]["hostDefaults"]["status"].get("copy", ""))
    assert len(fixture["settings_posts"]) == 1
    fixture["host_idle"] = False
    refresh(can_change=False)
    assert not host()["status"]["canChange"]
    choose("headless_stream")
    assert len(fixture["settings_posts"]) == 1, "active host admitted settings write"
    command("xdotool", "windowsize", "--sync", window, "960", "600")
    time.sleep(.2)
    save_capture("every-game-active-large-960.png")
    fixture["host_idle"] = True
    refresh()
    fixture["drop_settings"] = True
    choose("headless_stream")
    wait(lambda s: s["playSetup"]["hostDefaults"]["status"].get("phase") == "unconfirmed")
    assert len(fixture["settings_posts"]) == 2 and not host()["status"]["settings"]
    save_capture("every-game-unconfirmed-large-960.png")
    refresh()
    assert len(fixture["settings_posts"]) == 2 and host()["status"]["settings"]["desiredMode"] == "headless_stream"
    assert state()["playSetup"]["configuration"] == original, "host defaults rewrote per-game choices"
    fixture["catalog_status"] = 403
    refresh(phase="unavailable", can_change=False)
    assert not host()["status"]["settings"] and not host()["status"]["canChange"]
    fixture["catalog_status"] = 200
    refresh()
    # Controller-scrollable plan and Back keep the host scope independent.
    keys("Up", "Left", "Down", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "host-defaults-plan")
    save_capture("every-game-read-focus-large-960.png")
    keys("Escape")
    wait(lambda s: not s["playSetup"]["hostDefaults"]["opened"] and s.get("focus") == "play-setup-back")
    save_capture("every-game-review-invalidated-large-960.png")
    keys("Return")
    wait(lambda s: not s.get("nativePreviewOpen"))
    assert fixture["settings_posts"] == [{"stream_display_mode": "desktop_display"}, {"stream_display_mode": "headless_stream"}]


def profile_sync_navigation(wait, keys, state, fixtures, save_capture, window):
    fixture = fixtures["a"]
    assert state()["fontScale"] == 1.3, "partial appearance record did not load"
    def host(s=None):
        return (state() if s is None else s).get("playSetup", {}).get("hostDefaults", {})
    def status(s=None):
        return host(s).get("status", {})
    def focus_action(action):
        order = ["match", "send", "use", "clear", "reset"]
        target = order.index(action)
        wait(lambda s: status(s).get("phase") == "ready" and not status(s).get("busy"))
        # A completed refresh restores the remembered control asynchronously.
        # Observe each move instead of sending a batch from a transient Back.
        for _ in range(20):
            focus = state().get("focus", "")
            if focus == "host-profile-" + action:
                break
            if focus.startswith("host-profile-"):
                current = order.index(focus.removeprefix("host-profile-"))
                direction = "Down" if current < target else "Up"
            elif focus.startswith("host-defaults-mode-") or focus == "host-edit-defaults":
                direction = "Down"
            else:
                assert focus in ("host-defaults-back", "host-defaults-refresh",
                                 "host-sync-off", "host-sync-on", "host-resume-timeout"), focus
                direction = "Up"
            keys(direction)
            wait(lambda s: s.get("focus") != focus)
        wait(lambda s: s.get("focus") == "host-profile-"+action and s.get("focusVisible"))
    def refresh(expected_display):
        p = host()["controls"]["refresh"]
        command("xdotool", "mousemove", str(p["x"]), str(p["y"]), "click", "1")
        wait(lambda s: status(s).get("phase") == "ready" and
             status(s).get("settings", {}).get("desiredDisplay") == expected_display and
             s.get("focus") == "host-defaults-back")
    keys("Return", "Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action")
    # Keep an explicit game bitrate while importing the other device defaults.
    keys("Down", "Down", "Down", "Return", "Down", "Down", "Return")
    wait(lambda s: s["playSetup"]["configuration"]["bitrateKbps"] == 40000 and not s["playSetup"]["choicesOpen"])
    keys("Left", "Up")
    wait(lambda s: s.get("focus") == "play-setup-every-game")
    keys("Return")
    wait(lambda s: host(s).get("opened") and status(s).get("phase") == "ready")
    assert not fixture["settings_posts"] and status()["profileState"] == "Different from Nova"
    focus_action("use")
    save_capture("profile-use-scope-1280.png")
    keys("Return")
    wait(lambda s: status(s).get("novaDisplay") == "1920x1080x30" and status(s).get("phase") == "ready")
    assert not fixture["settings_posts"] and not state()["playSetup"]["playEnabled"]
    assert state()["playSetup"]["configuration"]["bitrateKbps"] == 40000
    save_capture("profile-used-1280.png")
    focus_action("clear"); keys("Return")
    wait(lambda s: status(s).get("copy", "").startswith("Cleared this paired device") and status(s).get("phase") == "ready")
    assert len(fixture["settings_posts"]) == 1 and status()["novaDisplay"] == "1920x1080x30"
    assert status()["profileState"] == "No Polaris profile"
    focus_action("send"); keys("Return")
    wait(lambda s: status(s).get("copy", "").startswith("Saved Nova's defaults") and status(s).get("phase") == "ready")
    assert fixture["settings_posts"][-1] == {"display_mode": "1920x1080x30", "target_bitrate_kbps": 30000}
    fixture["catalog"]["desired"].update(display_mode="1280x720x60", target_bitrate_kbps=20000)
    fixture["catalog"]["revision"] = "external-edit"
    keys("Return")
    wait(lambda s: "settings changed" in status(s).get("copy", ""))
    assert len(fixture["settings_posts"]) == 2
    focus_action("use"); keys("Return")
    wait(lambda s: status(s).get("novaDisplay") == "1280x720x60" and status(s).get("phase") == "ready")
    fixture["catalog"]["desired"]["display_mode"] = "1920x1080x59.94"
    fixture["catalog"]["revision"] = "unsupported-profile"
    refresh("1920x1080x59.94")
    command("xdotool", "windowsize", "--sync", window, "960", "600")
    focus_action("use")
    assert not next(a["enabled"] for a in host()["profileActions"] if a["id"] == "use")
    keys("Return")
    assert status()["novaDisplay"] == "1280x720x60" and len(fixture["settings_posts"]) == 2
    save_capture("profile-unsupported-large-960.png")
    keys("Left", "Down", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "host-defaults-plan" and s.get("focusVisible"))
    save_capture("profile-comparison-large-960.png")
    keys("Right")
    wait(lambda s: s.get("focus") == "host-profile-use")
    focus_action("send")
    fixture["drop_settings"] = True
    p = next(a["center"] for a in host()["profileActions"] if a["id"] == "send")
    command("xdotool", "mousemove", str(p["x"]), str(p["y"]), "click", "1")
    wait(lambda s: status(s).get("phase") == "unconfirmed")
    wait(lambda s: s.get("focus") == "host-defaults-refresh" and s.get("focusVisible"))
    assert len(fixture["settings_posts"]) == 3 and not status()["settings"]
    save_capture("profile-unconfirmed-large-960.png")
    refresh("1280x720x60")
    assert len(fixture["settings_posts"]) == 3
    focus_action("reset"); keys("Return")
    wait(lambda s: status(s).get("novaDisplay") == "1280x800x60")
    assert len(fixture["settings_posts"]) == 3 and status()["settings"]["desiredDisplay"] == "1280x720x60"
    focus_action("use"); keys("Return")
    wait(lambda s: status(s).get("novaDisplay") == "1280x720x60" and status(s).get("phase") == "ready")
    keys("Escape")
    wait(lambda s: not host(s).get("opened") and s.get("focus") == "play-setup-back")
    save_capture("profile-review-invalidated-960.png")
    keys("Return")
    wait(lambda s: not s.get("nativePreviewOpen"))
    assert fixture["settings_posts"] == [
        {"clear_display_mode": True, "clear_target_bitrate": True},
        {"display_mode": "1920x1080x30", "target_bitrate_kbps": 30000},
        {"display_mode": "1280x720x60", "target_bitrate_kbps": 20000}]


def keep_in_step_navigation(wait, keys, state, fixtures, save_capture, window):
    fixture = fixtures["a"]
    def status(s=None):
        return (state() if s is None else s).get("playSetup", {}).get("hostDefaults", {}).get("status", {})
    keys("Return", "Return")
    wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action")
    keys("Down", "Down", "Down", "Return", "Down", "Down", "Return")
    wait(lambda s: s["playSetup"]["configuration"]["bitrateKbps"] == 40000 and not s["playSetup"]["choicesOpen"])
    keys("Left", "Up", "Return")
    wait(lambda s: status(s).get("phase") == "ready")
    time.sleep(3.2)
    assert status()["keepInStep"] == "off" and not fixture["settings_posts"]
    keys("Up", *(["Down"] * 9), "Right")
    wait(lambda s: s.get("focus") == "host-sync-on" and s.get("focusVisible"))
    save_capture("keep-in-step-off-1280.png")
    keys("Return")
    wait(lambda s: status(s).get("keepInStep") == "on" and len(fixture["settings_posts"]) == 1 and not status(s).get("busy"))
    assert fixture["settings_posts"] == [{"display_mode": "1280x800x60", "target_bitrate_kbps": 20000}]
    assert state()["playSetup"]["configuration"]["bitrateKbps"] == 40000 and not state()["playSetup"]["playEnabled"]
    # Polling and a subsequent automatic save must not steal comparison focus.
    keys("Left", "Left")
    wait(lambda s: s.get("focus") == "host-defaults-plan")
    fixture["catalog"]["desired"]["target_bitrate_kbps"] = 30000
    fixture["catalog"]["revision"] = "changed-after-enable"
    wait(lambda s: len(fixture["settings_posts"]) == 2 and not status(s).get("busy"), timeout=9)
    assert fixture["settings_post_times"][1] - fixture["settings_post_times"][0] >= 5
    assert state()["focus"] == "host-defaults-plan" and status()["keepInStep"] == "on"
    command("xdotool", "windowsize", "--sync", window, "960", "600")
    keys("Right", "Right")
    wait(lambda s: s.get("focus") == "host-sync-on" and s.get("focusVisible"))
    save_capture("keep-in-step-on-large-960.png")
    fixture["catalog"]["desired"]["target_bitrate_kbps"] = 30000
    fixture["catalog"]["revision"] = "before-lost-reply"
    fixture["drop_settings"] = True
    wait(lambda s: status(s).get("phase") == "unconfirmed", timeout=9)
    wait(lambda s: s.get("focus") == "host-defaults-refresh" and s.get("focusVisible"))
    assert status()["keepInStep"] == "paused" and len(fixture["settings_posts"]) == 3
    save_capture("keep-in-step-paused-large-960.png")
    fixture["catalog"]["desired"]["target_bitrate_kbps"] = 30000
    fixture["catalog"]["revision"] = "after-lost-reply"
    keys("Return")
    wait(lambda s: status(s).get("phase") == "ready" and status(s).get("keepInStep") == "paused")
    keys("Up", *(["Down"] * 9), "Right")
    wait(lambda s: s.get("focus") == "host-sync-on" and s.get("focusVisible"))
    save_capture("keep-in-step-resume-large-960.png")
    time.sleep(3.2)
    assert len(fixture["settings_posts"]) == 3, "read-back replayed uncertain automatic save"
    keys("Escape")
    wait(lambda s: s.get("focus") == "play-setup-back")
    keys("Return")
    wait(lambda s: not s.get("nativePreviewOpen"))


def setup_parity_navigation(wait, keys, state, fixtures, save_capture, window):
    def review(during_refresh=False):
        fixture = fixtures["a"]
        if during_refresh:
            fixture["entered"].clear()
            fixture["release"].clear()
        try:
            if during_refresh:
                wait(lambda s: s.get("busy") and s.get("automatic") and fixture["entered"].is_set())
            keys("Return")
            opened = wait(lambda s: s.get("detailOpen") and s.get("focus") in ("game-detail-play", "game-detail-back"))
            if during_refresh:
                assert opened["busy"] and not opened["launchEnabled"] and opened["focus"] == "game-detail-back"
        finally:
            if during_refresh:
                fixture["release"].set()
        ready = wait(lambda s: s.get("detailOpen") and not s.get("busy") and s.get("launchEnabled"))
        # An in-flight refresh disables Play when details open. Completing it
        # must preserve the player's Back focus; navigate explicitly to Play.
        if during_refresh:
            assert ready["focus"] == "game-detail-back", "background refresh stole details focus"
        if ready["focus"] == "game-detail-back":
            keys("Right")
        wait(lambda s: s.get("focus") == "game-detail-play")
        keys("Return")
        wait(lambda s: s.get("nativePreviewOpen") and s.get("focus") == "native-preview-action")
    def setup():
        return state()["playSetup"]
    def values(**expected):
        return wait(lambda s: not s.get("playSetup", {}).get("choicesOpen") and
                    all(s["playSetup"]["configuration"].get(k) == v for k, v in expected.items()))
    def picker_reset(downs):
        keys("Return")
        for _ in range(16):
            if state().get("focus") == "play-setup-choice-reset": break
            keys("Down")
            time.sleep(.05)
        wait(lambda s: s.get("focus") == "play-setup-choice-reset" and s.get("focusVisible"))
        save_capture("setup-reset-choice-large.png")
        keys("Return")
    review(during_refresh=True)
    assert setup()["destination"] == {"id": "room-a", "name": "Arcade", "space": True}
    assert "in Arcade on Living Room PC" in setup()["readPlan"]["intro"] and setup()["audio"]["channels"] == 2
    assert not any(setup()["overrides"].values())
    save_capture("setup-space-defaults-1280.png")
    keys("Down", "Return", "Down", "Down", "Return")
    values(width=1920, height=1080)
    assert setup()["overrides"]["resolution"] and not setup()["overrides"]["fps"], "resolution silently overrode FPS"
    keys("Down", "Return", "Up", "Return")
    values(fps=30)
    keys("Down", "Return", "Down", "Return")
    values(bitrateKbps=30000)
    keys("Down", "Return", "Down", "Down", "Return")
    values(faceButtonLayout="positions")
    save_capture("setup-space-custom-1280.png")
    picker_reset(1)
    values(faceButtonLayout="default", width=1920, height=1080, fps=30, bitrateKbps=30000)
    assert not setup()["overrides"]["faceButtonLayout"] and setup()["overrides"]["fps"]
    keys("Return", "Down", "Down", "Return")
    values(faceButtonLayout="positions")
    keys("Up", "Up")
    wait(lambda s: s.get("focus") == "play-setup-rate")
    picker_reset(2)
    values(fps=60, width=1920, height=1080, bitrateKbps=30000, faceButtonLayout="positions")
    assert not setup()["overrides"]["fps"] and setup()["overrides"]["resolution"]
    command("xdotool", "windowsize", window, "960", "600")
    keys("Up", "Return", "Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "play-setup-choice-reset" and s.get("focusVisible"))
    point = setup()["controls"]["resetChoice"]
    command("xdotool", "mousemove", "--window", window, str(point["x"]), str(point["y"]), "click", "1")
    values(width=1280, height=800, fps=60, bitrateKbps=30000, faceButtonLayout="positions")
    assert not setup()["overrides"]["resolution"] and setup()["overrides"]["bitrateKbps"]
    keys("Left")
    wait(lambda s: s.get("focus") == "play-setup-plan")
    keys(*(["Down"] * 8))
    wait(lambda s: s["playSetup"]["readPlan"]["scroll"] > 0)
    save_capture("setup-plan-scroll-large-960.png")
    keys("Right")
    wait(lambda s: s.get("focus") == "play-setup-resolution")
    save_capture("setup-space-large-960.png")
    keys("Escape")
    wait(lambda s: not s.get("nativePreviewOpen") and s.get("detailOpen"))
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == "space.room-a.7")
    keys("Up", "Up", "Up", "Left", "Return")
    wait(lambda s: s.get("destination", {}).get("opened") and not s.get("busy") and s.get("focus") == "destination-choice-room-a")
    keys("Up", "Return")
    wait(lambda s: not s.get("busy") and not s.get("destination", {}).get("opened") and s.get("destinationName") == "Desktop")
    keys("Down")
    wait(lambda s: s.get("focus") == "game-7")
    review()
    assert setup()["destination"] == {"id": "desktop", "name": "Desktop", "space": False}
    assert not setup()["custom"] and setup()["configuration"]["bitrateKbps"] == 20000, "Space settings crossed into Desktop game"
    keys("Down", "Down", "Return", "Up", "Return")
    values(fps=30)
    keys("Down", "Return", "Down", "Down", "Return")
    values(bitrateKbps=40000)
    picker_reset(1)
    values(fps=30, bitrateKbps=20000)
    assert setup()["overrides"]["fps"] and not setup()["overrides"]["bitrateKbps"]
    save_capture("setup-desktop-large-960.png")
    keys("Escape")
    wait(lambda s: not s.get("nativePreviewOpen"))
    assert fixtures["a"]["selections"] == [{"space_id": "desktop", "previous_space_id": "room-a"}], "setup sent a host mutation"


def spaces_snapshot(fixture):
    return {"schema": 1, "status": True, "enabled": True,
            "available": bool(fixture["selected_destination"]), "can_switch": fixture.get("can_switch", True) and bool(fixture["selected_destination"]),
            "selected_space_id": fixture["selected_destination"], "desktop_allowed": fixture["desktop_allowed"],
            "unavailable_reason": None if fixture["selected_destination"] else "no_space_assigned",
            "spaces": [dict(row, selected=row["id"] == fixture["selected_destination"]) for row in fixture["spaces"]]}


def spaces_navigation(wait, keys, state, fixtures, save_capture, window):
    a = fixtures["a"]
    def opened():
        return wait(lambda s: s.get("destination", {}).get("opened") and not s.get("busy"))
    def open_from_library():
        keys("Up", "Up", "Up", "Left", "Return")
        opened()
    def refreshed():
        keys("Return")
        wait(lambda s: not s.get("busy") and s.get("destination", {}).get("known"))
    wait(lambda s: s.get("destinationName") == "Arcade" and s.get("artwork", {}).get("posterReady") and s.get("artwork", {}).get("heroReady"))
    assert all(g.startswith("space.room-a.") for g in state()["games"])
    save_capture("space-library-1280.png")
    assert any("/space-artwork/poster" in path for path in a["artwork_requests"])
    open_from_library()
    wait(lambda s: s.get("focus") == "destination-choice-room-a")
    save_capture("destination-picker-1280.png")
    keys("Down")
    wait(lambda s: s.get("focus") == "destination-choice-room-b")
    assert not a["selections"] and state()["destination"]["selectedId"] == "room-a", "focus changed the destination"
    # Keep the host request pending while exercising duplicate activation.
    # Otherwise the second key may arrive after success and reopen the picker.
    selection_release = threading.Event()
    a["selection_release"] = selection_release
    try:
        keys("Return")
        wait(lambda s: s.get("busy") and len(a["selections"]) == 1)
        keys("Return")
        assert len(a["selections"]) == 1, "pending destination switch was posted twice"
    finally:
        selection_release.set()
        del a["selection_release"]
    wait(lambda s: not s.get("busy") and not s.get("destination", {}).get("opened") and s.get("destinationName") == "Lounge")
    assert len(a["selections"]) == 1 and a["selections"][0] == {"space_id": "room-b", "previous_space_id": "room-a"}
    assert all(g.startswith("space.room-b.") for g in state()["games"]) and not state()["launchEnabled"]
    keys("Down", "Return")
    wait(lambda s: s.get("detailOpen") and s.get("focus") == "game-detail-back")
    assert not state()["launchEnabled"], "in-use Space offered Play"
    keys("Escape")
    wait(lambda s: not s.get("detailOpen") and s.get("focus") == s.get("game"))
    a["desktop_allowed"] = False
    open_from_library()
    wait(lambda s: s.get("focus") == "destination-choice-room-b")
    command("xdotool", "windowsize", window, "960", "600")
    keys("Up", "Up")
    wait(lambda s: s.get("focus") == "destination-choice-desktop" and s.get("focusVisible"))
    keys("Return")
    assert len(a["selections"]) == 1, "disabled Desktop access was bypassed"
    assert not state()["destination"]["rows"][0]["available"]
    save_capture("destination-no-desktop-large-960.png")
    # Selection reaches the host once, but its reply is deliberately lost.
    a["drop_selection"] = True
    keys("Down", "Return")
    wait(lambda s: not s.get("busy") and s.get("failed") and not s.get("destination", {}).get("known"))
    assert len(a["selections"]) == 2 and state()["games"] == [] and not state()["launchEnabled"]
    save_capture("destination-unconfirmed-large-960.png")
    keys("Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "destination-refresh")
    refreshed()
    assert len(a["selections"]) == 2 and state()["destination"]["selectedId"] == "room-a", "refresh replayed an uncertain selection"
    # Pointer activation of the permitted Desktop row follows the same path.
    a["desktop_allowed"] = True
    refreshed()
    keys("Up", "Up", "Up", "Up")
    wait(lambda s: s.get("focus") == "destination-choice-desktop" and s.get("focusVisible"))
    point = state()["destination"]["choices"][0]
    command("xdotool", "mousemove", "--window", window, str(int(point["x"])), str(int(point["y"])), "click", "1")
    wait(lambda s: not s.get("busy") and not s.get("destination", {}).get("opened") and s.get("destinationName") == "Desktop")
    assert state()["games"] == ["game-7", "game-42"] and a["games_queries"][-1].get("environment") == ["desktop"]
    assert state()["query"] == "" and state()["filter"] == "all"
    save_capture("desktop-library-large-960.png")
    # The host changed since the chooser opened. Fresh preflight must refuse
    # this stale previous_space_id without posting or falling back to Desktop.
    keys("Return")
    opened()
    wait(lambda s: s.get("focus") == "destination-choice-desktop")
    a["selected_destination"] = "room-b"
    before = len(a["selections"])
    keys("Down", "Return")
    wait(lambda s: not s.get("busy") and s.get("failed"))
    assert len(a["selections"]) == before and state()["games"] == []
    keys("Down", "Down", "Down")
    wait(lambda s: s.get("focus") == "destination-refresh")
    refreshed()
    assert state()["destination"]["selectedId"] == "room-b"
    # Duplicate authority fields and unavailable routes cannot authorize the
    # old library. An explicit fresh read recovers without another POST.
    a["spaces_body"] = '{"schema":1,"status":true,"enabled":true,"enabled":false}'
    keys("Return")
    wait(lambda s: not s.get("busy") and s.get("failed") and not s.get("destination", {}).get("known"))
    assert state()["games"] == [] and len(a["selections"]) == before
    del a["spaces_body"]
    a["spaces"] = []
    a["selected_destination"] = ""
    a["desktop_allowed"] = False
    refreshed()
    assert state()["games"] == [] and not state()["launchEnabled"]
    assert "No Space" in state()["destination"]["caption"]
    save_capture("destination-none-assigned-large-960.png")
    keys("Escape")
    wait(lambda s: not s.get("destination", {}).get("opened") and s.get("focus") == "library-destination")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--capture-dir", type=Path)
    parser.add_argument("--automatic", action="store_true")
    parser.add_argument("--experience", action="store_true")
    parser.add_argument("--filters", action="store_true")
    parser.add_argument("--stage", action="store_true")
    parser.add_argument("--play-setup", action="store_true")
    parser.add_argument("--artwork", action="store_true")
    parser.add_argument("--polish", action="store_true")
    parser.add_argument("--spaces", action="store_true")
    parser.add_argument("--setup-parity", action="store_true")
    parser.add_argument("--host-scope", action="store_true")
    parser.add_argument("--profile-sync", action="store_true")
    parser.add_argument("--keep-in-step", action="store_true")
    parser.add_argument("--launch-modes", action="store_true")
    parser.add_argument("--stream-plan", action="store_true")
    parser.add_argument("--audio-settings", action="store_true")
    parser.add_argument("--appearance", action="store_true")
    parser.add_argument("--host-power", action="store_true")
    args = parser.parse_args()
    args.host_scope = args.host_scope or args.profile_sync or args.keep_in_step
    args.spaces = args.spaces or args.setup_parity or args.host_scope
    with tempfile.TemporaryDirectory(prefix="nova-library-navigation-") as temporary, ExitStack() as stack:
        root = Path(temporary)
        for name in ("client", "a", "b"):
            command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", f"/CN=Nova {name} fixture", "-out", str(root/f"{name}.crt"),
                    "-keyout", str(root/f"{name}.key"))
        client = (root/"client.crt").read_text()
        client_der = ssl.PEM_cert_to_DER_cert(client)
        violations = []
        fixtures = {}

        class Host(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                fixture = self.server.fixture
                path = urlsplit(self.path).path
                secure = isinstance(self.connection, ssl.SSLSocket)
                if path != "/appasset" and not path.endswith("/cover") and "/artwork/" not in path:
                    fixture["requests"].append((secure, path))
                status = 200
                body = ""
                if secure and self.connection.getpeercert(binary_form=True) != client_der:
                    violations.append("unexpected client certificate")
                if not secure and path == "/serverinfo":
                    body = f'<root status_code="200"><HttpsPort>{fixture["tls_port"]}</HttpsPort></root>'
                elif secure and path == "/polaris/v1/capabilities":
                    status = 403 if fixture["denied"] else 503 if fixture.get("unavailable") else 200 if "metadata" in fixture else 404
                    if status == 200:
                        response = {"server": "polaris", "features": {"game_library": True, "client_settings_v1": "catalog" in fixture}}
                        if "spaces" in fixture:
                            response["features"]["spaces_v1"] = True
                        if "power" in fixture:
                            response["features"]["host_sleep_v1"] = True
                            response["host_power"] = fixture["power"]
                        if "capture" in fixture:
                            response["capture"] = fixture["capture"]
                        body = json.dumps(response)
                elif secure and path == "/polaris/v1/spaces" and "spaces" in fixture:
                    status = fixture.get("spaces_status", 200)
                    body = fixture.get("spaces_body", json.dumps(spaces_snapshot(fixture)))
                elif secure and path == "/polaris/v1/spaces/library" and "spaces" in fixture:
                    fixture["entered"].set()
                    if not fixture["release"].wait(5):
                        violations.append("Space library fixture release timed out")
                    sid = parse_qs(urlsplit(self.path).query).get("space_id", [""])[0]
                    if sid != fixture["selected_destination"]:
                        violations.append("library requested for the wrong Space")
                    body = json.dumps({"schema": 1, "status": True, "space_id": sid, "library_available": True,
                        "games": fixture["space_libraries"].get(sid, []), "total": len(fixture["space_libraries"].get(sid, []))})
                elif args.spaces and secure and "/space-artwork/" in path:
                    fixture["artwork_requests"].append(self.path)
                    game_id, kind = path.split("/")[4], path.split("/")[-1]
                    if not game_id.startswith("space." + fixture["selected_destination"] + "."):
                        status = 403
                    elif kind in ("poster", "hero", "logo", "icon"):
                        body = artwork_png(kind, "game-7", "one")
                    else:
                        violations.append("invalid Space artwork route")
                        status = 405
                elif secure and path == "/polaris/v1/spaces" and not args.spaces:
                    status = 404  # Optional desktop permission discovery on legacy hosts.
                elif secure and path == "/polaris/v1/host/power" and "power" in fixture:
                    body = json.dumps(fixture["power"])
                elif secure and path == "/polaris/v1/client-settings" and "catalog" in fixture:
                    status = fixture.get("catalog_status", 200)
                    body = json.dumps(fixture["catalog"])
                elif args.host_scope and secure and path == "/polaris/v1/session/status":
                    body = json.dumps({"state": "idle" if fixture["host_idle"] else "streaming", "streaming_active": not fixture["host_idle"],
                                       "game_uuid": "" if fixture["host_idle"] else "fixture-active"})
                elif secure and path == "/polaris/v1/games" and "metadata" in fixture:
                    fixture["entered"].set()
                    if not fixture["release"].wait(5):
                        violations.append("Polaris fixture release timed out")
                    if "spaces" in fixture:
                        query = parse_qs(urlsplit(self.path).query)
                        fixture["games_queries"].append(query)
                        if fixture.get("spaces_status", 200) != 404 and (fixture["selected_destination"] != "desktop" or query.get("environment") != ["desktop"]):
                            violations.append("desktop library read without matching selection")
                    body = json.dumps({"games": fixture["metadata"], "total": len(fixture["metadata"])})
                elif (args.artwork or args.polish or args.spaces) and secure and path.startswith("/polaris/v1/games/") and "/artwork/" in path:
                    fixture["artwork_requests"].append(self.path)
                    game_id, kind = path.split("/")[4], path.split("/")[-1]
                    game = next((g for g in fixture.get("metadata", []) if g["id"] == game_id), None)
                    if game_id == "game-103":
                        status = 403
                    elif game and kind in ("poster", "hero", "logo", "icon"):
                        if args.polish and game_id == "game-42" and kind == "logo":
                            fixture["logo_release"].wait(6)
                        body = artwork_png(kind, game_id, game["artwork"]["revision"])
                    else:
                        status = 404
                elif secure and path.startswith("/polaris/v1/games/game-") and path.endswith("/cover"):
                    status = 404
                elif secure and path == "/serverinfo":
                    body = f'<root status_code="200"><uniqueid>{fixture["id"]}</uniqueid><PairStatus>1</PairStatus></root>'
                elif secure and path == "/appasset":
                    status = 404
                elif secure and path == "/applist":
                    fixture["entered"].set()
                    if not fixture["release"].wait(5):
                        violations.append("fixture release timed out")
                    body = '<root status_code="200">' + ''.join(
                        f'<App><ID>{app_id}</ID><AppTitle>{escape(title)}</AppTitle></App>'
                        for app_id, title in fixture["games"]) + '</root>'
                else:
                    status = 405
                    violations.append(f"unexpected request: {path}")
                data = body if isinstance(body, bytes) else body.encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                fixture = self.server.fixture
                assert isinstance(self.connection, ssl.SSLSocket) and self.connection.getpeercert(binary_form=True) == client_der
                if args.host_scope and self.path == "/polaris/v1/client-settings":
                    data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    mode_write = set(data) == {"stream_display_mode"} and data["stream_display_mode"] in ("headless_stream", "desktop_display")
                    profile_write = (args.profile_sync or args.keep_in_step) and (data == {"clear_display_mode": True, "clear_target_bitrate": True} or
                        (set(data) == {"display_mode", "target_bitrate_kbps"} and data["display_mode"] in ("1280x720x60", "1920x1080x30", "1280x800x60") and data["target_bitrate_kbps"] in (20000, 30000)))
                    if not fixture["host_idle"] or not (mode_write or profile_write):
                        violations.append("unexpected host settings mutation")
                    fixture["settings_posts"].append(data)
                    fixture["settings_post_times"].append(time.monotonic())
                    if mode_write:
                        fixture["catalog"]["desired"]["stream_display_mode"] = data["stream_display_mode"]
                    elif data.get("clear_display_mode"):
                        for scope in ("desired", "effective"):
                            fixture["catalog"][scope].update(display_mode="", target_bitrate_kbps=0)
                    else:
                        fixture["catalog"]["desired"].update(data)
                    fixture["catalog"]["revision"] = str(10 + len(fixture["settings_posts"]))
                    fixture["catalog"]["relaunch_required"] = fixture["catalog"]["desired"] != fixture["catalog"]["effective"]
                    if fixture.pop("drop_settings", False):
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    reply = json.dumps({"status": True, "client_settings": fixture["catalog"]}).encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(reply)))
                    self.end_headers()
                    self.wfile.write(reply)
                    return
                if args.spaces and self.path == "/polaris/v1/spaces/select":
                    data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    fixture["selections"].append(data)
                    selection_release = fixture.get("selection_release")
                    if selection_release is not None and not selection_release.wait(timeout=3):
                        violations.append("destination selection fixture was not released")
                    permitted = [s["id"] for s in fixture["spaces"] if s["state"] != "unavailable"] + (["desktop"] if fixture["desktop_allowed"] else [])
                    accepted = data.get("previous_space_id") == fixture["selected_destination"] and data.get("space_id") in permitted and fixture.get("can_switch", True)
                    if accepted:
                        fixture["selected_destination"] = data["space_id"]
                    if fixture.pop("drop_selection", False):
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    reply = json.dumps(spaces_snapshot(fixture) if accepted else {"status": False, "code": "selection_changed"}).encode()
                    self.send_response(200 if accepted else 409)
                    self.send_header("Content-Length", str(len(reply)))
                    self.end_headers()
                    self.wfile.write(reply)
                    return
                assert args.host_power and self.path == "/polaris/v1/host/sleep" and fixture["power"]["sleep_permitted"]
                data = self.rfile.read(int(self.headers["Content-Length"]))
                fixture["sleeps"].append(data)
                fixture["power"].update(last_sleep_at=2, last_sleep_outcome="failed", last_sleep_message="Sleep was blocked by a running task.")
                reply = b'{"status":true}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)

        hosts = []
        for host_id, name in (("a", "Living Room PC"), ("b", "Studio PC")):
            fixture = {"id": host_id, "games": [(7, f"Moonlit Harbor {host_id}"), (42, f"Orbit & Beyond {host_id}")],
                       "requests": [], "artwork_requests": [], "denied": False, "entered": threading.Event(), "release": threading.Event()}
            if args.host_power and host_id == "a":
                fixture["sleeps"] = []
                fixture["power"] = {"sleep_supported": True, "sleep_enabled": True, "sleep_permitted": True, "last_sleep_at": 1}
                fixture["metadata"] = [{"id": f"game-{app_id}", "app_id": app_id, "name": title, "source": "steam", "installed": True}
                    for app_id, title in fixture["games"]]
            if args.launch_modes and host_id == "a":
                fixture["catalog"] = {"version": 1, "desired": {"stream_display_mode": "desktop_display"},
                    "effective": {"stream_display_mode": "desktop_display"}, "capabilities": {"modes": [
                        {"value": mode, "available": mode not in ("host_virtual_display", "desktop_takeover", "windowed_stream"), "session_overridable": mode != "gamescope_stream"}
                        for mode in ("headless_stream", "desktop_display", "headless_dongle", "host_virtual_display", "desktop_takeover", "windowed_stream", "gamescope_stream")]}}
                fixture["metadata"] = [{"id": f"game-{app_id}", "app_id": app_id, "name": title, "source": "steam", "installed": True,
                    "launch_mode": {"allowed_modes": [mode["value"] for mode in fixture["catalog"]["capabilities"]["modes"]]}}
                    for app_id, title in fixture["games"]]
            if args.host_scope and host_id == "a":
                fixture.update(host_idle=True, settings_posts=[], settings_post_times=[])
                profile = {"stream_display_mode": "headless_stream", "display_mode": "1280x800x60", "target_bitrate_kbps": 20000}
                if args.profile_sync or args.keep_in_step:
                    profile.update(display_mode="1920x1080x30", target_bitrate_kbps=30000)
                fixture["catalog"] = {"version": 1, "revision": "1", "desired": dict(profile), "effective": dict(profile), "relaunch_required": False,
                    "capabilities": {"display_mode_override": True, "target_bitrate_override": True, "modes": [{"value": mode, "label": label, "available": mode != "headless_dongle", "session_overridable": mode != "headless_dongle",
                        "unavailable_reason": "Connect a display adapter to use this mode." if mode == "headless_dongle" else ""}
                        for mode, label in [("headless_stream", "Private Stream"), ("desktop_display", "Mirror Desktop"), ("headless_dongle", "Headless Dongle")]]}}
            if args.stream_plan and host_id == "a":
                fixture["capture"] = {"codecs": ["h264", "hevc"], "max_fps": 120}
                fixture["metadata"] = [{"id": f"game-{app_id}", "app_id": app_id, "name": title, "source": "steam", "hdr_supported": True,
                    "display_planner": {"available": True, "recommended_id": "balanced", "choices": [
                        {"id": "balanced", "target_mode": "1920x1200x90", "reason": "Preserve aspect ratio."},
                        {"id": "unsafe", "target_mode": "1280x720x60", "safe": False},
                        {"id": "hidden", "target_mode": "1920x1080x60", "hidden": True}]}}
                    for app_id, title in fixture["games"]]
            if (args.artwork or args.polish or args.spaces) and host_id == "a":
                fixture["metadata"] = [
                    {"id": f"game-{app_id}", "app_id": app_id, "name": title, "source": "steam", "installed": True,
                     "artwork": {"version": 1, "revision": "one", "assets": {
                         kind: {"cached": True, "url": f"/polaris/v1/games/game-{app_id}/artwork/{kind}"}
                         for kind in ("poster", "hero", "logo", "icon")}}}
                    for app_id, title in [(7, "Moonlit Harbor"), (42, "Orbit & Beyond"), (103, "Missing Artwork")]]
            if args.spaces and host_id == "a":
                fixture["metadata"] = fixture["metadata"][:2]
                fixture.update(selected_destination="room-a", desktop_allowed=True, selections=[], games_queries=[])
                fixture["spaces"] = [
                    {"id": "room-a", "name": "Arcade", "state": "ready", "library_enabled": True, "can_open": True},
                    {"id": "room-b", "name": "Lounge", "state": "in_use", "library_enabled": True, "can_open": False},
                    {"id": "room-off", "name": "Workshop", "state": "unavailable", "library_enabled": True, "can_open": False}]
                fixture["space_libraries"] = {row["id"]: [
                    {"id": f'space.{row["id"]}.{target}', "app_id": 1347244801, "name": title, "source": "steam", "installed": True,
                     "space": {"id": row["id"], "name": row["name"], "target": target},
                     "artwork": {"version": 1, "revision": "one", "assets": {}}}
                    for target, title in [("big-picture-v1", "Steam Big Picture"), ("7", "Moonlit Harbor"), ("42", "Orbit & Beyond")]]
                    for row in fixture["spaces"]}
            if args.polish and host_id == "a":
                fixture["logo_release"] = threading.Event()
                fixture["metadata"] += [
                    {"id": f"game-{110+i}", "app_id": 110+i, "name": f"Southern Sky {i+1:02d}", "source": "steam", "installed": True,
                     "artwork": {"version": 1, "revision": "one", "assets": {
                         kind: {"cached": True, "url": f"/polaris/v1/games/game-{110+i}/artwork/{kind}"}
                         for kind in ("poster", "hero", "logo", "icon")}}} for i in range(13)]
                fixture["metadata"][0]["play_time"] = {"seconds": 0, "source": "steam"}
                fixture["metadata"][1].update(
                    play_time={"seconds": 30000, "source": "steam"},
                    beat_time={"main_seconds": 36000, "extras_seconds": 54000, "completionist_seconds": 72000,
                               "matched_name": "Orbit & Beyond"},
                    category="cinematic", genres=["Adventure", "Exploration"], last_launched=1718200000)
                fixture["metadata"][3]["beat_time"] = {"main_seconds": 3600, "extras_seconds": "invalid"}
                fixture["metadata"][2]["name"] = "Southern Sky — A Very Long Journey Beyond the Northern Constellations and Distant Stars"
            if (args.experience or args.stage) and host_id == "a":
                fixture["games"] += [(100+i, f"Southern Sky {i:02d}") for i in range(24)]
            if args.stage and host_id == "a":
                fixture["games"][-1] = (123, "Southern Sky — A Very Long Journey Beyond the Northern Constellations and Distant Stars")
            if args.filters and host_id == "a":
                fixture["metadata"] = [
                    {"id": f"game-{app_id}", "app_id": app_id, "name": title, "source": source,
                     "platform": platform, "runtime": runtime, "category": category, "genres": genres,
                     "hdr_supported": hdr, "last_launched": recent, "installed": True}
                    for app_id, title, source, platform, runtime, category, genres, hdr, recent in (
                        (7, "Moonlit Harbor", "steam", "linux", "proton", "cinematic", ["Adventure", "Puzzle"], True, 1718190000),
                        (42, "Orbit & Beyond", "heroic", "windows", "wine", "fast_action", ["Action"], False, 1718200000),
                        (103, "Amber Orchard", "steam", "linux", "native", "cinematic", ["Adventure"], True, 1718180000),
                        (104, "Desktop", "manual", "linux", "native", "desktop", [], False, 0),
                        (105, "Lunar Rally", "lutris", "windows", "wine", "fast_action", ["Racing"], True, 0),
                        (106, "Orbit Architect", "heroic", "linux", "native", "cinematic", ["puzzle"], False, 1718200000),
                    )]
            fixture["release"].set()
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(root/f"{host_id}.crt", root/f"{host_id}.key")
            context.load_verify_locations(root/"client.crt")
            context.verify_mode = ssl.CERT_REQUIRED
            http = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Host))
            tls = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Host))
            tls.socket = context.wrap_socket(tls.socket, server_side=True)
            fixture["tls_port"] = tls.server_port
            for server in (http, tls):
                server.fixture = fixture
                threading.Thread(target=server.serve_forever, daemon=True).start()
                stack.callback(server.shutdown)
            fixtures[host_id] = fixture
            hosts.append({"uuid": host_id, "name": name, "address": "127.0.0.1",
                          "http_port": http.server_port, "https_port": tls.server_port,
                          "server_certificate": (root/f"{host_id}.crt").read_text()})
        identity = root/"identity.json"
        identity.write_text(json.dumps({"version": 1, "certificate": client,
                                       "private_key": (root/"client.key").read_text(), "hosts": hosts}))
        identity.chmod(0o600)
        before = identity.read_bytes()
        observation = root/"state.json"
        capture = root/"library.png"
        log = root/"app.log"
        env = dict(os.environ, NOVA_DECK_IDENTITY_DIR=str(root), NOVA_DECK_GAMEPAD_DEVICE="/dev/null",
                   XDG_CONFIG_HOME=str(root/"config"), QT_QPA_PLATFORM="xcb", QT_QUICK_BACKEND="software", QT_SCALE_FACTOR="1",
                   QT_SCREEN_SCALE_FACTORS="1", QT_FORCE_STDERR_LOGGING="1")
        if args.spaces:
            (root/"config/Nova").mkdir(parents=True)
            (root/"config/Nova/NovaDeck.conf").write_text("[Appearance]\ntextScale=1.3\n")
        output = stack.enter_context(log.open("w"))
        auto_args = ["--frontend-smoke-library-refresh-ms", "800"] if args.automatic or args.filters or args.stage or (args.artwork or args.polish or args.spaces) or args.launch_modes or args.stream_plan else []
        app = subprocess.Popen([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs", "--frontend-smoke-library-state",
                                str(observation), "--frontend-smoke-capture", str(capture),
                                "--frontend-smoke-exit-after-ms", "55000" if args.keep_in_step else "95000" if args.spaces else "65000" if args.polish else "50000" if args.host_power else "40000" if args.appearance else "30000" if args.audio_settings else "42000" if args.filters or args.stage or args.play_setup or (args.artwork or args.polish or args.spaces) or args.launch_modes or args.stream_plan else "24000" if args.automatic else "14000", *auto_args],
                               env=env, stdout=output, stderr=output)

        def state():
            return json.loads(observation.read_text()) if observation.exists() else {}

        def wait(predicate, timeout=6):
            deadline = time.monotonic()+timeout
            while time.monotonic() < deadline:
                if app.poll() is not None:
                    raise AssertionError(f"Nova exited {app.returncode}: {log.read_text()}")
                current = state()
                if predicate(current):
                    return current
                time.sleep(.03)
            raise AssertionError(f"UI did not settle: {state()}\n{log.read_text()}")

        def keys(*values):
            for value in values:
                command("xdotool", "key", "--clearmodifiers", value)
                wait_for_ui_observations(observation, app)

        def save_capture(name):
            if args.capture_dir:
                if args.experience or args.filters or args.stage or args.play_setup or (args.artwork or args.polish or args.spaces) or args.launch_modes or args.stream_plan or args.audio_settings or args.appearance or args.host_power:
                    args.capture_dir.mkdir(parents=True, exist_ok=True)
                    time.sleep(.15)  # Allow the current frame to paint after the state readout.
                    command("import", "-window", window, str(args.capture_dir/name))
                    return
                previous = capture.stat().st_mtime_ns if capture.exists() else 0
                image = b""
                def completed_capture(_):
                    nonlocal image
                    if not capture.exists() or capture.stat().st_mtime_ns == previous:
                        return False
                    data = capture.read_bytes()
                    if not data.endswith(b"\x00\x00\x00\x00IEND\xaeB\x60\x82"):
                        return False
                    image = data
                    return True
                wait(completed_capture)
                args.capture_dir.mkdir(parents=True, exist_ok=True)
                (args.capture_dir/name).write_bytes(image)

        try:
            prefix = "game-" if args.host_power or args.filters or (args.artwork or args.polish or args.spaces) or args.launch_modes or args.stream_plan else "gamestream-app-"
            wait(lambda s: s.get("host") == "a" and s.get("focus") == ("space.room-a.big-picture-v1" if args.spaces else prefix+"7"))
            window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
            command("xdotool", "windowfocus", "--sync", window)
            wait(lambda s: s.get("windowActive"))
            keys("Right")
            wait(lambda s: s.get("game") == ("space.room-a.7" if args.spaces else prefix+"42"))
            if args.keep_in_step:
                keep_in_step_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.profile_sync:
                profile_sync_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.host_scope:
                host_scope_navigation(wait, keys, state, fixtures, save_capture, window,
                                      lambda: wait_for_ui_observations(observation, app))
            elif args.setup_parity:
                setup_parity_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.spaces:
                spaces_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.host_power:
                host_power_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.appearance:
                appearance_navigation(wait, keys, state, save_capture, window)
            elif args.audio_settings:
                audio_settings_navigation(wait, keys, state, save_capture, window)
            elif args.stream_plan:
                stream_plan_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.launch_modes:
                launch_mode_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.polish:
                polish_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.artwork:
                artwork_navigation(wait, keys, state, fixtures, save_capture)
            elif args.play_setup:
                play_setup_navigation(wait, keys, state, save_capture, window)
            elif args.stage:
                stage_navigation(wait, keys, state, fixtures, save_capture, window)
            elif args.filters:
                filter_navigation(wait, keys, state, fixtures, save_capture)
            elif args.experience:
                save_capture("android-library-grid.png")
                experience_navigation(wait, keys, state, save_capture, window)
            elif args.automatic:
                automatic_navigation(wait, keys, state, fixtures, save_capture, window)
            else:
                a, b = fixtures["a"], fixtures["b"]
                a_before, b_before = len(a["requests"]), len(b["requests"])
                a["games"] = [(42, "Orbit & Beyond — updated"), (103, "Northern Lights")]
                a["entered"].clear()
                a["release"].clear()
                keys("Up", "Up", "Up", "Right", "Return")
                wait(lambda s: s.get("focus") == "library-refresh-button")
                keys("Return")
                wait(lambda s: s.get("busy") is True and not s.get("launchEnabled"))
                assert a["entered"].wait(3), "refresh did not reach app list"
                keys("Return")  # Repeated activation must not start another request.
                a["release"].set()
                wait(lambda s: not s.get("busy") and s.get("title") == "Orbit & Beyond — updated"
                     and s.get("focus") == "gamestream-app-42")
                assert len(a["requests"]) == a_before+4 and len(b["requests"]) == b_before
                save_capture("library-refreshed.png")

                keys("Up", "Up", "Up", "Left", "Return")
                wait(lambda s: s.get("pickerOpen") is True and s.get("focus") == "a")
                keys("Down")
                wait(lambda s: s.get("focus") == "b")
                assert state()["host"] == "a" and len(b["requests"]) == b_before, "focus alone switched hosts"
                keys("Return")
                wait(lambda s: s.get("host") == "b" and s.get("title") == "Moonlit Harbor b"
                     and s.get("focus") == "gamestream-app-7" and not s.get("busy"))
                assert len(a["requests"]) == a_before+4 and len(b["requests"]) == b_before+4
                save_capture("library-switched-pc.png")

                b["denied"] = True
                keys("Up", "Up", "Up", "Right", "Return")
                wait(lambda s: s.get("focus") == "library-refresh-button")
                keys("Return")
                wait(lambda s: s.get("failed") is True and s.get("games") == []
                     and s.get("focus") == "game-empty-state" and not s.get("launchEnabled"))
                assert state()["host"] == "b" and len(a["requests"]) == a_before+4
                save_capture("library-unavailable.png")
                b["denied"] = False
                b["games"] = []
                keys("Up", "Up", "Up", "Right", "Return")
                wait(lambda s: s.get("focus") == "library-refresh-button")
                keys("Return")
                wait(lambda s: not s.get("busy") and s.get("failed") is False and s.get("games") == []
                     and s.get("focus") == "game-empty-state" and not s.get("launchEnabled"))
                b["games"] = [(7, "Moonlit Harbor returns")]
                keys("Up", "Up", "Up", "Right", "Return")
                wait(lambda s: s.get("focus") == "library-refresh-button")
                keys("Return")
                wait(lambda s: s.get("title") == "Moonlit Harbor returns" and s.get("focus") == "gamestream-app-7"
                     and not s.get("busy") and s.get("launchEnabled"))
            assert identity.read_bytes() == before, "library interaction changed credentials"
            assert not violations, violations
            app.wait(timeout=100 if args.spaces else 70 if args.polish else 55 if args.host_power else 45 if args.appearance or args.audio_settings or args.filters or args.stage or args.play_setup or (args.artwork or args.polish or args.spaces) or args.launch_modes or args.stream_plan else 16)
            assert app.returncode == 0, log.read_text()
            assert not any(error in log.read_text() for error in (
                "ReferenceError", "TypeError", "failed to load", "is not a type", "Cannot assign",
            )), log.read_text()
            if args.keep_in_step:
                observation.unlink()
                app = subprocess.Popen([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs",
                    "--frontend-smoke-library-state", str(observation), "--frontend-smoke-exit-after-ms", "17000"],
                    env=env, stdout=output, stderr=output)
                wait(lambda s: s.get("host") == "a" and not s.get("busy") and s.get("focus") == "space.room-a.big-picture-v1")
                window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
                command("xdotool", "windowfocus", "--sync", window)
                keys("Right", "Return", "Return")
                wait(lambda s: s.get("nativePreviewOpen") and s["playSetup"]["configuration"]["bitrateKbps"] == 40000)
                keys("Left", "Left", "Up", "Return")
                def sync_status(s=None):
                    return (state() if s is None else s).get("playSetup", {}).get("hostDefaults", {}).get("status", {})
                wait(lambda s: sync_status(s).get("phase") == "ready")
                assert sync_status()["keepInStep"] == "paused" and len(fixtures["a"]["settings_posts"]) == 3
                keys("Up", *(["Down"] * 9), "Right")
                wait(lambda s: s.get("focus") == "host-sync-on" and s.get("focusVisible"))
                save_capture("keep-in-step-restarted-paused-1280.png")
                keys("Return")
                wait(lambda s: sync_status(s).get("keepInStep") == "on" and len(fixtures["a"]["settings_posts"]) == 4 and not sync_status(s).get("busy"))
                keys("Left")
                wait(lambda s: s.get("focus") == "host-sync-off")
                point = state()["playSetup"]["hostDefaults"]["controls"]["syncOff"]
                command("xdotool", "mousemove", str(point["x"]), str(point["y"]), "click", "1")
                wait(lambda s: sync_status(s).get("keepInStep") == "off")
                time.sleep(3.2)
                assert len(fixtures["a"]["settings_posts"]) == 4 and identity.read_bytes() == before and not violations
                assert state()["fontScale"] == 1.3 and state()["playSetup"]["configuration"]["bitrateKbps"] == 40000
                app.wait(timeout=22)
                assert app.returncode == 0, log.read_text()
            if args.profile_sync:
                observation.unlink()
                app = subprocess.Popen([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs",
                    "--frontend-smoke-library-state", str(observation), "--frontend-smoke-exit-after-ms", "12000"],
                    env=env, stdout=output, stderr=output)
                wait(lambda s: s.get("host") == "a" and not s.get("busy") and s.get("focus") == "space.room-a.big-picture-v1")
                window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
                command("xdotool", "windowfocus", "--sync", window)
                keys("Return", "Return")
                wait(lambda s: s.get("nativePreviewOpen") and s["playSetup"]["configuration"]["height"] == 720)
                # The Space launcher is a different game: it inherits the new
                # device defaults without the original game's explicit bitrate.
                assert state()["playSetup"]["configuration"]["bitrateKbps"] == 20000 and not any(state()["playSetup"]["overrides"].values()), state()["playSetup"]
                keys("Escape", "Escape", "Right")
                wait(lambda s: s.get("game") == "space.room-a.7" and not s.get("nativePreviewOpen"))
                keys("Return", "Return")
                wait(lambda s: s.get("nativePreviewOpen") and s["playSetup"]["configuration"]["bitrateKbps"] == 40000)
                assert state()["playSetup"]["configuration"] == {"width": 1280, "height": 720, "fps": 60, "bitrateKbps": 40000, "faceButtonLayout": "default", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}, state()["playSetup"]
                assert state()["playSetup"]["overrides"] == {"resolution": False, "fps": False, "bitrateKbps": True, "faceButtonLayout": False, "launchMode": False, "videoCodec": False, "profilePreference": False, "encoderBackend": False}
                assert state()["fontScale"] == 1.3, "partial appearance record was overwritten during startup"
                assert len(fixtures["a"]["settings_posts"]) == 3 and identity.read_bytes() == before and not violations
                save_capture("profile-restarted-1280.png")
                app.wait(timeout=17)
                assert app.returncode == 0, log.read_text()
            if args.setup_parity:
                observation.unlink()
                app = subprocess.Popen([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs",
                    "--frontend-smoke-library-state", str(observation), "--frontend-smoke-exit-after-ms", "7000"],
                    env=env, stdout=output, stderr=output)
                wait(lambda s: s.get("host") == "a" and s.get("focus") == "game-7")
                window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
                command("xdotool", "windowfocus", "--sync", window)
                keys("Return", "Return")
                wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("configuration", {}).get("fps") == 30)
                assert state()["playSetup"]["overrides"] == {"resolution": False, "fps": True, "bitrateKbps": False,
                    "faceButtonLayout": False, "launchMode": False, "videoCodec": False, "profilePreference": False, "encoderBackend": False}, "independent reset or scope lost on restart"
                assert state()["playSetup"]["destination"]["id"] == "desktop" and identity.read_bytes() == before and not violations
                save_capture("setup-restarted-1280.png")
                app.wait(timeout=12)
                assert app.returncode == 0, log.read_text()
            if args.play_setup or args.launch_modes or args.stream_plan or args.audio_settings:
                observation.unlink()
                app = subprocess.Popen([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs",
                    "--frontend-smoke-library-state", str(observation),
                    "--frontend-smoke-exit-after-ms", "6000"], env=env, stdout=output, stderr=output)
                wait(lambda s: s.get("host") == "a" and s.get("focus") == ("game-7" if args.launch_modes or args.stream_plan else "gamestream-app-7"))
                window = command("xdotool", "search", "--onlyvisible", "--pid", str(app.pid), "--name", "^Nova Deck$").splitlines()[-1]
                command("xdotool", "windowfocus", "--sync", window)
                keys("Return", "Return")
                expected = {"width": 1280, "height": 800, "fps": 60, "bitrateKbps": 20000, "faceButtonLayout": "default", "launchMode": "desktop_display", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""} if args.launch_modes else {"width": 1920, "height": 1080, "fps": 30, "bitrateKbps": 30000, "faceButtonLayout": "positions", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}
                if args.stream_plan:
                    expected = {"width": 1920, "height": 1200, "fps": 60, "bitrateKbps": 20000, "faceButtonLayout": "default", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}
                if args.audio_settings:
                    expected = {"width": 1280, "height": 800, "fps": 60, "bitrateKbps": 20000, "faceButtonLayout": "default", "launchMode": "default", "videoCodec": "h264", "profilePreference": "auto", "encoderBackend": ""}
                wait(lambda s: s.get("nativePreviewOpen") and s.get("playSetup", {}).get("configuration") == expected)
                if args.play_setup:
                    assert state()["defaultFaceButtonLayout"] == "positions", "device default lost on restart"
                if args.audio_settings:
                    assert state()["playSetup"]["audio"] == {"channels": 8, "playHostAudio": True, "label": "7.1 surround"}, "audio lost on restart"
                    assert not state()["rumble"]["enabled"], "rumble preference lost on restart or PC switch"
                assert identity.read_bytes() == before and not violations, "Play Setup restart changed credentials or sent a mutation"
                app.wait(timeout=10)
                assert app.returncode == 0, log.read_text()
            if args.experience or args.filters or args.stage or args.appearance:
                observation.unlink()
                restarted = subprocess.run([str(args.binary.resolve()), "--standalone", "--frontend-smoke-codecs",
                    "--frontend-smoke-library-state", str(observation),
                    "--frontend-smoke-exit-after-ms", "600"], env=env, capture_output=True, text=True, timeout=15)
                assert restarted.returncode == 0, restarted.stderr
                if args.appearance:
                    assert state()["theme"] == "portable_chrome" and state()["fontScale"] == 1.3, "appearance did not survive restart"
                elif args.stage:
                    assert state()["layout"] == "stage" and state()["stageVisible"] and state()["selectionVisible"], "Stage did not survive restart"
                elif args.filters:
                    assert state()["filter"] == "genre" and state()["filterValue"] == "Puzzle" and state()["sort"] == "hdr", "filters/sort did not survive restart"
                else:
                    assert state()["layout"] == "compact" and state()["sort"] == "name", "library options did not survive restart"
                assert identity.read_bytes() == before and not violations, "restart changed pairing or sent a mutation"
        finally:
            for fixture in fixtures.values():
                fixture["release"].set()
            if app.poll() is None:
                app.terminate()
                app.wait(timeout=5)
    print("Actual library navigation passed: " + ("manual profile import/send/clear, game/device scope, stale/unsupported/lost-reply recovery, large-text focus and restart"
          if args.profile_sync else "Keep in step scope, throttle, focus, uncertain save, restart and explicit resume/off"
          if args.keep_in_step else "Every Game host defaults, desired/effective truth, stale/busy/denied/lost-reply gates, large text and controller/pointer focus"
          if args.host_scope else "destination-aware plan, independent choices/reset, scope, large-text reading, pointer/controller focus and restart"
          if args.setup_parity else "audio/rumble settings, touch/controller focus, reset, scope, review and restart"
          if args.audio_settings else "theme selection, large text, focus visibility, responsive sheets and appearance persistence"
          if args.appearance else "Sleep Host hold/cancel, permission withdrawal, one POST, verified still-awake outcome and focus"
          if args.host_power else "display recommendations, effective stream plan, capability changes, codec refusal, scope and restart"
          if args.stream_plan else "launch-mode authority, persistence, retirement, catalog outage, scope, pointer/controller choices and resizing"
          if args.launch_modes else "cinematic overview, optional durations, late/denied art, layouts, large text, themes, pointer scrolling and focus restoration"
          if args.polish else "destination choice, scoped artwork, permissions, stale selection, lost reply, read-back recovery and large-text focus"
          if args.spaces else "manifest posters, hero/icon/logo presentation, stable caching, revision refresh, removal, denied assets and PC isolation"
          if args.artwork else "Play Setup choices, touch, cancellation, game/PC scope, reset, resizing and restart"
          if args.play_setup else "Stage rail, hero, review, touch, resizing, refresh, empty recovery, PC switching and persistence"
          if args.stage else "Polaris metadata, five filters, sorting, search, refresh, PC switching and persistence"
          if args.filters else "automatic updates, in-flight navigation, focus, pauses, reconnect and auth-stop"
          if args.automatic else "grid, details, back navigation, search, empty results, touch, options and viewport resizing"
          if args.experience else "async refresh, selected-game focus, PC switch, denied/empty lists and recovery"))


if __name__ == "__main__":
    main()
