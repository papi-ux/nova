package com.limelight;

import android.app.ActivityManager;
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
        requestFocusToExternalDisplayControl(context);
        requestFocusToGameActivity();
    }

    public static void requestFocusToExternalDisplayControl(Context context) {
        if (ExternalDisplayControlActivity.instance != null) {
            ActivityManager am = (ActivityManager) ExternalDisplayControlActivity.instance.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(ExternalDisplayControlActivity.instance.getTaskId(), 0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intentTouchpad = new Intent(context, ExternalDisplayControlActivity.class);
            intentTouchpad.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Bundle optionsDefault = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle();
            context.startActivity(intentTouchpad, optionsDefault);
        }
    }

    public static void requestFocusToGameActivity() {
        if (Game.instance != null) {
            ActivityManager am = (ActivityManager) Game.instance.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(Game.instance.getTaskId(), 0);
        }
    }
}
