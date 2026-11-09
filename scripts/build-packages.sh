#!/usr/bin/env bash
# Builds the EVIA package server's folder: the application jar and every toolchain for every platform,
# each as one archive, and the index.json that EVIA Paradigma reads (Settings, Packages and Updates; the
# first start of an installed copy; --packages / --install on the command line).
#
#   scripts/build-packages.sh                          # into build/packages-repo/
#   scripts/build-packages.sh --publish /var/www/evia  # and install it as <root>/packages/ (https://evia.am/packages/)
#   PLATFORMS="linux windows" scripts/build-packages.sh
#   JAR=path/to/evia-paradigma-0.9.0.jar scripts/build-packages.sh
#
# A toolchain archive that is already in the folder with the same version is kept as it is, so a new
# release of the application only adds its jar (the toolchains are gigabytes, and pinned). Archives of
# versions the index no longer names are deleted; the application keeps its previous jar (to go back to).
# The index is written last, and replaced in one step, so a client never reads one that points at files
# that are not there yet.
#
# Every toolchain comes from packaging/fetch-toolchains.sh: pinned releases, checked against SHA-256.
# No sudo: the web root must be writable by whoever runs this (see scripts/deploy-site.sh).
set -uo pipefail
cd "$(dirname "$0")/.."

PUBLISH=""
for a in "$@"; do
    case "$a" in
        --publish) PUBLISH="__next__" ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) if [ "$PUBLISH" = "__next__" ]; then PUBLISH="$a"; else echo "unknown argument: $a" >&2; exit 2; fi ;;
    esac
done
[ "$PUBLISH" = "__next__" ] && { echo "--publish needs the web root, e.g. /var/www/evia" >&2; exit 2; }

PLATFORMS="${PLATFORMS:-linux linux-arm64 windows macos-arm64 macos-x64}"
REPO="${REPO_OUT:-build/packages-repo}"
WORK=build/packages-work
export TOOLCHAINS_CACHE="${TOOLCHAINS_CACHE:-build/toolchain-downloads}"
die() { echo "build-packages: $*" >&2; exit 1; }
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
sha() { if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }
plat() { case "$1" in linux) echo linux-x64 ;; windows) echo windows-x64 ;; *) echo "$1" ;; esac; }

# A published folder is the starting point, so unchanged toolchains are not fetched again
if [ -n "$PUBLISH" ] && [ -d "$PUBLISH/packages" ] && [ ! -d "$REPO" ]; then
    say "starting from what $PUBLISH/packages has"
    mkdir -p "$REPO" && cp -a "$PUBLISH/packages/." "$REPO/"
fi
mkdir -p "$REPO/app" "$REPO/tools" "$WORK"
MANIFEST="$WORK/files.tsv"      # id  platform  version  file  size  sha256  unpacked
: > "$MANIFEST"

# ---------------------------------------------------------------- the application
say "the application"
JAR="${JAR:-$(ls target/evia-paradigma-*.jar 2> /dev/null | grep -v original | sort -V | tail -n 1)}"
[ -f "$JAR" ] || die "no jar: build it (mvn package) or give JAR=..."
VER=$(unzip -p "$JAR" META-INF/MANIFEST.MF 2> /dev/null | sed -n 's/^Implementation-Version: *//p' | tr -d '\r')
[ -n "$VER" ] || VER=$(basename "$JAR" .jar | sed 's/^evia-paradigma-//')
cp "$JAR" "$REPO/app/evia-paradigma-$VER.jar"
printf 'app\tany\t%s\t%s\t%s\t%s\t0\n' "$VER" "app/evia-paradigma-$VER.jar" "$(wc -c < "$JAR" | tr -d ' ')" \
    "$(sha "$JAR")" >> "$MANIFEST"
echo "   $VER ($(basename "$JAR"))"

