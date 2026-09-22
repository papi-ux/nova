#include "stream/deck_gamestream_library.h"

#include <QCoreApplication>
#include <QUrl>
#include <QUrlQuery>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::stream;
using nova::deck::polaris::DeckPolarisRequestStatus;
namespace {
void require(bool ok, const char* detail) { if (!ok) { std::cerr << detail << '\n'; std::exit(1); } }
std::string root(const std::string& body) { return "<root status_code=\"200\">" + body + "</root>"; }
std::string app(const std::string& id, const std::string& title) { return "<App><AppTitle>" + title + "</AppTitle><ID>" + id + "</ID></App>"; }
}
int main(int argc, char** argv) {
    QCoreApplication application(argc, argv);
    const auto list = parseStandardAppList(root("<App><ID>7</ID><AppTitle>Steam &amp; Games</AppTitle><IsHdrSupported>1</IsHdrSupported>"
        "<UUID>host-extension</UUID><Extra><Safe>ignored</Safe></Extra></App>" + app("8", "Steam &amp; Games")));
    require(list.ok() && list.value->size() == 2 && list.value->front().id == 7 &&
        list.value->front().title == "Steam & Games" && list.value->front().hdrSupported && !list.value->back().hdrSupported,
        "standard app ids, titles, HDR flags or forward-compatible fields were lost");
    require(parseStandardAppList(root("")).ok(), "empty authorized app list was rejected");
    for (const auto& invalid : {
        root(app("0", "Invalid")), root(app("-1", "Invalid")), root(app("+1", "Invalid")),
        root(app("2147483648", "Invalid")), root(app("1", " ")), root(app("1", "A") + app("1", "B")),
        root("<App><ID>1</ID><ID>2</ID><AppTitle>A</AppTitle></App>"),
        root("<App><ID>1</ID><AppTitle>A</AppTitle><IsHdrSupported>true</IsHdrSupported></App>"),
        root("<App><ID>1</ID></App>"), root("<App><ID><Value>1</Value></ID><AppTitle>A</AppTitle></App>"),
        root("<Outer>" + app("1", "Nested") + "</Outer>"), root(app("1", std::string(1025, 'x'))),
        root(app("1", "A")) + "<other/>", std::string("<root status_code=\"200\"><App>"),
        std::string("<!DOCTYPE root [<!ENTITY title 'expanded'>]>") + root(app("1", "&title;")),
        std::string("<html><App><ID>1</ID><AppTitle>Not GameStream</AppTitle></App></html>")}) {
        const auto bad = parseStandardAppList(invalid);
        require(bad.status == DeckPolarisRequestStatus::MalformedBody && !bad.value, "invalid or partial app list was accepted");
    }
    std::string tooMany;
    for (int i = 1; i <= 4097; ++i) tooMany += app(std::to_string(i), "App");
    require(!parseStandardAppList(root(tooMany)).ok(), "app count limit missing");
    require(!parseStandardAppList(std::string(kStandardAppListLimit + 1, ' ')).ok(), "app body limit missing");
    require(parseStandardAppList("<root status_code=\"403\"/>").status == DeckPolarisRequestStatus::Unauthorized,
        "XML authorization failure was hidden");
    require(parseStandardAppList("<root status_code=\"500\"/>").status == DeckPolarisRequestStatus::HttpError,
        "XML server failure was hidden");
    const auto info = parseStandardHostInfo(root("<uniqueid>saved-host</uniqueid><PairStatus>1</PairStatus><Extension><Value>1</Value></Extension>"));
    require(info.ok() && info.value->id == "saved-host" && info.value->paired, "authenticated host identity parsing failed");
    for (int mask : {0, 1, 256, 257, 512, 768, 262144}) {
        const auto codecInfo = parseStandardHostInfo(root("<uniqueid>saved-host</uniqueid><PairStatus>1</PairStatus><ServerCodecModeSupport>" +
            std::to_string(mask) + "</ServerCodecModeSupport>"));
        require(codecInfo.ok() && codecInfo.value->streamCapabilities.h264 == (mask == 0 || (mask & 1)) &&
            codecInfo.value->streamCapabilities.hevc == bool(mask & 256), "standard host codec mask misread");
    }
    for (const auto* invalid : {"", "-1", "+256", "1.5", "2147483648", "broken"})
        require(!parseStandardHostInfo(root(std::string("<uniqueid>A</uniqueid><PairStatus>1</PairStatus><ServerCodecModeSupport>") +
            invalid + "</ServerCodecModeSupport>")).ok(), "malformed standard codec capability admitted");
    require(!parseStandardHostInfo(root("<uniqueid>A</uniqueid><PairStatus>1</PairStatus><ServerCodecModeSupport>1</ServerCodecModeSupport>"
        "<ServerCodecModeSupport>256</ServerCodecModeSupport>")).ok(), "duplicate codec fields admitted");
    const auto unpaired = parseStandardHostInfo(root("<uniqueid>saved-host</uniqueid><PairStatus>0</PairStatus>"));
    require(unpaired.ok() && !unpaired.value->paired, "unpaired host was trusted");
    require(!parseStandardHostInfo(root("<uniqueid>A</uniqueid><uniqueid>B</uniqueid><PairStatus>1</PairStatus>")).ok(),
        "ambiguous host identity accepted");
    require(!parseStandardHostInfo(root("<uniqueid>A</uniqueid>")).ok(), "missing pairing state accepted");
    const QUrl url(QString::fromStdString(standardHostTarget("/launch?appid=7", "public&id")));
    const QUrlQuery query(url);
    require(url.path() == "/launch" && query.queryItemValue("appid") == "7" &&
        query.queryItemValue("uniqueid") == "public&id" && !query.queryItemValue("uuid").isEmpty() &&
        query.queryItemValue("devicename") == "Nova Deck" && !query.hasQueryItem("id"), "GameStream identifiers corrupted request parameters");
    std::cout << "Standard-host library parsing passed: complete XML, authority status, limits, app ids and request identifiers\n";
}
