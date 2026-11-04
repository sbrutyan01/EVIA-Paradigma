package evia.paradigma;

import java.util.Locale;

/**
 * The microcontroller boards EVIA Paradigma builds for. The generated code is the same for every
 * board of a family (pins are written as LED_BUILTIN, A0 ... and resolved by the board's headers);
 * the board only decides how the program is built and uploaded.
 *
 * @param fqbn       arduino-cli board name
 * @param mcu        avr-gcc -mmcu value (AVR boards only)
 * @param programmer avrdude programmer and baud rate for the bootloader (AVR boards only)
 */
enum Board {
    UNO("Arduino Uno", "arduino:avr:uno", "atmega328p", "arduino -b 115200"),
    NANO("Arduino Nano", "arduino:avr:nano:cpu=atmega328", "atmega328p", "arduino -b 115200"),
    MEGA("Arduino Mega 2560", "arduino:avr:mega:cpu=atmega2560", "atmega2560", "wiring -b 115200"),
    LEONARDO("Arduino Leonardo", "arduino:avr:leonardo", "atmega32u4", "avr109 -b 57600"),
    MICRO("Arduino Micro", "arduino:avr:micro", "atmega32u4", "avr109 -b 57600"),
    PRO_MINI("Arduino Pro Mini (5 V, 16 MHz)", "arduino:avr:pro:cpu=16MHzatmega328", "atmega328p", "arduino -b 57600"),
    ESP32("ESP32", "esp32:esp32:esp32", null, null),
    ESP32_S2("ESP32-S2", "esp32:esp32:esp32s2", null, null),
    ESP32_S3("ESP32-S3", "esp32:esp32:esp32s3", null, null),
    ESP32_C3("ESP32-C3", "esp32:esp32:esp32c3", null, null),
    ESP32_C6("ESP32-C6", "esp32:esp32:esp32c6", null, null),
    ESP32_H2("ESP32-H2", "esp32:esp32:esp32h2", null, null);

    final String display, fqbn, mcu, programmer;

    Board(String display, String fqbn, String mcu, String programmer) {
        this.display = display;
        this.fqbn = fqbn;
        this.mcu = mcu;
        this.programmer = programmer;
    }

    boolean isAvr() {
        return mcu != null;
    }

    /** Short stored and command-line name: "uno", "esp32-s3". */
    String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The boards a language can build for: all of them for Arduino sketches, the AVR ones for bare-metal C. */
    boolean supports(Target.Lang lang) {
        return lang == Target.Lang.ARDUINO || lang == Target.Lang.AVR && isAvr();
    }

    static Board parse(String key) {
        if (key == null) return UNO;
        String k = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Board b : values()) if (b.key().equals(k) || b.fqbn.equals(k)) return b;
        return UNO;
    }

    /** The first board of {@code lang}, if {@code b} can't be built for it. */
    static Board forLang(Board b, Target.Lang lang) {
        return b.supports(lang) ? b : UNO;
    }

    /** "avr-gcc -mmcu=atmega328p -DF_CPU=16000000UL -Os ..." for bare-metal C. */
    String avrGcc(String src, String elf) {
        return "avr-gcc -mmcu=" + mcu + " -DF_CPU=16000000UL -std=gnu11 -Os -Wall -Wextra -o " + elf + " " + src + " -lm";
    }

    @Override public String toString() {
        return display;
    }
}
