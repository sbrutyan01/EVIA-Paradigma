#!/usr/bin/env bash
# Every language x flow x values combination, for every program in examples/ and tests/programs/:
# generate the code, compile it with -Wall -Wextra -Wpedantic -Werror, run it, and require the
# same stdout, stderr and exit code as the interpreter. An optional NAME.input file is fed to stdin.
#
# Fast values hold numbers only, so programs with text in variables are expected to be refused
# (the generator explains why); programs named err_* are only checked with safe values, because
# fast values skip the runtime checks on purpose.
#
# Python is checked the same way, run by $PYTHON instead of compiled; it is left out (with a note)
# when no Python 3 is installed.
#
# Usage: [CC=clang] [CXX=clang++] [PYTHON=python3] [LANGS="c11 c++20 python"] scripts/check-targets.sh [jar]
set -uo pipefail

JAR="${1:-$(ls target/evia-paradigma-*.jar | head -n 1)}"
CC="${CC:-clang}"
CXX="${CXX:-clang++}"
PYTHON="${PYTHON:-python3}"
if [ -z "${LANGS:-}" ]; then
  LANGS="c99 c11 c17 c++98 c++11 c++14 c++17 c++20"
  if "$PYTHON" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2> /dev/null; then
    LANGS="$LANGS python"
  else
    echo "note  no Python 3.8+ as '$PYTHON', so Python is not checked"
  fi
fi
OUT=build/targets
rm -rf "$OUT" && mkdir -p "$OUT"
EXTRA="${CXXFLAGS:-}"
EXE=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;; esac   # names with dots get no .exe added on Windows

"$CC" --version | head -n 1
"$CXX" --version | head -n 1
case " $LANGS " in *" python "*) "$PYTHON" --version ;; esac
ok=0; refused=0; failed=0

for src in examples/*.evia tests/programs/*.evia; do
  name=$(basename "$src" .evia)
  dir=$(dirname "$src")
  input="$dir/$name.input"
  [ -f "$input" ] || input=/dev/null
  java -jar "$JAR" --run "$src" < "$input" > "$OUT/$name.expected.out" 2> "$OUT/$name.expected.err"
  echo $? > "$OUT/$name.expected.rc"
  sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$name.expected.err"

  for lang in $LANGS; do
    for flow in structured goto; do
      for values in safe fast; do
        [[ "$name" == err_* && "$values" == fast ]] && continue
        id="$name.$lang.$flow.$values"
        case "$lang" in
          python) ext=py ;;
          c++*) ext=cpp; cmd=("$CXX" "-std=$lang") ;;
          *)    ext=c;   cmd=("$CC" "-std=$lang") ;;
        esac
        if ! java -jar "$JAR" --gen "$src" --lang "$lang" --flow "$flow" --values "$values" \
              -o "$OUT/$id.$ext" 2> "$OUT/$id.gen.err"; then
          if [ "$values" = fast ] && grep -q "fast values only hold numbers" "$OUT/$id.gen.err"; then
            refused=$((refused + 1))
            continue
          fi
          echo "FAIL  $id: generation failed"; cat "$OUT/$id.gen.err"; failed=$((failed + 1)); continue
        fi
        if [ "$ext" = py ]; then
          # the file must at least be valid Python before it runs
          if ! "$PYTHON" -m py_compile "$OUT/$id.py" 2> "$OUT/$id.cc.err"; then
            echo "FAIL  $id: not valid Python"; head -n 20 "$OUT/$id.cc.err"; failed=$((failed + 1)); continue
          fi
          PYTHONIOENCODING=utf-8 "$PYTHON" "$OUT/$id.py" < "$input" > "$OUT/$id.out" 2> "$OUT/$id.err"
          echo $? > "$OUT/$id.rc"
        else
          libs=(); [ "$ext" = c ] && libs=(-lm)
          # shellcheck disable=SC2086
          if ! "${cmd[@]}" -O1 -Wall -Wextra -Wpedantic -Werror $EXTRA -o "$OUT/$id$EXE" "$OUT/$id.$ext" "${libs[@]}" 2> "$OUT/$id.cc.err"; then
            echo "FAIL  $id: does not compile"; head -n 20 "$OUT/$id.cc.err"; failed=$((failed + 1)); continue
          fi
          "./$OUT/$id$EXE" < "$input" > "$OUT/$id.out" 2> "$OUT/$id.err"
          echo $? > "$OUT/$id.rc"
        fi
        # Windows: line endings may be \r\n on one side (sed in Git Bash drops \r), so ignore them
        if diff -q --strip-trailing-cr "$OUT/$name.expected.out" "$OUT/$id.out" > /dev/null \
           && diff -q --strip-trailing-cr "$OUT/$name.expected.err" "$OUT/$id.err" > /dev/null \
           && diff -q --strip-trailing-cr "$OUT/$name.expected.rc" "$OUT/$id.rc" > /dev/null; then
          ok=$((ok + 1))
        else
          echo "FAIL  $id: differs from the interpreter"
          diff --strip-trailing-cr "$OUT/$name.expected.out" "$OUT/$id.out" | head -n 10
          diff --strip-trailing-cr "$OUT/$name.expected.err" "$OUT/$id.err" | cat -A | head -n 5
          echo "  exit code: expected $(cat "$OUT/$name.expected.rc"), got $(cat "$OUT/$id.rc")"
          failed=$((failed + 1))
        fi
      done
    done
  done
  echo "done  $name"
done

echo "$ok identical to the interpreter, $refused refused by fast values (text in variables), $failed failed"
[ "$failed" -eq 0 ]
