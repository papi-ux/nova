package com.papi.nova.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.papi.nova.R
import com.papi.nova.NovaActivity
import com.papi.nova.ui.compose.librarySurfaces
import com.papi.nova.ui.compose.novaComposeColors

/**
 * Nova-themed QR code scanner activity: the camera fills the screen, and what to do stands on a
 * panel attached to the bottom edge, in the theme's panel colour with the drawer's corners on top.
 */
class NovaQrScanActivity : NovaActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nova_qr_scanner)
        // The camera fills the screen under the bars, where nothing it shows needs to be read; the
        // panel meets the bottom edge and pads its own text clear of the bars and any cutout.
        attachPanelToBottomEdge(findViewById(R.id.qr_panel))

        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    private fun attachPanelToBottomEdge(panel: View) {
        val opacity = NovaMenuPreferences.opacityScale(
            NovaMenuPreferences.readOpacityPercent(PreferenceManager.getDefaultSharedPreferences(this)),
        )
        val surfaces = novaComposeColors(this).librarySurfaces(NovaThemeManager.getTheme(this), opacity)
        val corner = resources.getDimension(R.dimen.nova_radius_drawer)
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
            setColor(surfaces.panel.toArgb())
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), surfaces.panelBorder.toArgb())
        }
        val start = panel.paddingStart
        val top = panel.paddingTop
        val end = panel.paddingEnd
        val bottom = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(start + bars.left, top, end + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(panel)
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}
