package com.papi.nova.ui

import android.content.Context
import android.content.SharedPreferences
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsRepository
import com.papi.nova.preferences.NovaSettingsStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NovaMenuOpacityPreview {
    private val nextOwner = AtomicLong()
    private val mutableOpacityPercent = MutableStateFlow<Int?>(null)
    private var activeOwner: Long? = null
    val opacityPercent: StateFlow<Int?> = mutableOpacityPercent.asStateFlow()

    fun newOwner(): Long = nextOwner.incrementAndGet()

    @Synchronized
    fun update(owner: Long, percent: Int) {
        activeOwner = owner
        mutableOpacityPercent.value = NovaMenuPreferences.coerceOpacityPercent(percent)
    }

    @Synchronized
    fun clear(owner: Long) {
        if (activeOwner != owner) return
        activeOwner = null
        mutableOpacityPercent.value = null
    }
}

object NovaMenuPreferences {
    const val KEY_OPACITY = "nova_menu_opacity"
    const val DEFAULT_OPACITY_PERCENT = 64
    const val MIN_OPACITY_PERCENT = 0
    const val MAX_OPACITY_PERCENT = 100
    const val MAX_BLUR_RADIUS_DP = 24f
    const val MIN_READABILITY_SCRIM_ALPHA = 0.54f
    const val MIN_DARK_TEXT_SCRIM_ALPHA = 0.57f
    const val MIN_DARK_TEXT_SURFACE_ALPHA = 0.71f

    val OPACITY_PRESETS = listOf(0, 25, 64, 90, 100)

    fun coerceOpacityPercent(percent: Int): Int {
        return percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
    }

    fun readOpacityPercent(prefs: SharedPreferences): Int {
        return coerceOpacityPercent(prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY_PERCENT))
    }

    fun writeOpacityPercent(prefs: SharedPreferences, percent: Int) {
        prefs.edit()
            .putInt(KEY_OPACITY, coerceOpacityPercent(percent))
            .apply()
    }

    suspend fun writeOpacityPercent(context: Context, percent: Int) {
        val definitions = NovaSettingDefinitions.load(context)
        writeOpacityPercent(
            store = NovaSettingsRepository.create(context),
            definition = definitions.require(KEY_OPACITY),
            percent = percent
        )
    }

    suspend fun writeOpacityPercent(
        store: NovaSettingsStore,
        definition: NovaSettingDefinition,
        percent: Int
    ) {
        store.set(definition, NovaSettingValue.IntValue(coerceOpacityPercent(percent)))
    }

    fun opacityScale(percent: Int): Float {
        return coerceOpacityPercent(percent) / 100f
    }

    fun readabilityScrimAlpha(baseAlpha: Float, opacityPercent: Int): Float {
        return readabilityScrimAlpha(baseAlpha, opacityScale(opacityPercent))
    }

    fun readabilityScrimAlpha(baseAlpha: Float, opacityScale: Float): Float {
        return readabilityScrimAlpha(baseAlpha, opacityScale, usesDarkText = false)
    }

    fun readabilityScrimAlpha(
        baseAlpha: Float,
        opacityScale: Float,
        usesDarkText: Boolean
    ): Float {
        val scale = opacityScale.coerceIn(0f, 1f)
        val floor = if (usesDarkText) MIN_DARK_TEXT_SCRIM_ALPHA else MIN_READABILITY_SCRIM_ALPHA
        return (
            baseAlpha.coerceIn(0f, 1f) * scale +
                floor * (1f - scale)
            ).coerceIn(0f, 1f)
    }

    fun outerSurfaceAlpha(opacityPercent: Int, usesDarkText: Boolean): Float {
        return outerSurfaceAlpha(opacityScale(opacityPercent), usesDarkText)
    }

    fun outerSurfaceAlpha(opacityScale: Float, usesDarkText: Boolean): Float {
        val absoluteAlpha = opacityScale.coerceIn(0f, 1f)
        if (!usesDarkText || absoluteAlpha >= 1f) return absoluteAlpha
        return maxOf(absoluteAlpha, MIN_DARK_TEXT_SURFACE_ALPHA)
    }

    fun alphaByte(baseAlpha: Float, percent: Int): Int {
        return (baseAlpha.coerceIn(0f, 1f) * opacityScale(percent) * 255f)
            .toInt()
            .coerceIn(0, 255)
    }

    fun blurRadiusDp(percent: Int): Float {
        return MAX_BLUR_RADIUS_DP * (1f - opacityScale(percent))
    }

    fun scaleAlpha(baseAlpha: Float, percent: Int): Float {
        return (baseAlpha.coerceIn(0f, 1f) * opacityScale(percent)).coerceIn(0f, 1f)
    }
}
