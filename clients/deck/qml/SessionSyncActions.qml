import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

NovaScrollColumn {
    id: actions
    required property var session
    required property var profileState
    property real unit: 1
    property string error: ""
    readonly property var live: session && session.hud ? session.hud : ({})
    readonly property var review: profileState.profileReview || ({})
    readonly property bool ready: profileState.phase === "ready" && !profileState.busy
        && review.displayOverride === true && review.bitrateOverride === true
        && live.canSyncProfile === true && !live.syncBusy
    readonly property bool canSend: ready && live.canSetBitrate === true
    readonly property bool canClear: ready && (!!review.display || review.bitrate > 0)
    property int focusedIndex: 0
    readonly property var controls: [tuning, bitrate, match, send, clear]
    signal readingFocus()
    signal footerFocus()
    signal bitrateRequested()
    reserveScrollBarSpace: true
    spacing: 12 * unit
    function focusSaved() { controls[focusedIndex].forceActiveFocus() }
    function focusBitrate() { bitrate.forceActiveFocus() }
    function move(index, delta) {
        const next = index + delta
        if (next < 0 || next >= controls.length) footerFocus()
        else controls[next].forceActiveFocus()
    }
    function sendProfile(clearProfile) {
        if (clearProfile ? !canClear : !canSend) return
        error = session.setSyncProfile(profileState.hostId, clearProfile ? "" : profileState.novaDisplay,
            clearProfile ? 0 : profileState.novaBitrate, clearProfile, review)
            ? "" : "Couldn't start this change. Refresh the current session and profile before trying again."
    }
    component Copy: Label {
        Layout.fillWidth: true; textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.secondary; font.pixelSize: 16 * actions.unit * NovaTheme.fontScale
    }
    component Action: NovaButton {
        required property int position
        unit: actions.unit; Layout.fillWidth: true
        onActiveFocusChanged: if (activeFocus) actions.focusedIndex = position
        Keys.onUpPressed: actions.move(position, -1)
        Keys.onDownPressed: actions.move(position, 1)
        Keys.onLeftPressed: actions.readingFocus()
    }
    Copy { text: "APPLIES NOW"; font.pixelSize: 12 * actions.unit * NovaTheme.fontScale }
    Action {
        id: tuning; position: 0; objectName: "sync-live-tuning"
        text: "Live Tuning · " + (actions.live.tuningBusy ? "Saving…" : actions.live.tuningKnown ? actions.live.tuningEnabled ? "On" : "Off" : "Unavailable")
        opacity: actions.live.canTune || activeFocus ? 1 : 0.55
        Accessible.checkable: true; Accessible.checked: actions.live.tuningEnabled === true
        onClicked: if (actions.live.canTune && !actions.live.tuningBusy) actions.session.setLiveTuningEnabled(!actions.live.tuningEnabled)
    }
    Copy { text: actions.live.tuningCopy || "Live Tuning is unavailable for this stream." }
    Action {
        id: bitrate; position: 1; objectName: "sync-live-bitrate"
        text: "Change live bitrate"
        opacity: actions.live.canSetBitrate || activeFocus ? 1 : 0.55
        onClicked: if (actions.live.canSetBitrate && !actions.live.tuningBusy) actions.bitrateRequested()
    }
    Copy { text: "Live bitrate changes leave Play Setup and the saved profile unchanged. Turning tuning off holds the last confirmed bitrate." }
    Copy { text: "SAVE PROFILE + APPLY BITRATE"; font.pixelSize: 12 * actions.unit * NovaTheme.fontScale }
    Copy { text: "Resolution and frame rate: next stream. Bitrate: apply now, turn Live Tuning off and replace pending Doctor bitrate changes. Per-game choices stay separate." }
    Action {
        id: match; position: 2; objectName: "sync-profile-match"
        text: "Match Nova"
        opacity: actions.canSend && actions.profileState.profileState !== "Matches Nova" || activeFocus ? 1 : 0.55
        onClicked: if (actions.profileState.profileState !== "Matches Nova") actions.sendProfile(false)
    }
    Action {
        id: send; position: 3; objectName: "sync-profile-send"
        text: actions.live.syncBusy ? "Saving profile…" : "Send Nova"
        opacity: actions.canSend || activeFocus ? 1 : 0.55
        onClicked: actions.sendProfile(false)
    }
    Copy { text: "CLEAR FOR NEXT STREAM"; font.pixelSize: 12 * actions.unit * NovaTheme.fontScale }
    Action {
        id: clear; position: 4; objectName: "sync-profile-clear"
        text: "Clear paired profile"
        opacity: actions.canClear || activeFocus ? 1 : 0.55
        onClicked: actions.sendProfile(true)
    }
    Copy { text: "Clears this device's profile on the paired PC. This action leaves live bitrate and Nova's device defaults alone." }
    Copy { visible: !actions.canSend && !actions.live.syncBusy; text: "Sending requires a fresh owned session, an encoder that supports live bitrate, and a current paired profile. Refresh if these are unavailable." }
    Copy { text: "PC-wide display topology and resume timeout stay locked during play. Keep in step stays paused; these profile changes are explicit." }
    Copy { visible: actions.error.length > 0; text: actions.error; color: NovaTheme.warning }
}
