#!/usr/bin/env bash
# Runs the whole check suite on a computer of your own: the same scripts GitHub runs, but with the
# toolchains that are already installed instead of downloading them every time.
#
#   scripts/local-ci.sh              # build, then every check whose tools are present
#   scripts/local-ci.sh app mqtt     # only those checks
#
# A check whose tool is missing is skipped with the reason and does not fail the run, so a machine
# with, say, no XC32 still runs everything else. The summary at the end says what ran, what was
# skipped and what failed, and the exit status is the number of failures.
#
# Where the tools are looked for, and how to point at your own:
#   OSS_CAD_SUITE   folder of the OSS CAD Suite (its environment file is sourced)  - FPGA checks
#   XC32_BIN        folder with xc32-gcc                                            - PIC32 checks
#   AVR_GCC_BIN     folder with avr-gcc                                             - AVR board checks
#   EVIA_SIM        the simavr-based simulator built by scripts/simavr/build.sh      - AVR board checks
#   CXX / CC        the C++ and C compilers to check the generated code with         - C/C++ checks
set -uo pipefail
cd "$(dirname "$0")/.."

CXX="${CXX:-$(command -v clang++ || command -v g++ || true)}"
CC="${CC:-$(command -v clang || command -v gcc || true)}"
ran=() skipped=() failed=()

have() { command -v "$1" > /dev/null 2>&1; }

note() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

skip() {   # name, why
    skipped+=("$1 ($2)")
    printf '\n\033[33m-- skipping %s: %s\033[0m\n' "$1" "$2"
}

run() {    # name, command...
    local name=$1
    shift
    note "$name"
    if "$@"; then
        ran+=("$name")
    else
        failed+=("$name")
        printf '\033[31m** %s failed\033[0m\n' "$name"
    fi
}

# ---------------------------------------------------------------- the toolchains that are here

: "${PREFIX:=/opt}"

# Where setup-toolchains.sh puts things, so a machine it has run on needs no variables set at all.
# An explicit OSS_CAD_SUITE / AVR_GCC_BIN / XC32_BIN always wins over these.
for d in "${OSS_CAD_SUITE:-}" "$PREFIX/oss-cad-suite" "$HOME/tools/oss-cad-suite" /opt/oss-cad-suite; do
    [ -n "$d" ] && [ -f "$d/environment" ] || continue
    # shellcheck disable=SC1091
    source "$d/environment"
    break
done
for d in "${AVR_GCC_BIN:-}" "$PREFIX/avr-gcc/bin" "$HOME/tools/avr-gcc/bin" /opt/avr-gcc/bin; do
    [ -n "$d" ] && [ -x "$d/avr-gcc" ] || continue
    PATH="$d:$PATH"
    break
done
for d in "${XC32_BIN:-}" /opt/microchip/xc32/v4.35/bin; do
    [ -n "$d" ] && [ -x "$d/xc32-gcc" ] || continue
    PATH="$d:$PATH"
    break
done
# arduino-cli and simavr land beside them
[ -x "$PREFIX/arduino-cli/arduino-cli" ] && PATH="$PREFIX/arduino-cli:$PATH"
[ -x "$HOME/tools/arduino-cli/arduino-cli" ] && PATH="$HOME/tools/arduino-cli:$PATH"
[ -z "${EVIA_SIM:-}" ] && [ -x "$PREFIX/simavr/bin/simavr" ] && export EVIA_SIM="$PREFIX/simavr/bin/simavr"
export PATH

PICK=("$@")

want() {   # true for a check named on the command line, or for every check when none were
    [ "${#PICK[@]}" -eq 0 ] && return 0
    local p
    for p in "${PICK[@]}"; do [ "$p" = "$1" ] && return 0; done
    return 1
}

# ---------------------------------------------------------------- build first: every check needs the jar

if want build; then
    note "build (mvn -B package)"
    if mvn -B -q package; then ran+=(build); else failed+=(build); echo "** the build failed: stopping"; exit 1; fi
fi
JAR=$(ls -t target/evia-paradigma-*.jar 2> /dev/null | head -n 1)
if [ -z "$JAR" ]; then
    echo "no target/evia-paradigma-*.jar: run without arguments, or mvn package first"
    exit 1
fi
echo "jar: $JAR"

# ---------------------------------------------------------------- the checks

want app   && run "the application, classes, modules, projects and Git" scripts/check-app.sh "$JAR"
want linux && run "Linux images: workspaces and build.sh for Buildroot, Yocto, OpenWrt" scripts/check-linux.sh "$JAR"
want mqtt  && { have "$CC" && run "MQTT against a broker on this computer" scripts/check-mqtt.sh "$JAR" \
               || skip mqtt "no C compiler (set CC)"; }

if want examples; then
    [ -n "$CXX" ] && run "the examples as C++, compared with the interpreter" scripts/check-examples.sh "$CXX" \
        || skip examples "no C++ compiler (set CXX)"
fi
if want targets; then
    [ -n "$CXX" ] && run "every language and mode" env CC="$CC" CXX="$CXX" scripts/check-targets.sh \
        || skip targets "no C++ compiler (set CXX)"
fi
if want zoom; then
    if have xvfb-run; then run "zoom and panning on a real window" scripts/check-zoom.sh "$JAR"
    elif [ -n "${DISPLAY:-}" ]; then run "zoom and panning on a real window" scripts/check-zoom.sh "$JAR"
    else skip zoom "no X display and no xvfb-run"; fi
fi
if want pic32; then
    have xc32-gcc && run "PIC32 C against Microchip's device headers" scripts/check-pic32.sh "$JAR" \
        || skip pic32 "xc32-gcc not on the PATH (set XC32_BIN)"
fi
if want boards; then
    if have avr-gcc && have arduino-cli; then run "AVR C in simavr and Arduino sketches" scripts/check-boards.sh "$JAR"
    else skip boards "needs avr-gcc (AVR_GCC_BIN) and arduino-cli"; fi
fi
if want fpga; then
    if have yosys && have iverilog; then run "FPGA: simulation against the interpreter, then synthesis" scripts/check-fpga.sh "$JAR"
    else skip fpga "needs the OSS CAD Suite (set OSS_CAD_SUITE)"; fi
fi

# ---------------------------------------------------------------- what happened

printf '\n\033[1m== summary\033[0m\n'
for x in "${ran[@]:-}";     do [ -n "$x" ] && printf '  \033[32mpassed \033[0m %s\n' "$x"; done
for x in "${skipped[@]:-}"; do [ -n "$x" ] && printf '  \033[33mskipped\033[0m %s\n' "$x"; done
for x in "${failed[@]:-}";  do [ -n "$x" ] && printf '  \033[31mFAILED \033[0m %s\n' "$x"; done
printf '%s passed, %s skipped, %s failed\n' "${#ran[@]}" "${#skipped[@]}" "${#failed[@]}"
exit "${#failed[@]}"
