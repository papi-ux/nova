package com.papi.nova.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.papi.nova.Game

object NovaStreamPendingIntents {
    @JvmStatic
    fun createReturnToStreamIntent(context: Context): PendingIntent {
        val intent = Intent()
        intent.setClass(context, Game::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @JvmStatic
    fun createDisconnectIntent(context: Context): PendingIntent {
        val intent = Intent()
        intent.setClass(context, Game::class.java)
        intent.action = NovaQsTile.NOVA_DISCONNECT_ACTION
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
