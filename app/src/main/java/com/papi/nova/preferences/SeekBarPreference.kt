package com.papi.nova.preferences

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import androidx.preference.Preference
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A number set on the legacy settings screen. A tap or A opens a Slider page in the right-edge
 * panel: a track moved with Left and Right, an exact value, and Save. Focus returns to this row
 * when the panel closes.
 */
class SeekBarPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    private val suffix: String?
    private val defaultValue: Int
    private val maxValue: Int
    private val minValue: Int
    private val stepSize: Int
    private val keyStepSize: Int
    private val divisor: Int
    private var currentValue = 0

    init {
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
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1).coerceAtLeast(1)
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1)
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0)
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
    }

    fun getProgress(): Int = currentValue + minValue

    /** The page this preference opens: its current value, the range, and Save. */
    internal fun sliderPage(): NovaCommonPage.Slider {
        if (shouldPersist()) currentValue = getPersistedInt(defaultValue)
        return NovaCommonPage.Slider(
            key = "slider:$key",
            title = title?.toString().orEmpty(),
            value = currentValue.coerceIn(minValue, maxValue.coerceAtLeast(minValue)),
            range = minValue..maxValue.coerceAtLeast(minValue),
            step = if (keyStepSize > 0) keyStepSize else stepSize,
            format = ::format,
            onSave = ::save,
        )
    }

    fun showDialog() {
        val activity = context.findActivity() ?: return
        NovaSurfaces.of(activity).open(
            root = sliderPage(),
            edge = NovaEdge.End,
            returnFocus = activity.currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
        )
    }

    override fun onClick() {
        super.onClick()
        showDialog()
    }

    /** Saves [value], held to the preference's step, as the legacy slider did. */
    private fun save(value: Int) {
        val stepped = ((value.toFloat() / stepSize).roundToInt() * stepSize).coerceIn(minValue, maxValue.coerceAtLeast(minValue))
        if (!shouldPersist()) return
        currentValue = stepped
        persistInt(stepped)
        callChangeListener(stepped)
    }

    private fun format(value: Int): String {
        val label = if (divisor != 1) {
            String.format(null as Locale?, "%.1f", value / divisor.toFloat())
        } else {
            value.toString()
        }
        val unit = suffix ?: return label
        return if (unit.length > 1) context.getString(R.string.nova_settings_value_with_unit, label, unit) else label + unit
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    companion object {
        private const val ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar"
    }
}
