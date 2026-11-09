#!/usr/bin/env bash
# The package system (Packages, --packages, --install, --remove, the application's self-update), against a
# repository in a local folder, so nothing is downloaded and nothing outside build/ is touched:
#
#   1. --packages lists the index, with nothing installed
#   2. a toolchain installs: its bin/ program is there and runnable, installed.properties says its version
#   3. a newer version in the index shows as an update, and --install updates takes it
#   4. a damaged download (wrong SHA-256) is refused and the installed version stays as it was
#   5. --remove takes it away again
#   6. the application: a newer jar that does not start is marked broken, and this version starts instead
#
# Usage: scripts/check-packages.sh [jar]
set -uo pipefail
cd "$(dirname "$0")/.."
JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
OUT="$PWD/build/packages-check"
rm -rf "$OUT" && mkdir -p "$OUT/repo/tools" "$OUT/home"
REPO="file://$OUT/repo/"
ok=0; failed=0
pass() { echo "ok    $*"; ok=$((ok + 1)); }
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }
evia() { java -Devia.packages="$REPO" -Devia.packages.home="$OUT/home" -jar "$JAR" "$@"; }
sha() { sha256sum "$1" | awk '{print $1}'; }

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PLAT=linux-x64 ;; Linux-aarch64) PLAT=linux-arm64 ;;
  Darwin-arm64) PLAT=macos-arm64 ;; Darwin-*) PLAT=macos-x64 ;; *) PLAT=windows-x64 ;;
esac

# a toolchain archive, version $1: one top folder with bin/demo-tool (prints its version) and EVIA-VERSION
tool_archive() {
  local v=$1 d="$OUT/src/demo-tool"
  rm -rf "$OUT/src" && mkdir -p "$d/bin"
  printf '#!/bin/sh\necho demo-tool %s\n' "$v" > "$d/bin/demo-tool"
  chmod -x "$d/bin/demo-tool"                    # the installer must make it runnable
  echo "Demo tool $v" > "$d/EVIA-VERSION"
  tar -czf "$OUT/repo/tools/demo-tool-$v.tar.gz" -C "$OUT/src" demo-tool
}

index() {   # version of the tool, its sha256 (or "bad")
  local v=$1 s=$2 f="tools/demo-tool-$1.tar.gz"
  [ "$s" = bad ] && s=0000000000000000000000000000000000000000000000000000000000000000
  cat > "$OUT/repo/index.json" <<EOF
{
  "format": 1,
  "packages": [
    {"id": "app", "name": "EVIA Paradigma", "group": "Application", "about": "The application.",
     "version": "0.0.1", "files": {"any": {"file": "app/evia-paradigma-0.0.1.jar", "size": 1, "sha256": ""}}},
    {"id": "demo-tool", "name": "Demo Tool", "group": "Test", "about": "A tool for this check.", "recommended": true,
     "files": {"$PLAT": {"version": "$v", "file": "$f", "size": $(wc -c < "$OUT/repo/$f"), "sha256": "$s"}}},
    {"id": "elsewhere", "name": "Other Systems Only", "group": "Test", "about": "No file for this computer.",
     "files": {"no-such-platform": {"version": "1", "file": "x.tar.gz", "size": 1, "sha256": ""}}}
  ]
}
EOF
}

# ---- 1
tool_archive 1.0
index 1.0 "$(sha "$OUT/repo/tools/demo-tool-1.0.tar.gz")"
if evia --packages > "$OUT/list1.txt" 2>&1 && grep -q "demo-tool .*installed -  .*available 1.0" "$OUT/list1.txt" \
    && grep -q "elsewhere .*not for this computer" "$OUT/list1.txt"; then pass "--packages lists the index"
else fail "--packages"; cat "$OUT/list1.txt"; fi

# ---- 2
if evia --install demo-tool > "$OUT/install1.txt" 2>&1 && [ "$("$OUT/home/demo-tool/bin/demo-tool")" = "demo-tool 1.0" ] \
    && grep -q '^demo-tool=1.0$' "$OUT/home/installed.properties"; then pass "a toolchain installs and runs"
else fail "--install"; cat "$OUT/install1.txt"; fi

# ---- 3
tool_archive 2.0
index 2.0 "$(sha "$OUT/repo/tools/demo-tool-2.0.tar.gz")"
evia --packages > "$OUT/list2.txt" 2>&1
if grep -q "demo-tool .*UPDATE" "$OUT/list2.txt" && evia --install updates > "$OUT/update.txt" 2>&1 \
    && [ "$("$OUT/home/demo-tool/bin/demo-tool")" = "demo-tool 2.0" ] && [ ! -e "$OUT/home/demo-tool.old" ]; then
  pass "an update is offered and installed in place"
else fail "update"; cat "$OUT/list2.txt" "$OUT/update.txt" 2> /dev/null; fi

# ---- 4
tool_archive 3.0
index 3.0 bad
if ! evia --install demo-tool > "$OUT/bad.txt" 2>&1 && grep -q "damaged" "$OUT/bad.txt" \
    && [ "$("$OUT/home/demo-tool/bin/demo-tool")" = "demo-tool 2.0" ]; then pass "a damaged download is refused, the old version stays"
else fail "damaged download"; cat "$OUT/bad.txt"; fi

# ---- 5
if evia --remove demo-tool > /dev/null 2>&1 && [ ! -e "$OUT/home/demo-tool" ] \
    && ! grep -q '^demo-tool=' "$OUT/home/installed.properties"; then pass "--remove"
else fail "--remove"; fi

# ---- 6
mkdir -p "$OUT/home/app"
echo "not a jar" > "$OUT/home/app/evia-paradigma-99.0.0.jar"
if evia --version > "$OUT/version.txt" 2> "$OUT/version.err" && grep -q "^EVIA Paradigma " "$OUT/version.txt" \
    && [ -f "$OUT/home/app/evia-paradigma-99.0.0.jar.broken" ]; then pass "a newer jar that does not start is skipped and marked"
else fail "self-update fallback"; cat "$OUT/version.txt" "$OUT/version.err"; fi
# and a working newer jar is the one that runs: this same jar, named as a newer version
rm -f "$OUT"/home/app/*
cp "$JAR" "$OUT/home/app/evia-paradigma-99.0.1.jar"
if evia --version > "$OUT/version2.txt" 2> "$OUT/version2.err" && [ ! -f "$OUT/home/app/evia-paradigma-99.0.1.jar.broken" ] \
    && grep -q "^Updated:   started by the installed " "$OUT/version2.txt"; then pass "a working newer jar is handed over to"
else fail "self-update hand-over"; cat "$OUT/version2.txt" "$OUT/version2.err"; fi

echo "$ok passed, $failed failed"
[ "$failed" = 0 ]
