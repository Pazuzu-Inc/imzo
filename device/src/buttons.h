// One usable button: BOOT (GPIO0) on the T-Display S3 AMOLED, active low. The board's second button is the
// chip reset, so it cannot be used as a control. Only plain clicks are reported; counting a series of clicks
// is done by the caller (see main.cpp).
#pragma once

#include <Arduino.h>

class Buttons {
public:
    void begin();
    /** Call often. True once per click, when the button is released after a real press (bounce is ignored). */
    bool clicked();

private:
    static constexpr uint8_t PIN = 0;
    bool down_ = false;
    uint32_t since_ = 0;
};
