#pragma once
#include "Arduino.h"
#define WL_CONNECTED 3
#define WIFI_STA 1
struct WiFiStub {
    int status() { return WL_CONNECTED; }
    void mode(int) {}
    void begin(const char *, const char *) {}
};
extern WiFiStub WiFi;
struct WiFiClient {
    bool connect(const char *, uint16_t) { return true; }
    void stop() {}
    bool connected() { return true; }
    int available() { return 0; }
    int read() { return -1; }
    size_t write(const uint8_t *, size_t n) { return n; }
};
