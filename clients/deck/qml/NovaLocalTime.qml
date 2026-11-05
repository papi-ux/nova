pragma Singleton
import QtQuick

// When a stored UTC stamp was, for a person to read (Nova #418). Saved moment
// reports and baselines keep their UTC stamps; on screen it is this device's time
// zone, language and clock. The same family as Android's NovaLocalTime: the time
// alone on the day it is now, the date with it on any other, then the zone, as in
// "9:00 AM EDT". Qt names the zone; where it can't tell, local time is UTC and the
// zone reads UTC. A stamp that isn't one Nova writes is shown as written, never guessed at.
QtObject {
    // Qt::ISODate or Qt::ISODateWithMs, in UTC.
    function parse(stamp) {
        const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?Z$/.exec(typeof stamp === "string" ? stamp : "")
        if (!match) return null
        const part = match.slice(1, 7).map(Number)
        const moment = new Date(Date.UTC(part[0], part[1] - 1, part[2], part[3], part[4], part[5], Number(match[7] || 0)))
        // Date.UTC rolls 31 February into March and two-digit years into the 1900s.
        const exact = moment.getUTCFullYear() === part[0] && moment.getUTCMonth() === part[1] - 1 && moment.getUTCDate() === part[2]
            && moment.getUTCHours() === part[3] && moment.getUTCMinutes() === part[4] && moment.getUTCSeconds() === part[5]
        return exact ? moment : null
    }
    function written(stamp) { return stamp === undefined || stamp === null ? "" : String(stamp) }
    function of(stamp, now) {
        const moment = parse(stamp)
        if (!moment) return written(stamp)
        const today = now || new Date()
        const time = Qt.formatDateTime(moment, Qt.locale().timeFormat(Locale.ShortFormat))
        if (moment.getFullYear() === today.getFullYear() && moment.getMonth() === today.getMonth() && moment.getDate() === today.getDate())
            return time
        return Qt.formatDateTime(moment, moment.getFullYear() === today.getFullYear() ? "MMM d" : "MMM d, yyyy") + ", " + time
    }
    // For a record, where the time stands alone: "9:00 AM EDT".
    function withZone(stamp, now) {
        const moment = parse(stamp)
        if (!moment) return written(stamp)
        const zone = Qt.formatDateTime(moment, "t")
        return of(stamp, now) + (zone.length > 0 ? " " + zone : "")
    }
}
