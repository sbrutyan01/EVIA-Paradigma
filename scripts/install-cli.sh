#!/usr/bin/env bash
# Installs the evia command for this user: the jar into ~/.local/share/evia, the launcher into ~/.local/bin.
# Usage: scripts/install-cli.sh [jar]      (default: the newest target/evia-paradigma-*.jar)
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
SHARE="${XDG_DATA_HOME:-$HOME/.local/share}/evia"
BIN="$HOME/.local/bin"
mkdir -p "$SHARE" "$BIN"
cp "$JAR" "$SHARE/evia-paradigma.jar"
install -m 755 packaging/cli/evia "$BIN/evia"
echo "installed $BIN/evia ($(basename "$JAR"))"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "note  $BIN is not on PATH: add it to your shell profile" ;; esac
