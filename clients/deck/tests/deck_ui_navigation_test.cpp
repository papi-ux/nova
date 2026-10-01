#include "runtime/deck_ui_navigation.h"
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
using nova::deck::stream::DeckControllerState;
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
int main() {
    DeckUiNavigation nav;
    DeckControllerState state;
    require(nav.update(state, true, 0).empty(), "neutral navigated");
    state.buttons = RIGHT_FLAG;
    require(nav.update(state, true, 1) == std::vector<int>{Qt::Key_Right}, "D-pad edge missing");
    require(nav.update(state, true, 300).empty(), "held direction repeated too early");
    require(nav.update(state, true, 401) == std::vector<int>{Qt::Key_Right}, "held D-pad did not repeat after delay");
    require(nav.update(state, true, 420).empty(), "repeat exceeded cadence");
    require(nav.update(state, true, 481) == std::vector<int>{Qt::Key_Right}, "repeat cadence missing");
    state.buttons = LEFT_FLAG;
    require(nav.update(state, true, 482) == std::vector<int>{Qt::Key_Left}, "direction change kept repeat delay");
    state = {}; require(nav.update(state, true, 483).empty(), "release navigated");
    state.leftY = 22000;
    require(nav.update(state, true, 484) == std::vector<int>{Qt::Key_Up}, "left stick Up missing");
    state.leftY = 13000;
    require(nav.update(state, true, 884) == std::vector<int>{Qt::Key_Up}, "stick hysteresis lost hold");
    state.leftY = 8000; require(nav.update(state, true, 885).empty(), "stick drift navigated");
    state = {}; nav.update(state, true, 886);
    state.leftX = -25000;
    require(nav.update(state, true, 887) == std::vector<int>{Qt::Key_Left}, "left stick Left missing");
    state.buttons = DOWN_FLAG;
    require(nav.update(state, true, 888) == std::vector<int>{Qt::Key_Down}, "D-pad did not override stick");
    nav.update({}, true, 889);
    state = {}; state.buttons = LB_FLAG;
    require(nav.update(state, true, 890) == std::vector<int>{Qt::Key_PageUp}, "left shoulder page missing");
    require(nav.update(state, true, 1500).empty(), "shoulder unexpectedly repeated");
    state.buttons = RB_FLAG;
    require(nav.update(state, true, 1501) == std::vector<int>{Qt::Key_PageDown}, "right shoulder page missing");
    state.buttons = DOWN_FLAG;
    require(nav.update(state, false, 1502).empty(), "captured gameplay navigated UI");
    require(nav.update(state, true, 2500).empty(), "focus restore replayed held direction");
    nav.update({}, true, 2501);
    state.buttons = DOWN_FLAG;
    require(nav.update(state, true, 2502) == std::vector<int>{Qt::Key_Down}, "neutral did not rearm UI navigation");
    DeckUiNavigation initialized;
    require(initialized.update(state, true, 0).empty(), "held init state navigated");
    std::cout << "UI D-pad/stick repeat, shoulder paging, drift and capture/focus neutral gate passed\n";
}
