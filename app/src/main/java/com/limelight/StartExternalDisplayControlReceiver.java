package com.limelight;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;

import androidx.annotation.RequiresApi;

import com.limelight.utils.ExternalDisplayControlActivity;

public class StartExternalDisplayControlReceiver extends BroadcastReceiver {
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent intentTouchpad = new Intent(context, ExternalDisplayControlActivity.class);
        intentTouchpad.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Bundle optionsDefault = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle();
        context.startActivity(intentTouchpad, optionsDefault);
        requestFocusToSecondScreen();
    }

    public static void requestFocusToSecondScreen() {
        if (Game.instance != null) {
            Intent gameIntent = new Intent(Game.instance, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Game.instance.startActivity(gameIntent);
        }
    }
}
