package com.limelight;

import android.app.Application;
import com.limelight.profiles.ProfilesManager;

public class MoonlightApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager.getInstance().load(this);
    }
}