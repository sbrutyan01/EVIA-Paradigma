#!/usr/bin/env bash
# Synthesises every FPGA example with the open-source tools, for Artix-7 (yosys synth_xilinx) and for the
# GateMate (yosys synth_gatemate, then place and route with nextpnr-himbaechel and gmpack a bitstream).
# SystemVerilog and Verilog are read by yosys directly, VHDL through its ghdl plugin (when it has one).
#
# usage: check-synth.sh JAR [WORKDIR]      needs: java, yosys; nextpnr-himbaechel and gmpack for the GateMate flow
set -u
JAR=${1:?usage: check-synth.sh JAR [WORKDIR]}
JAR=$(readlink -f "$JAR")
W=$(readlink -f "${2:-/tmp/evia-fpga-synth}")
HERE=$(cd "$(dirname "$0")" && pwd)
EX="$HERE/../../examples/fpga"
mkdir -p "$W"
fail=0
pass() { printf 'ok   %s\n' "$1"; }
bad()  { printf 'FAIL %s\n' "$1"; fail=$((fail + 1)); }

have_ghdl_plugin=0
yosys -m ghdl -p "help ghdl" >/dev/null 2>&1 && have_ghdl_plugin=1

# the test programs of fpga_check.py are written next to the examples so they are synthesised too
python3 - "$W" <<'PY'
import sys, os
sys.path.insert(0, os.environ.get("HERE", ".") )
PY
python3 - "$HERE" "$W" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
import programs
for name, (build, kind, ms, ex) in programs.TESTS.items():
    open(f"{sys.argv[2]}/test_{name}.evia", "w").write(build().text())
PY

gen() { java -jar "$JAR" --gen "$1" --lang "$2" --board "$3" -o "$4" 2>"$4.err"; }

artix() {   # file.evia board
    local src=$1 board=$2 base; base=$W/$(basename "${src%.evia}")_$board
    for l in "sv sv" "verilog v" "vhdl vhd"; do
        set -- $l
        if ! gen "$src" "$1" "$board" "$base.$2"; then bad "$(basename "$src") $board $1: $(tail -1 "$base.$2.err")"; continue; fi
        case $1 in
            vhdl) if [ $have_ghdl_plugin = 1 ]; then
                      yosys -q -m ghdl -p "ghdl --std=08 $base.vhd -e evia_top; synth_xilinx -top evia_top" >"$base.log" 2>&1 && pass "$(basename "$src") $board vhdl synth_xilinx" || bad "$(basename "$src") $board vhdl synth_xilinx"
                  elif command -v ghdl >/dev/null; then
                      ghdl -a --std=08 --workdir="$W" "$base.vhd" >"$base.log" 2>&1 && pass "$(basename "$src") $board vhdl (ghdl analyse)" || bad "$(basename "$src") $board vhdl (ghdl analyse)"
                  fi ;;
            sv) yosys -q -p "read_verilog -sv $base.sv; synth_xilinx -top evia_top" >"$base.log" 2>&1 && pass "$(basename "$src") $board sv synth_xilinx" || bad "$(basename "$src") $board sv synth_xilinx" ;;
            *)  yosys -q -p "read_verilog $base.v; synth_xilinx -top evia_top" >"$base.log" 2>&1 && pass "$(basename "$src") $board verilog synth_xilinx" || bad "$(basename "$src") $board verilog synth_xilinx" ;;
        esac
    done
}

gatemate() {   # file.evia board
    local src=$1 board=$2 base; base=$W/$(basename "${src%.evia}")_$board
    if ! gen "$src" sv "$board" "$base.sv"; then bad "$(basename "$src") $board: $(tail -1 "$base.sv.err")"; return; fi
    if ! yosys -q -p "read_verilog -sv $base.sv; synth_gatemate -top evia_top -luttree -nomx8; write_json $base.json" >"$base.log" 2>&1; then bad "$(basename "$src") $board synth_gatemate"; return; fi
    if command -v nextpnr-himbaechel >/dev/null && command -v gmpack >/dev/null; then
        nextpnr-himbaechel --device CCGM1A1 --json "$base.json" --vopt ccf="$base.ccf" --vopt out="$base.txt" --router router2 --freq 10 >"$base.pnr" 2>&1 \
            && gmpack "$base.txt" "$base.bit" >>"$base.pnr" 2>&1 && [ -s "$base.bit" ] \
            && pass "$(basename "$src") $board place, route and bitstream" || bad "$(basename "$src") $board place and route (see $base.pnr)"
    else
        pass "$(basename "$src") $board synth_gatemate (nextpnr-himbaechel/gmpack not installed: no place and route)"
    fi
}

for f in "$EX"/*.evia; do artix "$f" nexys-a7-100t; done
artix "$EX/blink.evia" arty-a7-35
artix "$EX/switches.evia" basys3
for f in "$W"/test_*.evia; do artix "$f" nexys-a7-100t; done
gatemate "$EX/blink.evia" olimex-gatemate
gatemate "$EX/blink.evia" ccgm1a1-evb
gatemate "$EX/uart_hello.evia" olimex-gatemate
for f in "$W"/test_arith.evia "$W"/test_loops.evia "$W"/test_prints.evia "$W"/test_divide.evia "$W"/test_millis.evia; do gatemate "$f" olimex-gatemate; done

if [ $fail -eq 0 ]; then echo "all synthesis checks passed"; else echo "$fail synthesis check(s) failed"; fi
exit $((fail > 0))
