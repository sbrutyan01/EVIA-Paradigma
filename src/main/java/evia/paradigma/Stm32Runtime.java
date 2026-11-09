package evia.paradigma;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The runtime of the STM32 target: bare-metal C for arm-none-eabi-gcc, written against the registers of
 * the STM32F1 and STM32F4 (no HAL, no CMSIS, no vendor headers: the generated file carries its own
 * register addresses, vector table and start-up code, and needs only the compiler and newlib). The same
 * function names as the Arduino, AVR and PIC32 targets - pinMode, digitalWrite, analogRead, millis,
 * uartOpen, spiTransfer, i2cRead ... - so the generated program reads the same.
 *
 * <ul>
 *   <li><b>Clock</b>: the internal oscillator and the PLL, so no crystal is needed: 64 MHz on the F1, 84 MHz on
 *       the F401/F411, 168 MHz on the F407. If the PLL does not start, the chip stays on the internal oscillator
 *       and every divider below follows.</li>
 *   <li><b>Pins</b>: PA0 ... PI15, numbered 16 * port + bit. A0 ... A15 are the ADC channels 0 ... 15
 *       (PA0 ... PA7, PB0, PB1, PC0 ... PC5).</li>
 *   <li><b>Print and Input</b>: USART1 (PA9 TX, PA10 RX) or USART2 (PA2 TX, PA3 RX, the ST-Link's virtual serial port
 *       on the Nucleo boards), 115200 baud; the board picks.</li>
 *   <li><b>PWM</b>: TIM2 on PA0 ... PA3, TIM3 on PA6, PA7, PB0, PB1, TIM4 on PB6 ... PB9 (and on the F4 also PA5,
 *       TIM2, and PD12 ... PD15, TIM4: the LEDs of the Nucleo and of the Discovery), about 1 kHz, 8 bits.</li>
 *   <li><b>SPI1</b>: PA5 SCK, PA6 MISO, PA7 MOSI. <b>I2C1</b>: PB6 SCL, PB7 SDA.</li>
 * </ul>
 */
final class Stm32Runtime {
    private Stm32Runtime() {}

    private static final Map<String, EmbeddedRuntime.Piece> PIECES = new LinkedHashMap<>();

    private static void piece(String defines, String needs, String code) {
        EmbeddedRuntime.piece(PIECES, defines, needs, code);
    }

    private static final java.util.regex.Pattern PIN_NAME = java.util.regex.Pattern.compile("\\b(P[A-I]\\d{1,2}|A(?:\\d|1[0-5]))\\b");

    /** The top of the generated file: the chip, its registers, the start-up code and the clock. */
    static String head(Board b, String used) {
        Board.Stm32 c = b.stm32;
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.regex.Matcher m = PIN_NAME.matcher(used + " " + c.led());
        while (m.find()) names.add(m.group(1));
        StringBuilder s = new StringBuilder();
        s.append("""
                #include <math.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                """);
        s.append("#define RT_").append(c.f1() ? "F1" : "F4").append(" 1          /* ").append(c.chip()).append(" */\n");
        if (c.family() == Board.Stm32.Family.F407) s.append("#define RT_F407 1\n");
        s.append("#define RT_USART ").append(c.usart()).append("    /* Print and Input: USART").append(c.usart())
                .append(c.usart() == 1 ? ", PA9 TX, PA10 RX" : ", PA2 TX, PA3 RX").append(" */\n");
        s.append("""

                #define RT_TEXT(s) s
                typedef const char *rt_text;

                /* Arduino's names, so the program reads the same as the sketch */
                #define HIGH 1
                #define LOW 0
                #define INPUT 0
                #define OUTPUT 1
                #define INPUT_PULLUP 2
                """);
        s.append("#define LED_BUILTIN ").append(c.led()).append("   /* the LED of this board")
                .append(c.ledLow() ? ", lit at LOW" : "").append(": change it to the pin of yours */\n");
        s.append("\n/* Pins are numbered 16 * port + bit: PA0 = 0 ... PA15 = 15, PB0 = 16 ... PI15 = 143. A0 ... A15 are the ADC channels. */\n");
        for (char port = 'A'; port <= 'I'; port++) {
            for (int bit = 0; bit < 16; bit++) {
                String n = "P" + port + bit;
                if (names.contains(n)) s.append("#define ").append(n).append(' ').append((port - 'A') * 16 + bit).append('\n');
            }
        }
        s.append("#define RT_AN0 256\n");
        for (int i = 0; i < 16; i++) {
            if (names.contains("A" + i)) s.append("#define A").append(i).append(" (RT_AN0 + ").append(i).append(")\n");
        }
        s.append(REGISTERS).append(STARTUP);
        return s.toString();
    }

