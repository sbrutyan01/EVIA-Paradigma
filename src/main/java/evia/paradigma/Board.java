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
 * @param pic       xc32-gcc -mprocessor value, e.g. "32MX250F128B" (PIC32 boards only)
 * @param stm32     the chip of an STM32 board (bare-metal STM32 C and OpenOCD; the fqbn is its Arduino core name)
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
    ESP32_H2("ESP32-H2", "esp32:esp32:esp32h2", null, null),

    // PIC32MX: family 1 = MX1xx/MX2xx (pin remapping), 3 = MX3xx/MX4xx, 5 = MX5xx/MX6xx/MX7xx (fixed pins);
    // package: B = 28 pins, H = 64 pins, L = 100 pins
    PIC32MX150F128B("PIC32MX150F128B (28 pins)", "32MX150F128B", 1, 'B', "RB2"),
    PIC32MX250F128B("PIC32MX250F128B (chipKIT DP32)", "32MX250F128B", 1, 'B', "RB2"),
    PIC32MX270F256B("PIC32MX270F256B (28 pins)", "32MX270F256B", 1, 'B', "RB2"),
    PIC32MX320F128H("PIC32MX320F128H (chipKIT Uno32)", "32MX320F128H", 3, 'H', "RF0"),
    PIC32MX340F512H("PIC32MX340F512H (chipKIT uC32)", "32MX340F512H", 3, 'H', "RF0"),
    PIC32MX440F256H("PIC32MX440F256H (64 pins)", "32MX440F256H", 3, 'H', "RF0"),
    PIC32MX460F512L("PIC32MX460F512L (Cerebot MX4cK)", "32MX460F512L", 3, 'L', "RB10"),
    PIC32MX695F512L("PIC32MX695F512L (chipKIT WF32)", "32MX695F512L", 5, 'L', "RF0"),
    PIC32MX795F512L("PIC32MX795F512L (chipKIT Max32)", "32MX795F512L", 5, 'L', "RC1"),
    PIC32_ESK2("PIC32 Ethernet Starter Kit II (MX795F512L)", "32MX795F512L", 5, 'L', "RD0"),
    // Digilent chipKIT Pro MX7 (formerly Cerebot MX7cK): LD1-LD4 on RG12-RG15, BTN1/BTN2/BTN3 on RG6/RG7/RA0,
    // the USB-UART on UART1 (RF2/RF8), a LAN8720 Ethernet PHY
    CHIPKIT_PRO_MX7("chipKIT Pro MX7 (MX795F512L)", "32MX795F512L", 5, 'L', "RG12"),

    // STM32: Arduino sketches with STMicroelectronics' core, or bare-metal STM32 C; both upload with OpenOCD
    BLUEPILL_F103C8("Blue Pill (STM32F103C8)", "STMicroelectronics:stm32:GenF1:pnum=BLUEPILL_F103C8",
            new Stm32(Stm32.Family.F1, "STM32F103C8", 64, 20, 1, "PC13", true)),
    BLACKPILL_F411CE("Black Pill (STM32F411CE)", "STMicroelectronics:stm32:GenF4:pnum=BLACKPILL_F411CE",
            new Stm32(Stm32.Family.F4, "STM32F411CE", 512, 128, 1, "PC13", true)),
    NUCLEO_F401RE("Nucleo-F401RE", "STMicroelectronics:stm32:Nucleo_64:pnum=NUCLEO_F401RE",
            new Stm32(Stm32.Family.F4, "STM32F401RE", 512, 96, 2, "PA5", false)),
    NUCLEO_F411RE("Nucleo-F411RE", "STMicroelectronics:stm32:Nucleo_64:pnum=NUCLEO_F411RE",
            new Stm32(Stm32.Family.F4, "STM32F411RE", 512, 128, 2, "PA5", false)),
    DISCO_F407VG("STM32F4DISCOVERY (STM32F407VG)", "STMicroelectronics:stm32:Disco:pnum=DISCO_F407VG",
            new Stm32(Stm32.Family.F407, "STM32F407VG", 1024, 128, 2, "PD12", false)),
    // 32F429IDISCOVERY (STM32F429I-DISC1): the F407's clock and peripherals, 2 MB flash, 192 KB of SRAM from 0x20000000
    // (the 64 KB CCM is left out), LD3 green PG13 / LD4 red PG14, the ST-LINK's virtual serial port on USART1 (PA9/PA10)
    DISCO_F429ZI("32F429IDISCOVERY (STM32F429ZI)", "STMicroelectronics:stm32:Disco:pnum=DISCO_F429ZI",
            new Stm32(Stm32.Family.F407, "STM32F429ZI", 2048, 192, 1, "PG13", false)),

    // FPGA boards: the pins, clock and chip are in Fpga
    NEXYS_A7_100T("Digilent Nexys A7-100T (Artix-7)", Fpga::nexysA7100T),
    ARTY_A7_35("Digilent Arty A7-35 (Artix-7)", Fpga::artyA7_35),
    ARTY_A7_100("Digilent Arty A7-100 (Artix-7)", Fpga::artyA7_100),
    BASYS3("Digilent Basys 3 (Artix-7)", Fpga::basys3),
    OLIMEX_GATEMATE("Olimex GateMateA1-EVB (GateMate)", Fpga::olimexGateMate),
    CCGM1A1_EVB("Cologne Chip GateMate A1 evaluation board", Fpga::cologneChipGateMate);

    final String display, fqbn, mcu, programmer, pic;
    /** PIC32 only: 1, 3 or 5 (see above), and the package letter; 0 for the other boards. */
    final int family;
    final char pkg;
    /** PIC32 only: the pin of the board's LED, which LED_BUILTIN names. */
    final String led;
    /** FPGA only: the chip, clock and pins of the board. */
    private final java.util.function.Supplier<Fpga.Spec> fpga;
    /** STM32 only: the chip, its memory, the serial port Print uses and the LED. */
    final Stm32 stm32;

    /**
     * The chip of an STM32 board.
     *
     * @param family  F1 (Cortex-M3, 64 MHz from the internal oscillator), F4 (Cortex-M4F, 84 MHz) or F407 (168 MHz)
     * @param chip    the part number, e.g. "STM32F103C8"
     * @param flashK  flash in KiB, from 0x08000000
     * @param ramK    RAM in KiB, from 0x20000000
     * @param usart   the USART that Print and Input use: 1 (PA9 TX, PA10 RX) or 2 (PA2 TX, PA3 RX; the ST-Link's
     *                virtual serial port on the Nucleo boards)
     * @param led     the pin of the board's LED, which LED_BUILTIN names
     * @param ledLow  true when the LED lights at LOW (the Blue and Black Pill)
     */
    record Stm32(Family family, String chip, int flashK, int ramK, int usart, String led, boolean ledLow) {
        enum Family { F1, F4, F407 }

        boolean f1() {
            return family == Family.F1;
        }

        /** arm-none-eabi-gcc's options for the core: the Cortex-M4F has a single-precision FPU. */
        java.util.List<String> cpuFlags() {
            return f1() ? java.util.List.of("-mcpu=cortex-m3", "-mthumb", "-mfloat-abi=soft")
                    : java.util.List.of("-mcpu=cortex-m4", "-mthumb", "-mfpu=fpv4-sp-d16", "-mfloat-abi=hard");
        }

        /** OpenOCD's configuration of the chip family. */
        String openocdTarget() {
            return f1() ? "target/stm32f1x.cfg" : "target/stm32f4x.cfg";
        }

        /** The system clock in Hz the runtime sets up. */
        long sysclk() {
            return switch (family) {
                case F1 -> 64_000_000L;
                case F4 -> 84_000_000L;
                case F407 -> 168_000_000L;
            };
        }
    }

    Board(String display, java.util.function.Supplier<Fpga.Spec> fpga) {
        this.display = display;
        this.fqbn = "";
        this.mcu = null;
        this.programmer = null;
        this.pic = null;
        this.family = 0;
        this.pkg = 0;
        this.led = null;
        this.fpga = fpga;
        this.stm32 = null;
    }

    Board(String display, String fqbn, String mcu, String programmer) {
        this.display = display;
        this.fqbn = fqbn;
        this.mcu = mcu;
        this.programmer = programmer;
        this.pic = null;
        this.family = 0;
        this.pkg = 0;
        this.led = null;
        this.fpga = null;
        this.stm32 = null;
    }

    Board(String display, String fqbn, Stm32 stm32) {
        this.display = display;
        this.fqbn = fqbn;
        this.mcu = null;
        this.programmer = null;
        this.pic = null;
        this.family = 0;
        this.pkg = 0;
        this.led = stm32.led();
        this.fpga = null;
        this.stm32 = stm32;
    }

    Board(String display, String pic, int family, char pkg, String led) {
        this.display = display;
        this.fqbn = "";
        this.mcu = null;
        this.programmer = null;
        this.pic = pic;
        this.family = family;
        this.pkg = pkg;
        this.led = led;
        this.fpga = null;
        this.stm32 = null;
    }

    boolean isAvr() {
        return mcu != null;
    }

    /** An ESP32 board (built with the esp32:esp32 core of arduino-cli). */
    boolean isEsp32() {
        return fqbn != null && fqbn.startsWith("esp32:");
    }

    /** An Arduino board with an AVR chip (Uno, Nano, Mega, Leonardo, Micro, Pro Mini). */
    boolean isArduinoAvr() {
        return !isEsp32() && !isPic32() && !isFpga() && !isStm32();
    }

    /** An STM32 board: Arduino sketches with STMicroelectronics' core, or bare-metal STM32 C. */
    boolean isStm32() {
        return stm32 != null;
    }

    boolean isPic32() {
        return pic != null;
    }

    boolean isFpga() {
        return fpga != null;
    }

    /** The chip, clock and pins of an FPGA board. */
    Fpga.Spec fpga() {
        return fpga.get();
    }

    /** Short stored and command-line name: "uno", "esp32-s3". */
    String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The boards a language can build for: Arduino, ESP32 and STM32 for sketches, the AVR ones for AVR C, the PIC32 ones for PIC32 C, the STM32 ones for STM32 C. */
    boolean supports(Target.Lang lang) {
        return switch (lang) {
            case ARDUINO -> !isPic32() && !isFpga();
            case AVR -> isAvr();
            case PIC32 -> isPic32();
            case STM32 -> isStm32();
            case SYSTEMVERILOG, VERILOG, VHDL -> isFpga();
            default -> false;
        };
    }

    static Board parse(String key) {
        if (key == null) return UNO;
        String k = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Board b : values()) if (b.key().equals(k) || b.fqbn.equalsIgnoreCase(key.trim())) return b;
        return UNO;
    }

    /** The first board of {@code lang}, if {@code b} can't be built for it. */
    static Board forLang(Board b, Target.Lang lang) {
        return b.supports(lang) ? b : lang == Target.Lang.PIC32 ? PIC32MX250F128B : lang == Target.Lang.STM32 ? BLUEPILL_F103C8
                : lang.fpga() ? NEXYS_A7_100T : UNO;
    }

    /** "avr-gcc -mmcu=atmega328p -DF_CPU=16000000UL -Os ..." for bare-metal C. */
    String avrGcc(String src, String elf) {
        return "avr-gcc -mmcu=" + mcu + " -DF_CPU=16000000UL -std=gnu11 -Os -Wall -Wextra -o " + elf + " " + src + " -lm";
    }

    /** "xc32-gcc -mprocessor=32MX250F128B -Os ..." for bare-metal PIC32 C. */
    String xc32(String gcc, String src, String elf) {
        return gcc + " -mprocessor=" + pic + " -std=gnu99 -Os -Wall -Wextra -Wl,--defsym=_min_heap_size=2048 -o " + elf + " " + src + " -lm";
    }

    @Override public String toString() {
        return display;
    }
}
