package evia.paradigma;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The runtime of the PIC32 target: bare-metal C for Microchip's XC32 compiler, written against the
 * registers of the PIC32MX (no Harmony, no plib). The same function names as the Arduino and AVR
 * targets - pinMode, digitalWrite, analogRead, millis, uartOpen, spiTransfer, i2cRead ... - so the
 * generated program reads the same.
 *
 * <p>Three families of chips differ, and the board (see {@link Board}) picks the variant:</p>
 * <ul>
 *   <li><b>MX1xx / MX2xx</b> (family 1): peripheral pin select. The runtime routes UART1 to RB13 (RX) and
 *       RB3 (TX), SPI1 to RA1 (SDI), RA4 (SDO) and RB14 (SCK), PWM to RB15, RB8, RB9 and RB2 (OC1 ... OC4);
 *       I2C1 is on RB8 (SCL) and RB9 (SDA). These are the pins of the chipKIT DP32.</li>
 *   <li><b>MX3xx / MX4xx</b> (family 3) and <b>MX5xx / MX6xx / MX7xx</b> (family 5): fixed pins. UART1 on RF2 (RX)
 *       and RF3 (64-pin chips) or RF8 (100-pin chips) (TX), SPI2 on RG7 (SDI), RG8 (SDO), RG6 (SCK),
 *       I2C1 on RG2/RG3 (64 pins) or RA14/RA15 (100 pins), PWM on RD0 ... RD4 (OC1 ... OC5). These are the
 *       pins of the chipKIT Uno32, uC32, Max32 and WF32.</li>
 *   <li>The system and peripheral clock is 30 MHz from the internal 8 MHz oscillator and the PLL: no crystal
 *       is needed, and flash needs no wait states.</li>
 * </ul>
 */
final class Pic32Runtime {
    private Pic32Runtime() {}

    private static final Map<String, EmbeddedRuntime.Piece> PIECES = new LinkedHashMap<>();

    private static void piece(String defines, String needs, String code) {
        EmbeddedRuntime.piece(PIECES, defines, needs, code);
    }

    // ---------------------------------------------------------------- the head: depends on the chip

    private static String pin(char port, int bit) {
        return "R" + port + bit;
    }

    private static final java.util.regex.Pattern PIN_NAME = java.util.regex.Pattern.compile("\\b(R[A-G]\\d{1,2}|A(?:\\d|1[0-5]))\\b");