    /** The registers the runtime uses, by address: nothing here needs a vendor header. */
    private static final String REGISTERS = """

            /* ---- registers */
            #define RT_REG(a) (*(volatile uint32_t *) (a))
            #if defined(RT_F1)
            #define RT_RCC_CR RT_REG(0x40021000UL)
            #define RT_RCC_CFGR RT_REG(0x40021004UL)
            #define RT_RCC_APB2ENR RT_REG(0x40021018UL)
            #define RT_RCC_APB1ENR RT_REG(0x4002101CUL)
            #define RT_FLASH_ACR RT_REG(0x40022000UL)
            #define RT_AFIO_MAPR RT_REG(0x40010004UL)
            #define RT_GPIO(port) (0x40010800UL + 0x400UL * (uint32_t) (port))
            #define RT_PORTS 5                    /* A ... E */
            #define RT_USART1 0x40013800UL
            #define RT_ADC1 0x40012400UL
            #else
            #define RT_RCC_CR RT_REG(0x40023800UL)
            #define RT_RCC_PLLCFGR RT_REG(0x40023804UL)
            #define RT_RCC_CFGR RT_REG(0x40023808UL)
            #define RT_RCC_AHB1ENR RT_REG(0x40023830UL)
            #define RT_RCC_APB1ENR RT_REG(0x40023840UL)
            #define RT_RCC_APB2ENR RT_REG(0x40023844UL)
            #define RT_FLASH_ACR RT_REG(0x40023C00UL)
            #define RT_ADC_CCR RT_REG(0x40012304UL)
            #define RT_GPIO(port) (0x40020000UL + 0x400UL * (uint32_t) (port))
            #define RT_PORTS 9                    /* A ... I */
            #define RT_USART1 0x40011000UL
            #define RT_ADC1 0x40012000UL
            #endif
            #define RT_USART2 0x40004400UL
            #define RT_SPI1 0x40013000UL
            #define RT_I2C1 0x40005400UL
            #define RT_TIM(n) (0x40000000UL + 0x400UL * (uint32_t) ((n) - 2))   /* TIM2, TIM3, TIM4 */
            #define RT_SYST_CSR RT_REG(0xE000E010UL)
            #define RT_SYST_RVR RT_REG(0xE000E014UL)
            #define RT_SYST_CVR RT_REG(0xE000E018UL)
            #define RT_WAIT 200000UL                /* how long to wait for the hardware before giving up */

            /* the clocks, set by rt_clock_init: the core (HCLK), the two peripheral buses and the timers on APB1 */
            static uint32_t rt_hclk, rt_pclk1, rt_pclk2, rt_tclk1;
            """;

    /** The vector table, the reset handler (copies .data, clears .bss, starts the FPU and the clock) and the clock. */
    private static final String STARTUP = """

            /* ---- start-up: the linker script (stm32.ld, see the top of this file) places the vector table first in flash */
            extern uint32_t _sidata, _sdata, _edata, _sbss, _ebss, _estack;
            int main(void);
            void Reset_Handler(void);
            void rt_default_handler(void);
            void SysTick_Handler(void);              /* the millisecond clock below, or an empty one */

            void rt_default_handler(void) {
                for (;;) {
                }
            }

            __attribute__((section(".isr_vector"), used))
            static void (*const rt_vectors[16])(void) = {
                (void (*)(void)) (uintptr_t) &_estack, Reset_Handler,
                rt_default_handler, rt_default_handler, rt_default_handler, rt_default_handler, rt_default_handler,
                0, 0, 0, 0, rt_default_handler, rt_default_handler, 0, rt_default_handler, SysTick_Handler
            };

            static void rt_clock_init(void) {
                uint32_t n;
                RT_RCC_CR |= 1u;                                  /* HSI on (it is after reset) */
            #if defined(RT_F1)
                RT_FLASH_ACR = 0x12u;                             /* prefetch, 2 wait states (48 ... 72 MHz) */
                /* PLL = HSI / 2 * 16 = 64 MHz; AHB / 1, APB1 / 2 (32 MHz), APB2 / 1, ADC / 6 (10.7 MHz) */
                RT_RCC_CFGR = (0xEu << 18) | (4u << 8) | (2u << 14);
            #elif defined(RT_F407)
                RT_RCC_APB1ENR |= 1u << 28;                       /* PWR */
                RT_FLASH_ACR = (7u << 8) | 5u;                    /* caches and prefetch, 5 wait states */
                /* PLL = HSI / 16 * 336 / 2 = 168 MHz (/ 7 = 48 MHz); APB1 / 4 (42 MHz), APB2 / 2 (84 MHz) */
                RT_RCC_PLLCFGR = 16u | (336u << 6) | (0u << 16) | (7u << 24);
                RT_RCC_CFGR = (5u << 10) | (4u << 13);
            #else
                RT_RCC_APB1ENR |= 1u << 28;                       /* PWR */
                RT_FLASH_ACR = (7u << 8) | 2u;                    /* caches and prefetch, 2 wait states */
                /* PLL = HSI / 16 * 336 / 4 = 84 MHz (/ 7 = 48 MHz); APB1 / 2 (42 MHz), APB2 / 1 (84 MHz) */
                RT_RCC_PLLCFGR = 16u | (336u << 6) | (1u << 16) | (7u << 24);
                RT_RCC_CFGR = 4u << 10;
            #endif
                RT_RCC_CR |= 1u << 24;                            /* PLL on */
                for (n = 0; n < RT_WAIT && !(RT_RCC_CR & (1u << 25)); n++) {
                }
                if (RT_RCC_CR & (1u << 25)) {
                    RT_RCC_CFGR |= 2u;                            /* run from the PLL */
                    for (n = 0; n < RT_WAIT && ((RT_RCC_CFGR >> 2) & 3u) != 2u; n++) {
                    }
                }
                if (((RT_RCC_CFGR >> 2) & 3u) == 2u) {
            #if defined(RT_F1)
                    rt_hclk = 64000000UL; rt_pclk1 = 32000000UL; rt_pclk2 = 64000000UL;
            #elif defined(RT_F407)
                    rt_hclk = 168000000UL; rt_pclk1 = 42000000UL; rt_pclk2 = 84000000UL;
            #else
                    rt_hclk = 84000000UL; rt_pclk1 = 42000000UL; rt_pclk2 = 84000000UL;
            #endif
                } else {                                          /* no PLL: the internal oscillator, same dividers */
            #if defined(RT_F1)
                    rt_hclk = 8000000UL; rt_pclk1 = 4000000UL; rt_pclk2 = 8000000UL;
            #elif defined(RT_F407)
                    rt_hclk = 16000000UL; rt_pclk1 = 4000000UL; rt_pclk2 = 8000000UL;
            #else
                    rt_hclk = 16000000UL; rt_pclk1 = 8000000UL; rt_pclk2 = 16000000UL;
            #endif
                }
                rt_tclk1 = rt_pclk1 == rt_hclk ? rt_pclk1 : 2 * rt_pclk1;   /* APB1 divided: its timers run at twice */
            }

            void Reset_Handler(void) {
                uint32_t *src = &_sidata, *dst = &_sdata;
                while (dst < &_edata) *dst++ = *src++;
                for (dst = &_sbss; dst < &_ebss; ) *dst++ = 0;
            #if !defined(RT_F1)
                RT_REG(0xE000ED88UL) |= 0xFu << 20;               /* the FPU: full access */
                __asm__ volatile ("dsb\\n\\tisb");
            #endif
                rt_clock_init();
                main();
                for (;;) {
                }
            }
            """;

