package com.papi.nova;

import android.app.Application;
import android.widget.Toast;

import com.papi.nova.profiles.ProfilesManager;

public class NovaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}