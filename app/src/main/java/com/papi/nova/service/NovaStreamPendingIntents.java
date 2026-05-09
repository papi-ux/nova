package com.papi.nova.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.papi.nova.Game;

public final class NovaStreamPendingIntents {
    private NovaStreamPendingIntents() {
    }

    public static PendingIntent createReturnToStreamIntent(Context context) {
        Intent intent = new Intent();
        intent.setClass(context, Game.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent createDisconnectIntent(Context context) {
        Intent intent = new Intent();
        intent.setClass(context, Game.class);
        intent.setAction(NovaQsTile.NOVA_DISCONNECT_ACTION);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        return PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_IMMUTABLE);
    }
}
