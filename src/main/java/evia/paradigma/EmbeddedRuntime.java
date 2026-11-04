package evia.paradigma;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The runtimes of the microcontroller targets. Like the C runtime, a program gets only the pieces
 * it uses (and what those need).
 *
 * <ul>
 *   <li>Arduino sketch: Print and Input go through {@code Serial} at 115200 baud; pins and time are
 *       the board's own Arduino functions, so one sketch builds for AVR and ESP32 boards.</li>
 *   <li>AVR C (bare metal, avr-gcc and avr-libc only): the same function names - pinMode,
 *       digitalWrite, analogRead, millis ... - written against the registers of the ATmega328P,
 *       ATmega2560 and ATmega32U4, with Arduino's pin numbering, timer setup and 115200 baud UART.</li>
 * </ul>
 */
final class EmbeddedRuntime {
    private EmbeddedRuntime() {}

    record Piece(List<String> defines, List<String> needs, String code) {}

    private static final Map<String, Piece> ARDUINO = new LinkedHashMap<>();
    private static final Map<String, Piece> AVR = new LinkedHashMap<>();

    static void piece(Map<String, Piece> map, String defines, String needs, String code) {
        Piece p = new Piece(List.of(defines.split(" ")), needs.isEmpty() ? List.of() : List.of(needs.split(" ")), code);
        for (String d : p.defines()) map.put(d, p);
    }

    // ---------------------------------------------------------------- shared C code (valid C and C++)

    static final String DIGITS = """
            /* Writes the decimal digits of v at p and returns the end. */
            static char *rt_digits(unsigned long long v, char *p) {
                char tmp[21];
                int n = 0;
                do {
                    tmp[n++] = (char) ('0' + (int) (v % 10));
                    v /= 10;
                } while (v);
                while (n) *p++ = tmp[--n];
                return p;
            }
            """;

    static final String FMT = """
            /* A number the way the interpreter prints it: whole numbers without ".0", others with up to
               6 decimals ("0.25"), very large or small ones as "1.5E10" (buf: 32 chars). */
            static void rt_fmt(double d, char *buf) {
                char *p = buf;
                int e = 0;
                unsigned long long scaled, whole, frac;
                if (d != d) {
                    strcpy(buf, "NaN");
                    return;
                }
                if (d < 0) {
                    *p++ = '-';
                    d = -d;
                }
                if (d - d != 0) {
                    strcpy(p, "Infinity");
                    return;
                }
                if (d == floor(d) && d < 1e15) {
                    *rt_digits((unsigned long long) d, p) = '\\0';
                    return;
                }
                if (d >= 1e7 || d < 1e-3) {
                    while (d >= 10) { d /= 10; e++; }
                    while (d < 1) { d *= 10; e--; }
                }
                scaled = (unsigned long long) (d * 1e6 + 0.5);
                whole = scaled / 1000000ULL;
                frac = scaled % 1000000ULL;
                if (e != 0 && whole >= 10) { whole = 1; frac = 0; e++; }
                p = rt_digits(whole, p);
                *p++ = '.';
                if (frac == 0) {
                    *p++ = '0';
                } else {
                    char f[6];
                    int i, n = 6;
                    for (i = 5; i >= 0; i--) { f[i] = (char) ('0' + (int) (frac % 10)); frac /= 10; }
                    while (f[n - 1] == '0') n--;
                    for (i = 0; i < n; i++) *p++ = f[i];
                }
                if (e != 0) {
                    *p++ = 'E';
                    if (e < 0) { *p++ = '-'; e = -e; }
                    p = rt_digits((unsigned long long) e, p);
                }
                *p = '\\0';
            }
            """;

    static final String PARSE = """
            /* Input: the whole line must be a number (spaces around it are fine). */
            static int rt_parse_num(const char *s, double *out) {
                char *end;
                while (*s == ' ' || *s == '\\t') s++;
                if (!*s) return 0;
                *out = strtod(s, &end);
                if (end == s) return 0;
                while (*end == ' ' || *end == '\\t') end++;
                return *end == '\\0';
            }
            """;

    static final String HELPERS_MS = """
            /* Delay: a millisecond count (negative waits 0). */
            static unsigned long rt_ms(double d) {
                return d > 0 ? (d < 4294967295.0 ? (unsigned long) d : 4294967295UL) : 0UL;
            }
            """;

    static final String HELPERS_DUTY = """
            /* Analog Write: a PWM duty 0..255, rounded and clamped. */
            static int rt_duty(double d) {
                return d <= 0 ? 0 : d >= 255 ? 255 : (int) (d + 0.5);
            }
            """;

    static final String HELPERS_HZ = """
            /* UART, SPI and I2C: a rate in Hz, at least 1. */
            static unsigned long rt_hz(double d) {
                return d >= 1 ? (d < 4294967295.0 ? (unsigned long) d : 4294967295UL) : 1UL;
            }
            """;

