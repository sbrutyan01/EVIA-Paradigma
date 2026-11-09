#!/usr/bin/env bash
# Fetches the toolchains that go inside the installers, for one platform, into build/toolchains/:
#
#   avr-gcc/        avr-gcc and avr-libc (bare-metal AVR C), with avrdude (upload) in its bin/
#   oss-cad-suite/  yosys, nextpnr, gmpack, openFPGALoader ... (FPGA)
#
#   packaging/fetch-toolchains.sh linux|windows|macos-arm64|macos-x64
#
# Every file is a pinned release, checked against its SHA-256 before it is unpacked, so an installer
# always carries the same, known tools. All of them are free software that may be passed on (GPL and
# similar licences); each folder gets a note saying what it is, its licence and where its source is.
#
# Microchip's XC32 is NOT here: its licence does not allow passing it on. The application fetches it
# from microchip.com instead (Settings, PIC32, Get XC32).
set -euo pipefail
KIND="${1:?usage: fetch-toolchains.sh linux|windows|macos-arm64|macos-x64}"
OUT="${TOOLCHAINS_OUT:-build/toolchains}"
CACHE="${TOOLCHAINS_CACHE:-build/toolchain-downloads}"
mkdir -p "$OUT" "$CACHE"

OSS_TAG=2026-11-09
OSS_DATE=20261109
AVR_TAG=v16.1.0-1
GH=https://github.com
ARDUINO=https://downloads.arduino.cc/tools

case "$KIND" in
  linux)
    OSS=("oss-cad-suite-linux-x64-$OSS_DATE.tgz" 256b113c88b8da8b363e739dd97babe89eefbfdbb84373ccae0666ae19ac304d)
    AVR=("$GH/ZakKemble/avr-gcc-build/releases/download/$AVR_TAG/avr-gcc-16.1.0-x64-linux.tar.bz2"
         8621ecc6514df50202b58b23b0f8f72f0e535ec35ee40426194e9a15c57692f6 "avr-gcc 16.1.0 (ZakKemble/avr-gcc-build $AVR_TAG)")
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
          and the build scripts at https://github.com/ZakKemble/avr-gcc-build (Linux, Windows)
          or https://github.com/arduino/toolchain-avr (macOS).

avrdude 8.0 (Arduino's build)
  GNU GPL v2 or later.
  Downloaded from: ${DUDE[0]}
  Source: https://github.com/avrdudes/avrdude and https://github.com/arduino/avrdude-packing

To get the exact source of these builds, see the projects above or write to sbrutyan01@gmail.com.
EOF

# ---- the OSS CAD Suite
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

du -sh "$OUT"/* >&2
