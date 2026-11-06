#!/usr/bin/env bash
# Installs the outside toolchains the checks need, so the FPGA and board checks stop being skipped.
# Each one goes into its own folder under a prefix (/opt by default) and nothing else is touched.
#
#   scripts/setup-toolchains.sh --check              # what is here, what is missing, how big
#   scripts/setup-toolchains.sh --fpga               # the OSS CAD Suite: yosys, nextpnr, iverilog, ghdl
#   scripts/setup-toolchains.sh --avr                # avr-gcc, arduino-cli with its cores, simavr
#   scripts/setup-toolchains.sh --fpga --avr         # both
#   scripts/setup-toolchains.sh --avr --prefix ~/tools --no-sudo
#
# Afterwards it prints the two lines to put in the runner's environment, or as repository variables,
# so both scripts/local-ci.sh and the local-ci workflow find them.
set -euo pipefail
cd "$(dirname "$0")/.."

PREFIX="${PREFIX:-/opt}"
SUDO=sudo
WANT_FPGA=0
WANT_AVR=0
CHECK=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --check) CHECK=1 ;;
        --fpga) WANT_FPGA=1 ;;
        --avr) WANT_AVR=1 ;;
        --prefix) PREFIX="${2:?--prefix needs a folder}"; shift ;;
        --no-sudo) SUDO="" ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "unknown option: $1"; exit 2 ;;
    esac
    shift
done
[ "$CHECK" = 1 ] || [ "$WANT_FPGA$WANT_AVR" != "00" ] || { echo "nothing to do: pass --fpga, --avr or --check"; exit 2; }

have() { command -v "$1" > /dev/null 2>&1; }
say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
run()  { if [ -n "$SUDO" ]; then $SUDO "$@"; else "$@"; fi; }

# The newest file of a GitHub project's latest release whose name matches a pattern.
asset() {   # repo, extended regex
    curl -fsSL "https://api.github.com/repos/$1/releases/latest" \
        | grep -oE "https://[^\"]*$2" | head -n 1
}

if [ "$CHECK" = 1 ]; then
    say "the toolchains"
    for t in yosys nextpnr-himbaechel iverilog ghdl gmpack openFPGALoader avr-gcc arduino-cli; do
        printf '%-22s %s\n' "$t" "$(command -v "$t" || echo '-')"
    done
    printf '%-22s %s\n' evia_sim "$([ -x build/evia_sim ] && echo build/evia_sim || echo '-')"
    echo
    echo "--fpga  about 1 GB unpacked, unlocks the FPGA checks (simulation and synthesis)"
    echo "--avr   about 250 MB plus the Arduino cores, unlocks the board checks (simavr, 12 boards)"
    exit 0
fi

run mkdir -p "$PREFIX"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# ---------------------------------------------------------------- the FPGA tools

if [ "$WANT_FPGA" = 1 ]; then
    say "OSS CAD Suite"
    if [ -f "$PREFIX/oss-cad-suite/environment" ]; then
        echo "already in $PREFIX/oss-cad-suite"
    else
        url=$(asset YosysHQ/oss-cad-suite-build 'oss-cad-suite-linux-x64-[0-9]+\.tgz')
        [ -n "$url" ] || { echo "no linux-x64 file in the latest release"; exit 1; }
        echo "${url##*/}"
        curl -fSL --retry 5 -o "$tmp/oss.tgz" "$url"
        run tar xzf "$tmp/oss.tgz" -C "$PREFIX"      # the archive holds oss-cad-suite/
    fi
    # shellcheck disable=SC1091
    source "$PREFIX/oss-cad-suite/environment"
    for t in yosys nextpnr-himbaechel iverilog ghdl gmpack; do
        printf '%-22s %s\n' "$t" "$(command -v "$t" || echo 'MISSING')"
    done
fi

# ---------------------------------------------------------------- avr-gcc, arduino-cli, simavr

if [ "$WANT_AVR" = 1 ]; then
    say "avr-gcc"
    if [ -x "$PREFIX/avr-gcc/bin/avr-gcc" ]; then
        echo "already in $PREFIX/avr-gcc"
    else
        url=$(asset ZakKemble/avr-gcc-build 'avr-gcc-[0-9.]+-x64-linux\.tar\.bz2')
        [ -n "$url" ] || { echo "no x64-linux file in the latest release"; exit 1; }
        echo "${url##*/}"
        curl -fSL --retry 5 -o "$tmp/avr.tar.bz2" "$url"
        run tar xjf "$tmp/avr.tar.bz2" -C "$PREFIX"
        dir=$(find "$PREFIX" -maxdepth 1 -name 'avr-gcc-*-x64-linux' | sort | tail -n 1)
        run ln -sfn "$dir" "$PREFIX/avr-gcc"
    fi
    "$PREFIX/avr-gcc/bin/avr-gcc" --version | head -1

    say "arduino-cli and the cores"
    if have arduino-cli; then
        echo "already here: $(command -v arduino-cli)"
    else
        curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh \
            | BINDIR="$tmp" sh
        run install -m 0755 "$tmp/arduino-cli" /usr/local/bin/arduino-cli
    fi
    arduino-cli config init --overwrite \
        --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json > /dev/null
    arduino-cli core update-index > /dev/null
    arduino-cli core install arduino:avr esp32:esp32
    arduino-cli core list

    say "simavr (built as build/evia_sim)"
    if [ -x build/evia_sim ]; then
        echo "already built"
    else
        git init -q "$tmp/simavr"
        git -C "$tmp/simavr" fetch -q --depth 1 https://github.com/buserror/simavr.git \
            cb4aa607634469a6c1e1170fae30ece0672d4405
        git -C "$tmp/simavr" checkout -q FETCH_HEAD
        mkdir -p build
        scripts/simavr/build.sh "$tmp/simavr" "$PREFIX/avr-gcc/avr/include" build/evia_sim
    fi
    ls -l build/evia_sim
fi

# ---------------------------------------------------------------- how to use them

say "tell the checks where they are"
[ "$WANT_FPGA" = 1 ] && echo "OSS_CAD_SUITE=$PREFIX/oss-cad-suite"
[ "$WANT_AVR" = 1 ] && echo "AVR_GCC_BIN=$PREFIX/avr-gcc/bin"
cat <<'EOF'

For this shell:        export OSS_CAD_SUITE=... AVR_GCC_BIN=...
For the runner:        add the same lines to ~/actions-runner/.env, then
                       cd ~/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh start
For the workflow:      Settings > Actions > Variables > New repository variable (same names)
EOF
