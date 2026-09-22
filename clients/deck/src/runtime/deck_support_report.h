#pragma once
#include <QJsonObject>
#include <QVariantMap>

namespace nova::deck::runtime {
// Closed-schema export: no identity, free-form host strings, journals or logs.
QJsonObject deckSupportReport(const QVariantMap& hud);
QVariantMap saveDeckSupportReport(const QVariantMap& hud, const QString& directory);
QString deckSupportReportDirectory();
}