    /** The head for a chip; {@code used} is all code that follows, so only the pin names it uses are defined. */
    static String head(Board b, String used) {
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.regex.Matcher m = PIN_NAME.matcher(used + " " + b.led);
        while (m.find()) names.add(m.group(1));
        StringBuilder s = new StringBuilder();
        s.append("""
                #include <xc.h>
                #include <cp0defs.h>
                #include <math.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                /* ---- device configuration: the internal 8 MHz oscillator, divided by 2, times 15, divided by 2
                   gives a 30 MHz system clock; the peripheral clock is the same (no crystal needed) */
                #pragma config FNOSC = FRCPLL
                #pragma config FPLLIDIV = DIV_2
                #pragma config FPLLMUL = MUL_15
                #pragma config FPLLODIV = DIV_2
                #pragma config FPBDIV = DIV_1
                #pragma config POSCMOD = OFF
                #pragma config FSOSCEN = OFF
                #pragma config IESO = OFF
                #pragma config OSCIOFNC = OFF
                #pragma config FCKSM = CSDCMD
                #pragma config FWDTEN = OFF
                """);
        if (b.family == 1) {
            s.append("""
                    #pragma config JTAGEN = OFF
                    #pragma config IOL1WAY = OFF
                    #pragma config PMDL1WAY = OFF
                    """);
        }
        s.append("""

                #define RT_SYSCLK 30000000UL
                #define RT_PBCLK 30000000UL
                #define RT_FAMILY\040""").append(b.family).append("""

                #define RT_TEXT(s) s
                typedef const char *rt_text;

                /* Arduino's names, so the program reads the same as the sketch */
                #define HIGH 1
                #define LOW 0
                #define INPUT 0
                #define OUTPUT 1
                #define INPUT_PULLUP 2
                """);
        s.append("#define LED_BUILTIN ").append(b.led).append("   /* the LED of this board: change it to the pin of yours */\n");
        s.append("\n/* Pins are numbered 16 * port + bit: RA0 = 0 ... RA15 = 15, RB0 = 16 ... RG15 = 111. A0 ... A15 are the analog inputs AN0 ... AN15. */\n");
        for (char port = 'A'; port <= 'G'; port++) {
            for (int bit = 0; bit < 16; bit++) {
                if (names.contains(pin(port, bit))) s.append("#define ").append(pin(port, bit)).append(' ').append((port - 'A') * 16 + bit).append('\n');
            }
        }
        s.append("#define RT_AN0 256\n");
        for (int i = 0; i < 16; i++) {
            if (names.contains("A" + i)) s.append("#define A").append(i).append(" (RT_AN0 + ").append(i).append(")\n");
        }
        s.append('\n');
        if (b.family == 1) {
            s.append("""
                    /* ---- peripheral pin select: UART1 RX = RB13, TX = RB3; SPI1 SDI = RA1, SDO = RA4, SCK = RB14;
                       I2C1 SCL = RB8, SDA = RB9 (fixed pins) */
                    static inline void rt_pps(int unlock) {
                        if (unlock) {
                            SYSKEY = 0;
                            SYSKEY = 0xAA996655;
                            SYSKEY = 0x556699AA;
                            CFGCONbits.IOLOCK = 0;
                        } else {
                            CFGCONbits.IOLOCK = 1;
                            SYSKEY = 0x33333333;
                        }
                    }
                    #define RT_UART_PINS() do { rt_pps(1); U1RXR = 3; RPB3R = 1; rt_pps(0); \\
                        ANSELBCLR = (1UL << 13) | (1UL << 3); TRISBSET = 1UL << 13; TRISBCLR = 1UL << 3; } while (0)
                    #define RT_SPI_PINS() do { rt_pps(1); SDI1R = 0; RPA4R = 3; rt_pps(0); \\
                        ANSELACLR = 1UL << 1; ANSELBCLR = 1UL << 14; TRISASET = 1UL << 1; TRISACLR = 1UL << 4; \\
                        TRISBCLR = 1UL << 14; } while (0)
                    #define RT_I2C_PINS() do { } while (0)
                    #define RT_SPICON SPI1CON
                    #define RT_SPICONbits SPI1CONbits
                    #define RT_SPISTATbits SPI1STATbits
                    #define RT_SPIBRG SPI1BRG
                    #define RT_SPIBUF SPI1BUF
                    """);
        } else {
            String tx = b.pkg == 'H' && b.family == 3 ? "3" : "8";
            s.append("/* ---- fixed pins: UART1 RX = RF2, TX = RF").append(tx).append("; SPI2 SDI = RG7, SDO = RG8, SCK = RG6; I2C1 ")
                    .append(b.pkg == 'L' ? "SCL = RA14, SDA = RA15" : "SCL = RG2, SDA = RG3").append(" */\n");
            s.append("#define RT_UART_PINS() do { TRISFSET = 1UL << 2; TRISFCLR = 1UL << ").append(tx).append("; } while (0)\n");
            s.append("""
                    #define RT_SPI_PINS() do { TRISGSET = 1UL << 7; TRISGCLR = (1UL << 6) | (1UL << 8); } while (0)
                    #define RT_I2C_PINS() do { } while (0)
                    #define RT_SPICON SPI2CON
                    #define RT_SPICONbits SPI2CONbits
                    #define RT_SPISTATbits SPI2STATbits
                    #define RT_SPIBRG SPI2BRG
                    #define RT_SPIBUF SPI2BUF
                    """);
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- shared C code (valid C)

    static {
        // ============================================================ pins
        piece("rt_port_reg", "", """
                /* ---- pins: the TRIS (0), LAT (1) or PORT (2) register of a port (A = 0 ... G = 6); 0 if the chip has no such port.
                   Every register has CLR, SET and INV twins right behind it: reg[1] clears bits, reg[2] sets them. */
                static volatile uint32_t *rt_port_reg(uint8_t port, uint8_t which) {
                    switch (port) {
                #if defined(_PORTA_BASE_ADDRESS)
                    case 0: return which == 0 ? &TRISA : which == 1 ? &LATA : &PORTA;
                #endif
                #if defined(_PORTB_BASE_ADDRESS)
                    case 1: return which == 0 ? &TRISB : which == 1 ? &LATB : &PORTB;
                #endif
                #if defined(_PORTC_BASE_ADDRESS)
                    case 2: return which == 0 ? &TRISC : which == 1 ? &LATC : &PORTC;
                #endif
                #if defined(_PORTD_BASE_ADDRESS)
                    case 3: return which == 0 ? &TRISD : which == 1 ? &LATD : &PORTD;
                #endif
                #if defined(_PORTE_BASE_ADDRESS)
                    case 4: return which == 0 ? &TRISE : which == 1 ? &LATE : &PORTE;
                #endif
                #if defined(_PORTF_BASE_ADDRESS)
                    case 5: return which == 0 ? &TRISF : which == 1 ? &LATF : &PORTF;
                #endif
                #if defined(_PORTG_BASE_ADDRESS)
                    case 6: return which == 0 ? &TRISG : which == 1 ? &LATG : &PORTG;
                #endif
                    default: return 0;
                    }
                }
                """);
        piece("rt_digital", "", """
                /* Switches a pin's analog function off (reset leaves the analog pins analog). */
                static void rt_digital(uint8_t port, uint32_t mask) {
                #if RT_FAMILY == 1
                    switch (port) {
                #if defined(_ANSELA_ANSA0_POSITION)
                    case 0: ANSELACLR = mask; break;
                #endif
                #if defined(_ANSELB_ANSB0_POSITION)
                    case 1: ANSELBCLR = mask; break;
                #endif
                #if defined(_ANSELC_ANSC0_POSITION)
                    case 2: ANSELCCLR = mask; break;
                #endif
                    default: break;
                    }
                #else
                    if (port == 1) AD1PCFGSET = mask;   /* AN0 ... AN15 are RB0 ... RB15; 1 = digital */
                #endif
                }
                """);
        piece("rt_pullup", "", """
                /* The internal pull-up of a pin (change notice pull-up). */
                static void rt_pullup(uint8_t port, uint8_t bit, int on) {
                #if RT_FAMILY == 1
                    uint32_t mask = 1UL << bit;
                    switch (port) {
                #if defined(_CNPUA_CNPUA0_POSITION)
                    case 0: if (on) CNPUASET = mask; else CNPUACLR = mask; break;
                #endif
                #if defined(_CNPUB_CNPUB0_POSITION)
                    case 1: if (on) CNPUBSET = mask; else CNPUBCLR = mask; break;
                #endif
                #if defined(_CNPUC_CNPUC0_POSITION)
                    case 2: if (on) CNPUCSET = mask; else CNPUCCLR = mask; break;
                #endif
                    default: break;
                    }
                #else
                    /* the change notice input (CN) of the pin; pins without one have no pull-up */
                    int cn = -1;
                    switch (port) {
                    case 1: cn = bit <= 5 ? 2 + bit : bit == 15 ? 12 : -1; break;       /* RB0 ... RB5 = CN2 ... CN7, RB15 = CN12 */
                    case 3: cn = bit >= 4 && bit <= 7 ? 9 + bit : -1; break;            /* RD4 ... RD7 = CN13 ... CN16 */
                    case 5: cn = bit == 4 || bit == 5 ? 13 + bit : -1; break;           /* RF4, RF5 = CN17, CN18 */
                    case 6: cn = bit >= 6 && bit <= 9 ? 2 + bit : -1; break;            /* RG6 ... RG9 = CN8 ... CN11 */
                    default: break;
                    }
                    if (cn >= 0) {
                        if (on) CNPUESET = 1UL << cn;
                        else CNPUECLR = 1UL << cn;
                    }
                #endif
                }
                """);
        piece("rt_an_pin", "", """
                /* The pin of an analog input AN0 ... AN15, or -1 if no pin of this chip has it. */
                static int rt_an_pin(int ch) {
                #if RT_FAMILY == 1
                    switch (ch) {
                    case 0: return RA0;
                    case 1: return RA1;
                    case 2: case 3: case 4: case 5: return RB0 + (ch - 2);
                    case 9: case 10: case 11: case 12: return RB0 + (24 - ch);   /* AN9 ... AN12 = RB15 ... RB12 */
                    default: return -1;
                    }
                #else
                    return ch >= 0 && ch <= 15 ? RB0 + ch : -1;
                #endif
                }
                """);
        piece("rt_pwm_stop rt_oc_of rt_oc_route rt_oc_set rt_pwm_on", "", """
                /* ---- PWM: the output compare modules OC1 ... OC4 (OC5) on timer 2, 8 bits, about 1.8 kHz */
                static uint8_t rt_pwm_on;     /* bit n - 1: OCn is running */

                static uint8_t rt_oc_of(int pin) {
                #if RT_FAMILY == 1
                    switch (pin) {
                    case RB15: return 1;
                    case RB8: return 2;
                    case RB9: return 3;
                    case RB2: return 4;
                    default: return 0;
                    }
                #else
                    return pin >= RD0 && pin <= RD4 ? (uint8_t) (pin - RD0 + 1) : 0;   /* OC1 ... OC5 are RD0 ... RD4 */
                #endif
                }

                /* Connects (on) or disconnects the pin of an output compare module (pin select chips only). */
                static void rt_oc_route(uint8_t oc, int on) {
                #if RT_FAMILY == 1
                    rt_pps(1);
                    switch (oc) {
                    case 1: RPB15R = on ? 5 : 0; break;
                    case 2: RPB8R = on ? 5 : 0; break;
                    case 3: RPB9R = on ? 5 : 0; break;
                    case 4: RPB2R = on ? 5 : 0; break;
                    default: break;
                    }
                    rt_pps(0);
                #else
                    (void) oc;
                    (void) on;
                #endif
                }

                #define RT_OC_CASE(n) case n: \\
                    if (on) { \\
                        OC##n##RS = duty; \\
                        if (!OC##n##CONbits.ON) { OC##n##R = duty; OC##n##CON = 6; OC##n##CONbits.ON = 1; } \\
                    } else { \\
                        OC##n##CONbits.ON = 0; \\
                    } \\
                    break;
                /* Starts the output compare module with a duty 1 ... 254 (of 256), or stops it. */
                static void rt_oc_set(uint8_t oc, uint32_t duty, int on) {
                    switch (oc) {
                    RT_OC_CASE(1)
                    RT_OC_CASE(2)
                    RT_OC_CASE(3)
                    RT_OC_CASE(4)
                #if RT_FAMILY != 1
                    RT_OC_CASE(5)
                #endif
                    default: break;
                    }
                }

                /* Takes a pin away from PWM before it is used as plain output or input. */
                static void rt_pwm_stop(int pin) {
                    uint8_t oc = rt_oc_of(pin);
                    if (oc && (rt_pwm_on & (1u << (oc - 1)))) {
                        rt_oc_set(oc, 0, 0);
                        rt_oc_route(oc, 0);
                        rt_pwm_on &= (uint8_t) ~(1u << (oc - 1));
                    }
                }
                """);
        piece("pinMode", "rt_port_reg rt_digital rt_pullup rt_an_pin rt_pwm_stop", """
                static void pinMode(int pin, uint8_t mode) {
                    uint8_t port;
                    volatile uint32_t *tris;
                    if (pin >= RT_AN0) pin = rt_an_pin(pin - RT_AN0);
                    if (pin < 0) return;
                    port = (uint8_t) (pin >> 4);
                    tris = rt_port_reg(port, 0);
                    if (!tris) return;
                    rt_pwm_stop(pin);
                    rt_digital(port, 1UL << (pin & 15));
                    if (mode == OUTPUT) tris[1] = 1UL << (pin & 15);   /* TRIS: 0 = output */
                    else tris[2] = 1UL << (pin & 15);
                    rt_pullup(port, (uint8_t) (pin & 15), mode == INPUT_PULLUP);
                }
                """);
        piece("digitalWrite", "rt_port_reg rt_pwm_stop", """
                static void digitalWrite(int pin, uint8_t value) {
                    volatile uint32_t *lat;
                    if (pin < 0 || pin >= RT_AN0) return;
                    lat = rt_port_reg((uint8_t) (pin >> 4), 1);
                    if (!lat) return;
                    rt_pwm_stop(pin);
                    if (value != LOW) lat[2] = 1UL << (pin & 15);
                    else lat[1] = 1UL << (pin & 15);
                }
                """);
        piece("digitalRead", "rt_port_reg rt_pwm_stop", """
                static int digitalRead(int pin) {
                    volatile uint32_t *port;
                    if (pin < 0 || pin >= RT_AN0) return LOW;
                    port = rt_port_reg((uint8_t) (pin >> 4), 2);
                    if (!port) return LOW;
                    rt_pwm_stop(pin);
                    return (*port & (1UL << (pin & 15))) ? HIGH : LOW;
                }
                """);
        piece("analogWrite", "pinMode digitalWrite rt_oc_of rt_oc_route rt_oc_set", """
                static void rt_pwm_timer(void) {
                    if (!T2CONbits.ON) {
                        T2CON = 0x0060;     /* clock / 64 */
                        PR2 = 255;
                        TMR2 = 0;
                        T2CONbits.ON = 1;
                    }
                }

                /* PWM on the pins of an output compare module; other pins are only switched (HIGH from 128 on). */
                static void analogWrite(int pin, int value) {
                    uint8_t oc = rt_oc_of(pin);
                    pinMode(pin, OUTPUT);
                    if (value <= 0 || value >= 255 || oc == 0) {
                        digitalWrite(pin, value >= 128 ? HIGH : LOW);
                    } else {
                        rt_pwm_timer();
                        rt_oc_route(oc, 1);
                        rt_oc_set(oc, (uint32_t) value, 1);
                        rt_pwm_on |= (uint8_t) (1u << (oc - 1));
                    }
                }
                """);
        piece("analogRead", "rt_port_reg rt_an_pin", """
                /* 0..1023 for 0 V to the supply (10 bits). Accepts A0 ... A15 or the channel number. */
                static int analogRead(int pin) {
                    static uint8_t ready;
                    int ch = pin >= RT_AN0 ? pin - RT_AN0 : pin, p;
                    if (ch < 0 || ch > 15) return 0;
                    if (!ready) {
                        AD1CON1 = 0x00E0;      /* integer result, the converter ends sampling by itself */
                        AD1CON2 = 0;           /* reference: the supply */
                        AD1CON3 = 0x1F02;      /* sample for 31 Tad, Tad = 6 clocks (200 ns) */
                        AD1CON1bits.ON = 1;
                        ready = 1;
                    }
                    p = rt_an_pin(ch);
                    if (p >= 0) {
                        volatile uint32_t *tris = rt_port_reg((uint8_t) (p >> 4), 0);
                        if (tris) tris[2] = 1UL << (p & 15);
                #if RT_FAMILY == 1
                        if ((p >> 4) == 0) ANSELASET = 1UL << (p & 15);
                        else ANSELBSET = 1UL << (p & 15);
                #else
                        AD1PCFGCLR = 1UL << ch;   /* 0 = analog */
                #endif
                    }
                    AD1CHSbits.CH0SA = (unsigned) ch;
                    AD1CON1bits.DONE = 0;
                    AD1CON1bits.SAMP = 1;
                    while (!AD1CON1bits.DONE) {
                    }
                    return (int) ADC1BUF0;
                }
                """);

        // ============================================================ time
        piece("millis RT_HAS_CLOCK", "", """
                /* ---- time: the core timer counts every second system clock (15000 per millisecond). millis() adds up
                   what has passed since it was last asked, so it must be called at least every 4 minutes (Delay does). */
                #define RT_HAS_CLOCK 1
                static uint32_t rt_clock_last;
                static uint32_t rt_clock_ticks;
                static unsigned long rt_clock_ms;

                static unsigned long millis(void) {
                    uint32_t now = _CP0_GET_COUNT();
                    rt_clock_ticks += now - rt_clock_last;
                    rt_clock_last = now;
                    while (rt_clock_ticks >= 15000UL) {
                        rt_clock_ticks -= 15000UL;
                        rt_clock_ms++;
                    }
                    return rt_clock_ms;
                }
                """);
        piece("delay", "millis", """
                static void delay(unsigned long ms) {
                    unsigned long start = millis();
                    while (millis() - start < ms) {
                    }
                }
                """);

        // ============================================================ UART1 (Print, Input and the UART blocks)
        piece("rt_uart rt_putc rt_uart_start RT_HAS_UART", "", """
                /* ---- UART1, 115200 baud 8N1 (the high-speed divider: baud = peripheral clock / (4 * (U1BRG + 1))) */
                #define RT_HAS_UART 1
                static void rt_uart_start(unsigned long baud) {
                    unsigned long brg = (RT_PBCLK / 4 + baud / 2) / baud;
                    brg = brg > 0 ? brg - 1 : 0;
                    U1MODE = 0;
                    U1STA = 0;
                    U1BRG = brg > 65535UL ? 65535UL : brg;
                    U1MODEbits.BRGH = 1;
                    U1MODEbits.ON = 1;
                    U1STAbits.UTXEN = 1;
                    U1STAbits.URXEN = 1;
                }

                static inline void rt_putc(char c) {
                    while (U1STAbits.UTXBF) {
                    }
                    U1TXREG = (uint8_t) c;
                }
                """);
        piece("rt_begin", "", """
                /* Starts the clock, makes every pin digital and starts the UART. */
                static void rt_begin(void) {
                #if RT_FAMILY == 1
                    ANSELA = 0;
                    ANSELB = 0;
                #else
                    AD1PCFG = 0xFFFF;
                #endif
                #if defined(RT_HAS_CLOCK)
                    rt_clock_last = _CP0_GET_COUNT();
                #endif
                #if defined(RT_HAS_UART)
                    RT_UART_PINS();
                    rt_uart_start(115200UL);
                #endif
                }
                """);
        piece("rt_end", "", """
                /* The program is over: let the UART finish, then stop here (pins and PWM keep their state). */
                __attribute__((noreturn)) static void rt_end(void) {
                #if defined(RT_HAS_UART)
                    while (!U1STAbits.TRMT) {
                    }
                #endif
                    for (;;) {
                    }
                }
                """);
        piece("rt_put_text", "rt_uart", """
                static void rt_put_text(rt_text s) {
                    char c;
                    while ((c = *s++) != '\\0') {
                        if (c == '\\n') rt_putc('\\r');
                        rt_putc(c);
                    }
                }
                """);
        piece("rt_newline", "rt_uart", """
                static void rt_newline(void) {
                    rt_putc('\\r');
                    rt_putc('\\n');
                }
                """);
        piece("rt_print_line", "rt_put_text rt_newline", """
                static void rt_print_line(rt_text s) {
                    rt_put_text(s);
                    rt_newline();
                }
                """);
        piece("rt_fail", "rt_put_text rt_newline rt_end", """
                /* An error stops the program: the message goes to the UART, then the chip stops. */
                __attribute__((noreturn)) static void rt_fail(rt_text msg) {
                    rt_put_text(">> error: ");
                    rt_put_text(msg);
                    rt_newline();
                    rt_end();
                }
                """);
        piece("rt_digits", "", EmbeddedRuntime.DIGITS);
        piece("rt_fmt", "rt_digits", EmbeddedRuntime.FMT);
        piece("rt_put_num", "rt_fmt rt_put_text", """
                static void rt_put_num(double d) {
                    char buf[32];
                    rt_fmt(d, buf);
                    rt_put_text(buf);
                }
                """);
        piece("rt_parse_num", "", EmbeddedRuntime.PARSE);
        piece("rt_read_line", "rt_uart", """
                /* One line from the UART (Enter); empty lines are skipped. */
                static const char *rt_read_line(void) {
                    static char line[48];
                    unsigned n = 0;
                    for (;;) {
                        char c;
                        if (U1STAbits.OERR) U1STAbits.OERR = 0;   /* an overrun stops the receiver until it is cleared */
                        while (!U1STAbits.URXDA) {
                        }
                        c = (char) U1RXREG;
                        if (c == '\\n' || c == '\\r') {
                            if (n > 0) break;
                            continue;
                        }
                        if (n + 1 < sizeof line) line[n++] = c;
                    }
                    line[n] = '\\0';
                    return line;
                }
                """);
        piece("rt_input_num", "rt_read_line rt_parse_num rt_put_text rt_newline rt_end", """
                /* Input block: asks on the UART; the answer must be a number. */
                static double rt_input_num(rt_text name) {
                    double d = 0;
                    const char *line;
                    rt_put_text("Enter a value for ");
                    rt_put_text(name);
                    rt_put_text(": ");
                    line = rt_read_line();
                    if (!rt_parse_num(line, &d)) {
                        rt_put_text(">> error: '");
                        rt_put_text(line);
                        rt_put_text("' is not a number");
                        rt_newline();
                        rt_end();
                    }
                    return d;
                }
                """);
        piece("rt_alloc", "rt_fail", """
                static void *rt_alloc(size_t n) {
                    void *p = calloc(1, n ? n : 1);
                    if (!p) rt_fail("out of memory");
                    return p;
                }
                """);
        piece("rt_ms", "", EmbeddedRuntime.HELPERS_MS);
        piece("rt_duty", "", EmbeddedRuntime.HELPERS_DUTY);
        piece("rt_hz", "", EmbeddedRuntime.HELPERS_HZ);

        // ============================================================ UART, SPI and I2C blocks
        piece("uartOpen uartWrite uartRead", "rt_uart", """
                /* The serial port is the one Print uses; UART Open changes its speed. */
                static void uartOpen(unsigned long baud) {
                    while (!U1STAbits.TRMT) {
                    }
                    rt_uart_start(baud);
                }
                static void uartWrite(uint8_t b) { rt_putc((char) b); }
                static int uartRead(void) {
                    if (U1STAbits.OERR) U1STAbits.OERR = 0;
                    if (!U1STAbits.URXDA) return -1;   /* nothing has arrived */
                    return (int) (U1RXREG & 0xFF);
                }
                """);
        piece("spiOpen spiTransfer", "", """
                /* ---- SPI master, mode 0, most significant bit first: clock = peripheral clock / (2 * (SPIxBRG + 1)) */
                static void spiOpen(unsigned long hz) {
                    unsigned long brg = RT_PBCLK / (2 * hz);
                    brg = brg > 0 ? brg - 1 : 0;
                    RT_SPI_PINS();
                    RT_SPICON = 0;
                    (void) RT_SPIBUF;
                    RT_SPIBRG = brg > 511UL ? 511UL : brg;
                    RT_SPICONbits.MSTEN = 1;
                    RT_SPICONbits.CKE = 1;      /* mode 0: clock idles low, data changes on the falling edge */
                    RT_SPICONbits.ON = 1;
                }
                static int spiTransfer(uint8_t b) {
                    RT_SPIBUF = b;
                    while (!RT_SPISTATbits.SPIRBF) {
                    }
                    return (int) (RT_SPIBUF & 0xFF);
                }
                """);
        piece("i2cOpen i2cWrite i2cRead", "", """
                /* ---- I2C master (I2C1); a device that does not answer reads as -1 */
                static void i2cOpen(unsigned long hz) {
                    /* clock = 1 / (2 * (I2C1BRG + 2) / peripheral clock + 104 ns) */
                    unsigned long brg = RT_PBCLK / (2 * hz);
                    brg = brg > 5 ? brg - (RT_PBCLK / 1000000UL) * 104UL / 1000UL - 2 : 2;
                    RT_I2C_PINS();
                    I2C1CON = 0;
                    I2C1STAT = 0;
                    I2C1BRG = brg > 4095UL ? 4095UL : brg;
                    I2C1CONbits.ON = 1;
                }

                /* Waits until the bus is idle; 0 if it never is (stuck low). */
                static int rt_i2c_wait(void) {
                    unsigned long n = 400000UL;
                    while (((I2C1CON & 0x1F) || I2C1STATbits.TRSTAT) && --n) {
                    }
                    return n != 0;
                }

                static int rt_i2c_send(uint8_t b) {
                    I2C1TRN = b;
                    return rt_i2c_wait() && !I2C1STATbits.ACKSTAT;
                }

                static void rt_i2c_stop(void) {
                    I2C1STATbits.BCL = 0;
                    I2C1CONbits.PEN = 1;
                    rt_i2c_wait();
                }

                /* START, the device's address for writing and the register; 0 if the device does not answer. */
                static int rt_i2c_select(uint8_t addr, uint8_t reg) {
                    if (!rt_i2c_wait()) return 0;
                    I2C1CONbits.SEN = 1;
                    return rt_i2c_wait() && rt_i2c_send((uint8_t) (addr << 1)) && rt_i2c_send(reg);
                }

                static void i2cWrite(uint8_t addr, uint8_t reg, uint8_t value) {
                    if (rt_i2c_select(addr, reg)) rt_i2c_send(value);
                    rt_i2c_stop();
                }

                static int i2cRead(uint8_t addr, uint8_t reg) {
                    int value = -1;
                    if (rt_i2c_select(addr, reg)) {
                        I2C1CONbits.RSEN = 1;
                        if (rt_i2c_wait() && rt_i2c_send((uint8_t) ((addr << 1) | 1))) {
                            unsigned long n = 400000UL;
                            I2C1CONbits.RCEN = 1;
                            while (!I2C1STATbits.RBF && --n) {
                            }
                            if (n) {
                                value = (int) (I2C1RCV & 0xFF);
                                I2C1CONbits.ACKDT = 1;   /* the last byte: NACK */
                                I2C1CONbits.ACKEN = 1;
                                rt_i2c_wait();
                            }
                        }
                    }
                    rt_i2c_stop();
                    return value;
                }
                """);
    }

    /** The runtime for a PIC32 program: the head for this chip, then only the pieces {@code program} uses. */
    static String source(Board b, String program) {
        String title = "bare-metal PIC32 " + b.pic.substring(2);
        String body = EmbeddedRuntime.assemble(title, "", PIECES, program);
        return EmbeddedRuntime.assemble(title, head(b, program + body), PIECES, program);
    }
}
