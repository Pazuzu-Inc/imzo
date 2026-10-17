// Two buttons: A = BOOT (GPIO0), B = KEY (GPIO21) on the T-Display S3 AMOLED. Active low.
#pragma once

#include <Arduino.h>

enum class Btn : uint8_t { None, AShort, BShort, AHold, BHold };

class Buttons {
public:
    void begin();
    /** Call often. A hold fires once after HOLD_MS while the button is still down; its release gives nothing. */
    Btn poll();
    /** 0 when nothing is held, else 0..1 progress toward a hold, and which side. */
    float holdProgress(char& side) const;

    static constexpr uint32_t HOLD_MS = 1500;

private:
    struct One {
        uint8_t pin;
        bool down = false;
        bool fired = false;
        uint32_t since = 0;
    };
    One a_{0}, b_{21};
    Btn step(One& b, Btn shortEv, Btn holdEv);
};
