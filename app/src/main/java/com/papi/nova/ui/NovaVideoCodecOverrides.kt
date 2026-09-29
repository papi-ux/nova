package com.papi.nova.ui

import com.papi.nova.binding.video.PyroWaveAvailability
import android.content.Context
import android.content.Intent
import com.papi.nova.BuildConfig
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption

/** A client codec preference for one host/game, independent of global Settings and host profiles. */
object NovaVideoCodecOverrides {
    private const val PREFS = "nova_prefs"
    private const val PREFIX = "video_codec_override_"

    fun normalize(value: String?, experimental: Boolean = BuildConfig.EXPERIMENTAL_CODECS): String? =
        value?.takeIf {
            it in setOf("auto", "neverh265", "forceh265", "forceav1") ||
                (experimental && it == "forcepyrowave")
        }

    private fun key(host: String?, game: String?, appId: Int): String? {
        if (host.isNullOrBlank() || WorkerLaunchContract.isProfileApp(game)) return null
        val identity = game?.takeIf { it.isNotBlank() }?.let { "uuid:$it" }
            ?: appId.takeIf { it > 0 }?.let { "app:$it" } ?: return null
        // Length-prefix the host so neither an opaque game ID nor the numeric fallback can collide.
        return "$PREFIX${host.length}:$host:$identity"
    }

    fun load(context: Context, host: String?, game: String?, appId: Int): String? {
        val key = key(host, game, appId) ?: return null
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(key, null) ?: return null
        return normalize(raw).also {
            // A restored beta preference must never become hidden launch authority in stable.
            if (it == null) preferences.edit().remove(key).apply()
        }
    }

    fun save(context: Context, host: String?, game: String?, appId: Int, value: String?) {
        val key = key(host, game, appId) ?: return
        val normalized = normalize(value)
        if (value != null && normalized == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (normalized == null) remove(key) else putString(key, normalized)
        }.apply()
    }

    fun resolve(value: String?, appSetting: FormatOption?): FormatOption? = when (normalize(value)) {
        "auto" -> FormatOption.AUTO
        "neverh265" -> FormatOption.FORCE_H264
        "forceh265" -> FormatOption.FORCE_HEVC
        "forceav1" -> FormatOption.FORCE_AV1
        "forcepyrowave" -> FormatOption.FORCE_PYROWAVE
        else -> appSetting
    }

    /** Called before Game creates its decoder; covers library, shortcuts, and resumed launches. */
    fun applyToLaunch(context: Context, intent: Intent, preferences: PreferenceConfiguration) {
        val saved = load(context, intent.getStringExtra(Game.EXTRA_PC_UUID),
            intent.getStringExtra(Game.EXTRA_APP_UUID), intent.getIntExtra(Game.EXTRA_APP_ID, 0))
        preferences.videoFormat = resolve(saved, preferences.videoFormat)
    }

    fun encoderBackend(format: FormatOption?, saved: String): String =
        if (format == FormatOption.FORCE_PYROWAVE) "" else saved

    fun label(format: FormatOption?): String = when (format) {
        FormatOption.FORCE_H264 -> "H.264"
        FormatOption.FORCE_HEVC -> "HEVC"
        FormatOption.FORCE_AV1 -> "AV1"
        FormatOption.FORCE_PYROWAVE -> "PyroWave"
        else -> "Auto"
    }
}

/**
 * Uses the same variant-specific resource catalog as Settings; stable has no experimental entry.
 * The row opens its page (R2): each codec carries a sentence, and PyroWave's is too long for a
 * row. [preview] gives what the plan card shows while a codec has focus there, from the codec it
 * would launch with; null for one that leaves the choice to the app setting or the host.
 */
/**
 * One sentence for each codec, the same on Play Setup's page and in Settings: every standard codec
 * had carried one shared sentence, and "Recommended" sat in the name instead of leading its note.
 */
internal fun novaCodecOptionDetail(context: Context, value: String): String = context.getString(
    when (value) {
        "auto" -> R.string.nova_codec_detail_auto
        "forceav1" -> R.string.nova_codec_detail_av1
        "forceh265" -> R.string.nova_codec_detail_hevc
        "neverh265" -> R.string.nova_codec_detail_h264
        "forcepyrowave" -> R.string.nova_play_setup_codec_pyrowave_detail
        else -> R.string.nova_play_setup_codec_standard_detail
    },
)

internal fun novaPlaySetupCodecRow(
    context: Context,
    selected: String?,
    appSetting: FormatOption?,
    preview: (FormatOption) -> NovaPlaySetupPreview? = { null },
    onSelect: (String?) -> Unit,
): NovaPlaySetupRowState {
    val values = context.resources.getStringArray(R.array.video_format_values)
    val labels = context.resources.getStringArray(R.array.video_format_names)
    val effective = NovaVideoCodecOverrides.resolve(selected, appSetting)
    val options = values.zip(labels).filter { NovaVideoCodecOverrides.normalize(it.first) != null }
    val availability = if (options.any { it.first == "forcepyrowave" })
        PyroWaveAvailability.inspect(context.applicationContext)
        else PyroWaveAvailability.Status.AVAILABLE
    val unavailableReason = PyroWaveAvailability.reason(context, availability)
    return NovaPlaySetupRowState(
        row = NovaPlaySetupRow.VIDEO_CODEC,
        label = context.getString(R.string.nova_play_setup_video_codec),
        value = if (selected == null) context.getString(R.string.nova_play_setup_codec_inherited,
            NovaVideoCodecOverrides.label(appSetting)) else options.firstOrNull { it.first == selected }?.second.orEmpty(),
        caption = if (effective == FormatOption.FORCE_PYROWAVE && unavailableReason.isNotEmpty())
            unavailableReason else context.getString(if (effective == FormatOption.FORCE_PYROWAVE)
                R.string.nova_play_setup_codec_pyrowave_caption else R.string.nova_play_setup_codec_caption),
        options = listOf(NovaPlaySetupOption(
            label = context.getString(R.string.nova_play_setup_codec_app_setting),
            value = NovaVideoCodecOverrides.label(appSetting),
            consequence = if (!PyroWaveAvailability.canLaunch(appSetting, availability))
                unavailableReason else context.getString(R.string.nova_play_setup_codec_inherit_detail),
            current = selected == null,
            enabled = PyroWaveAvailability.canLaunch(appSetting, availability),
            onSelect = if (PyroWaveAvailability.canLaunch(appSetting, availability))
                ({ onSelect(null) }) else null,
        )) + options.map { (value, label) ->
            val format = NovaVideoCodecOverrides.resolve(value, null)
            NovaPlaySetupOption(
                label = label,
                consequence = if (value == "forcepyrowave" && unavailableReason.isNotEmpty())
                    unavailableReason else novaCodecOptionDetail(context, value),
                current = value == selected,
                enabled = PyroWaveAvailability.canSelect(value, availability),
                onSelect = if (PyroWaveAvailability.canSelect(value, availability))
                    ({ onSelect(value) }) else null,
                preview = format?.takeIf { it != FormatOption.AUTO }?.let(preview),
            )
        },
        overridden = selected != null,
        opensPage = true,
    )
}
