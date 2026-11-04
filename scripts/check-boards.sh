#!/usr/bin/env bash
# Board programs (examples/boards/*.evia):
#
#  1. Bare-metal AVR C, structured and goto flow, built with avr-gcc -Wall -Wextra -Werror for the
#     ATmega328P (Uno, Nano, Pro Mini), ATmega2560 (Mega 2560) and ATmega32U4 (Leonardo, Micro).
#  2. Each build runs in simavr (scripts/simavr/evia_sim) with the same button (pin 4) and analog
#     signal (A0) as the interpreter's simulated board. The UART output must equal the interpreter's
#     output; analog readings may differ by a few counts (the chip spends real time printing).
#  3. The built-in LED (pin 13: PB5, PB7 or PC7) must switch as often as in the interpreter.
#  4. Arduino sketches, structured flow, for every board in FQBNS (default: the 12 boards), and goto flow
#     for the first AVR and the first ESP32 board, with arduino-cli - no warnings in the sketch.
#     The Uno and Mega 2560 builds also run in simavr and are compared like the bare-metal ones.
#
# Usage: [EVIA_SIM=path] [AVR_GCC=avr-gcc] [ARDUINO_CLI=arduino-cli] [FQBNS="..."] [SKIP_ARDUINO=1]
#        scripts/check-boards.sh [jar]
set -uo pipefail

JAR="${1:-$(ls target/evia-paradigma-*.jar | head -n 1)}"
AVR_GCC="${AVR_GCC:-avr-gcc}"
OBJCOPY="${AVR_GCC%gcc}objcopy"
EVIA_SIM="${EVIA_SIM:-build/evia_sim}"
ARDUINO_CLI="${ARDUINO_CLI:-arduino-cli}"
FQBNS="${FQBNS:-arduino:avr:uno arduino:avr:nano:cpu=atmega328 arduino:avr:mega:cpu=atmega2560 arduino:avr:leonardo arduino:avr:micro arduino:avr:pro:cpu=16MHzatmega328 esp32:esp32:esp32 esp32:esp32:esp32s2 esp32:esp32:esp32s3 esp32:esp32:esp32c3 esp32:esp32:esp32c6 esp32:esp32:esp32h2}"
OUT=build/boards
rm -rf "$OUT" && mkdir -p "$OUT"
# chip, built-in LED (pin 13), button (pin 4), ADC channel of A0
CHIPS=("atmega328p B5 D4 0" "atmega2560 B7 G5 0" "atmega32u4 C7 D4 7")

ok=0; failed=0
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }

# Runs a .hex in simavr and compares it with the interpreter. Arduino sketches never stop (loop()
# runs forever), so for them reaching the time limit is fine.
simulate() {   # name base mcu led button adc sketch(0/1)
  local name=$1 base=$2 mcu=$3 led=$4 button=$5 adc=$6 sketch=$7 rc
  "$EVIA_SIM" "$mcu" "$base.hex" --watch "$led" --button "$button" --adc "$adc" --uart-loop --i2c 80 --seconds "$([ "$sketch" = 1 ] && echo 30 || echo 120)" \
      > "$base.raw" 2> "$base.pins"
  rc=$?
  if [ "$rc" != 0 ] && ! { [ "$sketch" = 1 ] && [ "$rc" = 3 ]; }; then
    fail "$name $mcu: simavr (exit code $rc)"; tail -3 "$base.pins"; return 1
  fi
  tr -d '\r' < "$base.raw" > "$base.out"
  if [ "$name" = analog_meter ]; then
    # same lines; each reading within 16 counts
    if [ "$(wc -l < "$OUT/$name.interp")" != "$(wc -l < "$base.out")" ] || ! paste -d'|' "$OUT/$name.interp" "$base.out" | awk -F'|' '
        { a = $1; b = $2; sub(/^A0 = /, "", a); sub(/^A0 = /, "", b); split(a, x, " "); split(b, y, " ");
          if ((substr($1,1,4) == "A0 =") != (substr($2,1,4) == "A0 =")) bad = 1;
          else if (substr($1,1,4) == "A0 =" && (x[1] - y[1] > 16 || y[1] - x[1] > 16)) bad = 1;
          else if (substr($1,1,4) != "A0 =" && $1 != $2) bad = 1 }
        END { exit bad }'; then
      fail "$name $mcu: output"; paste -d'|' "$OUT/$name.interp" "$base.out" | head -5; return 1
    fi
  elif ! diff -q "$OUT/$name.interp" "$base.out" > /dev/null; then
    fail "$name $mcu: output differs from the interpreter"; diff "$OUT/$name.interp" "$base.out" | head -6; return 1
  fi
  local want got
  want=$(levels 'pin 13 (HIGH|LOW)' "$OUT/$name.events"); got=$(levels "P$led [01]" "$base.pins")
  if [ "${#want}" != "${#got}" ]; then
    fail "$name $mcu: LED switched ${#got} times, the interpreter ${#want} times"; return 1
  fi
  return 0
}