# ---------------------------------------------------------------- the toolchains
for kind in $PLATFORMS; do
    p=$(plat "$kind")
    say "toolchains for $p"
    ext=tar.gz
    [ "$kind" = windows ] && ext=zip
    while read -r id ver; do
        [ -n "$id" ] || continue
        file="tools/$id/$id-$ver-$p.$ext"
        meta="$REPO/$file.meta"                    # unpacked size, kept next to the archive
        if [ -f "$REPO/$file" ] && [ -f "$meta" ]; then
            echo "   = $id $ver (already built)"
        else
            echo "   + $id $ver"
            rm -rf "$WORK/$kind"
            ONLY="$id" TOOLCHAINS_OUT="$WORK/$kind" packaging/fetch-toolchains.sh "$kind" > "$WORK/fetch-$kind-$id.log" 2>&1 \
                || { tail -5 "$WORK/fetch-$kind-$id.log"; die "fetching $id for $kind failed"; }
            [ -d "$WORK/$kind/$id" ] || die "fetch-toolchains.sh made no $id folder for $kind"
            mkdir -p "$REPO/tools/$id"
            rm -f "$REPO/$file.part"
            if [ "$ext" = zip ]; then
                python3 - "$WORK/$kind" "$id" "$REPO/$file.part" <<'PY' || die "zipping $id failed"
import os, sys, zipfile
base, top, out = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for root, dirs, files in os.walk(os.path.join(base, top)):
        dirs.sort()
        for f in sorted(files):
            full = os.path.join(root, f)
            if os.path.islink(full):
                continue
            z.write(full, os.path.relpath(full, base))
PY
            else
                tar -czf "$REPO/$file.part" -C "$WORK/$kind" "$id" || die "packing $id failed"
            fi
            mv "$REPO/$file.part" "$REPO/$file"
            du -sk "$WORK/$kind/$id" | awk '{print $1 * 1024}' > "$meta"
            rm -rf "$WORK/$kind"
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$p" "$ver" "$file" "$(wc -c < "$REPO/$file" | tr -d ' ')" \
            "$(sha "$REPO/$file")" "$(cat "$meta")" >> "$MANIFEST"
    done < <(VERSIONS_ONLY=1 packaging/fetch-toolchains.sh "$kind")
done

# ---------------------------------------------------------------- the index
say "index.json"
python3 - "$MANIFEST" "$REPO" <<'PY' || die "the index could not be written"
import json, os, sys, time
manifest, repo = sys.argv[1], sys.argv[2]
about = {
    "app": ("EVIA Paradigma", "Application", True,
            "The application itself. A newer version installs next to the one you have and starts the next time; "
            "nothing needs to be uninstalled or installed again."),
    "arduino-cli": ("Arduino CLI", "Arduino, ESP32, STM32", True,
                    "Builds and uploads Arduino sketches for the Arduino, ESP32 and STM32 boards. The board cores "
                    "(arduino:avr, esp32:esp32, STMicroelectronics:stm32) are fetched through it the first time."),
    "avr-gcc": ("AVR GCC and avrdude", "AVR", True,
                "Bare-metal AVR C for the ATmega boards (Uno, Nano, Mega, Leonardo, Micro, Pro Mini): avr-gcc, "
                "avr-libc and avrdude for uploading."),
    "arm-none-eabi-gcc": ("Arm GNU Toolchain", "STM32", True,
                          "Bare-metal STM32 C: arm-none-eabi-gcc with newlib (xPack build), for the Blue Pill, "
                          "Black Pill, Nucleo-F401RE/F411RE and F4DISCOVERY."),
    "openocd": ("OpenOCD", "STM32", True,
                "Uploads to STM32 boards through an ST-Link (built into the Nucleo and Discovery; a separate "
                "ST-Link V2 for the Blue and Black Pill)."),
    "pic32prog": ("pic32prog", "PIC32", True,
                  "Uploads PIC32 programs: PICkit2, PICkit3 in its PICkit2 mode, the chipKIT and Microchip "
                  "bootloaders, FT2232 JTAG adapters. The PIC32 compiler, XC32, comes from Microchip "
                  "(Settings, PIC32, Get XC32): its licence does not allow passing it on."),
    "oss-cad-suite": ("OSS CAD Suite", "FPGA", False,
                      "The open-source FPGA tools: yosys, nextpnr, gmpack, openFPGALoader, GHDL and more, for "
                      "Artix-7 and GateMate boards. Large: about 1 GB to download."),
}
order = ["app", "arduino-cli", "avr-gcc", "arm-none-eabi-gcc", "openocd", "pic32prog", "oss-cad-suite"]
pk = {}
for line in open(manifest, encoding="utf-8"):
    i, plat, ver, f, size, sha, unpacked = line.rstrip("\n").split("\t")
    pk.setdefault(i, {})[plat] = {"version": ver, "file": f, "size": int(size), "sha256": sha, "unpacked": int(unpacked)}
