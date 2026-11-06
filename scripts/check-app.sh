#!/usr/bin/env bash
# Headless checks of classes/modules, "View C++ source" line mapping, projects and the
# Git integration (against a local bare repository). Needs git on PATH.
#
# Usage: scripts/check-app.sh [jar]
set -euo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | head -n 1)}"
OUT=build/app-checks
rm -rf "$OUT" && mkdir -p "$OUT"
javac -cp "$JAR" -d "$OUT" scripts/app-checks/evia/paradigma/AppChecks.java
# commits made by the checks need an author even on a fresh CI machine
export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-EVIA checks}" GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-checks@example.com}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-EVIA checks}" GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-checks@example.com}"
java -Djava.awt.headless=true -cp "$OUT:$JAR" evia.paradigma.AppChecks
app=$?

# the interface in Russian and Armenian: every string the interface asks for has to be translated
i18n=0
for lang in ru hy; do
  missing=$(python3 scripts/i18n/collect.py --missing "$lang" | sed '/^$/d' | wc -l)
  if [ "$missing" = 0 ]; then
    echo "ok    the interface is complete in $lang"
  else
    echo "FAIL  $missing interface strings are missing in $lang (scripts/i18n/collect.py --missing $lang)"
    python3 scripts/i18n/collect.py --missing "$lang" | cut -f1 | head -10
    i18n=1
  fi
done
[ "$app" = 0 ] && [ "$i18n" = 0 ]
