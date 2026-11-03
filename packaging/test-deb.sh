#!/usr/bin/env bash
# Installs the .deb with apt (dependencies only, no recommends) in clean containers of
# several Ubuntu/Debian releases, checks that every bundled native library resolves,
# runs an example program, then removes the package and checks it cleaned up.
#
# Usage: packaging/test-deb.sh packages/evia-paradigma_<ver>_amd64.deb
set -euo pipefail
DEB="$1"
for image in ubuntu:22.04 ubuntu:24.04 debian:12 debian:13; do
  echo "=== $image"
  docker run --rm -v "$PWD:/w" -w /w "$image" bash -c '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends "./'"$DEB"'" > /tmp/apt.log 2>&1 || { tail -60 /tmp/apt.log; exit 1; }
    missing=$(find /opt/evia-paradigma -name "*.so" -exec ldd {} \; 2>/dev/null | grep "not found" | sort -u || true)
    if [ -n "$missing" ]; then echo "missing libraries:"; echo "$missing"; exit 1; fi
    evia-paradigma --version
    evia-paradigma --run examples/example.evia | tail -n 1
    test -f /usr/share/applications/evia-paradigma.desktop
    test -f /usr/share/icons/hicolor/512x512/apps/evia-paradigma.png
    test -f /usr/share/mime/packages/evia-paradigma.xml
    echo "menu entry, icon and .evia MIME type installed"
    apt-get remove -y -qq evia-paradigma > /tmp/remove.log 2>&1 || { tail -30 /tmp/remove.log; exit 1; }
    for f in /usr/bin/evia-paradigma /usr/share/applications/evia-paradigma.desktop /opt/evia-paradigma/bin; do
      if [ -e "$f" ]; then echo "left behind after removal: $f"; exit 1; fi
    done
    echo "removed cleanly"
  '
done
echo "deb installs, runs and uninstalls on all tested releases"
