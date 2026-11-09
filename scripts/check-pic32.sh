#!/usr/bin/env bash
# PIC32 programs (examples/boards/*.evia generated with --lang pic32):
#
#  There is no XC32 compiler or PIC32 hardware in CI, so this checks what can be checked on a PC:
#  every example, structured and goto flow, for the boards of the three chip families is compiled
#  with the PC's gcc (-Wall -Wextra -Werror, syntax only) against Microchip's real device headers.
#  That catches every misspelled register or bit field, wrong types and unused code. It does not run
#  the program and it does not check pin assignments, the pragma config names or timing: those need
#  the chip (or at least xc32-gcc).
#
#  Where do the headers come from? Microchip's device headers (p32mx250f128b.h ...) are not part of this
#  repository. Give the folder with them in PIC32_PROC (the proc/ folder of an XC32 installation or of the
#  PIC32MX_DFP), and the folder with cp0defs.h and p32xxxx.h in PIC32_INC; the script's default is to
#  fetch the headers of the Pinguino project (github.com/PinguinoIDE/pinguino-libraries).
#
# Usage: [PIC32_PROC=dir PIC32_INC=dir] scripts/check-pic32.sh [jar]
set -uo pipefail

JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
OUT=build/pic32
rm -rf "$OUT" && mkdir -p "$OUT/mock"

if [ -z "${PIC32_PROC:-}" ]; then
  if [ ! -d "$OUT/pinguino/p32/include/non-free/proc" ]; then
    git clone -q --depth 1 --filter=blob:none --sparse https://github.com/PinguinoIDE/pinguino-libraries "$OUT/pinguino" \
      && git -C "$OUT/pinguino" sparse-checkout set p32/include/non-free || { echo "FAIL  can't fetch the device headers"; exit 1; }
  fi
  PIC32_INC="$OUT/pinguino/p32/include/non-free"
  PIC32_PROC="$PIC32_INC/proc"
fi
PIC32_INC="${PIC32_INC:-$PIC32_PROC/..}"

# xc.h of XC32 pulls in the device header; _mfc0 is a function of the MIPS compiler (the core timer is read with it)
cat > "$OUT/mock/xc.h" <<'H'
#include EVIA_PROC_HEADER
static inline unsigned _mfc0(int reg, int sel) { (void) reg; (void) sel; return 0; }
H

# board, device header (a chip of the same family if the board's own header isn't there)
BOARDS=(
  "pic32mx150f128b p32mx250f128b" "pic32mx250f128b p32mx250f128b" "pic32mx270f256b p32mx270f256b"
  "pic32mx320f128h p32mx440f256h" "pic32mx340f512h p32mx440f256h" "pic32mx440f256h p32mx440f256h"
  "pic32mx460f512l p32mx460f512l"
  "pic32mx695f512l p32mx795f512l" "pic32mx795f512l p32mx795f512l" "chipkit-pro-mx7 p32mx795f512l")

ok=0; failed=0
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }

for src in examples/boards/*.evia; do
  name=$(basename "$src" .evia)
  for b in "${BOARDS[@]}"; do
    set -- $b
    board=$1; header=$2
    [ -f "$PIC32_PROC/$header.h" ] || { echo "skip  $board ($header.h missing)"; continue; }
    # The WiFi module on UART2 works on every chip. Harmony over the built-in Ethernet MAC exists only on
    # the MX695/795, and only for a program that actually connects to a broker, so that pass is added just
    # for those: it is a different output shape (no #pragma config, no main(), app_run() instead).
    nets=uart
    case $board in pic32mx695f512l | pic32mx795f512l | chipkit-pro-mx7) grep -q MQTT_CONNECT "$src" && nets="uart ethernet";; esac
    for flow in structured goto; do
      for net in $nets; do
        c="$OUT/$name-$board-$flow-$net.c"
        # --net is pinned, never taken from the saved settings: a leftover "ethernet" there would otherwise
        # put every MQTT program on the Harmony path and change what this check compiles.
        if ! java -jar "$JAR" --gen "$src" --lang pic32 --board "$board" --flow "$flow" --net "$net" -o "$c" 2> "$c.err"; then
          fail "$name $board $flow $net: generate"; cat "$c.err"; continue
        fi
        # A Harmony application (#include "definitions.h") is compiled against the stubs in
        # scripts/mqtt/harmony-stubs/; every other program against xc.h and the device header.
        if grep -q '^#include "definitions.h"' "$c"; then
          if ! gcc -fsyntax-only -std=gnu99 -Wall -Wextra -Werror -Wno-unknown-pragmas \
                -D__LANGUAGE_C__ -D__PIC32MX__ "-DEVIA_PROC_HEADER=\"$header.h\"" \
                -I"$(dirname "$0")/mqtt/harmony-stubs" -I"$PIC32_PROC" "$c" 2> "$c.log"; then
            fail "$name $board $flow $net: gcc (Harmony)"; head -12 "$c.log"; continue
          fi
        elif ! gcc -fsyntax-only -std=gnu99 -Wall -Wextra -Werror -Wno-unknown-pragmas \
              -D__LANGUAGE_C__ -D__PIC32MX__ "-DEVIA_PROC_HEADER=\"$header.h\"" \
              -I"$OUT/mock" -I"$PIC32_PROC" -I"$PIC32_INC" "$c" 2> "$c.log"; then
          fail "$name $board $flow $net: gcc"; head -12 "$c.log"; continue
        fi
        ok=$((ok + 1))
      done
    done
  done
  echo "done  $name"
done
echo "$ok passed, $failed failed"
[ "$failed" = 0 ]
