#!/usr/bin/env bash
# Zoom checks on the real main window with real mouse-wheel and drag events.
# Needs a display; without one it uses Xvfb (xvfb-run). Screenshots go to build/zoom.
#
# Usage: scripts/check-zoom.sh [jar]
set -euo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
OUT=build/zoom
HOMEDIR="$(mktemp -d)"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac -cp "$JAR" -d "$OUT/classes" scripts/gui-checks/evia/paradigma/ZoomTest.java
run=(java -Duser.home="$HOMEDIR" -Dout="$OUT" -cp "$OUT/classes:$JAR" evia.paradigma.ZoomTest)
if [ -z "${DISPLAY:-}" ]; then
  HOME="$HOMEDIR" xvfb-run -a -s "-screen 0 1600x1000x24" "${run[@]}"
else
  HOME="$HOMEDIR" "${run[@]}"
fi
