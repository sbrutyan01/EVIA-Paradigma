#!/usr/bin/env bash
# Fetches the toolchains for one platform into build/toolchains/, one folder each with its programs in bin/;
# scripts/build-packages.sh turns each folder into a package of the EVIA package server:
#
#   avr-gcc/            avr-gcc and avr-libc (bare-metal AVR C), with avrdude (upload) in its bin/
#   arm-none-eabi-gcc/  the Arm GNU Toolchain with newlib (bare-metal STM32 C), xPack build
#   openocd/            OpenOCD (STM32 upload through an ST-Link), xPack build
#   arduino-cli/        arduino-cli (Arduino, ESP32 and STM32 sketches; the cores come through it)
#   oss-cad-suite/      yosys, nextpnr, gmpack, openFPGALoader ... (FPGA)
#
#   [ONLY="openocd arduino-cli"] packaging/fetch-toolchains.sh linux|linux-arm64|windows|macos-arm64|macos-x64
#
# Each folder also gets EVIA-VERSION (a line for people) and EVIA-PACKAGE-VERSION (the package version).
#
# Every file is a pinned release, checked against its SHA-256 before it is unpacked, so an installer
# always carries the same, known tools. All of them are free software that may be passed on (GPL and
# similar licences); each folder gets a note saying what it is, its licence and where its source is.
#
# Microchip's XC32 is NOT here: its licence does not allow passing it on. The application fetches it
# from microchip.com instead (Settings, PIC32, Get XC32).
set -euo pipefail
KIND="${1:?usage: fetch-toolchains.sh linux|linux-arm64|windows|macos-arm64|macos-x64}"
ONLY="${ONLY:-avr-gcc arm-none-eabi-gcc openocd arduino-cli oss-cad-suite}"
want() { case " $ONLY " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
OUT="${TOOLCHAINS_OUT:-build/toolchains}"
CACHE="${TOOLCHAINS_CACHE:-build/toolchain-downloads}"
mkdir -p "$OUT" "$CACHE"

OSS_TAG=2026-11-09
OSS_DATE=20261109
AVR_TAG=v16.1.0-1
ARM_VER=15.2.1-1.1
OCD_VER=0.12.0-7
CLI_VER=1.5.1
GH=https://github.com
ARDUINO=https://downloads.arduino.cc/tools
XPACK=$GH/xpack-dev-tools

# the xPack builds and arduino-cli: file suffix and SHA-256 for each platform
case "$KIND" in
  linux)       XP=linux-x64.tar.gz
               ARM_SHA=da6a49ad4003944b823c6c93702a8787c922ab34bd7e918ec0eaf6933a9b1ff6
               OCD_SHA=94b3790983beaf8ed57e646c0620dd66d705fddae03d290823a6ed3b439468d6
               CLI=("Linux_64bit.tar.gz" 28a8e119c498a25607821c36cb2dc49e8463941b261a0d99091baa7bc692dd2b) ;;
  linux-arm64) XP=linux-arm64.tar.gz
               ARM_SHA=67980c7990eba7bb7ffdf39699102effd70889f5ac427be19a8c8a6c5fab2972
               OCD_SHA=db73a3ab91c556ecec2405a7e02d404b11139df6aba1031cad94a7e6766d06cc
               CLI=("Linux_ARM64.tar.gz" 1e69e077479f300614d4551334e0a33f08ee40b04315d83b8e7e0e94f0d0ee62) ;;
  windows)     XP=win32-x64.zip
               ARM_SHA=bae6a3d1667697ce750c3b13d6d26d80973ecedc2cc87bf04869e83447fd93ea
               OCD_SHA=6bfd3c97135aafef8affc9af1acf34fd0e2b9ca26044506f6abd7f95b7630052
               CLI=("Windows_64bit.zip" fabe42e0eb04d00e776a66178299ff95a46c623dbc260f997e58fd514853dd40) ;;
  macos-arm64) XP=darwin-arm64.tar.gz
               ARM_SHA=574082d35e49a2bcbdc355836b2a3ae5e5bb3b9456c9f5e37177db2ab4aad870
               OCD_SHA=667342c086984f3e5a55b4e0d5f711add13fb04de040fca493303000e6c19327
               CLI=("macOS_ARM64.tar.gz" cb952e8c1621c95ef5f1d17831c945e3d0ec5973f89c557a7ec8feb9c4f7d4c9) ;;
  macos-x64)   XP=darwin-x64.tar.gz
               ARM_SHA=5be906a5194c3173e145a58048e5607ffff947773237802b93e55e23c415f1b6
               OCD_SHA=668ad25350103a4357e11629ec833eae5982e973889ce25bad0c2963e37fa8bf
               CLI=("macOS_64bit.tar.gz" c982e940027996bea9901050e95fae99c59c1dcfee54beedecaf28141e7bf2e7) ;;
  *) echo "unknown platform $KIND" >&2; exit 2 ;;