    /** The linker script for a chip: flash from 0x08000000, RAM from 0x20000000, the heap after .bss. */
    static String linkerScript(Board.Stm32 c) {
        return """
                /* stm32.ld: EVIA Paradigma's linker script for the %s */
                MEMORY
                {
                  FLASH (rx) : ORIGIN = 0x08000000, LENGTH = %dK
                  RAM (rwx)  : ORIGIN = 0x20000000, LENGTH = %dK
                }
                _estack = ORIGIN(RAM) + LENGTH(RAM);
                ENTRY(Reset_Handler)
                SECTIONS
                {
                  .isr_vector : { KEEP(*(.isr_vector)) } > FLASH
                  .text : { *(.text*) *(.rodata*) KEEP(*(.init)) KEEP(*(.fini)) . = ALIGN(4); } > FLASH
                  .ARM.extab : { *(.ARM.extab*) } > FLASH
                  .ARM.exidx : { *(.ARM.exidx*) } > FLASH
                  .init_array : { KEEP(*(.preinit_array*)) KEEP(*(.init_array*)) } > FLASH
                  .fini_array : { KEEP(*(.fini_array*)) } > FLASH
                  _sidata = LOADADDR(.data);
                  .data : { . = ALIGN(4); _sdata = .; *(.data*) . = ALIGN(4); _edata = .; } > RAM AT > FLASH
                  .bss (NOLOAD) : { . = ALIGN(4); _sbss = .; *(.bss*) *(COMMON) . = ALIGN(4); _ebss = .; } > RAM
                  end = _ebss;
                  _end = end;
                }
                """.formatted(c.chip(), c.flashK(), c.ramK());
    }

