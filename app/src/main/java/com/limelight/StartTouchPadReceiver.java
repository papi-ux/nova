package com.limelight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class StartTouchPadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Open TouchpadView
        Intent serviceIntent = new Intent(context, TouchPadOverlayService.class);
        context.startService(serviceIntent);
        if (Game.instance != null && Game.instance.conn != null) {
            Intent gameIntent = new Intent(context, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(gameIntent);
        }
    }
}
