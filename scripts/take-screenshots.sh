#!/usr/bin/env bash
# Drives the real UI through the README tutorial and saves docs/screenshots/*.png
# (2x scale). Needs Xvfb (xvfb-run), git and a C++ compiler. Uses a throwaway home
# folder so your settings and projects are not touched.
#
# Usage: scripts/take-screenshots.sh [jar]
set -euo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
OUT=build/screenshots-classes
HOMEDIR="${SCREENSHOT_HOME:-/tmp/evia-screenshots-home}"
rm -rf "$OUT" "$HOMEDIR" && mkdir -p "$OUT" "$HOMEDIR" docs/screenshots
javac -cp "$JAR" -d "$OUT" scripts/screenshots/evia/paradigma/Tour.java
HOME="$HOMEDIR" git config --global user.name "EVIA Paradigma"
HOME="$HOMEDIR" git config --global user.email "evia@example.com"
HOME="$HOMEDIR" xvfb-run -a -s "-screen 0 2600x1900x24" \
  java -Duser.home="$HOMEDIR" -Dsun.java2d.uiScale=2 -Dawt.useSystemAAFontSettings=on \
       -Dout=docs/screenshots -cp "$OUT:$JAR" evia.paradigma.Tour
