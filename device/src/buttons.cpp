#include "buttons.h"

void Buttons::begin() { pinMode(PIN, INPUT_PULLUP); }

bool Buttons::clicked() {
    bool pressed = digitalRead(PIN) == LOW;
    uint32_t now = millis();
    if (pressed && !down_) {
        down_ = true;
        since_ = now;
        return false;
    }
    if (!pressed && down_) {
        down_ = false;
        return now - since_ >= 30;  // ignore contact bounce
    }
    return false;
}