esac

case "$KIND" in
  linux-arm64)
    # neither the OSS CAD Suite nor an avr-gcc are built for 64-bit ARM Linux: only the STM32 and Arduino tools
    ONLY=$(echo " $ONLY " | sed 's/ avr-gcc / /; s/ oss-cad-suite / /') ;;
  linux)
    OSS=("oss-cad-suite-linux-x64-$OSS_DATE.tgz" 256b113c88b8da8b363e739dd97babe89eefbfdbb84373ccae0666ae19ac304d)
    # Arduino's build, not avr-gcc-build's 16.1.0: that one needs glibc 2.36, which Ubuntu 22.04 (2.35) does
    # not have. This one needs glibc 2.11, so it runs on every Linux the .deb and the AppImage are for.
    AVR=("$ARDUINO/avr-gcc-7.3.0-atmel3.6.1-arduino7-x86_64-pc-linux-gnu.tar.bz2"
         bd8c37f6952a2130ac9ee32c53f6a660feb79bee8353c8e289eb60fdcefed91e "avr-gcc 7.3.0 (Arduino's build, 7.3.0-atmel3.6.1-arduino7)")
    DUDE=("$ARDUINO/avrdude_8.0-arduino.1_Linux_64bit.tar.gz" 1d2edadb07e070fcd016e8c802e961e30bfa606c2db63625c930526e7044aaa6) ;;
  windows)
    OSS=("oss-cad-suite-windows-x64-$OSS_DATE.tgz" e7fcbfbea8bb1aafe350ab454dc98330dcad03c1f788f8d2d77c778f3a6a9c42)
    AVR=("$GH/ZakKemble/avr-gcc-build/releases/download/$AVR_TAG/avr-gcc-16.1.0-x64-windows.zip"
         de4aad501ee42b63d5d262e45470924cd2654f6bc437f0299875efa6b8d8d0fe "avr-gcc 16.1.0 (ZakKemble/avr-gcc-build $AVR_TAG)")
    DUDE=("$ARDUINO/avrdude_8.0-arduino.1_Windows_32bit.tar.gz" 833aa1a66a8e70cd597fcfdbd7e559a91a00eca1d7aa3be2ce9bcadf7ccb987c) ;;
  macos-arm64|macos-x64)
    if [ "$KIND" = macos-arm64 ]; then
      OSS=("oss-cad-suite-darwin-arm64-$OSS_DATE.tgz" 024cd74ba5d8bc1891c7b5ec1f1d0fc307429f0ac7fb1bd1f1c2e1d1083d92c4)
    else
      OSS=("oss-cad-suite-darwin-x64-$OSS_DATE.tgz" ec71f713b35534f0e76ae06952ae1b724acfdf388f8a613b44e152b8ca213e8e)
    fi
    # avr-gcc-build has no macOS build any more; Arduino's is the macOS avr-gcc (Intel, runs under Rosetta on Apple Silicon)
    AVR=("$ARDUINO/avr-gcc-7.3.0-atmel3.6.1-arduino7-x86_64-apple-darwin14.tar.bz2"
         f6ed2346953fcf88df223469088633eb86de997fa27ece117fd1ef170d69c1f8 "avr-gcc 7.3.0 (Arduino's build, 7.3.0-atmel3.6.1-arduino7)")
    DUDE=("$ARDUINO/avrdude_8.0-arduino.1_macOS_64bit.tar.gz" 8a36cf441a3ce21622eb30b40f0b71d27864b30ea851cb795ae654ee10e2c132) ;;
  *) echo "unknown platform $KIND" >&2; exit 2 ;;
esac

# VERSIONS_ONLY=1: say which version of each toolchain this platform gets, download nothing
if [ "${VERSIONS_ONLY:-}" = 1 ]; then
    case "$KIND" in windows) avr_v="16.1.0-1" ;; *) avr_v="7.3.0-arduino7" ;; esac
    want avr-gcc && echo "avr-gcc $avr_v+avrdude-8.0"
    want arm-none-eabi-gcc && echo "arm-none-eabi-gcc $ARM_VER"
    want openocd && echo "openocd $OCD_VER"
    want arduino-cli && echo "arduino-cli $CLI_VER"
    want oss-cad-suite && echo "oss-cad-suite $OSS_TAG"
    exit 0
fi

sha() { if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }

