package com.papi.nova.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.papi.nova.Game
import com.papi.nova.R

/**
 * Quick Settings tile for Nova streaming status.
 * Shows whether a stream is active and allows quick disconnect.
 */
class NovaQsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (isStreaming()) {
            // Send disconnect intent to the Game activity
            val intent = Intent(NOVA_DISCONNECT_ACTION).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)

            // Update tile state after a brief delay
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.subtitle = "Disconnected"
            qsTile?.updateTile()
        } else {
            // Open Nova app
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(android.app.PendingIntent.getActivity(
                        this, 0, launchIntent, android.app.PendingIntent.FLAG_IMMUTABLE
                    ))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launchIntent)
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return

        if (isStreaming()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Nova"
            tile.subtitle = "Streaming"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Nova"
            tile.subtitle = "Not streaming"
        }

        tile.updateTile()
    }

    private fun isStreaming(): Boolean {
        return Game.isStreamActive
    }

    companion object {
        const val NOVA_DISCONNECT_ACTION = "com.papi.nova.DISCONNECT"
    }
}
