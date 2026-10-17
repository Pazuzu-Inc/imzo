// Stand-in for the U8g2 library. GFX Library for Arduino 1.5.x only turns on its u8g2 font support when
// <U8g2lib.h> exists, but the fonts it ships (font/*.h) need nothing from U8g2 except this macro.
// Providing it here avoids downloading the whole U8g2 library just for a one-line macro.
#pragma once
#include <Arduino.h>
#define U8G2_FONT_SECTION(name)
