#!/usr/bin/env bash
# STM32 programs (examples/boards/*.evia generated with --lang stm32):
#
#  1. Every example (MQTT needs a network, so not those), structured and goto flow, for every STM32 board,
#     compiled for its core (Cortex-M3 or Cortex-M4F) by clang with -Wall -Wextra -Werror. clang has no C
#     library for bare ARM, so it gets the few declarations of math.h, stdlib.h and string.h the programs
#     use; this catches misspelled registers, wrong types and unused code without any ARM toolchain.
#  2. With arm-none-eabi-gcc (ARM_GCC, or on PATH): the same programs built for real with the linker script
#     and newlib, -Werror, and the image checked: vector table at 0x08000000, the reset handler in flash.
#  3. With qemu-system-arm as well: the Nucleo-F401RE builds run on QEMU's netduinoplus2 (an STM32F405, the
#     same USART2, GPIO and SysTick; no RCC, so the runtime falls back to the internal oscillator as it would
#     on a chip whose PLL does not start). Programs without inputs must print exactly what the interpreter
#     prints; the others (ADC, UART, SPI and I2C devices differ from the simulated board) must run to the end.
#  4. With arduino-cli and the STM32 core: the examples as Arduino sketches for every STM32 board.
#
# Usage: [ARM_GCC=arm-none-eabi-gcc] [QEMU=qemu-system-arm] [ARDUINO_CLI=arduino-cli] [SKIP_ARDUINO=1]
#        scripts/check-stm32.sh [jar]
set -uo pipefail
cd "$(dirname "$0")/.."

JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
ARM_GCC="${ARM_GCC:-arm-none-eabi-gcc}"
QEMU="${QEMU:-qemu-system-arm}"
ARDUINO_CLI="${ARDUINO_CLI:-arduino-cli}"
OUT=build/stm32
# QEMU: output that must equal the interpreter's (no inputs, no pin numbers in it); button waits for presses
QEMU_EXACT="blink fade"
rm -rf "$OUT" && mkdir -p "$OUT/mock"

# board, clang target, clang cpu flags
BOARDS=(
  "bluepill-f103c8 thumbv7m-none-eabi -mcpu=cortex-m3 -mfloat-abi=soft"
  "blackpill-f411ce thumbv7em-none-eabihf -mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard"
  "nucleo-f401re thumbv7em-none-eabihf -mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard"
  "nucleo-f411re thumbv7em-none-eabihf -mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard"
  "disco-f407vg thumbv7em-none-eabihf -mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard"
  "disco-f429zi thumbv7em-none-eabihf -mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard")

# ---- the declarations clang needs in place of newlib's headers
cat > "$OUT/mock/math.h" <<'H'
double fmod(double, double); double floor(double); double ceil(double); double round(double); double trunc(double);
double fabs(double); double sqrt(double); double pow(double, double); double exp(double); double log(double);
double log10(double); double sin(double); double cos(double); double tan(double); double atan(double);
double atan2(double, double); double asin(double); double acos(double); double modf(double, double *);
double ldexp(double, int); double frexp(double, int *); double rint(double); long lround(double);
#define NAN __builtin_nan("")
#define INFINITY __builtin_inf()
#define HUGE_VAL __builtin_huge_val()
#define isnan(x) __builtin_isnan(x)
#define isinf(x) __builtin_isinf(x)
#define isfinite(x) __builtin_isfinite(x)
#define signbit(x) __builtin_signbit(x)
H
cat > "$OUT/mock/stdlib.h" <<'H'
#include <stddef.h>
void *calloc(size_t, size_t); void *malloc(size_t); void *realloc(void *, size_t); void free(void *);
int atoi(const char *); long atol(const char *); double atof(const char *); double strtod(const char *, char **);
long strtol(const char *, char **, int); unsigned long strtoul(const char *, char **, int);
int abs(int); long labs(long); void abort(void); void exit(int);
H
cat > "$OUT/mock/string.h" <<'H'
#include <stddef.h>
size_t strlen(const char *); int strcmp(const char *, const char *); int strncmp(const char *, const char *, size_t);
char *strcpy(char *, const char *); char *strncpy(char *, const char *, size_t); char *strcat(char *, const char *);
char *strncat(char *, const char *, size_t); char *strstr(const char *, const char *); char *strchr(const char *, int);
char *strrchr(const char *, int); void *memcpy(void *, const void *, size_t); void *memmove(void *, const void *, size_t);
void *memset(void *, int, size_t); int memcmp(const void *, const void *, size_t);
H