    /** arm-none-eabi-gcc's command line for {@code src}, as the header and Build write it. */
    static java.util.List<String> gcc(Board.Stm32 c, String gcc, String src, String elf, String ld) {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(gcc));
        cmd.addAll(c.cpuFlags());
        cmd.addAll(java.util.List.of("-std=gnu11", "-Os", "-Wall", "-Wextra", "-ffunction-sections", "-fdata-sections",
                "-nostartfiles", "--specs=nano.specs", "--specs=nosys.specs", "-Wl,--gc-sections", "-T", ld, "-o", elf, src, "-lm"));
        return cmd;
    }

    // ---------------------------------------------------------------- shared C code

    static {
        // ============================================================ pins
        piece("rt_port", "", """
                /* ---- pins: the GPIO port of a pin (its clock switched on), or 0 if the chip has no such port */
                static uint32_t rt_port(int pin) {
                    uint32_t port;
                    if (pin < 0 || pin >= 16 * RT_PORTS) return 0;
                    port = (uint32_t) pin >> 4;
                #if defined(RT_F1)
                    RT_RCC_APB2ENR |= (1u << (2 + port)) | 1u;      /* IOPx and AFIO */
                #else
                    RT_RCC_AHB1ENR |= 1u << port;
                #endif
                    return RT_GPIO(port);
                }

                #if defined(RT_F1)
                /* The 4-bit configuration of a pin (CRL for 0 ... 7, CRH for 8 ... 15): 0x2 output, 0x4 input,
                   0x8 input with pull, 0x0 analog, 0xB alternate function push-pull, 0xF alternate function open drain. */
                static void rt_cfg(int pin, uint32_t cfg) {
                    uint32_t gpio = rt_port(pin), sh = ((uint32_t) pin & 7u) * 4u;
                    volatile uint32_t *cr;
                    if (!gpio) return;
                    cr = &RT_REG(gpio + ((pin & 8) ? 4u : 0u));
                    *cr = (*cr & ~(0xFu << sh)) | (cfg << sh);
                }
                #else
                /* The mode (0 input, 1 output, 2 alternate function, 3 analog), pull (0 none, 1 up), alternate function
                   and output type (1 open drain) of a pin. */
                static void rt_cfg(int pin, uint32_t mode, uint32_t pull, uint32_t af, int od) {
                    uint32_t gpio = rt_port(pin), bit = (uint32_t) pin & 15u;
                    volatile uint32_t *afr;
                    if (!gpio) return;
                    afr = &RT_REG(gpio + 0x20u + (bit >= 8 ? 4u : 0u));
                    *afr = (*afr & ~(0xFu << ((bit & 7u) * 4u))) | (af << ((bit & 7u) * 4u));
                    RT_REG(gpio + 0x04u) = (RT_REG(gpio + 0x04u) & ~(1u << bit)) | ((od ? 1u : 0u) << bit);
                    RT_REG(gpio + 0x08u) |= 2u << (2 * bit);                 /* fast */
                    RT_REG(gpio + 0x0Cu) = (RT_REG(gpio + 0x0Cu) & ~(3u << (2 * bit))) | (pull << (2 * bit));
                    RT_REG(gpio + 0x00u) = (RT_REG(gpio + 0x00u) & ~(3u << (2 * bit))) | (mode << (2 * bit));
                }
                #endif
                """);
        piece("rt_is_af", "rt_port", """
                /* True while a pin is given to a peripheral (PWM, SPI ...) instead of being a plain pin. */
                static int rt_is_af(int pin) {
                    uint32_t gpio = rt_port(pin), bit = (uint32_t) pin & 15u;
                    if (!gpio) return 0;
                #if defined(RT_F1)
                    {
                        uint32_t cfg = (RT_REG(gpio + ((pin & 8) ? 4u : 0u)) >> ((bit & 7u) * 4u)) & 0xFu;
                        return (cfg & 3u) != 0 && (cfg & 8u) != 0;
                    }
                #else
                    return ((RT_REG(gpio) >> (2 * bit)) & 3u) == 2u;
                #endif
                }
                """);
        piece("rt_an_pin", "", """
                /* The pin of ADC channel 0 ... 15, or -1. */
                static int rt_an_pin(int ch) {
                    if (ch >= 0 && ch <= 7) return ch;                 /* PA0 ... PA7 */
                    if (ch == 8 || ch == 9) return 16 + ch - 8;        /* PB0, PB1 */
                    if (ch >= 10 && ch <= 15) return 32 + ch - 10;     /* PC0 ... PC5 */
                    return -1;
                }
                """);
        piece("rt_an_ch", "", """
                /* The ADC channel of A0 ... A15 or of a pin, or -1 if the pin has none. */
                static int rt_an_ch(int pin) {
                    if (pin >= RT_AN0) return pin - RT_AN0 <= 15 ? pin - RT_AN0 : -1;
                    if (pin >= 0 && pin <= 7) return pin;
                    if (pin == 16 || pin == 17) return 8 + pin - 16;
                    if (pin >= 32 && pin <= 37) return 10 + pin - 32;
                    return -1;
                }
                """);
        piece("pinMode", "rt_port rt_an_pin", """
                static void pinMode(int pin, uint8_t mode) {
                    if (pin >= RT_AN0) pin = rt_an_pin(pin - RT_AN0);
                    if (!rt_port(pin)) return;
                #if defined(RT_F1)
                    if (mode == OUTPUT) {
                        rt_cfg(pin, 0x2u);
                    } else if (mode == INPUT_PULLUP) {
                        RT_REG(rt_port(pin) + 0x10u) = 1u << (pin & 15);   /* ODR 1 = pull up */
                        rt_cfg(pin, 0x8u);
                    } else {
                        rt_cfg(pin, 0x4u);
                    }
                #else
                    rt_cfg(pin, mode == OUTPUT ? 1u : 0u, mode == INPUT_PULLUP ? 1u : 0u, 0u, 0);
                #endif
                }
                """);
        piece("digitalWrite", "rt_port rt_is_af pinMode", """
                static void digitalWrite(int pin, uint8_t value) {
                    uint32_t gpio;
                    if (pin >= RT_AN0) return;
                    gpio = rt_port(pin);
                    if (!gpio) return;
                    if (rt_is_af(pin)) pinMode(pin, OUTPUT);           /* PWM stops, as on an Arduino */
                #if defined(RT_F1)
                    RT_REG(gpio + 0x10u) = value != LOW ? 1u << (pin & 15) : 1u << ((pin & 15) + 16);
                #else
                    RT_REG(gpio + 0x18u) = value != LOW ? 1u << (pin & 15) : 1u << ((pin & 15) + 16);
                #endif
                }
                """);
        piece("digitalRead", "rt_port rt_an_pin", """
                static int digitalRead(int pin) {
                    uint32_t gpio;
                    if (pin >= RT_AN0) pin = rt_an_pin(pin - RT_AN0);
                    gpio = rt_port(pin);
                    if (!gpio) return LOW;
                #if defined(RT_F1)
                    return (RT_REG(gpio + 0x08u) & (1u << (pin & 15))) ? HIGH : LOW;
                #else
                    return (RT_REG(gpio + 0x10u) & (1u << (pin & 15))) ? HIGH : LOW;
                #endif
                }
                """);
        piece("analogWrite", "pinMode digitalWrite rt_port", """
                /* ---- PWM: the timer (2, 3, 4) and channel (1 ... 4) of a pin, as 16 * timer + channel; 0 if it has none */
                static int rt_pwm_of(int pin) {
                    switch (pin) {
                    case 0: case 1: case 2: case 3: return 32 + pin + 1;          /* PA0 ... PA3: TIM2 CH1 ... CH4 */
                    case 6: case 7: return 48 + pin - 5;                          /* PA6, PA7: TIM3 CH1, CH2 */
                    case 16: case 17: return 48 + pin - 13;                       /* PB0, PB1: TIM3 CH3, CH4 */
                    case 22: case 23: case 24: case 25: return 64 + pin - 21;     /* PB6 ... PB9: TIM4 CH1 ... CH4 */
                #if !defined(RT_F1)
                    case 5: return 32 + 1;                                        /* PA5: TIM2 CH1 (the Nucleo's LED) */
                    case 60: case 61: case 62: case 63: return 64 + pin - 59;     /* PD12 ... PD15: TIM4 (the Discovery's LEDs) */
                #endif
                    default: return 0;
                    }
                }

                /* PWM on the pins of TIM2 ... TIM4 at about 1 kHz; other pins are only switched (HIGH from 128 on). */
                static void analogWrite(int pin, int value) {
                    int tc = rt_pwm_of(pin), tim, ch;
                    uint32_t t;
                    volatile uint32_t *ccmr;
                    if (value <= 0 || value >= 255 || tc == 0) {
                        pinMode(pin, OUTPUT);
                        digitalWrite(pin, value >= 128 ? HIGH : LOW);
                        return;
                    }
                    tim = tc >> 4;
                    ch = tc & 15;
                    t = RT_TIM(tim);
                    RT_RCC_APB1ENR |= 1u << (tim - 2);              /* TIM2 ... TIM4: bits 0 ... 2 on both families */
                    if (!(RT_REG(t) & 1u)) {                         /* not running yet */
                        RT_REG(t + 0x28u) = rt_tclk1 / (255u * 1000u) - 1u;   /* PSC */
                        RT_REG(t + 0x2Cu) = 254u;                    /* ARR: 255 steps */
                        RT_REG(t + 0x14u) = 1u;                      /* EGR: load them */
                        RT_REG(t) = (1u << 7) | 1u;                  /* ARPE, CEN */
                    }
                    ccmr = &RT_REG(t + (ch <= 2 ? 0x18u : 0x1Cu));
                    *ccmr = (*ccmr & ~(0xFFu << (ch % 2 ? 0 : 8))) | (0x68u << (ch % 2 ? 0 : 8));   /* PWM mode 1, preload */
                    RT_REG(t + 0x34u + 4u * (uint32_t) (ch - 1)) = (uint32_t) value;                 /* CCRx */
                    RT_REG(t + 0x20u) |= 1u << (4 * (ch - 1));                                       /* CCxE */
                #if defined(RT_F1)
                    rt_cfg(pin, 0xBu);
                #else
                    rt_cfg(pin, 2u, 0u, tim == 2 ? 1u : 2u, 0);
                #endif
                }
                """);
        piece("analogRead", "rt_port rt_an_ch rt_an_pin", """
                /* 0..1023 for 0 V to the supply (the 12-bit result / 4, as on an Arduino). Accepts A0 ... A15 or a pin. */
                static int analogRead(int pin) {
                    static uint8_t ready;
                    int ch = rt_an_ch(pin), p;
                    unsigned long n;
                    if (ch < 0) return 0;
                    p = rt_an_pin(ch);
                    if (!ready) {
                #if defined(RT_F1)
                        RT_RCC_APB2ENR |= 1u << 9;
                        RT_REG(RT_ADC1 + 0x0Cu) = 0x00FFFFFFu;        /* SMPR1: the longest sampling time */
                        RT_REG(RT_ADC1 + 0x10u) = 0x3FFFFFFFu;        /* SMPR2 */
                        RT_REG(RT_ADC1 + 0x08u) = 1u | (7u << 17) | (1u << 20);   /* ADON, started by SWSTART */
                        for (n = 0; n < 1000; n++) {
                        }
                        RT_REG(RT_ADC1 + 0x08u) |= 1u << 2;           /* calibrate */
                        for (n = 0; n < RT_WAIT && (RT_REG(RT_ADC1 + 0x08u) & (1u << 2)); n++) {
                        }
                #else
                        RT_RCC_APB2ENR |= 1u << 8;
                        RT_ADC_CCR = 1u << 16;                        /* ADC clock = APB2 / 4 */
                        RT_REG(RT_ADC1 + 0x0Cu) = 0x07FFFFFFu;        /* SMPR1: the longest sampling time */
                        RT_REG(RT_ADC1 + 0x10u) = 0x3FFFFFFFu;        /* SMPR2 */
                        RT_REG(RT_ADC1 + 0x04u) = 0;                  /* 12 bits */
                        RT_REG(RT_ADC1 + 0x08u) = 1u;                 /* ADON */
                #endif
                        ready = 1;
                    }
                #if defined(RT_F1)
                    rt_cfg(p, 0x0u);
                #else
                    rt_cfg(p, 3u, 0u, 0u, 0);
                #endif
                    RT_REG(RT_ADC1 + 0x2Cu) = 0;                      /* SQR1: one conversion */
                    RT_REG(RT_ADC1 + 0x34u) = (uint32_t) ch;          /* SQR3: of this channel */
                    RT_REG(RT_ADC1 + 0x00u) = 0;
                #if defined(RT_F1)
                    RT_REG(RT_ADC1 + 0x08u) |= 1u << 22;              /* SWSTART */
                #else
                    RT_REG(RT_ADC1 + 0x08u) |= 1u << 30;
                #endif
                    for (n = 0; n < RT_WAIT && !(RT_REG(RT_ADC1) & 2u); n++) {
                    }
                    return (int) ((RT_REG(RT_ADC1 + 0x4Cu) & 0xFFFu) >> 2);
                }
                """);

        // ============================================================ time
        piece("millis RT_HAS_CLOCK SysTick_Handler", "", """
                /* ---- time: SysTick interrupts every millisecond */
                #define RT_HAS_CLOCK 1
                static volatile unsigned long rt_clock_ms;

                void SysTick_Handler(void) {
                    rt_clock_ms++;
                }

                static unsigned long millis(void) {
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

        // ============================================================ the USART (Print, Input and the UART blocks)
        piece("rt_uart rt_putc rt_uart_start RT_HAS_UART", "rt_port", """
                /* ---- the USART of Print and Input, 8N1 */
                #define RT_HAS_UART 1
                #if RT_USART == 1
                #define RT_UART RT_USART1
                #else
                #define RT_UART RT_USART2
                #endif

                static void rt_uart_start(unsigned long baud) {
                    uint32_t clk;
                #if RT_USART == 1
                    #if defined(RT_F1)
                    RT_RCC_APB2ENR |= 1u << 14;
                    rt_cfg(9, 0xBu);                                 /* PA9 TX */
                    rt_cfg(10, 0x4u);                                /* PA10 RX */
                    #else
                    RT_RCC_APB2ENR |= 1u << 4;
                    rt_cfg(9, 2u, 0u, 7u, 0);
                    rt_cfg(10, 2u, 1u, 7u, 0);
                    #endif
                    clk = rt_pclk2;
                #else
                    RT_RCC_APB1ENR |= 1u << 17;
                    #if defined(RT_F1)
                    rt_cfg(2, 0xBu);                                 /* PA2 TX */
                    rt_cfg(3, 0x4u);                                 /* PA3 RX */
                    #else
                    rt_cfg(2, 2u, 0u, 7u, 0);
                    rt_cfg(3, 2u, 1u, 7u, 0);
                    #endif
                    clk = rt_pclk1;
                #endif
                    RT_REG(RT_UART + 0x0Cu) = 0;                     /* CR1: off while it is set up */
                    RT_REG(RT_UART + 0x08u) = (clk + baud / 2) / baud;   /* BRR */
                    RT_REG(RT_UART + 0x0Cu) = (1u << 13) | (1u << 3) | (1u << 2);   /* UE, TE, RE */
                }

                static inline void rt_putc(char c) {
                    while (!(RT_REG(RT_UART) & (1u << 7))) {         /* TXE */
                    }
                    RT_REG(RT_UART + 0x04u) = (uint8_t) c;
                }

                /* Waits until the last byte has left (TC), but never for ever. */
                static void rt_uart_flush(void) {
                    unsigned long n;
                    for (n = 0; n < RT_WAIT && !(RT_REG(RT_UART) & (1u << 6)); n++) {
                    }
                }
                """);
        piece("rt_begin", "", """
                #if !defined(RT_HAS_CLOCK)
                void SysTick_Handler(void) {             /* this program never starts SysTick */
                }
                #endif

                /* Starts the millisecond clock and the USART; frees the JTAG pins (PA15, PB3, PB4) on the F1, keeping SWD. */
                static void rt_begin(void) {
                #if defined(RT_F1)
                    RT_RCC_APB2ENR |= 1u;
                    RT_AFIO_MAPR = (RT_AFIO_MAPR & ~(7u << 24)) | (2u << 24);
                #endif
                #if defined(RT_HAS_CLOCK)
                    RT_SYST_RVR = rt_hclk / 1000u - 1u;
                    RT_SYST_CVR = 0;
                    RT_SYST_CSR = 7u;                                /* the core clock, interrupt, on */
                #endif
                #if defined(RT_HAS_UART)
                    rt_uart_start(115200UL);
                #endif
                }
                """);
        piece("rt_end", "", """
                /* The program is over: let the USART finish, then stop here (pins and PWM keep their state). */
                __attribute__((noreturn)) static void rt_end(void) {
                #if defined(RT_HAS_UART)
                    rt_uart_flush();
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
                /* An error stops the program: the message goes to the USART, then the chip stops. */
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
        piece("rt_getc", "rt_uart", """
                /* The next byte from the USART, or -1 if none has arrived (an overrun is cleared on the way). */
                static int rt_getc(void) {
                    uint32_t sr = RT_REG(RT_UART);
                    if (sr & (1u << 3)) (void) RT_REG(RT_UART + 0x04u);   /* ORE: reading SR then DR clears it */
                    if (!(RT_REG(RT_UART) & (1u << 5))) return -1;         /* RXNE */
                    return (int) (RT_REG(RT_UART + 0x04u) & 0xFFu);
                }
                """);
        piece("rt_read_line", "rt_getc", """
                /* One line from the USART (Enter); empty lines are skipped. */
                static const char *rt_read_line(void) {
                    static char line[48];
                    unsigned n = 0;
                    for (;;) {
                        int c;
                        while ((c = rt_getc()) < 0) {
                        }
                        if (c == '\\n' || c == '\\r') {
                            if (n > 0) break;
                            continue;
                        }
                        if (n + 1 < sizeof line) line[n++] = (char) c;
                    }
                    line[n] = '\\0';
                    return line;
                }
                """);
        piece("rt_input_num", "rt_read_line rt_parse_num rt_put_text rt_newline rt_end", """
                /* Input block: asks on the USART; the answer must be a number. */
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
        piece("uartOpen uartWrite uartRead", "rt_uart rt_getc", """
                /* The serial port is the one Print uses; UART Open changes its speed. */
                static void uartOpen(unsigned long baud) {
                    rt_uart_flush();
                    rt_uart_start(baud);
                }
                static void uartWrite(uint8_t b) { rt_putc((char) b); }
                static int uartRead(void) { return rt_getc(); }   /* -1 if nothing has arrived */
                """);
        piece("spiOpen spiTransfer", "rt_port", """
                /* ---- SPI1 master, mode 0, most significant bit first: PA5 SCK, PA6 MISO, PA7 MOSI */
                static void spiOpen(unsigned long hz) {
                    uint32_t br = 0;
                    RT_RCC_APB2ENR |= 1u << 12;
                    while (br < 7 && (rt_pclk2 >> (br + 1)) > hz) br++;       /* clock = APB2 / 2^(br + 1) */
                #if defined(RT_F1)
                    rt_cfg(5, 0xBu);
                    rt_cfg(6, 0x4u);
                    rt_cfg(7, 0xBu);
                #else
                    rt_cfg(5, 2u, 0u, 5u, 0);
                    rt_cfg(6, 2u, 0u, 5u, 0);
                    rt_cfg(7, 2u, 0u, 5u, 0);
                #endif
                    RT_REG(RT_SPI1) = 0;
                    RT_REG(RT_SPI1) = (1u << 9) | (1u << 8) | (br << 3) | (1u << 2);   /* SSM, SSI, BR, MSTR */
                    RT_REG(RT_SPI1) |= 1u << 6;                                        /* SPE */
                }

                static int spiTransfer(uint8_t b) {
                    unsigned long n;
                    for (n = 0; n < RT_WAIT && !(RT_REG(RT_SPI1 + 0x08u) & 2u); n++) {    /* TXE */
                    }
                    RT_REG(RT_SPI1 + 0x0Cu) = b;
                    for (n = 0; n < RT_WAIT && !(RT_REG(RT_SPI1 + 0x08u) & 1u); n++) {    /* RXNE */
                    }
                    return (int) (RT_REG(RT_SPI1 + 0x0Cu) & 0xFFu);
                }
                """);
        piece("i2cOpen i2cWrite i2cRead", "rt_port", """
                /* ---- I2C1 master: PB6 SCL, PB7 SDA (open drain: the bus needs its pull-ups). A device that does not answer reads as -1. */
                #define RT_I2C_CR1 RT_REG(RT_I2C1 + 0x00u)
                #define RT_I2C_DR RT_REG(RT_I2C1 + 0x10u)
                #define RT_I2C_SR1 RT_REG(RT_I2C1 + 0x14u)
                #define RT_I2C_SR2 RT_REG(RT_I2C1 + 0x18u)

                static void i2cOpen(unsigned long hz) {
                    uint32_t mhz = rt_pclk1 / 1000000u, ccr;
                    RT_RCC_APB1ENR |= 1u << 21;
                #if defined(RT_F1)
                    rt_cfg(22, 0xFu);
                    rt_cfg(23, 0xFu);
                #else
                    rt_cfg(22, 2u, 1u, 4u, 1);
                    rt_cfg(23, 2u, 1u, 4u, 1);
                #endif
                    RT_I2C_CR1 = 1u << 15;                                 /* reset */
                    RT_I2C_CR1 = 0;
                    RT_REG(RT_I2C1 + 0x04u) = mhz;                         /* CR2: the bus clock in MHz */
                    if (hz <= 100000UL) {
                        ccr = rt_pclk1 / (2u * hz);
                        RT_REG(RT_I2C1 + 0x1Cu) = ccr < 4u ? 4u : ccr;
                        RT_REG(RT_I2C1 + 0x20u) = mhz + 1u;                /* TRISE: 1000 ns */
                    } else {
                        ccr = rt_pclk1 / (3u * hz);
                        RT_REG(RT_I2C1 + 0x1Cu) = (1u << 15) | (ccr < 1u ? 1u : ccr);   /* fast mode */
                        RT_REG(RT_I2C1 + 0x20u) = mhz * 300u / 1000u + 1u;              /* TRISE: 300 ns */
                    }
                    RT_I2C_CR1 = 1u;                                       /* PE */
                }

                /* Waits for a bit of SR1; 0 if the device said no (AF) or the time ran out. */
                static int rt_i2c_wait(uint32_t bit) {
                    unsigned long n;
                    for (n = 0; n < RT_WAIT; n++) {
                        uint32_t sr = RT_I2C_SR1;
                        if (sr & bit) return 1;
                        if (sr & (1u << 10)) break;                        /* AF: no acknowledge */
                    }
                    RT_I2C_SR1 = ~(1u << 10) & 0xFFFFu;
                    return 0;
                }

                static void rt_i2c_stop(void) {
                    RT_I2C_CR1 |= 1u << 9;
                }

                /* START, the address (with the read bit) and, for a write, the register; 0 if the device does not answer. */
                static int rt_i2c_start(uint8_t addr, int read) {
                    RT_I2C_CR1 |= 1u << 8;
                    if (!rt_i2c_wait(1u)) return 0;                        /* SB */
                    RT_I2C_DR = (uint32_t) ((addr << 1) | (read ? 1 : 0));
                    return rt_i2c_wait(2u);                                /* ADDR */
                }

                static void i2cWrite(uint8_t addr, uint8_t reg, uint8_t value) {
                    if (rt_i2c_start(addr, 0)) {
                        (void) RT_I2C_SR2;                                 /* reading SR1 then SR2 clears ADDR */
                        RT_I2C_DR = reg;
                        if (rt_i2c_wait(1u << 7)) {                        /* TXE */
                            RT_I2C_DR = value;
                            rt_i2c_wait(1u << 2);                          /* BTF */
                        }
                    }
                    rt_i2c_stop();
                }

                static int i2cRead(uint8_t addr, uint8_t reg) {
                    int value = -1;
                    if (rt_i2c_start(addr, 0)) {
                        (void) RT_I2C_SR2;
                        RT_I2C_DR = reg;
                        if (rt_i2c_wait(1u << 2) && rt_i2c_start(addr, 1)) {   /* BTF, then a repeated START */
                            RT_I2C_CR1 &= ~(1u << 10);                     /* one byte: no acknowledge */
                            (void) RT_I2C_SR2;
                            rt_i2c_stop();
                            if (rt_i2c_wait(1u << 6)) value = (int) (RT_I2C_DR & 0xFFu);   /* RXNE */
                            return value;
                        }
                    }
                    rt_i2c_stop();
                    return value;
                }
                """);
    }

    /** The runtime for an STM32 program: the head for this chip, then only the pieces {@code program} uses. */
    static String source(Board b, String program) {
        String title = "bare-metal " + b.stm32.chip();
        String body = EmbeddedRuntime.assemble(title, "", PIECES, program);
        return EmbeddedRuntime.assemble(title, head(b, program + body), PIECES, program);
    }
}
