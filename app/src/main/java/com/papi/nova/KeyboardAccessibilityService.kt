package com.papi.nova

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyboardAccessibilityService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode
        val game = Game.instance

        if (game != null && game.connected && !BLACKLIST_KEYS.contains(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.scanCode == 1) {
                    game.handleKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                    return true
                }
                game.handleKeyDown(event)
                return true
            } else if (action == KeyEvent.ACTION_UP) {
                if (event.scanCode == 1) {
                    game.handleKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                    return true
                }
                game.handleKeyUp(event)
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    override fun onServiceConnected() {
        LimeLog.info("Keyboard service is connected")
        val info = AccessibilityServiceInfo().apply {
            packageNames = arrayOf(BuildConfig.APPLICATION_ID)
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(accessibilityEvent: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private val BLACKLIST_KEYS = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