ok=0; failed=0
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }

HAVE_GCC=0
command -v "$ARM_GCC" > /dev/null && HAVE_GCC=1 && "$ARM_GCC" --version | head -n 1
HAVE_QEMU=0
[ "$HAVE_GCC" = 1 ] && command -v "$QEMU" > /dev/null && HAVE_QEMU=1 && "$QEMU" --version | head -n 1

for src in examples/boards/*.evia; do
  name=$(basename "$src" .evia)
  if grep -q "MQTT_" "$src"; then echo "skip  $name (MQTT needs a network: ESP32 or PIC32)"; continue; fi
  if grep -qE "\bR[A-G][0-9]+\b" "$src"; then echo "skip  $name (PIC32 pin names)"; continue; fi
  java -jar "$JAR" --run "$src" > "$OUT/$name.interp" 2> "$OUT/$name.events" < /dev/null
  for b in "${BOARDS[@]}"; do
    set -- $b
    board=$1; triple=$2; shift 2; cpu=("$@")
    for flow in structured goto; do
      base="$OUT/$name-$board-$flow"
      mkdir -p "$base"
      c="$base/program.c"
      if ! java -jar "$JAR" --gen "$src" --lang stm32 --board "$board" --flow "$flow" -o "$c" 2> "$base/gen.log"; then
        fail "$name $board $flow: generate"; grep -v "^Picked up" "$base/gen.log" | head -3; continue
      fi
      # 1. clang, for the core
      if ! clang --target="$triple" "${cpu[@]}" -mthumb -ffreestanding -std=gnu11 -Os -Wall -Wextra -Werror \
            -isystem "$OUT/mock" -c -o "$base/clang.o" "$c" 2> "$base/clang.log"; then
        fail "$name $board $flow: clang"; head -12 "$base/clang.log"; continue
      fi
      # 2. arm-none-eabi-gcc with newlib and the linker script
      if [ "$HAVE_GCC" = 1 ]; then
        gccflags=$(grep -m1 '^// Build:' "$c" | sed 's|^// Build: *arm-none-eabi-gcc ||')
        # shellcheck disable=SC2086
        if ! (cd "$base" && "$ARM_GCC" $gccflags -Werror) > "$base/gcc.log" 2>&1; then
          fail "$name $board $flow: arm-none-eabi-gcc"; head -12 "$base/gcc.log"; continue
        fi
        vec=$("${ARM_GCC%gcc}objdump" -h "$base/program.elf" | awk '$2 == ".isr_vector" {print $4}')
        if [ "$vec" != "08000000" ]; then fail "$name $board $flow: vector table at $vec"; continue; fi
        "${ARM_GCC%gcc}objcopy" -O binary "$base/program.elf" "$base/program.bin"
        reset=$(od -An -tx4 -j4 -N4 "$base/program.bin" | tr -d ' ')
        case "$reset" in 080*) ;; *) fail "$name $board $flow: reset handler at $reset"; continue ;; esac
      fi
      # 3. QEMU: the Nucleo-F401RE build on an STM32F405 (USART2 is QEMU's second serial port)
      if [ "$HAVE_QEMU" = 1 ] && [ "$board" = nucleo-f401re ] && [ "$name" != button ]; then
        timeout 60 "$QEMU" -M netduinoplus2 -nographic -monitor none -serial null -serial "file:$base/qemu.raw" \
            -kernel "$base/program.elf" > /dev/null 2>&1 &
        qpid=$!
        # the program ends in a loop: wait until the output stops growing (or the time is up)
        last=-1
        for _ in $(seq 1 60); do
          sleep 1
          size=$(stat -c %s "$base/qemu.raw" 2> /dev/null || echo 0)
          [ "$size" = "$last" ] && [ "$size" -gt 0 ] && break
          last=$size
        done
        kill "$qpid" 2> /dev/null; wait "$qpid" 2> /dev/null
        tr -d '\r' < "$base/qemu.raw" > "$base/qemu.out"
        case " $QEMU_EXACT " in
          *" $name "*)
            if ! diff -q "$OUT/$name.interp" "$base/qemu.out" > /dev/null; then
              fail "$name $board $flow: QEMU output differs from the interpreter"; diff "$OUT/$name.interp" "$base/qemu.out" | head -6; continue
            fi ;;
          *)
            # inputs (ADC, the UART, SPI and I2C devices) are not the simulated board's: it must run to the end
            if [ "$(wc -l < "$OUT/$name.interp")" != "$(wc -l < "$base/qemu.out")" ]; then
              fail "$name $board $flow: QEMU printed $(wc -l < "$base/qemu.out") lines, the interpreter $(wc -l < "$OUT/$name.interp")"
              head -5 "$base/qemu.out"; continue
            fi ;;
        esac
      fi
      ok=$((ok + 1))
    done
  done
  echo "done  $name"
done
echo "STM32 C: $ok checked (clang$([ "$HAVE_GCC" = 1 ] && echo ", arm-none-eabi-gcc")$([ "$HAVE_QEMU" = 1 ] && echo ", QEMU")), $failed failed"

# 4. Arduino sketches with STMicroelectronics' core
if [ "${SKIP_ARDUINO:-}" != 1 ] && command -v "$ARDUINO_CLI" > /dev/null \
    && "$ARDUINO_CLI" core list 2> /dev/null | grep -q "^STMicroelectronics:stm32"; then
  sketches=0
  for src in examples/boards/*.evia; do
    name=$(basename "$src" .evia)
    grep -q "MQTT_" "$src" && continue
    grep -qE "\bR[A-G][0-9]+\b" "$src" && continue
    for b in "${BOARDS[@]}"; do
      set -- $b
      board=$1
      dir="$OUT/sketch/$name-$board/program"
      mkdir -p "$dir"
      if ! java -jar "$JAR" --gen "$src" --lang arduino --board "$board" -o "$dir/program.ino" 2> "$dir.gen.log"; then
        fail "$name $board: generate sketch"; continue
      fi
      fqbn=$(awk -v b="$board" 'BEGIN{
          m["bluepill-f103c8"]="STMicroelectronics:stm32:GenF1:pnum=BLUEPILL_F103C8";
          m["blackpill-f411ce"]="STMicroelectronics:stm32:GenF4:pnum=BLACKPILL_F411CE";
          m["nucleo-f401re"]="STMicroelectronics:stm32:Nucleo_64:pnum=NUCLEO_F401RE";
          m["nucleo-f411re"]="STMicroelectronics:stm32:Nucleo_64:pnum=NUCLEO_F411RE";
          m["disco-f407vg"]="STMicroelectronics:stm32:Disco:pnum=DISCO_F407VG";
          m["disco-f429zi"]="STMicroelectronics:stm32:Disco:pnum=DISCO_F429ZI"; print m[b] }')
      if ! "$ARDUINO_CLI" compile --fqbn "$fqbn" --warnings all --output-dir "$dir-build" "$dir" > "$dir.log" 2>&1; then
        fail "$name $board: arduino-cli"; grep -E "error|Error" "$dir.log" | head -8; continue
      fi
      if grep -qE "program\.ino:[0-9]+:[0-9]+: warning" "$dir.log"; then
        fail "$name $board: warnings in the sketch"; grep -A3 "program\.ino:[0-9]*:[0-9]*: warning" "$dir.log" | head -8; continue
      fi
      sketches=$((sketches + 1))
    done
  done
  echo "STM32 Arduino: $sketches sketches built"
else
  echo "STM32 Arduino: skipped (no arduino-cli with the STMicroelectronics:stm32 core)"
fi
[ "$failed" = 0 ]
