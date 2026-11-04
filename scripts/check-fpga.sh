#!/usr/bin/env bash
# FPGA designs (SystemVerilog, Verilog-2001, VHDL for Artix-7 and GateMate):
#
#  1. scripts/fpga/fpga_check.py: nine test programs run in the interpreter and, as generated hardware, in
#     Icarus Verilog (SystemVerilog and Verilog) and GHDL (VHDL) at a lowered clock; pin changes, printed text,
#     serial bytes, PWM duty and the divider must agree.
#  2. scripts/fpga/check-synth.sh: every example and test synthesised with yosys for Artix-7 (synth_xilinx) and for
#     the GateMate (synth_gatemate, then nextpnr-himbaechel place and route and a gmpack bitstream).
#
#  What this does not check: Vivado, nextpnr-xilinx and the real boards. The pin tables come from Digilent's
#  and Olimex's published files, and the constraint files are not run through a vendor tool here.
#
# Needs on the PATH: java, python3, iverilog, ghdl, yosys (nextpnr-himbaechel and gmpack for the GateMate place and route;
# yosys's ghdl plugin for VHDL synthesis). The OSS CAD Suite has all of them: github.com/YosysHQ/oss-cad-suite-build
#
# Usage: scripts/check-fpga.sh [jar]
set -uo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | head -n 1)}"
OUT=build/fpga
mkdir -p "$OUT"
rc=0
python3 scripts/fpga/fpga_check.py "$JAR" "$OUT/sim" || rc=1
bash scripts/fpga/check-synth.sh "$JAR" "$OUT/synth" || rc=1
exit $rc
