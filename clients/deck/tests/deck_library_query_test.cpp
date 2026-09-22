#include <QCoreApplication>
#include <QFile>
#include <QJSEngine>
#include <cstdlib>
#include <iostream>

namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QJSEngine engine;
    QFile source(NOVA_DECK_LIBRARY_QUERY_SOURCE);
    require(source.open(QIODevice::ReadOnly), "missing production library query");
    auto script = QString::fromUtf8(source.readAll());
    script.remove(0, script.indexOf('\n') + 1); // QML's .pragma library is not JavaScript.
    require(!engine.evaluate(script).isError(), "library query failed to load");
    require(!engine.evaluate(R"(
        var games = [
            { id: 'b', title: 'Beta', source: 'steam', lastLaunched: 900, hdrSupported: true, category: 'cinematic', genres: ['Puzzle', 'Action'] },
            { id: 'z', title: 'Zeta', source: 'heroic', lastLaunched: 400, hdrSupported: false, category: 'fast_action', genres: ['puzzle'] },
            { id: 'a', title: 'alpha', source: 'lutris', lastLaunched: 400, hdrSupported: true, category: 'cinematic', genres: ['Adventure'] },
            { id: 'c', title: 'beta', source: 'steam', lastLaunched: 0, hdrSupported: false, category: 'desktop', genres: [] },
            { id: 'u', title: '<b>Utility</b>', source: '__proto__', lastLaunched: 0, hdrSupported: false }
        ];
        var original = JSON.stringify(games);
        function ids(search, primary, value, sort) { return select(games, search, primary, value, sort).map(game => game.id).join(','); }
    )").isError(), "query fixture failed");
    const auto expect = [&](const QString& expression, const QString& expected) {
        const auto result = engine.evaluate(expression);
        if (result.isError() || result.toString() != expected) {
            std::cerr << expression.toStdString() << ": expected " << expected.toStdString()
                << ", got " << result.toString().toStdString() << '\n';
            std::exit(1);
        }
    };
    expect("ids('', 'all', '', 'library')", "b,z,a,c,u");
    expect("ids('BETA', 'all', '', 'library')", "b,c");
    expect("ids('', 'recent', '', 'library')", "b,z,a"); // Equal timestamps retain host order.
    expect("ids('', 'recent', '', 'name')", "a,b,z");
    expect("ids('', 'all', '', 'recent')", "b,a,z,u,c");
    expect("ids('', 'all', '', 'name-desc')", "z,b,c,a,u");
    expect("ids('', 'all', '', 'source')", "b,c,a,z,u");
    expect("ids('', 'all', '', 'hdr')", "a,b,u,c,z");
    expect("ids('', 'hdr', '', 'library')", "b,a");
    expect("ids('', 'source', 'steam', 'library')", "b,c");
    expect("ids('beta', 'genre', 'PUZZLE', 'library')", "b");
    expect("ids('', 'genre', 'Puzzle', 'library')", "b,z");
    expect("ids('', 'category', 'desktop', 'library')", "c");
    expect("ids('', 'source', 'missing', 'library')", "");
    expect("ids('<b>', 'all', '', 'library')", "u");
    expect("sources(games).map(choice => choice.title).join(',')", "Steam,Lutris,Heroic,__proto__");
    expect("moreFilters(games).map(choice => choice.id).join(',')", "fast_action,cinematic,desktop,Action,Adventure,Puzzle");
    expect("normalizedFilter('genre', ' Puzzle ').value", "Puzzle");
    expect("normalizedFilter('source', '').primary", "all");
    expect("normalizedFilter('unknown', 'value').primary", "all");
    expect("normalizedFilter('hdr', 'stale-source').value", "");
    expect("JSON.stringify(games) === original", "true");
    expect("(function() { var tied = Array.from({length: 40}, (_, i) => ({id: i, title: 'Same', lastLaunched: 100})); return JSON.stringify(select(tied, '', 'recent', '', 'name')) === JSON.stringify(tied); })()", "true");
    std::cout << "Android library semantics passed: search, recent/source/HDR/category/genre, six sorts, stable ties and host-order preservation\n";
}