    static {
        // ============================================================ Arduino sketch
        piece(ARDUINO, "rt_begin", "", """
                /* Serial at 115200 baud; boards with native USB (Leonardo, Micro, ESP32-S2/S3/C3/C6/H2)
                   get up to 3 seconds for the Serial Monitor to connect. */
                static void rt_begin(void) {
                    Serial.begin(115200);
                    unsigned long t0 = millis();
                    while (!Serial && millis() - t0 < 3000) {
                    }
                }
                """);
        piece(ARDUINO, "rt_end", "", """
                static void rt_end(void) {
                    Serial.flush();
                }
                """);
        piece(ARDUINO, "rt_fail", "", """
                /* An error stops the program: the message goes to the serial port, then the board waits. */
                __attribute__((noreturn)) static void rt_fail(rt_text msg) {
                    Serial.print(F(">> error: "));
                    Serial.println(msg);
                    Serial.flush();
                    for (;;) delay(1000);
                }
                """);
        piece(ARDUINO, "rt_put_text", "", "static void rt_put_text(rt_text s) { Serial.print(s); }\n");
        piece(ARDUINO, "rt_print_line", "", "static void rt_print_line(rt_text s) { Serial.println(s); }\n");
        piece(ARDUINO, "rt_newline", "", "static void rt_newline(void) { Serial.println(); }\n");
        piece(ARDUINO, "rt_digits", "", DIGITS);
        piece(ARDUINO, "rt_fmt", "rt_digits", FMT);
        piece(ARDUINO, "rt_put_num", "rt_fmt", """
                static void rt_put_num(double d) {
                    char buf[32];
                    rt_fmt(d, buf);
                    Serial.print(buf);
                }
                """);
        piece(ARDUINO, "rt_parse_num", "", PARSE);
        piece(ARDUINO, "rt_read_line", "", """
                /* One line from the serial port (Enter, or the Serial Monitor's "Newline" ending); empty lines are skipped. */
                static const char *rt_read_line(void) {
                    static char line[48];
                    size_t n = 0;
                    for (;;) {
                        while (!Serial.available()) yield();
                        int ch = Serial.read();
                        if (ch == '\\n' || ch == '\\r') {
                            if (n > 0) break;
                            continue;
                        }
                        if (ch >= 0 && n + 1 < sizeof line) line[n++] = (char) ch;
                    }
                    line[n] = '\\0';
                    return line;
                }
                """);
        piece(ARDUINO, "rt_input_num", "rt_read_line rt_parse_num rt_put_text", """
                /* Input block: asks on the serial port; the answer must be a number. */
                static double rt_input_num(rt_text name) {
                    double d = 0;
                    rt_put_text(F("Enter a value for "));
                    rt_put_text(name);
                    rt_put_text(F(": "));
                    const char *line = rt_read_line();
                    if (!rt_parse_num(line, &d)) {
                        Serial.print(F(">> error: '"));
                        Serial.print(line);
                        Serial.println(F("' is not a number"));
                        Serial.flush();
                        for (;;) delay(1000);
                    }
                    return d;
                }
                """);
        piece(ARDUINO, "rt_alloc", "rt_fail", """
                static void *rt_alloc(size_t n) {
                    void *p = calloc(1, n ? n : 1);
                    if (!p) rt_fail(F("out of memory"));
                    return p;
                }
                """);
        piece(ARDUINO, "rt_ms", "", HELPERS_MS);
        piece(ARDUINO, "rt_duty", "", HELPERS_DUTY);
        piece(ARDUINO, "rt_hz", "", HELPERS_HZ);

        // UART, SPI and I2C: the board's own Serial, SPI and Wire
        piece(ARDUINO, "uartOpen uartWrite uartRead", "", """
                /* The serial port is the one Print uses; UART Open changes its speed. */
                static void uartOpen(unsigned long baud) { Serial.begin(baud); }
                static void uartWrite(uint8_t b) { Serial.write(b); }
                static int uartRead(void) { return Serial.read(); }   /* -1 if nothing has arrived */
                """);
        piece(ARDUINO, "spiOpen spiTransfer", "", """
                #include <SPI.h>
                /* SPI master, mode 0, most significant bit first, on the board's default pins. */
                static unsigned long rt_spi_hz = 1000000UL;
                static void spiOpen(unsigned long hz) {
                    rt_spi_hz = hz;
                    SPI.begin();
                }
                static int spiTransfer(uint8_t b) {
                    SPI.beginTransaction(SPISettings(rt_spi_hz, MSBFIRST, SPI_MODE0));
                    int r = SPI.transfer(b);
                    SPI.endTransaction();
                    return r;
                }
                """);
        piece(ARDUINO, "i2cOpen i2cWrite i2cRead", "", """
                #include <Wire.h>
                /* I2C master on the board's default pins; a device that does not answer reads as -1. */
                static void i2cOpen(unsigned long hz) {
                    Wire.begin();
                    Wire.setClock(hz);
                }
                static void i2cWrite(uint8_t addr, uint8_t reg, uint8_t value) {
                    Wire.beginTransmission(addr);
                    Wire.write(reg);
                    Wire.write(value);
                    Wire.endTransmission();
                }
                static int i2cRead(uint8_t addr, uint8_t reg) {
                    Wire.beginTransmission(addr);
                    Wire.write(reg);
                    if (Wire.endTransmission(false) != 0) return -1;
                    if (Wire.requestFrom(addr, (uint8_t) 1) != 1) return -1;
                    return Wire.read();
                }
                """);

        // ============================================================ bare-metal AVR
        piece(AVR, "rt_uart", "", """
                /* ---- UART: USART0, or USART1 (pins 0 and 1) on the ATmega32U4, 115200 baud 8N1 */
                #if defined(UBRR0H)
                #define RT_UBRR UBRR0
                #define RT_UCSRA UCSR0A
                #define RT_UCSRB UCSR0B
                #define RT_UCSRC UCSR0C
                #define RT_UDR UDR0
                #define RT_U2X U2X0
                #define RT_RXEN RXEN0
                #define RT_TXEN TXEN0
                #define RT_UDRE UDRE0
                #define RT_RXC RXC0
                #define RT_TXC TXC0
                #define RT_UCSZ0 UCSZ00
                #define RT_UCSZ1 UCSZ01
                #else
                #define RT_UBRR UBRR1
                #define RT_UCSRA UCSR1A
                #define RT_UCSRB UCSR1B
                #define RT_UCSRC UCSR1C
                #define RT_UDR UDR1
                #define RT_U2X U2X1
                #define RT_RXEN RXEN1
                #define RT_TXEN TXEN1
                #define RT_UDRE UDRE1
                #define RT_RXC RXC1
                #define RT_TXC TXC1
                #define RT_UCSZ0 UCSZ10
                #define RT_UCSZ1 UCSZ11
                #endif
                static uint8_t rt_sent;   /* something was sent: rt_end waits until it has left */

                static inline void rt_putc(char c) {
                    while (!(RT_UCSRA & _BV(RT_UDRE))) {
                    }
                    RT_UCSRA |= _BV(RT_TXC);   /* writing 1 clears "transmit complete" */
                    RT_UDR = (uint8_t) c;
                    rt_sent = 1;
                }
                """);
        piece(AVR, "rt_clock", "", """
                /* ---- time: timer 0 overflows every 1.024 ms (16 MHz / 64 / 256), as on an Arduino */
                static volatile unsigned long rt_clock_ms;
                static volatile uint8_t rt_clock_fract;

                ISR(TIMER0_OVF_vect) {
                    unsigned long m = rt_clock_ms;
                    uint8_t f = rt_clock_fract;
                    m += 1;
                    f += 3;                 /* the extra 0.024 ms, counted in 8 us steps */
                    if (f >= 125) {
                        f -= 125;
                        m += 1;
                    }
                    rt_clock_fract = f;
                    rt_clock_ms = m;
                }
                """);
        piece(AVR, "rt_begin", "rt_uart rt_clock", """
                /* Timers as an Arduino sets them up (8-bit PWM about 1 kHz / 490 Hz, timer 0 also counts
                   milliseconds), the ADC, the UART, then interrupts on. */
                static void rt_begin(void) {
                    TCCR0A = _BV(WGM01) | _BV(WGM00);   /* fast PWM */
                    TCCR0B = _BV(CS01) | _BV(CS00);     /* clock / 64 */
                    TIMSK0 = _BV(TOIE0);
                #if defined(TCCR1B)
                    TCCR1B = _BV(CS11) | _BV(CS10);     /* phase-correct 8-bit PWM, clock / 64 */
                    TCCR1A = _BV(WGM10);
                #endif
                #if defined(TCCR2B)
                    TCCR2B = _BV(CS22);
                    TCCR2A = _BV(WGM20);
                #endif
                #if defined(TCCR3B)
                    TCCR3B = _BV(CS31) | _BV(CS30);
                    TCCR3A = _BV(WGM30);
                #endif
                #if defined(TCCR4E)                    /* ATmega32U4: high-speed timer 4 */
                    TCCR4B = _BV(CS42) | _BV(CS41) | _BV(CS40);
                    TCCR4D = _BV(WGM40);
                    TCCR4A = _BV(PWM4A);
                    TCCR4C = _BV(PWM4D);
                #elif defined(TCCR4B)
                    TCCR4B = _BV(CS41) | _BV(CS40);
                    TCCR4A = _BV(WGM40);
                #endif
                #if defined(TCCR5B)
                    TCCR5B = _BV(CS51) | _BV(CS50);
                    TCCR5A = _BV(WGM50);
                #endif
                    ADCSRA = _BV(ADEN) | _BV(ADPS2) | _BV(ADPS1) | _BV(ADPS0);   /* ADC clock / 128 */
                    RT_UCSRA = _BV(RT_U2X);
                    RT_UBRR = (F_CPU / 4 / 115200 - 1) / 2;
                    RT_UCSRC = _BV(RT_UCSZ1) | _BV(RT_UCSZ0);
                    RT_UCSRB = _BV(RT_RXEN) | _BV(RT_TXEN);
                    sei();
                }
                """);
        piece(AVR, "rt_end", "rt_uart", """
                /* The program is over: let the UART finish, then sleep for good (PWM keeps running). */
                __attribute__((noreturn)) static void rt_end(void) {
                    if (rt_sent) {
                        while (!(RT_UCSRA & _BV(RT_TXC))) {
                        }
                    }
                    cli();
                    set_sleep_mode(SLEEP_MODE_IDLE);
                    sleep_enable();
                    for (;;) sleep_cpu();
                }
                """);
        piece(AVR, "rt_put_text", "rt_uart", """
                /* Text kept in flash (RT_TEXT / PSTR), so it doesn't use the chip's small RAM. */
                static void rt_put_text(rt_text s) {
                    char c;
                    while ((c = (char) pgm_read_byte(s++)) != '\\0') {
                        if (c == '\\n') rt_putc('\\r');
                        rt_putc(c);
                    }
                }
                """);
        piece(AVR, "rt_newline", "rt_uart", """
                static void rt_newline(void) {
                    rt_putc('\\r');
                    rt_putc('\\n');
                }
                """);
        piece(AVR, "rt_print_line", "rt_put_text rt_newline", """
                static void rt_print_line(rt_text s) {
                    rt_put_text(s);
                    rt_newline();
                }
                """);
        piece(AVR, "rt_put_ram", "rt_uart", """
                static void rt_put_ram(const char *s) {
                    while (*s) rt_putc(*s++);
                }
                """);
        piece(AVR, "rt_fail", "rt_put_text rt_put_ram rt_newline rt_end", """
                /* An error stops the program: the message goes to the UART, then the chip sleeps. */
                __attribute__((noreturn)) static void rt_fail(rt_text msg) {
                    rt_put_ram(">> error: ");
                    rt_put_text(msg);
                    rt_newline();
                    rt_end();
                }
                """);
        piece(AVR, "rt_digits", "", DIGITS);
        piece(AVR, "rt_fmt", "rt_digits", FMT);
        piece(AVR, "rt_put_num", "rt_fmt rt_put_ram", """
                static void rt_put_num(double d) {
                    char buf[32];
                    rt_fmt(d, buf);
                    rt_put_ram(buf);
                }
                """);
        piece(AVR, "rt_parse_num", "", PARSE);
        piece(AVR, "rt_read_line", "rt_uart", """
                /* One line from the UART (Enter); empty lines are skipped. */
                static const char *rt_read_line(void) {
                    static char line[32];
                    uint8_t n = 0;
                    for (;;) {
                        char c;
                        while (!(RT_UCSRA & _BV(RT_RXC))) {
                        }
                        c = (char) RT_UDR;
                        if (c == '\\n' || c == '\\r') {
                            if (n > 0) break;
                            continue;
                        }
                        if (n + 1 < (uint8_t) sizeof line) line[n++] = c;
                    }
                    line[n] = '\\0';
                    return line;
                }
                """);
        piece(AVR, "rt_input_num", "rt_read_line rt_parse_num rt_put_text rt_put_ram rt_newline rt_end", """
                /* Input block: asks on the UART; the answer must be a number. */
                static double rt_input_num(rt_text name) {
                    double d = 0;
                    const char *line;
                    rt_put_ram("Enter a value for ");
                    rt_put_text(name);
                    rt_put_ram(": ");
                    line = rt_read_line();
                    if (!rt_parse_num(line, &d)) {
                        rt_put_ram(">> error: '");
                        rt_put_ram(line);
                        rt_put_ram("' is not a number");
                        rt_newline();
                        rt_end();
                    }
                    return d;
                }
                """);
        piece(AVR, "rt_alloc", "rt_fail", """
                static void *rt_alloc(size_t n) {
                    void *p = calloc(1, n ? n : 1);
                    if (!p) rt_fail(PSTR("out of memory"));
                    return p;
                }
                """);
        piece(AVR, "rt_ms", "", HELPERS_MS);
        piece(AVR, "rt_duty", "", HELPERS_DUTY);
        piece(AVR, "rt_hz", "", HELPERS_HZ);

        // UART, SPI and I2C (TWI) registers
        piece(AVR, "uartOpen uartWrite uartRead", "rt_uart", """
                /* The serial port is the one Print uses (8N1); UART Open changes its speed. */
                static void uartOpen(unsigned long baud) {
                    unsigned long div = F_CPU / 4 / baud;     /* double-speed mode: UBRR = F_CPU / (8 * baud) - 1 */
                    if (rt_sent) {
                        while (!(RT_UCSRA & _BV(RT_TXC))) {
                        }
                    }
                    if (div < 2) div = 2;
                    if ((div - 1) / 2 > 4095) {               /* too slow for double speed */
                        RT_UCSRA = 0;
                        div = F_CPU / 8 / baud;               /* UBRR = F_CPU / (16 * baud) - 1 */
                        RT_UBRR = (uint16_t) (div < 3 ? 0 : (div - 1) / 2 > 4095 ? 4095 : (div - 1) / 2);
                    } else {
                        RT_UCSRA = _BV(RT_U2X);
                        RT_UBRR = (uint16_t) ((div - 1) / 2);
                    }
                }
                static void uartWrite(uint8_t b) { rt_putc((char) b); }
                static int uartRead(void) {
                    if (!(RT_UCSRA & _BV(RT_RXC))) return -1;   /* nothing has arrived */
                    return RT_UDR;
                }
                """);
        piece(AVR, "spiOpen spiTransfer", "", """
                /* ---- SPI master, mode 0, most significant bit first */
                #if defined(__AVR_ATmega328P__)
                #define RT_SPI_MASK (_BV(PB2) | _BV(PB3) | _BV(PB5))   /* SS, MOSI, SCK are outputs; MISO is an input */
                #else
                #define RT_SPI_MASK (_BV(PB0) | _BV(PB1) | _BV(PB2))
                #endif
                static void spiOpen(unsigned long hz) {
                    /* the smallest divider (2 ... 128) that keeps the clock at or below hz */
                    static const uint8_t spr[] PROGMEM = { 0, 0, 1, 1, 2, 2, 3 };
                    static const uint8_t x2[] PROGMEM = { 1, 0, 1, 0, 1, 0, 0 };
                    uint8_t i = 0;
                    unsigned long div = 2;
                    while (i < 6 && F_CPU / div > hz) {
                        div <<= 1;
                        i++;
                    }
                    DDRB |= RT_SPI_MASK;
                    SPCR = (uint8_t) (_BV(SPE) | _BV(MSTR) | pgm_read_byte(spr + i));
                    SPSR = pgm_read_byte(x2 + i) ? _BV(SPI2X) : 0;
                }
                static int spiTransfer(uint8_t b) {
                    SPDR = b;
                    while (!(SPSR & _BV(SPIF))) {
                    }
                    return SPDR;
                }
                """);
        piece(AVR, "i2cOpen i2cWrite i2cRead", "", """
                /* ---- I2C (TWI) master; a device that does not answer reads as -1 */
                #define RT_TWI_START 0x08
                #define RT_TWI_RESTART 0x10
                #define RT_TWI_W_ACK 0x18
                #define RT_TWI_DATA_ACK 0x28
                #define RT_TWI_R_ACK 0x40
                #define RT_TWI_RDATA_NACK 0x58

                static void i2cOpen(unsigned long hz) {
                    unsigned long cycles = F_CPU / hz;
                    uint8_t prescale = 0;
                    unsigned long bit;
                #if defined(__AVR_ATmega328P__)
                    PORTC |= _BV(PC4) | _BV(PC5);     /* pull-ups on SDA and SCL */
                #else
                    PORTD |= _BV(PD0) | _BV(PD1);
                #endif
                    cycles = cycles > 16 ? cycles - 16 : 0;
                    while (prescale < 3 && cycles / (2UL << (2 * prescale)) > 255) prescale++;
                    bit = cycles / (2UL << (2 * prescale));
                    TWSR = prescale;
                    TWBR = (uint8_t) (bit > 255 ? 255 : bit);
                    TWCR = _BV(TWEN);
                }

                /* One step of the bus; returns the status, or 0 if the bus does not move (stuck low). */
                static uint8_t rt_twi(uint8_t command) {
                    unsigned long n = 250000UL;
                    TWCR = _BV(TWINT) | _BV(TWEN) | command;
                    while (!(TWCR & _BV(TWINT))) {
                        if (--n == 0) return 0;
                    }
                    return TWSR & 0xF8;
                }

                static void rt_twi_stop(void) {
                    unsigned long n = 250000UL;
                    TWCR = _BV(TWINT) | _BV(TWEN) | _BV(TWSTO);
                    while ((TWCR & _BV(TWSTO)) && --n) {
                    }
                }

                /* START, the device's address (write: 0, read: 1) and the register; 0 if the device does not answer. */
                static uint8_t rt_twi_select(uint8_t addr, uint8_t reg) {
                    if (rt_twi(_BV(TWSTA)) != RT_TWI_START) return 0;
                    TWDR = (uint8_t) (addr << 1);
                    if (rt_twi(0) != RT_TWI_W_ACK) return 0;
                    TWDR = reg;
                    return rt_twi(0) == RT_TWI_DATA_ACK;
                }

                static void i2cWrite(uint8_t addr, uint8_t reg, uint8_t value) {
                    if (rt_twi_select(addr, reg)) {
                        TWDR = value;
                        rt_twi(0);
                    }
                    rt_twi_stop();
                }

                static int i2cRead(uint8_t addr, uint8_t reg) {
                    int value = -1;
                    if (rt_twi_select(addr, reg) && rt_twi(_BV(TWSTA)) == RT_TWI_RESTART) {
                        TWDR = (uint8_t) ((addr << 1) | 1);
                        if (rt_twi(0) == RT_TWI_R_ACK && rt_twi(0) == RT_TWI_RDATA_NACK) value = TWDR;
                    }
                    rt_twi_stop();
                    return value;
                }
                """);
        piece(AVR, "rt_pins rt_pin_count", "", """
                /* ---- pins, numbered as on the Arduino board: port (high 4 bits: 1 = A ... 12 = L) and bit,
                   and the timer output that can drive the pin with PWM (Arduino's TIMER0A = 1 ... TIMER5C = 18) */
                #if defined(__AVR_ATmega328P__)
                static const uint8_t rt_pins[] PROGMEM = {
                    0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
                    0x30, 0x31, 0x32, 0x33, 0x34, 0x35 };
                static const uint8_t rt_pin_timer[] PROGMEM = {
                    0, 0, 0, 8, 0, 2, 1, 0, 0, 3, 4, 7, 0, 0, 0, 0, 0, 0, 0, 0 };
                #elif defined(__AVR_ATmega2560__)
                static const uint8_t rt_pins[] PROGMEM = {
                    0x50, 0x51, 0x54, 0x55, 0x75, 0x53, 0x83, 0x84, 0x85, 0x86, 0x24, 0x25, 0x26, 0x27, 0xA1, 0xA0,
                    0x81, 0x80, 0x43, 0x42, 0x41, 0x40, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x37, 0x36,
                    0x35, 0x34, 0x33, 0x32, 0x31, 0x30, 0x47, 0x72, 0x71, 0x70, 0xC7, 0xC6, 0xC5, 0xC4, 0xC3, 0xC2,
                    0xC1, 0xC0, 0x23, 0x22, 0x21, 0x20, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0xB0, 0xB1,
                    0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7 };
                static const uint8_t rt_pin_timer[] PROGMEM = {
                    0, 0, 10, 11, 2, 9, 12, 13, 14, 8, 7, 3, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 17, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
                #elif defined(__AVR_ATmega32U4__)
                static const uint8_t rt_pins[] PROGMEM = {
                    0x42, 0x43, 0x41, 0x40, 0x44, 0x36, 0x47, 0x56, 0x24, 0x25, 0x26, 0x27, 0x46, 0x37, 0x23, 0x21,
                    0x22, 0x20, 0x67, 0x66, 0x65, 0x64, 0x61, 0x60, 0x44, 0x47, 0x24, 0x25, 0x26, 0x46, 0x45 };
                static const uint8_t rt_pin_timer[] PROGMEM = {
                    0, 0, 0, 2, 0, 9, 15, 0, 0, 3, 4, 1, 0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
                #endif
                #define rt_pin_count ((uint8_t) sizeof rt_pins)

                /* The DDR, PORT and PIN registers of a port; 0 if the chip has no such port. */
                static volatile uint8_t *rt_port_reg(uint8_t port, uint8_t which) {
                    switch (port) {
                #if defined(PORTA)
                    case 1: return which == 0 ? &DDRA : which == 1 ? &PORTA : &PINA;
                #endif
                    case 2: return which == 0 ? &DDRB : which == 1 ? &PORTB : &PINB;
                    case 3: return which == 0 ? &DDRC : which == 1 ? &PORTC : &PINC;
                    case 4: return which == 0 ? &DDRD : which == 1 ? &PORTD : &PIND;
                #if defined(PORTE)
                    case 5: return which == 0 ? &DDRE : which == 1 ? &PORTE : &PINE;
                #endif
                #if defined(PORTF)
                    case 6: return which == 0 ? &DDRF : which == 1 ? &PORTF : &PINF;
                #endif
                #if defined(PORTG)
                    case 7: return which == 0 ? &DDRG : which == 1 ? &PORTG : &PING;
                #endif
                #if defined(PORTH)
                    case 8: return which == 0 ? &DDRH : which == 1 ? &PORTH : &PINH;
                #endif
                #if defined(PORTJ)
                    case 10: return which == 0 ? &DDRJ : which == 1 ? &PORTJ : &PINJ;
                #endif
                #if defined(PORTK)
                    case 11: return which == 0 ? &DDRK : which == 1 ? &PORTK : &PINK;
                #endif
                #if defined(PORTL)
                    case 12: return which == 0 ? &DDRL : which == 1 ? &PORTL : &PINL;
                #endif
                    default: return 0;
                    }
                }

                /* Sets (value 1) or clears (0) a pin's bit in its DDR (which = 0) or PORT (1) register. */
                static void rt_pin_bit(uint8_t pin, uint8_t which, uint8_t value) {
                    uint8_t code, mask, sreg;
                    volatile uint8_t *reg;
                    if (pin >= rt_pin_count) return;
                    code = pgm_read_byte(rt_pins + pin);
                    mask = (uint8_t) (1 << (code & 0x0F));
                    reg = rt_port_reg((uint8_t) (code >> 4), which);
                    if (!reg) return;
                    sreg = SREG;
                    cli();
                    if (value) *reg |= mask;
                    else *reg &= (uint8_t) ~mask;
                    SREG = sreg;
                }
                """);
        piece(AVR, "rt_pwm", "rt_pins", """
                /* Connects (duty >= 0) or disconnects (-1) a timer output from its pin. */
                static void rt_pwm(uint8_t timer, int duty) {
                    uint8_t v = (uint8_t) (duty < 0 ? 0 : duty), on = duty >= 0;
                #define RT_COM(reg, bit) do { if (on) reg |= _BV(bit); else reg &= (uint8_t) ~_BV(bit); } while (0)
                    switch (timer) {
                    case 1: RT_COM(TCCR0A, COM0A1); OCR0A = v; break;
                    case 2: RT_COM(TCCR0A, COM0B1); OCR0B = v; break;
                #if defined(COM1A1)
                    case 3: RT_COM(TCCR1A, COM1A1); OCR1A = v; break;
                    case 4: RT_COM(TCCR1A, COM1B1); OCR1B = v; break;
                #endif
                #if defined(COM1C1)
                    case 5: RT_COM(TCCR1A, COM1C1); OCR1C = v; break;
                #endif
                #if defined(COM2A1)
                    case 7: RT_COM(TCCR2A, COM2A1); OCR2A = v; break;
                    case 8: RT_COM(TCCR2A, COM2B1); OCR2B = v; break;
                #endif
                #if defined(COM3A1)
                    case 9: RT_COM(TCCR3A, COM3A1); OCR3A = v; break;
                    case 10: RT_COM(TCCR3A, COM3B1); OCR3B = v; break;
                #endif
                #if defined(COM3C1)
                    case 11: RT_COM(TCCR3A, COM3C1); OCR3C = v; break;
                #endif
                #if defined(TCCR4E)
                    case 12: RT_COM(TCCR4A, COM4A1); TCCR4A &= (uint8_t) ~_BV(COM4A0); OCR4A = v; break;
                    case 15: RT_COM(TCCR4C, COM4D1); TCCR4C &= (uint8_t) ~_BV(COM4D0); OCR4D = v; break;
                #elif defined(COM4A1)
                    case 12: RT_COM(TCCR4A, COM4A1); OCR4A = v; break;
                    case 13: RT_COM(TCCR4A, COM4B1); OCR4B = v; break;
                    case 14: RT_COM(TCCR4A, COM4C1); OCR4C = v; break;
                #endif
                #if defined(COM5A1)
                    case 16: RT_COM(TCCR5A, COM5A1); OCR5A = v; break;
                    case 17: RT_COM(TCCR5A, COM5B1); OCR5B = v; break;
                    case 18: RT_COM(TCCR5A, COM5C1); OCR5C = v; break;
                #endif
                    default: break;
                    }
                #undef RT_COM
                }

                static uint8_t rt_timer_of(uint8_t pin) {
                    return pin < rt_pin_count ? pgm_read_byte(rt_pin_timer + pin) : 0;
                }
                """);
        piece(AVR, "pinMode", "rt_pins", """
                static void pinMode(uint8_t pin, uint8_t mode) {
                    rt_pin_bit(pin, 0, mode == OUTPUT);                  /* DDR: 1 = output */
                    if (mode != OUTPUT) rt_pin_bit(pin, 1, mode == INPUT_PULLUP);   /* PORT: pull-up */
                }
                """);
        piece(AVR, "digitalWrite", "rt_pins rt_pwm", """
                static void digitalWrite(uint8_t pin, uint8_t value) {
                    rt_pwm(rt_timer_of(pin), -1);
                    rt_pin_bit(pin, 1, value != LOW);
                }
                """);
        piece(AVR, "digitalRead", "rt_pins rt_pwm", """
                static int digitalRead(uint8_t pin) {
                    uint8_t code;
                    volatile uint8_t *in;
                    if (pin >= rt_pin_count) return LOW;
                    rt_pwm(rt_timer_of(pin), -1);
                    code = pgm_read_byte(rt_pins + pin);
                    in = rt_port_reg((uint8_t) (code >> 4), 2);
                    return in && (*in & (1 << (code & 0x0F))) ? HIGH : LOW;
                }
                """);
        piece(AVR, "analogWrite", "rt_pins rt_pwm pinMode digitalWrite", """
                /* PWM on pins with a timer output; other pins are only switched (HIGH from 128 on). */
                static void analogWrite(uint8_t pin, int value) {
                    uint8_t timer = rt_timer_of(pin);
                    pinMode(pin, OUTPUT);
                    if (value <= 0 || value >= 255 || timer == 0) {
                        digitalWrite(pin, value >= 128 ? HIGH : LOW);
                    } else {
                        rt_pwm(timer, value);
                    }
                }
                """);
        piece(AVR, "analogRead", "", """
                /* 0..1023 for 0..5 V (AVcc reference). Accepts A0 ... or the channel number. */
                static int analogRead(uint8_t pin) {
                    uint8_t ch;
                #if defined(__AVR_ATmega32U4__)
                    static const uint8_t channels[] PROGMEM = { 7, 6, 5, 4, 1, 0, 8, 10, 11, 12, 13, 9 };
                    if (pin >= A0) pin = (uint8_t) (pin - A0);
                    ch = pin < sizeof channels ? pgm_read_byte(channels + pin) : pin;
                #else
                    ch = pin >= A0 ? (uint8_t) (pin - A0) : pin;
                #endif
                #if defined(MUX5)
                    ADCSRB = (uint8_t) ((ADCSRB & ~_BV(MUX5)) | (((ch >> 3) & 1) << MUX5));
                #endif
                    ADMUX = (uint8_t) (_BV(REFS0) | (ch & 7));
                    ADCSRA |= _BV(ADSC);
                    while (ADCSRA & _BV(ADSC)) {
                    }
                    return ADC;
                }
                """);
        piece(AVR, "millis", "rt_clock", """
                static unsigned long millis(void) {
                    unsigned long m;
                    uint8_t sreg = SREG;
                    cli();
                    m = rt_clock_ms;
                    SREG = sreg;
                    return m;
                }
                """);
        piece(AVR, "delay", "millis", """
                static void delay(unsigned long ms) {
                    unsigned long start = millis();
                    while (millis() - start < ms) {
                    }
                }
                """);
    }

