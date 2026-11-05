#include <QDate>
#include <QDateTime>
#include <QGuiApplication>
#include <QLocale>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QTime>
#include <QTimeZone>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <time.h>

// Nova #418 on the Deck: a stored UTC stamp reads in this device's time zone, in the
// family Android's NovaLocalTime uses. The zone is a POSIX rule so the fixture needs no
// tzdata. With --utc the device's zone is UTC, and only then does the line say UTC.
namespace {
void require(bool ok, const QString& message) {
    if (!ok) { std::cerr << qPrintable(message) << '\n'; std::exit(1); }
}
// Newer CLDR data puts a narrow no-break space before AM/PM; read it as a space.
QString shown(QString text) { return text.replace(QChar(0x202F), u' ').replace(QChar(0x00A0), u' '); }
QDateTime utcAt(int year, int month, int day, int hour, int minute) {
    return QDateTime(QDate(year, month, day), QTime(hour, minute), QTimeZone::utc());
}
}

int main(int argc, char** argv) {
    const bool utc = argc > 1 && QByteArray(argv[1]) == "--utc";
    qputenv("TZ", utc ? "UTC0" : "EST5EDT,M3.2.0,M11.1.0"); tzset();
    QGuiApplication app(argc, argv);
    QLocale::setDefault(QLocale(QLocale::English, QLocale::UnitedStates));
    require(utcAt(2026, 10, 8, 13, 0).toLocalTime().time() == (utc ? QTime(13, 0) : QTime(9, 0)), "the fixture time zone was not applied");

    QQmlEngine engine;
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
QtObject {
    function at(stamp, nowMs) { return NovaLocalTime.withZone(stamp, new Date(nowMs)) }
    function bare(stamp, nowMs) { return NovaLocalTime.of(stamp, new Date(nowMs)) }
    function unset() { return NovaLocalTime.withZone(undefined) + "|" + NovaLocalTime.withZone(null) }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/LocalTimeTest.qml"));
    std::unique_ptr<QObject> root(component.create());
    require(bool(root), component.errorString());
    const auto call = [&](const char* method, const QString& stamp, const QDateTime& now) {
        QVariant result;
        require(QMetaObject::invokeMethod(root.get(), method, Q_RETURN_ARG(QVariant, result),
            Q_ARG(QVariant, stamp), Q_ARG(QVariant, double(now.toMSecsSinceEpoch()))), QStringLiteral("cannot call ") + method);
        return shown(result.toString());
    };
    const auto expect = [&](const char* method, const QString& stamp, const QDateTime& now, const QString& wanted) {
        const auto actual = call(method, stamp, now);
        require(actual == wanted, QStringLiteral("%1(%2) at %3: expected \"%4\", got \"%5\"")
            .arg(method, stamp, now.toString(Qt::ISODate), wanted, actual));
    };

    const auto sameDay = utcAt(2026, 10, 8, 15, 0);
    const auto threeDaysOn = sameDay.addDays(3);
    const auto nextYear = utcAt(2027, 1, 2, 15, 0);
    if (utc) {
        expect("at", "2026-10-08T13:00:00Z", sameDay, "1:00 PM UTC");
        expect("at", "2026-10-08T13:00:00.123Z", threeDaysOn, "Oct 8, 1:00 PM UTC");
        std::cout << "With the device in UTC, a stamp reads as UTC\n";
        return 0;
    }

    // A record: the local time and its zone, today alone, another day with its date.
    expect("at", "2026-10-08T13:00:00Z", sameDay, "9:00 AM EDT");
    expect("at", "2026-10-08T13:00:00.123Z", sameDay, "9:00 AM EDT");
    expect("bare", "2026-10-08T13:00:00.123Z", sameDay, "9:00 AM");
    expect("at", "2026-10-08T13:00:00.000Z", threeDaysOn, "Oct 8, 9:00 AM EDT");
    expect("at", "2026-10-08T13:00:00.000Z", nextYear, "Oct 8, 2026, 9:00 AM EDT");
    // The day is this device's, not UTC's: 02:30 UTC on the 9th is still the 8th here.
    expect("at", "2026-10-09T02:30:00.000Z", sameDay, "10:30 PM EDT");
    // The zone follows the stamp's own date.
    expect("at", "2026-01-08T13:00:00.000Z", utcAt(2026, 1, 8, 15, 0), "8:00 AM EST");

    // A stamp Nova doesn't write is shown as written, never guessed at.
    for (const auto* written : {"unknown", "2026-10-08T13:00:00", "2026-10-08T13:00:00Zjunk", "2026-02-31T13:00:00Z",
            "2026-10-08T13:00:00.12Z", "2026-10-08 13:00:00Z", "0026-10-08T13:00:00Z", ""}) {
        expect("at", written, sameDay, written);
        expect("bare", written, sameDay, written);
    }
    QVariant unset;
    require(QMetaObject::invokeMethod(root.get(), "unset", Q_RETURN_ARG(QVariant, unset)) && unset.toString() == "|",
        "a missing stamp was not shown as empty: " + unset.toString());
    std::cout << "Stamps read in local time with their zone; unknown stamps are shown as written\n";
    return 0;
}
