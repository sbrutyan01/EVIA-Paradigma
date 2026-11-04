/* Just enough of the Arduino and ESP32 headers to compile a generated sketch on this computer:
   the MQTT check uses it to catch C++ mistakes without an ESP32 or its board core. */
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#define HIGH 1
#define LOW 0
#define OUTPUT 1
#define INPUT 0
#define INPUT_PULLUP 2
#define LED_BUILTIN 2
#define A0 36
typedef const char *__FlashStringHelper;
#define F(s) ((const __FlashStringHelper *) (s))
inline void pinMode(int, int) {}
inline void digitalWrite(int, int) {}
inline int digitalRead(int) { return 0; }
inline int analogRead(int) { return 0; }
inline void analogWrite(int, int) {}
inline void delay(unsigned long) {}
inline unsigned long millis() { return 0; }
inline void yield() {}
struct SerialStub {
    void begin(unsigned long) {}
    void print(const char *) {}
    void print(const __FlashStringHelper *) {}
    void println() {}
    void println(const char *) {}
    void println(const __FlashStringHelper *) {}
    void write(uint8_t) {}
    int read() { return -1; }
    int available() { return 0; }
    void flush() {}
    operator bool() const { return true; }
};
extern SerialStub Serial;
