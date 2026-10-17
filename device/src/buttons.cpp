#include "buttons.h"

void Buttons::begin() {
    pinMode(a_.pin, INPUT_PULLUP);
    pinMode(b_.pin, INPUT_PULLUP);
}

Btn Buttons::step(One& b, Btn shortEv, Btn holdEv) {
    bool pressed = digitalRead(b.pin) == LOW;
    uint32_t now = millis();
    if (pressed && !b.down) {  // press (20 ms debounce is the poll period of the main loop)
        b.down = true;
        b.fired = false;
        b.since = now;
        return Btn::None;
    }
    if (pressed && b.down && !b.fired && now - b.since >= HOLD_MS) {
        b.fired = true;
        return holdEv;
    }
    if (!pressed && b.down) {
        b.down = false;
        bool real = now - b.since >= 40;  // ignore contact bounce
        if (!b.fired && real) return shortEv;
    }
    return Btn::None;
}

Btn Buttons::poll() {
    Btn ev = step(a_, Btn::AShort, Btn::AHold);
    if (ev != Btn::None) return ev;
    return step(b_, Btn::BShort, Btn::BHold);
}

float Buttons::holdProgress(char& side) const {
    const One* held = a_.down ? &a_ : (b_.down ? &b_ : nullptr);
    if (!held || held->fired) return 0;
    side = (held == &a_) ? 'A' : 'B';
    float f = float(millis() - held->since) / float(HOLD_MS);
    return f > 1 ? 1 : f;
}
