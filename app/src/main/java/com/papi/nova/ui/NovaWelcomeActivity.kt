package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.PcView
import com.papi.nova.R
import com.papi.nova.preferences.AddComputerManually

/**
 * First-launch welcome screen. Shows once, then never again.
 */
class NovaWelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nova_welcome)

        findViewById<View>(R.id.welcome_discover_btn).setOnClickListener {
            finishWelcome(Intent(this, PcView::class.java))
        }
        findViewById<View>(R.id.welcome_add_manual_btn).setOnClickListener {
            finishWelcome(Intent(this, AddComputerManually::class.java))
        }
        findViewById<View>(R.id.welcome_scan_qr_btn).setOnClickListener {
            finishWelcome(
                Intent(this, PcView::class.java)
                    .putExtra(EXTRA_WELCOME_ACTION, ACTION_SCAN_QR),
            )
        }
    }

    private fun finishWelcome(next: Intent) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_WELCOME_SEEN, true)
            .commit()
        intent.extras?.let { next.putExtras(it) }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(next)
        finish()
    }

    companion object {
        const val EXTRA_WELCOME_ACTION = "com.papi.nova.extra.WELCOME_ACTION"
        const val ACTION_SCAN_QR = "scan_qr"
        private const val PREFS_NAME = "nova_prefs"
        private const val KEY_WELCOME_SEEN = "welcome_seen"

        fun shouldShow(context: Context): Boolean {
            return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WELCOME_SEEN, false)
        }
    }
}
