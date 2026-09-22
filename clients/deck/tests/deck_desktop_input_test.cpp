#include "stream/deck_desktop_input.h"
#include <QCoreApplication>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::stream;
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    for (const QString platform : {QString("xcb"), QString("wayland")}) {
        // A non-US logical symbol still sends the physical W gaming position.
        QKeyEvent physical(QEvent::KeyPress, Qt::Key_Z, Qt::NoModifier, 25, 0, 0);
        require(deckDesktopKey(physical, platform)=='W', "physical layout was replaced by logical symbol");
        QKeyEvent rightCtrl(QEvent::KeyPress, Qt::Key_Control, Qt::ControlModifier, 105, 0, 0);
        require(deckDesktopKey(rightCtrl, platform)==0xa3, "right Ctrl became left Ctrl");
        QKeyEvent unknown(QEvent::KeyPress, Qt::Key_A, Qt::NoModifier, 999, 0, 0);
        require(!deckDesktopKey(unknown, platform), "unknown native key guessed a physical mapping");
    }
    for (int key=Qt::Key_A;key<=Qt::Key_Z;++key) {
        QKeyEvent event(QEvent::KeyPress,key,Qt::NoModifier);
        require(deckDesktopKey(event,"offscreen")==key, "injected letter mapping failed");
    }
    QKeyEvent keypad(QEvent::KeyPress,Qt::Key_7,Qt::KeypadModifier);
    require(deckDesktopKey(keypad,"offscreen")==0x67,"keypad lost identity");
    QKeyEvent escape(QEvent::KeyPress,Qt::Key_Escape,Qt::NoModifier);
    require(deckDesktopKey(escape,"offscreen")==27,"Escape not forwardable");
    require(deckDesktopButton(Qt::MiddleButton)==2 && deckDesktopButton(Qt::RightButton)==3 &&
        deckDesktopButton(Qt::BackButton)==4 && deckDesktopButton(Qt::ForwardButton)==5,"mouse button mapping wrong");

    DeckDesktopRouter router;
    require(!router.key('W',true),"menu key forwarded");
    router.capture(true);
    require(!router.key('W',true,true) && !router.key('W',false,true) && !router.key('W',false),"held Resume key leaked");
    require(router.key('W',true)->down,"fresh key lost");
    require(!router.key('W',true) && !router.key('W',false,true),"duplicate/repeat released host key");
    // Clicking a local HUD item must not forget unrelated held host keys.
    require(!router.button(1,true,false),"local click leaked");
    require(router.key('W',false) && !router.button(1,false),"local UI lost keyboard release");
    require(router.button(1,true) && router.dragging(),"mouse press lost");
    router.capture(false); router.capture(true);
    require(!router.button(1,true) && !router.button(1,false) && !router.dragging(),"overlay replayed held mouse");
    require(router.key('A',true).has_value(),"key after overlay failed");
    router.focusLost(); router.capture(true);
    require(!router.key('A',true,true) && router.key('A',true),"focus return repeated held key or wedged fresh key");

    require(!deckDesktopPosition({640,10},{1280,800},{1920,1080},DeckVideoScaleMode::Fit),"letterbox sent a click");
    const auto top=deckDesktopPosition({640,40},{1280,800},{1920,1080},DeckVideoScaleMode::Fit);
    require(top && top->x==16383 && top->y==0,"Fit pointer does not match image");
    const auto edge=deckDesktopPosition({1500,900},{1280,800},{1920,1080},DeckVideoScaleMode::Fit,true);
    require(edge && edge->x==32766 && edge->y==32766,"drag outside video failed to clamp");
    const auto fill=deckDesktopPosition({0,400},{1280,800},{1920,1080},DeckVideoScaleMode::Fill);
    require(fill && fill->x==1638 && fill->y==16383,"Fill crop not reflected in pointer");
    const auto stretch=deckDesktopPosition({0,0},{1280,800},{1920,1080},DeckVideoScaleMode::Stretch);
    require(stretch && stretch->x==0 && stretch->y==0,"Stretch pointer mapping failed");
    require(!deckDesktopPosition({0,0},{0,0},{1920,1080},DeckVideoScaleMode::Fit),"empty viewport accepted");
    require(!deckDesktopPosition({0,0},{1280,800},{},DeckVideoScaleMode::Fit),"unknown video geometry accepted");
    const auto anamorphic=deckDesktopPosition({0,40},{1280,800},{1440,1080},DeckVideoScaleMode::Fit,false,4./3);
    require(anamorphic && anamorphic->x==0 && anamorphic->y==0,"pixel aspect ignored");

    DeckDesktopLedger ledger;
    std::vector<DeckDesktopPacket> packets;
    bool fail=true;
    const DeckDesktopSend send=[&](const auto& packet) { packets.push_back(packet); return fail ? -1 : 0; };
    require(ledger.deliver({DeckDesktopPacket::Key,'W',0,0,true},send)==-1,"failed key was ignored");
    fail=false; ledger.deliver({DeckDesktopPacket::Button,1,0,0,true},send);
    require(!ledger.release(send) && packets.size()==4 && packets[2]==DeckDesktopPacket{DeckDesktopPacket::Key,'W'} &&
        packets[3]==DeckDesktopPacket{DeckDesktopPacket::Button,1},"ambiguous failure did not release every held input");
    ledger.release(send); require(packets.size()==4,"release ledger replayed stale input");
    require(ledger.deliver({DeckDesktopPacket::Key,999},send)==-1 && packets.size()==4,"invalid packet reached transport");
    std::cout << "desktop key, pointer geometry, edge guards and release ledger passed\n";
}
