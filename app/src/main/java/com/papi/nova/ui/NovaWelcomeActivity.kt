package com.papi.nova.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.R

/**
 * First-launch welcome screen. Shows once, then never again.
 */
class NovaWelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nova_welcome)

        findViewById<android.view.View>(R.id.welcome_start_btn).setOnClickListener {
            // Mark as seen
            getSharedPreferences("nova_prefs", MODE_PRIVATE).edit()
                .putBoolean("welcome_seen", true)
                .apply()
            finish()
        }
    }

    companion object {
        fun shouldShow(context: android.content.Context): Boolean {
            return !context.getSharedPreferences("nova_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("welcome_seen", false)
        }
    }
}
