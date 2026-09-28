package com.papi.nova.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
        panel.background = novaBottomPanelBackground(
            fill = surfaces.panel.toArgb(),
            hairline = surfaces.panelBorder.toArgb(),
            corner = resources.getDimension(R.dimen.nova_radius_drawer),
            hairlinePx = resources.displayMetrics.density.toInt().coerceAtLeast(1),
        )
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

/**
 * A panel attached to the bottom edge: the panel colour with the drawer's corners on top, and its
 * hairline on the inner, top edge only (spec 4.3). The hairline layer runs one stroke past the
 * left, right and bottom edges, where the screen's own edges are and the panel's parent clips
 * it, so only its top edge and the curve of its top corners show.
 */
internal fun novaBottomPanelBackground(fill: Int, hairline: Int, corner: Float, hairlinePx: Int): LayerDrawable {
    val topCorners = floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
    val surface = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = topCorners
        setColor(fill)
    }
    val edge = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = topCorners
        setColor(Color.TRANSPARENT)
        setStroke(hairlinePx, hairline)
    }
    return LayerDrawable(arrayOf(surface, edge)).apply {
        setLayerInset(1, -hairlinePx, 0, -hairlinePx, -hairlinePx)
    }
}