    private static final String ARDUINO_HEAD = """
            #include <Arduino.h>
            #include <math.h>
            #include <stdlib.h>
            #include <string.h>

            #ifndef LED_BUILTIN
            #define LED_BUILTIN 2   /* classic ESP32 boards don't name their LED; most dev kits have it on GPIO 2 */
            #endif
            #define RT_TEXT(s) F(s)   /* text stays in flash on AVR boards */
            typedef const __FlashStringHelper *rt_text;
            """;

    private static final String AVR_HEAD = """
            #ifndef F_CPU
            #define F_CPU 16000000UL
            #endif
            #include <avr/interrupt.h>
            #include <avr/io.h>
            #include <avr/pgmspace.h>
            #include <avr/sleep.h>
            #include <math.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>

            #if !defined(__AVR_ATmega328P__) && !defined(__AVR_ATmega2560__) && !defined(__AVR_ATmega32U4__)
            #error "this runtime knows the ATmega328P (Uno, Nano, Pro Mini), ATmega2560 (Mega) and ATmega32U4 (Leonardo, Micro)"
            #endif

            #define RT_TEXT(s) PSTR(s)   /* text stays in flash */
            typedef const char *rt_text;

            /* Arduino's names, so the program reads the same as the sketch */
            #define HIGH 1
            #define LOW 0
            #define INPUT 0
            #define OUTPUT 1
            #define INPUT_PULLUP 2
            #define LED_BUILTIN 13
            #if defined(__AVR_ATmega328P__)
            #define A0 14
            #elif defined(__AVR_ATmega2560__)
            #define A0 54
            #else
            #define A0 18
            #endif
            #define A1 (A0 + 1)
            #define A2 (A0 + 2)
            #define A3 (A0 + 3)
            #define A4 (A0 + 4)
            #define A5 (A0 + 5)
            #define A6 (A0 + 6)
            #define A7 (A0 + 7)
            #if !defined(__AVR_ATmega328P__)
            #define A8 (A0 + 8)
            #define A9 (A0 + 9)
            #define A10 (A0 + 10)
            #define A11 (A0 + 11)
            #endif
            #if defined(__AVR_ATmega2560__)
            #define A12 (A0 + 12)
            #define A13 (A0 + 13)
            #define A14 (A0 + 14)
            #define A15 (A0 + 15)
            #endif
            """;

