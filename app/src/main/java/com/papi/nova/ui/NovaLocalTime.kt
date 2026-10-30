package com.papi.nova.ui

import android.content.Context
import android.text.format.DateUtils
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * When a stored UTC stamp was, for a person to read (#418). The stamp itself stays in the saved
 * reports and baselines; on screen it is this device's time zone, language and 12 or 24 hour clock.
 */
internal object NovaLocalTime {
    /**
     * The time alone on the day it is now, the date with it on any other. A stamp that does not parse
     * as Nova writes it is shown as written, never guessed at.
     */
    fun of(context: Context, utcStamp: String, nowMillis: Long = System.currentTimeMillis()): String {
        val moment = parse(utcStamp) ?: return utcStamp
        val flags = if (sameDay(moment.time, nowMillis)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        }
        return DateUtils.formatDateTime(context, moment.time, flags)
    }

    /** [of] with the zone it is in: "12:56 PM EDT". For a record, where the time stands alone. */
    fun withZone(context: Context, utcStamp: String, nowMillis: Long = System.currentTimeMillis()): String {
        val moment = parse(utcStamp) ?: return utcStamp
        val zone = TimeZone.getDefault()
        return of(context, utcStamp, nowMillis) + " " +
            zone.getDisplayName(zone.inDaylightTime(moment), TimeZone.SHORT, Locale.getDefault())
    }

    private fun parse(utcStamp: String): Date? {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val position = ParsePosition(0)
        return parser.parse(utcStamp, position)?.takeIf { position.index == utcStamp.length }
    }

    private fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