# LED levels from the interpreter ("board [500 ms] pin 13 HIGH") or evia_sim ("[500 ms] PB5 1"),
# without the leading LOWs (a pin starts low, so writing LOW first is no change on the chip)
levels() { grep -oE "$1" "$2" | grep -oE '(HIGH|LOW|[01])$' | sed 's/HIGH/1/;s/LOW/0/' | sed '/1/,$!d' | tr -d '\n'; }

"$AVR_GCC" --version | head -n 1
for src in examples/boards/*.evia; do
  name=$(basename "$src" .evia)
  java -jar "$JAR" --run "$src" > "$OUT/$name.interp" 2> "$OUT/$name.events" < /dev/null
  sed -i '/^Picked up/d' "$OUT/$name.events"
  for flow in structured goto; do
    c="$OUT/$name-$flow.c"
    java -jar "$JAR" --gen "$src" --lang avr --flow "$flow" -o "$c" 2>/dev/null || { fail "$name $flow: generate"; continue; }
    for chip in "${CHIPS[@]}"; do
      set -- $chip
      mcu=$1; led=$2; button=$3; adc=$4
      base="$OUT/$name-$flow-$mcu"
      if ! "$AVR_GCC" -mmcu="$mcu" -DF_CPU=16000000UL -std=gnu11 -Os -Wall -Wextra -Werror -o "$base.elf" "$c" -lm 2> "$base.log"; then
        fail "$name $flow $mcu: avr-gcc"; cat "$base.log"; continue
      fi
      "$OBJCOPY" -O ihex -R .eeprom "$base.elf" "$base.hex"
      if [ ! -x "$EVIA_SIM" ]; then ok=$((ok + 1)); continue; fi
      simulate "$name" "$base" "$mcu" "$led" "$button" "$adc" 0 || continue
      ok=$((ok + 1))
    done
  done
  echo "done  $name (avr)"
done
echo "AVR: $ok builds checked$([ -x "$EVIA_SIM" ] && echo " in simavr" || echo " (no simavr: build only)"), $failed failed"

if [ "${SKIP_ARDUINO:-}" != 1 ] && command -v "$ARDUINO_CLI" > /dev/null; then
  "$ARDUINO_CLI" version
  sketches=0; simulated=0
  first_avr=$(echo $FQBNS | tr ' ' '\n' | grep '^arduino:' | head -n 1)
  first_esp=$(echo $FQBNS | tr ' ' '\n' | grep '^esp32:' | head -n 1)
  for src in examples/boards/*.evia; do
    name=$(basename "$src" .evia)
    for fqbn in $FQBNS; do
      flows=structured
      [ "$fqbn" = "$first_avr" ] || [ "$fqbn" = "$first_esp" ] && flows="structured goto"
      for flow in $flows; do
        dir="$OUT/sketch/$name-${fqbn//[:=]/_}-$flow/program"
        mkdir -p "$dir"
        java -jar "$JAR" --gen "$src" --lang arduino --flow "$flow" -o "$dir/program.ino" 2>/dev/null
        if ! "$ARDUINO_CLI" compile --fqbn "$fqbn" --warnings all --output-dir "$dir-build" "$dir" > "$dir.log" 2>&1; then
          fail "$name $flow $fqbn: arduino-cli"; grep -E "error|Error" "$dir.log" | head -8; continue
        fi
        if grep -qE "program\.ino:[0-9]+:[0-9]+: warning" "$dir.log"; then
          fail "$name $flow $fqbn: warnings in the sketch"; grep -A3 "program\.ino:[0-9]*:[0-9]*: warning" "$dir.log" | head -8; continue
        fi
        sim=""
        case "$fqbn" in
          arduino:avr:uno) sim="atmega328p B5 D4 0" ;;
          arduino:avr:mega*) sim="atmega2560 B7 G5 0" ;;
        esac
        if [ -n "$sim" ] && [ -x "$EVIA_SIM" ]; then
          set -- $sim
          cp "$dir-build/program.ino.hex" "$dir.hex"
          simulate "$name" "$dir" "$1" "$2" "$3" "$4" 1 || continue
          simulated=$((simulated + 1))
        fi
        sketches=$((sketches + 1)); ok=$((ok + 1))
      done
    done
    echo "done  $name (arduino)"
  done
  echo "Arduino: $sketches sketches built, $simulated of them run in simavr"
else
  echo "Arduino: skipped (no $ARDUINO_CLI)"
fi

echo "$ok passed, $failed failed"
[ "$failed" = 0 ]
