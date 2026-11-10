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
# when no Python 3 is installed. Java and Kotlin are compiled all at once at the end (javac with
# -Xlint:all -Werror, kotlinc with -Werror), each file in a package of its own, then run one by one;
# each is left out (with a note) when its compiler is missing.
#
# Usage: [CC=clang] [CXX=clang++] [PYTHON=python3] [JAVAC=javac] [KOTLINC=kotlinc] [LANGS="c11 c++20 python java kotlin"]
#        scripts/check-targets.sh [jar]
set -uo pipefail

JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
CC="${CC:-clang}"
CXX="${CXX:-clang++}"
PYTHON="${PYTHON:-python3}"
JAVAC="${JAVAC:-javac}"
KOTLINC="${KOTLINC:-kotlinc}"
JAVA_RUN="$(dirname "$(command -v "$JAVAC" 2> /dev/null || echo java)")/java"
[ -x "$JAVA_RUN" ] || JAVA_RUN=java
if [ -z "${LANGS:-}" ]; then
  LANGS="c99 c11 c17 c++98 c++11 c++14 c++17 c++20"
  if "$PYTHON" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2> /dev/null; then
    LANGS="$LANGS python"
  else
    echo "note  no Python 3.8+ as '$PYTHON', so Python is not checked"
  fi
  if "$JAVAC" -version > /dev/null 2>&1; then LANGS="$LANGS java"; else echo "note  no JDK ('$JAVAC'), so Java is not checked"; fi
  if "$KOTLINC" -version > /dev/null 2>&1; then LANGS="$LANGS kotlin"; else echo "note  no kotlinc ('$KOTLINC'), so Kotlin is not checked"; fi
fi
OUT=build/targets
rm -rf "$OUT" && mkdir -p "$OUT"
EXTRA="${CXXFLAGS:-}"
EXE=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;; esac   # names with dots get no .exe added on Windows

"$CC" --version | head -n 1
"$CXX" --version | head -n 1
case " $LANGS " in *" python "*) "$PYTHON" --version ;; esac
case " $LANGS " in *" java "*) "$JAVAC" -version ;; esac
case " $LANGS " in *" kotlin "*) "$KOTLINC" -version 2>&1 | grep -v '^warning' ;; esac
ok=0; refused=0; failed=0
jvm_runs=()   # "id lang package input name": compiled together after the loop, then run

# compares one run with the interpreter's
compare() {   # id name
  local id=$1 name=$2
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
}

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
          java) ext=java ;;
          kotlin) ext=kt ;;
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
        if [ "$ext" = java ] || [ "$ext" = kt ]; then
          # a package of its own, so every file compiles in one go (Kotlin: after the @file annotation)
          pkg="t$(( ${#jvm_runs[@]} + 1 ))"
          if [ "$ext" = java ]; then sed -i "1i package $pkg;" "$OUT/$id.$ext"
          else sed -i "0,/^@file:/s//&/; /^@file:/a package $pkg" "$OUT/$id.$ext"; fi
          jvm_runs+=("$id $lang $pkg $input $name")
          continue
        elif [ "$ext" = py ]; then
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
        compare "$id" "$name"
      done
    done
  done
  echo "done  $name"
done

# ---------------------------------------------------------------- Java and Kotlin: one compile each, then run
for lang in java kotlin; do
  files=()
  for r in "${jvm_runs[@]}"; do
    read -r id l pkg input name <<< "$r"
    [ "$l" = "$lang" ] && files+=("$OUT/$id.$([ "$lang" = java ] && echo java || echo kt)")
  done
  [ ${#files[@]} -eq 0 ] && continue
  classes="$OUT/$lang-classes"
  mkdir -p "$classes"
  echo "compiling ${#files[@]} $lang files"
  if [ "$lang" = java ]; then
    "$JAVAC" -encoding UTF-8 -Xlint:all -Werror -d "$classes" "${files[@]}" > "$OUT/$lang.cc.err" 2>&1
  else
    "$KOTLINC" -Werror -d "$classes" "${files[@]}" > "$OUT/$lang.cc.err" 2>&1
  fi
  if [ $? -ne 0 ]; then
    echo "FAIL  $lang: does not compile"; grep -v '^warning: unable to find kotlin-stdlib' "$OUT/$lang.cc.err" | head -n 30
    failed=$((failed + ${#files[@]}))
    continue
  fi
  cp=("$classes")
  [ "$lang" = kotlin ] && cp=("$classes:$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar")
  for r in "${jvm_runs[@]}"; do
    read -r id l pkg input name <<< "$r"
    [ "$l" = "$lang" ] || continue
    main="$pkg.Program"; [ "$lang" = kotlin ] && main="$pkg.$(echo "$id" | sed 's/[^A-Za-z0-9_]/_/g; s/^./\u&/')Kt"
    "$JAVA_RUN" -cp "${cp[0]}" "$main" < "$input" > "$OUT/$id.out" 2> "$OUT/$id.err"
    echo $? > "$OUT/$id.rc"
    sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$id.err"
    compare "$id" "$name"
  done
  echo "done  $lang"
done

echo "$ok identical to the interpreter, $refused refused by fast values (text in variables), $failed failed"
[ "$failed" -eq 0 ]
