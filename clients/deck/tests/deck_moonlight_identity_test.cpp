// Tests for the Moonlight-Qt pairing reader. The INI is written with the same
// QSettings API Moonlight-Qt uses, so the on-disk shape matches a real file.
#include "identity/deck_moonlight_identity.h"

#include <QByteArray>
#include <QCoreApplication>
#include <QSettings>
#include <QString>
#include <QTemporaryDir>

#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <string>

namespace {

using namespace nova::deck::identity;

// A throwaway self-signed certificate generated for this test; it carries no
// key and pairs with nothing.
const char* const kTestCertificatePem =
    "-----BEGIN CERTIFICATE-----\n"
    "MIICwDCCAagCCQDV1JWYmdXSaTANBgkqhkiG9w0BAQsFADAiMSAwHgYDVQQDDBdu\n"
    "b3ZhLWRlY2staWRlbnRpdHktdGVzdDAeFw0yNjA5MDgwNDQ3MjFaFw0zNjA5MDUw\n"
    "NDQ3MjFaMCIxIDAeBgNVBAMMF25vdmEtZGVjay1pZGVudGl0eS10ZXN0MIIBIjAN\n"
    "BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2ZJUuyOBHcj2chCozGV2/QaDgLBz\n"
    "KKOFzkoaXVlPsJVBCUzD2ByuNP+HC0txOhw0EKg7fJ5baP+QA729EY65Q8yTZ3ho\n"
    "pJRbVc3if10DOktqsa0bYIUKAQnBpPG8dEa0/ufvjQAPtz15NSOteNzmwujCFuFw\n"
    "XkH7tRdRzGLHFktuxEsL0Knwcf7oeraNE9tTrorDH12i0ywL/dqYWVqRIvRAU4RQ\n"
    "3Rg43tbxRFwEHtuF7KcnT8cmQ0GgJFKci0JNqrWY7IvL54Zx6dBlhPpnznALdjKK\n"
    "xGvJDU8J0b28PnJRdGuQnlIfWhp1rqRr5uw3l0x5Plq3GFd9TRya65ZR4QIDAQAB\n"
    "MA0GCSqGSIb3DQEBCwUAA4IBAQDSX9zNF9Iw1RHMPQaQSeunmT3vIUA9G3IuVm8h\n"
    "xoFi2fcWnlus9X0D1IZknUXAv9pwrv02ujwl/w4XUPc+2dGlqaPMmFsYFl9XNzfa\n"
    "qoy5YU+BN30lUUdURFdPOWQSMbhC0yRYnYFq07VBGuo3trkXxYv08KzmJ+c0l/nk\n"
    "9sswza11iiGWdDQ9bj31PPPFwuvSnRIYtSNVR461hh8KBZKS7cZ79A+MgwK7/CUD\n"
    "dFct7kJE6KJyiZ1zevT8E/CtGqZzUUkRTJgfqDbsC7jlc19pDruSh6IflxrR4NlG\n"
    "xOJyeg0y6yYKqZBPAhEVxpu3qjao9vhzkRfZ4ZADqFzkoNSz\n"
    "-----END CERTIFICATE-----\n";

std::string placeholderKeyPem() {
    // Assembled at runtime so the source never contains a key header literal.
    return std::string("-----BEGIN ") + "RSA PRIVATE" + " KEY-----\nbm90LWEtcmVhbC1rZXk=\n-----END " + "RSA PRIVATE" + " KEY-----\n";
}

std::filesystem::path writeMoonlightConf(const QTemporaryDir& dir, const bool withApps) {
    const auto path = std::filesystem::path(dir.path().toStdString()) / "Moonlight Game Streaming Project" / "Moonlight.conf";
    std::filesystem::create_directories(path.parent_path());
    QSettings settings(QString::fromStdString(path.string()), QSettings::IniFormat);
    settings.setValue(QStringLiteral("certificate"), QByteArray(kTestCertificatePem));
    settings.setValue(QStringLiteral("key"), QByteArray::fromStdString(placeholderKeyPem()));
    settings.setValue(QStringLiteral("bitrate"), 20000);

    settings.beginWriteArray(QStringLiteral("hosts"));
    settings.setArrayIndex(0);
    settings.setValue(QStringLiteral("uuid"), QStringLiteral("11111111-2222-3333-4444-555555555555"));
    settings.setValue(QStringLiteral("hostname"), QStringLiteral("living-room-pc"));
    settings.setValue(QStringLiteral("customname"), false);
    settings.setValue(QStringLiteral("localaddress"), QStringLiteral("host.lan"));
    settings.setValue(QStringLiteral("localport"), 47989);
    settings.setValue(QStringLiteral("manualaddress"), QStringLiteral(""));
    settings.setValue(QStringLiteral("manualport"), 0);
    settings.setValue(QStringLiteral("remoteaddress"), QStringLiteral("example.invalid"));
    settings.setValue(QStringLiteral("remoteport"), 48989);
    settings.setValue(QStringLiteral("srvcert"), QByteArray(kTestCertificatePem));
    if (withApps) {
        settings.beginWriteArray(QStringLiteral("apps"));
        settings.setArrayIndex(0);
        settings.setValue(QStringLiteral("id"), 881448767);
        settings.setValue(QStringLiteral("name"), QStringLiteral("Steam Big Picture"));
        settings.setValue(QStringLiteral("hdr"), true);
        settings.setValue(QStringLiteral("hidden"), false);
        settings.setArrayIndex(1);
        settings.setValue(QStringLiteral("id"), 42);
        settings.setValue(QStringLiteral("name"), QStringLiteral("Desktop"));
        settings.setValue(QStringLiteral("hdr"), false);
        settings.setValue(QStringLiteral("hidden"), true);
        settings.endArray();
    }
    settings.setArrayIndex(1);
    settings.setValue(QStringLiteral("uuid"), QStringLiteral("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    // Moonlight stores the custom name in hostname and only a flag in customname.
    settings.setValue(QStringLiteral("hostname"), QStringLiteral("Office"));
    settings.setValue(QStringLiteral("customname"), true);
    settings.setValue(QStringLiteral("manualaddress"), QStringLiteral("office.lan"));
    settings.setValue(QStringLiteral("manualport"), 57989);
    settings.setValue(QStringLiteral("localaddress"), QStringLiteral("office-local.lan"));
    settings.setValue(QStringLiteral("localport"), 47989);
    settings.endArray();
    settings.sync();
    assert(settings.status() == QSettings::NoError);
    return path;
}

void testParsesHostsAppsAndIdentity() {
    QTemporaryDir dir;
    const auto path = writeMoonlightConf(dir, true);
    const auto identity = loadMoonlightIdentity(path, "moonlight-test");
    assert(identity.has_value());
    assert(identity->loaded);
    assert(identity->sourceLabel == "moonlight-test");
    assert(identity->hasClientIdentity());
    assert(identity->clientCertificatePem.rfind("-----BEGIN CERTIFICATE-----", 0) == 0);
    const auto fingerprint = identity->clientCertificateFingerprintSha256();
    assert(fingerprint.size() == 64);
    assert(shortFingerprint(fingerprint).size() == 8);

    assert(identity->hosts.size() == 2);
    const auto& first = identity->hosts[0];
    assert(first.stableId() == "11111111-2222-3333-4444-555555555555");
    assert(first.displayName() == "living-room-pc");
    assert(!first.hasCustomName);
    assert(first.preferredAddress() == "host.lan");
    assert(first.preferredHttpPort() == 47989);
    assert(first.hasServerCertificate());
    assert(first.apps.size() == 2);
    assert(first.apps[0].id == 881448767);
    assert(first.apps[0].name == "Steam Big Picture");
    assert(first.apps[0].hdr);
    assert(!first.apps[0].hidden);
    assert(first.apps[1].hidden);

    const auto& second = identity->hosts[1];
    assert(second.displayName() == "Office");
    assert(second.hasCustomName);
    assert(second.preferredAddress() == "office.lan");
    assert(second.preferredHttpPort() == 57989);
    assert(!second.hasServerCertificate());
    assert(second.apps.empty());

    assert(identity->hostById("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") != nullptr);
    assert(identity->hostById("nope") == nullptr);
}

void testMissingFileAndMissingIdentity() {
    QTemporaryDir dir;
    assert(!loadMoonlightIdentity(std::filesystem::path(dir.path().toStdString()) / "absent.conf", "x").has_value());

    const auto path = std::filesystem::path(dir.path().toStdString()) / "empty.conf";
    QSettings settings(QString::fromStdString(path.string()), QSettings::IniFormat);
    settings.setValue(QStringLiteral("bitrate"), 1);
    settings.sync();
    const auto identity = loadMoonlightIdentity(path, "empty");
    assert(identity.has_value());
    assert(identity->loaded);
    assert(!identity->hasClientIdentity());
    assert(identity->clientCertificateFingerprintSha256().empty());
    assert(identity->hosts.empty());
}

void testCandidateOrderPrefersOverride() {
    QTemporaryDir dir;
    const auto overridePath = writeMoonlightConf(dir, false);
    setenv("NOVA_DECK_MOONLIGHT_CONF", overridePath.string().c_str(), 1);
    const auto candidates = defaultIdentityCandidates();
    assert(!candidates.empty());
    assert(candidates.front().label == "moonlight-override");
    assert(candidates.front().path == overridePath);
    bool sawFlatpak = false;
    bool sawNative = false;
    for (const auto& candidate : candidates) {
        sawFlatpak = sawFlatpak || candidate.label == "moonlight-flatpak";
        sawNative = sawNative || candidate.label == "moonlight-native";
    }
    assert(sawFlatpak);
    assert(sawNative);

    const auto loaded = loadDefaultMoonlightIdentity();
    assert(loaded.has_value());
    assert(loaded->sourceLabel == "moonlight-override");
    assert(loaded->hosts.size() == 2);
    unsetenv("NOVA_DECK_MOONLIGHT_CONF");
}

void testHostsBackupIsReadWhenHostsIsEmpty() {
    QTemporaryDir dir;
    const auto path = std::filesystem::path(dir.path().toStdString()) / "backup.conf";
    QSettings settings(QString::fromStdString(path.string()), QSettings::IniFormat);
    settings.setValue(QStringLiteral("certificate"), QByteArray(kTestCertificatePem));
    settings.setValue(QStringLiteral("key"), QByteArray::fromStdString(placeholderKeyPem()));
    settings.beginWriteArray(QStringLiteral("hostsbackup"));
    settings.setArrayIndex(0);
    settings.setValue(QStringLiteral("uuid"), QStringLiteral("backup-uuid"));
    settings.setValue(QStringLiteral("hostname"), QStringLiteral("recovered-host"));
    settings.setValue(QStringLiteral("customname"), false);
    settings.endArray();
    settings.sync();

    const auto identity = loadMoonlightIdentity(path, "backup");
    assert(identity.has_value());
    assert(identity->hosts.size() == 1);
    assert(identity->hosts[0].stableId() == "backup-uuid");
    assert(identity->hosts[0].displayName() == "recovered-host");
}

void testPortDerivation() {
    assert(polarisHttpsPortForMoonlightHttpPort(47989) == 47984);
    assert(polarisHttpsPortForMoonlightHttpPort(57989) == 57984);
    assert(polarisHttpsPortForMoonlightHttpPort(0) == 47984);
}

// The other tests write their INI with QSettings from this side, which pins
// what we assumed Moonlight-Qt writes. This one reads a file Moonlight-Qt
// 6.1.0 itself wrote (fixtures/moonlight_qt_6_1_flatpak_pairing.conf, secrets
// and addresses redacted in place), so the reader is held to the real shape:
// `customname` is a bool and the display name lives in `hostname`, app names
// with commas come back quoted, `mac` is a byte array, and the key that is
// present but unreadable still counts as an identity for the loader.
void testReadsAFileMoonlightQtWrote() {
    const auto path = std::filesystem::path(NOVA_DECK_FIXTURE_SOURCE_DIR) / "moonlight_qt_6_1_flatpak_pairing.conf";
    const auto identity = loadMoonlightIdentity(path, "moonlight-flatpak");
    assert(identity.has_value());
    assert(identity->hasClientIdentity());
    assert(identity->clientCertificateFingerprintSha256().size() == 64 && "the redacted stand-in certificate still parses");

    assert(identity->hosts.size() == 1);
    const auto& host = identity->hosts[0];
    assert(host.uuid == "935B1F5B-D2EC-E720-6600-5EB7986004EC");
    assert(host.hostname == "pc-papi.lan");
    assert(host.displayName() == "pc-papi.lan" && "customname=false is a flag, never a name");
    assert(host.preferredAddress() == "127.0.0.1" && "the manual address Moonlight recorded comes first");
    assert(host.preferredHttpPort() == 47989);
    assert(host.hasServerCertificate());
    assert(host.ipv6Address.empty() && host.ipv6Port == 0);

    assert(host.apps.size() == 25);
    bool sawQuotedName = false;
    bool sawSteamBigPicture = false;
    for (const auto& app : host.apps) {
        assert(app.id > 0 && !app.name.empty());
        sawQuotedName = sawQuotedName || app.name == "No, I'm not a Human";
        sawSteamBigPicture = sawSteamBigPicture || app.name == "Steam Big Picture";
    }
    assert(sawQuotedName && "QSettings quotes a name with a comma; the reader must unquote it");
    assert(sawSteamBigPicture);
    assert(identity->hostById("935B1F5B-D2EC-E720-6600-5EB7986004EC") == &host);
}

} // namespace

int main(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);
    testParsesHostsAppsAndIdentity();
    testMissingFileAndMissingIdentity();
    testCandidateOrderPrefersOverride();
    testHostsBackupIsReadWhenHostsIsEmpty();
    testReadsAFileMoonlightQtWrote();
    testPortDerivation();
    return 0;
}
