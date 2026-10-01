#pragma once
#include "stream/deck_controller_input.h"
#include <Qt>
#include <Limelight.h>
#include <algorithm>
#include <cstdlib>
#include <vector>

namespace nova::deck::runtime {
// GUI navigation for one physical generation. Focus/capture changes require
// neutral before input resumes; repeat never catches up with a burst of keys.
class DeckUiNavigation {
public:
    bool armed() const { return armed_; }
    std::vector<int> update(stream::DeckControllerState state, bool enabled, qint64 nowMs) {
        if (!enabled) { armed_ = false; direction_ = 0; previous_ = state.buttons; return {}; }
        if (!armed_) {
            if (!(state.buttons & (LEFT_FLAG | RIGHT_FLAG | UP_FLAG | DOWN_FLAG | A_FLAG | B_FLAG | LB_FLAG | RB_FLAG))
                && std::abs(int(state.leftX)) < 10000 && std::abs(int(state.leftY)) < 10000) armed_ = true;
            previous_ = state.buttons;
            return {};
        }
        std::vector<int> keys;
        const auto pressed = state.buttons & ~previous_;
        if (pressed & LB_FLAG) keys.push_back(Qt::Key_PageUp);
        if (pressed & RB_FLAG) keys.push_back(Qt::Key_PageDown);
        previous_ = state.buttons;
        int direction = 0;
        const auto dpad = state.buttons & (LEFT_FLAG | RIGHT_FLAG | UP_FLAG | DOWN_FLAG);
        if (dpad) {
            if (dpad == LEFT_FLAG) direction = Qt::Key_Left;
            else if (dpad == RIGHT_FLAG) direction = Qt::Key_Right;
            else if (dpad == UP_FLAG) direction = Qt::Key_Up;
            else if (dpad == DOWN_FLAG) direction = Qt::Key_Down;
        } else {
            const int x = state.leftX, y = state.leftY;
            const int threshold = direction_ ? 10000 : 16000;
            if (std::max(std::abs(x), std::abs(y)) >= threshold)
                direction = std::abs(x) > std::abs(y) ? (x < 0 ? Qt::Key_Left : Qt::Key_Right)
                    : (y > 0 ? Qt::Key_Up : Qt::Key_Down);
        }
        if (direction != direction_) {
            direction_ = direction;
            if (direction) { keys.push_back(direction); nextRepeat_ = nowMs + 400; }
        } else if (direction && nowMs >= nextRepeat_) {
            keys.push_back(direction);
            nextRepeat_ = nowMs + 80;
        }
        return keys;
    }
private:
    quint32 previous_ = 0;
    bool armed_ = false;
    int direction_ = 0;
    qint64 nextRepeat_ = 0;
};
} // namespace nova::deck::runtime
