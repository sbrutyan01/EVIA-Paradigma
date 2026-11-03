#!/usr/bin/env bash
# For every examples/*.evia: generate C++20, compile it with -Werror, run both the
# built-in interpreter and the compiled program, and require identical stdout, stderr
# and exit code. An optional examples/NAME.input file is fed to stdin.
#
# Usage: scripts/check-examples.sh [compiler] [jar]
set -euo pipefail

CXX="${1:-clang++}"
JAR="${2:-$(ls target/evia-paradigma-*.jar | head -n 1)}"
OUT=build/examples
mkdir -p "$OUT"

"$CXX" --version | head -n 1
status=0
for src in examples/*.evia; do
  name=$(basename "$src" .evia)
  input="examples/$name.input"
  [ -f "$input" ] || input=/dev/null

  java -jar "$JAR" --gen "$src" -o "$OUT/$name.cpp"
  "$CXX" -std=c++20 -O2 -Wall -Wextra -Wpedantic -Werror -o "$OUT/$name" "$OUT/$name.cpp"

  set +e
  java -jar "$JAR" --run "$src" < "$input" > "$OUT/$name.interp.out" 2> "$OUT/$name.interp.err"
  echo $? > "$OUT/$name.interp.rc"
  # the JVM may announce JAVA_TOOL_OPTIONS on stderr; that's not program output
  sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$name.interp.err"
  "./$OUT/$name" < "$input" > "$OUT/$name.cpp.out" 2> "$OUT/$name.cpp.err"
  echo $? > "$OUT/$name.cpp.rc"
  set -e

  if diff -u "$OUT/$name.interp.out" "$OUT/$name.cpp.out" \
     && diff -u "$OUT/$name.interp.err" "$OUT/$name.cpp.err" \
     && diff -u "$OUT/$name.interp.rc" "$OUT/$name.cpp.rc"; then
    echo "ok    $name ($(wc -l < "$OUT/$name.cpp.out") lines of output)"
  else
    echo "FAIL  $name: interpreter and compiled C++ differ"
    status=1
  fi
done
exit $status
