#pragma once
#include <QJsonObject>
#include <QVariantMap>

namespace nova::deck::polaris {
// Read-only presentation. No action payload, result/session identity, arbitrary
// host prose, path, address or AI-generated recommendation crosses into QML.
QVariantMap doctorPresentation(const QJsonObject& doctor);
}
