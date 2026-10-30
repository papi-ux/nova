package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaField
import java.util.Calendar
import java.util.Locale

/**
 * Nova's small hidden things (#424). All of it is local: a little state on this device and the local
 * date, with nothing asked of the host and nothing sent anywhere. None of it runs during a stream
 * ([NovaSkyQuiet]), and under reduced motion what would move is a still image ([SpaceParticleView]).
 */
internal object NovaEasterEggs {
    enum class Code { SUPERNOVA, LEONIDS, POLARIS }

    /** The code this text names, however it was typed; anything else is no code at all. */
    fun codeFor(text: String?): Code? {
        val typed = text?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return Code.entries.firstOrNull { it.name == typed }
    }

    private const val PREFS = "nova_easter_eggs"
    private const val KEY_SUPERNOVA_THEME = "supernova_theme"
    private const val KEY_NORTH_STAR = "north_star"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** SUPERNOVA offers the hidden theme in the theme lists. Choosing it stays the player's to do. */
    fun supernovaThemeUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_SUPERNOVA_THEME, false)

    fun unlockSupernovaTheme(context: Context) {
        prefs(context).edit().putBoolean(KEY_SUPERNOVA_THEME, true).apply()
    }

    fun northStarEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NORTH_STAR, false)

    /** POLARIS turns the North Star on, and entering it again turns it back off. Returns whether it is now on. */
    fun toggleNorthStar(context: Context): Boolean {
        val on = !northStarEnabled(context)
        prefs(context).edit().putBoolean(KEY_NORTH_STAR, on).apply()
        return on
    }

    /** Nova's Doctor remarks on a control round trip under 2 ms. MoonBridge's 0 means no estimate yet. */
    fun fasterThanLight(controlRttMs: Int?): Boolean = controlRttMs != null && controlRttMs in 1 until 2

    /** The peak nights of the Perseids, Leonids and Geminids. [month] runs from 1 for January. */
    fun isMeteorNight(month: Int, day: Int): Boolean = when (month) {
        8 -> day == 12 || day == 13
        11 -> day == 17 || day == 18
        12 -> day == 13 || day == 14
        else -> false
    }

    /** From the local date alone, so no location or time zone lookup comes into it. */
    fun isMeteorNight(today: Calendar = Calendar.getInstance()): Boolean =
        isMeteorNight(today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))

    /**
     * The panel that takes a code, labelled, with the keyboard the field already brings on every
     * input. An unknown code leaves as an empty one does, quietly and with nothing changed.
     */
    fun codePage(context: Context, onCode: (Code) -> Unit): NovaCommonPage.Form {
        val label = context.getString(R.string.nova_egg_enter_code)
        return NovaCommonPage.Form(
            key = "nova-secret-code",
            title = label,
            fields = listOf(NovaField(key = CODE_FIELD, label = label, maxLength = CODE_MAX_LENGTH)),
            submitLabel = context.getString(R.string.nova_egg_enter_code_submit),
            onSubmit = { values ->
                codeFor(values[CODE_FIELD])?.let(onCode)
                null
            },
        )
    }

    private const val CODE_FIELD = "code"
    private const val CODE_MAX_LENGTH = 24
}

/**
 * Counts presses of the version line. The fifth in a row opens the code panel; a long enough pause
 * starts the count again, so five stray presses over a long session don't add up to anything.
 */
internal class NovaVersionLineTaps(private val pressesNeeded: Int = 5, private val gapMs: Long = 3_000L) {
    private var count = 0
    private var lastAt = 0L

    /** Returns true on the press that completes the count. */
    fun press(nowMs: Long): Boolean {
        if (count > 0 && nowMs - lastAt > gapMs) count = 0
        lastAt = nowMs
        if (++count < pressesNeeded) return false
        count = 0
        return true
    }
}

/**
 * Which hosts just came online: seen offline, and now online. A host's first answer after Nova
 * opens is not one coming online, only Nova catching up.
 */
internal class NovaHostsComingOnline {
    private val offline = HashSet<String>()

    /** Takes each host's current state by UUID; returns the hosts that just came online. */
    fun update(states: Map<String, Boolean?>): Set<String> {
        val cameOnline = states.filterValues { it == true }.keys.filter { it in offline }.toSet()
        offline.removeAll(cameOnline)
        offline.retainAll(states.keys)
        states.forEach { (uuid, online) -> if (online == false) offline += uuid }
        return cameOnline
    }
}

/**
 * Whether a stream is running in this process, which keeps every moment of the sky off however the
 * windows are arranged. Counted, because a replacing Game can start before the old one has gone.
 */
internal object NovaSkyQuiet {
    private var streams = 0

    val active: Boolean @Synchronized get() = streams > 0

    @Synchronized fun streamStarted() {
        streams++
    }

    @Synchronized fun streamEnded() {
        if (streams > 0) streams--
    }
}
