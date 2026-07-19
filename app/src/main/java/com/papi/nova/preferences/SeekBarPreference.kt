package com.papi.nova.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.papi.nova.R
import com.papi.nova.ui.NovaSheetChrome
import java.util.Locale
import kotlin.math.roundToInt

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
class SeekBarPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    private var dialog: AlertDialog? = null
    private var seekBar: SeekBar? = null
    private var valueText: TextView? = null

    private val dialogMessage: String?
    private val suffix: String?
    private val defaultValue: Int
    private val maxValue: Int
    private val minValue: Int
    private val stepSize: Int
    private val keyStepSize: Int
    private val divisor: Int
    private var currentValue = 0

    private val seekbarMax: Int

    init {
        val dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0)
        dialogMessage = if (dialogMessageId == 0) {
            attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage")
        } else {
            context.getString(dialogMessageId)
        }

        val suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0)
        suffix = if (suffixId == 0) {
            attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text")
        } else {
            context.getString(suffixId)
        }

        defaultValue = attrs.getAttributeIntValue(
            ANDROID_SCHEMA_URL,
            "defaultValue",
            PreferenceConfiguration.getDefaultBitrate(context)
        )
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100)
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1)
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1)
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1)
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0)
        seekbarMax = maxValue - minValue
    }

    protected fun getDialog(): AlertDialog {
        dialog?.let { return it }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }

        val splashText = TextView(context).apply {
            setPadding(30, 10, 30, 10)
            dialogMessage?.let { text = it }
        }
        layout.addView(splashText)

        valueText = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 32f
            // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%.
            text = "0%"
        }
        layout.addView(
            valueText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        seekBar = SeekBar(context).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    var value = progress + minValue
                    if (value < minValue) {
                        seekBar.progress = 0
                        return
                    }

                    val roundedValue = (value.toFloat() / stepSize).roundToInt() * stepSize
                    if (roundedValue != value) {
                        seekBar.progress = roundedValue - minValue
                        return
                    }

                    val valueLabel = if (divisor != 1) {
                        val floatValue = roundedValue / divisor.toFloat()
                        String.format(null as Locale?, "%.1f", floatValue)
                    } else {
                        value.toString()
                    }
                    valueText?.text = suffix?.let {
                        valueLabel + if (it.length > 1) " $it" else it
                    } ?: valueLabel
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        layout.addView(
            seekBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue)
        }

        updateSeekbar()

        val createdDialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("OK") { dialog, _ ->
                val activeSeekBar = seekBar ?: return@setPositiveButton
                if (shouldPersist()) {
                    currentValue = activeSeekBar.progress + minValue
                    persistInt(currentValue)
                    callChangeListener(currentValue)
                }
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .create()
        NovaSheetChrome.applyMenuOpacityToLegacyAlert(createdDialog)
        dialog = createdDialog
        return createdDialog
    }

    protected fun updateSeekbar() {
        seekBar?.apply {
            max = seekbarMax
            if (keyStepSize != 0) {
                keyProgressIncrement = keyStepSize
            }
            progress = currentValue - minValue
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restorePersistedValue, defaultValue)
        currentValue = if (restorePersistedValue) {
            if (shouldPersist()) getPersistedInt(this.defaultValue) else 0
        } else {
            defaultValue as Int
        }
    }

    fun setProgress(progress: Int) {
        currentValue = progress
        seekBar?.progress = progress - minValue
    }

    fun getProgress(): Int = currentValue + minValue

    fun showDialog() {
        val activeDialog = getDialog()
        updateSeekbar()
        activeDialog.show()
    }

    override fun onClick() {
        super.onClick()
        showDialog()
    }

    companion object {
        private const val ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar"
    }
}