out = []
for i in order + sorted(set(pk) - set(order)):
    if i not in pk:
        continue
    name, group, rec, text = about.get(i, (i, "", False, ""))
    entry = {"id": i, "name": name, "group": group, "about": text, "recommended": rec, "files": pk[i]}
    if i == "app":
        entry["version"] = pk[i]["any"]["version"]
    out.append(entry)
index = {"format": 1, "name": "EVIA Paradigma packages",
         "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "packages": out}
tmp = os.path.join(repo, "index.json.tmp")
with open(tmp, "w", encoding="utf-8") as fh:
    json.dump(index, fh, indent=1, ensure_ascii=False)
    fh.write("\n")
os.replace(tmp, os.path.join(repo, "index.json"))
# what the index no longer names goes (the application keeps the jar before this one)
named = {f["file"] for e in out for f in e["files"].values()}
jars = sorted((f for f in os.listdir(os.path.join(repo, "app")) if f.endswith(".jar")),
              key=lambda n: [int(x) if x.isdigit() else 0 for x in n[15:-4].split(".")])
keep = set(jars[-2:])
for root, _, files in os.walk(repo):
    for f in files:
        rel = os.path.relpath(os.path.join(root, f), repo).replace(os.sep, "/")
        if rel == "index.json" or rel.endswith(".meta") and rel[:-5] in named:
            continue
        if rel.startswith("app/") and f in keep:
            continue
        if rel not in named:
            os.remove(os.path.join(root, f))
            print("   - " + rel)
for e in out:
    for plat, f in e["files"].items():
        print("   %-18s %-12s %-26s %8.1f MB" % (e["id"], plat, f["version"], f["size"] / 1048576))
PY

# ---------------------------------------------------------------- publish
if [ -n "$PUBLISH" ]; then
    say "publishing into $PUBLISH/packages"
    [ -d "$PUBLISH" ] && [ -w "$PUBLISH" ] || die "$PUBLISH is not a writable folder"
    mkdir -p "$PUBLISH/packages"
    # everything but the index first (only what changed), then the index in one step, then what is no longer named
    python3 - "$REPO" "$PUBLISH/packages" <<'PY' || die "publishing failed"
import os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
files = set()
for root, _, names in os.walk(src):
    for n in names:
        rel = os.path.relpath(os.path.join(root, n), src)
        files.add(rel)
        if rel == "index.json":
            continue
        a, b = os.path.join(src, rel), os.path.join(dst, rel)
        if os.path.exists(b) and os.path.getsize(a) == os.path.getsize(b) and os.path.getmtime(a) <= os.path.getmtime(b):
            continue
        os.makedirs(os.path.dirname(b), exist_ok=True)
        shutil.copy2(a, b + ".part")
        os.replace(b + ".part", b)
shutil.copy2(os.path.join(src, "index.json"), os.path.join(dst, "index.json.tmp"))
os.replace(os.path.join(dst, "index.json.tmp"), os.path.join(dst, "index.json"))
for root, _, names in os.walk(dst):
    for n in names:
        rel = os.path.relpath(os.path.join(root, n), dst)
        if rel not in files:
            os.remove(os.path.join(dst, rel))
            print("   - " + rel)
PY
    echo "   https://evia.am/packages/index.json"
fi
du -sh "$REPO" | awk '{print "   " $1 " in " $2}'
