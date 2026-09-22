#include "runtime/deck_game_shortcuts.h"
#include <QCoreApplication>
#include <QTemporaryDir>
#include <QFile>
#include <QDir>
#include <iostream>
#include <algorithm>
using namespace nova::deck::runtime;
namespace {
void check(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::abort(); } }
QByteArray read(const QString& path) { QFile f(path); check(f.open(QIODevice::ReadOnly),"read failed"); return f.readAll(); }
void write(const QString& path, const QByteArray& bytes) { QFile f(path); check(f.open(QIODevice::WriteOnly) && f.write(bytes) == bytes.size(),"write failed"); }
}
int main(int argc, char** argv) {
    QCoreApplication app(argc,argv); QTemporaryDir tmp;
    DeckGameLink link{"paired-host","canonical-game","desktop"}; const auto encoded = encodeGameLink(link);
    check(decodeGameLink(encoded)->host == link.host && decodeGameLink(encoded)->game == link.game,"link roundtrip failed");
    check(!decodeGameLink(encoded + "=") && !decodeGameLink("$(touch /tmp/no)") && encodeGameLink({"host","space.fake.game","desktop"}).isEmpty(),"invalid link admitted");
    auto first = steamGameShortcut(link,"Same Title");
    auto second = steamGameShortcut({"another-host",link.game,"desktop"},"Same Title");
    DeckSteamShortcut unrelated; unrelated.appName = "Same Title"; unrelated.exe = "\"/usr/bin/other\"";
    const auto initial = registerShortcut({},unrelated);
    auto registered = registerShortcut(initial.document,first);
    auto other = registerShortcut(registered.document,second);
    check(!registered.replaced && !other.replaced && registered.appId != other.appId,"same-title games conflated");
    check(serializeBinaryVdf({{"entry",findKey(other.document,"shortcuts")->children.front().second}}) ==
        serializeBinaryVdf({{"entry",findKey(initial.document,"shortcuts")->children.front().second}}),"unrelated shortcut changed");
    first.appName = "Renamed Game";
    const auto renamed = registerShortcut(other.document,first);
    check(renamed.replaced && renamed.appId == registered.appId && findKey(renamed.document,"shortcuts")->children.size() == 3,"rename duplicated game or lost appid");
    auto withoutMetadata = renamed.document;
    for (auto& [key, value] : findKey(withoutMetadata,"shortcuts")->children) {
        std::erase_if(value.children, [](const auto& field) { return field.first == "NovaGameId"; });
    }
    const auto preserved = registerShortcut(withoutMetadata,first);
    check(preserved.replaced && preserved.appId == registered.appId && findKey(preserved.document,"shortcuts")->children.size() == 3,
        "Steam-normalized metadata duplicated a canonical game");
    const auto path = tmp.filePath("config/shortcuts.vdf"); QDir().mkpath(tmp.filePath("config"));
    const auto before = QByteArray::fromStdString(serializeBinaryVdf(initial.document)); write(path,before);
    const std::vector<std::filesystem::path> files{path.toStdString()};
    QImage poster(24,36,QImage::Format_RGB32); poster.fill(Qt::blue);
    QImage icon(24,24,QImage::Format_ARGB32); icon.fill(Qt::red);
    const QMap<QString,QImage> art{{"poster",poster},{"icon",icon}};
    check(!writeGameShortcut(files,first,art,[]{return std::optional{true};}).ok && read(path) == before,"running Steam changed file");
    check(!writeGameShortcut(files,first,art,[]{return std::optional<bool>{};}).ok && read(path) == before,"unknown Steam state allowed write");
    auto result = writeGameShortcut(files,first,art,[]{return std::optional{false};});
    check(result.ok,"Steam shortcut creation failed");
    check(!QImage(tmp.filePath("config/grid/" + QString::number(result.appId) + "p.png")).isNull(),"poster missing");
    const auto installed = read(path);
    result = writeGameShortcut(files,first,art,[]{return std::optional{false};});
    check(result.ok && read(path) == installed,"retry not idempotent");
    const auto broken = tmp.filePath("broken.vdf"); write(broken,"malformed");
    check(!writeGameShortcut({path.toStdString(),broken.toStdString()},second,art,[]{return std::optional{false};}).ok && read(path) == installed,"bad profile partly wrote account");
    int checks=0;
    check(!writeGameShortcut(files,second,art,[&]{return std::optional{++checks > 1};}).ok && read(path) == installed,"Steam restart race modified shortcuts");
    std::cout << "Canonical launch identity, artwork, idempotent updates, other entries and Steam-running refusal passed.\n";
}