fetch() {   # url, sha256 -> path of the checked file in the cache
    local url=$1 want=$2 file
    file="$CACHE/$(basename "$url")"
    if [ ! -f "$file" ] || [ "$(sha "$file")" != "$want" ]; then
        echo "downloading $(basename "$url")" >&2
        curl -fsSL --retry 5 --retry-delay 10 -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    local got
    got=$(sha "$file")
    if [ "$got" != "$want" ]; then
        echo "SHA-256 of $(basename "$url") is $got, expected $want: not used" >&2
        rm -f "$file"
        exit 1
    fi
    echo "$file"
}

unpack() {   # archive, destination folder (its one top folder is taken off)
    local a=$1 to=$2 tmp
    tmp=$(mktemp -d "$CACHE/unpack.XXXXXX")
    case "$a" in
        *.zip) if command -v unzip > /dev/null; then unzip -q "$a" -d "$tmp"; else "${PYTHON:-python}" -m zipfile -e "$a" "$tmp"; fi ;;
        *) tar -xf "$a" -C "$tmp" ;;
    esac
    rm -rf "$to"
    mkdir -p "$(dirname "$to")"
    local entries=("$tmp"/*)
    if [ ${#entries[@]} -eq 1 ] && [ -d "${entries[0]}" ]; then mv "${entries[0]}" "$to"; else mv "$tmp" "$to"; fi
    rm -rf "$tmp"
}

# ---- avr-gcc, with avrdude in its bin/ and avrdude.conf in its etc/ (where avrdude looks for it)
if want avr-gcc; then
echo "== avr-gcc" >&2
avr=$(fetch "${AVR[0]}" "${AVR[1]}")
unpack "$avr" "$OUT/avr-gcc"
dude=$(fetch "${DUDE[0]}" "${DUDE[1]}")
tmp=$(mktemp -d "$CACHE/dude.XXXXXX")
tar -xf "$dude" -C "$tmp"
bin=$(find "$tmp" -type f \( -name avrdude -o -name avrdude.exe \) | head -n 1)
conf=$(find "$tmp" -type f -name avrdude.conf | head -n 1)
[ -n "$bin" ] && [ -n "$conf" ] || { echo "avrdude or avrdude.conf missing from $(basename "${DUDE[0]}")" >&2; exit 1; }
mkdir -p "$OUT/avr-gcc/bin" "$OUT/avr-gcc/etc"
cp "$bin" "$OUT/avr-gcc/bin/"
cp "$conf" "$OUT/avr-gcc/etc/avrdude.conf"
for dll in $(find "$(dirname "$bin")" -maxdepth 1 -name '*.dll'); do cp "$dll" "$OUT/avr-gcc/bin/"; done
rm -rf "$tmp"
cat > "$OUT/avr-gcc/EVIA-VERSION" <<EOF
${AVR[2]}, avrdude 8.0 (Arduino's build 8.0-arduino.1)
EOF
cat > "$OUT/avr-gcc/SOURCES.txt" <<EOF
These tools are free software, passed on unchanged with EVIA Paradigma.

${AVR[2]}
  GCC and binutils: GNU GPL v3 (with the GCC runtime library exception); avr-libc: modified BSD licence.
  Downloaded from: ${AVR[0]}
  Source: https://gcc.gnu.org, https://www.gnu.org/software/binutils, https://github.com/avrdudes/avr-libc
          and the build scripts at https://github.com/ZakKemble/avr-gcc-build (Windows)
          or https://github.com/arduino/toolchain-avr (Linux, macOS).

avrdude 8.0 (Arduino's build)
  GNU GPL v2 or later.
  Downloaded from: ${DUDE[0]}
  Source: https://github.com/avrdudes/avrdude and https://github.com/arduino/avrdude-packing

To get the exact source of these builds, see the projects above or write to sbrutyan01@gmail.com.
EOF
case "$KIND" in windows) avr_v="16.1.0-1" ;; *) avr_v="7.3.0-arduino7" ;; esac
echo "$avr_v+avrdude-8.0" > "$OUT/avr-gcc/EVIA-PACKAGE-VERSION"
fi

# ---- the Arm GNU Toolchain (xPack): arm-none-eabi-gcc, binutils, newlib and newlib-nano
if want arm-none-eabi-gcc; then
echo "== arm-none-eabi-gcc" >&2
arm=$(fetch "$XPACK/arm-none-eabi-gcc-xpack/releases/download/v$ARM_VER/xpack-arm-none-eabi-gcc-$ARM_VER-$XP" "$ARM_SHA")
unpack "$arm" "$OUT/arm-none-eabi-gcc"
echo "Arm GNU Toolchain $ARM_VER (xPack arm-none-eabi-gcc)" > "$OUT/arm-none-eabi-gcc/EVIA-VERSION"
echo "$ARM_VER" > "$OUT/arm-none-eabi-gcc/EVIA-PACKAGE-VERSION"
cat > "$OUT/arm-none-eabi-gcc/SOURCES.txt" <<EOF
The xPack GNU Arm Embedded GCC $ARM_VER, passed on unchanged with EVIA Paradigma.
  GCC and binutils: GNU GPL v3 (with the GCC runtime library exception); newlib: BSD-style licences.
  Downloaded from: $XPACK/arm-none-eabi-gcc-xpack/releases/tag/v$ARM_VER
  Source: https://github.com/xpack-dev-tools/arm-none-eabi-gcc-xpack and Arm's sources it builds from
          (https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads).
EOF
fi

# ---- OpenOCD (xPack): uploads to STM32 boards through an ST-Link
if want openocd; then
echo "== OpenOCD" >&2
ocd=$(fetch "$XPACK/openocd-xpack/releases/download/v$OCD_VER/xpack-openocd-$OCD_VER-$XP" "$OCD_SHA")
unpack "$ocd" "$OUT/openocd"
echo "OpenOCD $OCD_VER (xPack)" > "$OUT/openocd/EVIA-VERSION"
echo "$OCD_VER" > "$OUT/openocd/EVIA-PACKAGE-VERSION"
cat > "$OUT/openocd/SOURCES.txt" <<EOF
The xPack OpenOCD $OCD_VER, passed on unchanged with EVIA Paradigma. GNU GPL v2 or later.
  Downloaded from: $XPACK/openocd-xpack/releases/tag/v$OCD_VER
  Source: https://github.com/xpack-dev-tools/openocd-xpack and https://openocd.org
On Linux, OpenOCD needs permission to open the ST-Link: the udev rules are in openocd/contrib/60-openocd.rules
(copy them to /etc/udev/rules.d/ once and plug the ST-Link in again).
EOF
fi

# ---- arduino-cli: one program; put in bin/ like the others
if want arduino-cli; then
echo "== arduino-cli" >&2
cli=$(fetch "$GH/arduino/arduino-cli/releases/download/v$CLI_VER/arduino-cli_${CLI_VER}_${CLI[0]}" "${CLI[1]}")
tmp=$(mktemp -d "$CACHE/cli.XXXXXX")
case "$cli" in
  *.zip) if command -v unzip > /dev/null; then unzip -q "$cli" -d "$tmp"; else "${PYTHON:-python}" -m zipfile -e "$cli" "$tmp"; fi ;;
  *) tar -xf "$cli" -C "$tmp" ;;
esac
rm -rf "$OUT/arduino-cli" && mkdir -p "$OUT/arduino-cli/bin"
mv "$tmp"/arduino-cli* "$OUT/arduino-cli/bin/"
[ -f "$tmp/LICENSE.txt" ] && mv "$tmp/LICENSE.txt" "$OUT/arduino-cli/"
rm -rf "$tmp"
echo "arduino-cli $CLI_VER" > "$OUT/arduino-cli/EVIA-VERSION"
echo "$CLI_VER" > "$OUT/arduino-cli/EVIA-PACKAGE-VERSION"
cat > "$OUT/arduino-cli/SOURCES.txt" <<EOF
arduino-cli $CLI_VER, passed on unchanged with EVIA Paradigma. GNU GPL v3.
  Downloaded from: $GH/arduino/arduino-cli/releases/tag/v$CLI_VER
  Source: https://github.com/arduino/arduino-cli
EOF
fi

# ---- the OSS CAD Suite
if want oss-cad-suite; then
echo "== OSS CAD Suite" >&2
oss=$(fetch "$GH/YosysHQ/oss-cad-suite-build/releases/download/$OSS_TAG/${OSS[0]}" "${OSS[1]}")
unpack "$oss" "$OUT/oss-cad-suite"
echo "OSS CAD Suite $OSS_TAG (${OSS[0]})" > "$OUT/oss-cad-suite/EVIA-VERSION"
cat > "$OUT/oss-cad-suite/SOURCES.txt" <<EOF
The OSS CAD Suite, passed on unchanged with EVIA Paradigma: open-source tools for FPGA design
(yosys, nextpnr, gmpack, openFPGALoader, Icarus Verilog, GHDL and more), each under its own
free licence (ISC, GPL, LGPL, MIT, BSD ...). The licences are in license/ inside this folder.

Release:  $OSS_TAG
File:     ${OSS[0]}
From:     $GH/YosysHQ/oss-cad-suite-build/releases/tag/$OSS_TAG
Source:   https://github.com/YosysHQ/oss-cad-suite-build (build scripts; each tool's own source is linked there)
EOF
echo "$OSS_TAG" > "$OUT/oss-cad-suite/EVIA-PACKAGE-VERSION"
fi

du -sh "$OUT"/* >&2