    private static final Pattern NAME = Pattern.compile("\\b[A-Za-z_]\\w*\\b");

    /** The runtime for a microcontroller program: only the pieces {@code program} uses. */
    static String source(Target t, String program) {
        if (t.lang() == Target.Lang.PIC32) return Pic32Runtime.source(Settings.board(Target.Lang.PIC32), program);
        boolean arduino = t.lang() == Target.Lang.ARDUINO;
        return assemble(arduino ? "Arduino sketch" : "bare-metal AVR", arduino ? ARDUINO_HEAD : AVR_HEAD,
                arduino ? ARDUINO : AVR, program);
    }

    /** The head, then the pieces of {@code pieces} that {@code program} uses (and what those need), in definition order. */
    static String assemble(String title, String head, Map<String, Piece> pieces, String program) {
        String code = program.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        Set<Piece> needed = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>();
        Matcher m = NAME.matcher(code);
        while (m.find()) todo.add(m.group());
        while (!todo.isEmpty()) {
            Piece p = pieces.get(todo.remove(todo.size() - 1));
            if (p != null && needed.add(p)) todo.addAll(p.needs());
        }
        StringBuilder s = new StringBuilder();
        s.append("/* ===================== EVIA Paradigma runtime (").append(title).append(") ===================== */\n");
        s.append(head);
        for (Piece p : new LinkedHashSet<>(pieces.values())) {
            if (needed.contains(p)) s.append('\n').append(p.code());
        }
        s.append("/* =================================================================== */\n");
        return s.toString();
    }
}
