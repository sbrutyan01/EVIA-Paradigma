#!/usr/bin/env bash
# Installs the .deb with apt in clean containers of several Ubuntu/Debian releases,
# checks that every bundled native library resolves, and runs an example program.
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
    apt-get install -y -qq "./'"$DEB"'" > /tmp/apt.log 2>&1 || { tail -20 /tmp/apt.log; exit 1; }
    missing=$(find /opt/evia-paradigma -name "*.so" -exec ldd {} \; 2>/dev/null | grep "not found" | sort -u || true)
    if [ -n "$missing" ]; then echo "missing libraries:"; echo "$missing"; exit 1; fi
    "/opt/evia-paradigma/bin/EVIA Paradigma" --version
    "/opt/evia-paradigma/bin/EVIA Paradigma" --run examples/example.evia | tail -n 1
    grep -q "evia" /usr/share/applications/*.desktop && echo "menu entry installed"
  '
done
echo "deb installs and runs on all tested releases"
