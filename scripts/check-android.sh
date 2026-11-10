#!/usr/bin/env bash
# Android apps. For every program in examples/ and tests/programs/, both flows and both value modes:
# write the app's project (--android), compile its MainActivity.kt with kotlinc against an Android
# platform, then run the program part on this computer with a console in place of the screen and
# require the interpreter's stdout, stderr and exit code (the same rules as check-targets.sh).
#
# The Android classes come from the SDK's platforms/*/android.jar (ANDROID_HOME, ANDROID_SDK_ROOT or
# EVIA Paradigma's own SDK), or from ANDROID_JAR, e.g. Robolectric's android-all jar from Maven Central.
# With an SDK that has build-tools, one app is also built into an APK by the application's own build
# and checked with apksigner verify.
#
# Usage: [KOTLINC=kotlinc] [ANDROID_JAR=android.jar] scripts/check-android.sh [jar]
set -uo pipefail

JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
KOTLINC="${KOTLINC:-kotlinc}"
OUT=build/android-checks
rm -rf "$OUT" && mkdir -p "$OUT"

if ! "$KOTLINC" -version > /dev/null 2>&1; then echo "SKIP  no kotlinc ('$KOTLINC')"; exit 0; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.evia-paradigma/android-sdk}}"
PLATFORM_JAR="${ANDROID_JAR:-$(ls "$SDK"/platforms/*/android.jar 2> /dev/null | sort -V | tail -n 1)}"
if [ -z "$PLATFORM_JAR" ] || [ ! -f "$PLATFORM_JAR" ]; then
  echo "SKIP  no Android platform: set ANDROID_HOME to an SDK, or ANDROID_JAR to an android.jar"
  exit 0
fi
STDLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
echo "android.jar: $PLATFORM_JAR"
ok=0; refused=0; failed=0; n=0
runs=()
files=()

for src in examples/*.evia tests/programs/*.evia; do
  name=$(basename "$src" .evia)
  dir=$(dirname "$src")
  input="$dir/$name.input"
  [ -f "$input" ] || input=/dev/null
  java -jar "$JAR" --run "$src" < "$input" > "$OUT/$name.expected.out" 2> "$OUT/$name.expected.err"
  echo $? > "$OUT/$name.expected.rc"
  sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$name.expected.err"
  for flow in structured goto; do
    for values in safe fast; do
      [[ "$name" == err_* && "$values" == fast ]] && continue
      n=$((n + 1))
      pkg="am.evia.check$n"
      id="$name.$flow.$values"
      if ! java -jar "$JAR" --android "$src" "$OUT/$id" --package "$pkg" --flow "$flow" --values "$values" \
            > /dev/null 2> "$OUT/$id.gen.err"; then
        if [ "$values" = fast ] && grep -q "fast values only hold numbers" "$OUT/$id.gen.err"; then
          refused=$((refused + 1)); continue
        fi
        echo "FAIL  $id: the project was not written"; cat "$OUT/$id.gen.err"; failed=$((failed + 1)); continue
      fi
      # the screen replaced by the console; the rest is the app's own code
      cat > "$OUT/$id/Harness.kt" <<EOF
package $pkg

fun main() {
    val input = java.io.BufferedReader(java.io.InputStreamReader(System.\`in\`, Charsets.UTF_8))
    val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
    val err = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8")
    rt_screen = object : RtScreen {
        override fun write(text: String, error: Boolean) { if (error) err.print(text) else out.print(text) }
        override fun read(): String? = input.readLine()
    }
    rt_reset()
    var code = 0
    try {
        Program().run()
    } catch (e: EviaError) {
        rt_error(e.message ?: "")
        code = 1
    }
    out.flush()
    System.exit(code)
}
EOF
      files+=("$OUT/$id/app/src/main/kotlin/${pkg//.//}/MainActivity.kt" "$OUT/$id/Harness.kt")
      runs+=("$id $pkg $input $name")
    done
  done
done

echo "compiling ${#runs[@]} apps"
if ! "$KOTLINC" -Werror -jvm-target 1.8 -classpath "$PLATFORM_JAR" -d "$OUT/classes" "${files[@]}" > "$OUT/kotlinc.err" 2>&1; then
  echo "FAIL  the apps do not compile"; grep -v 'unable to find kotlin-stdlib' "$OUT/kotlinc.err" | head -n 30
  exit 1
fi
for r in "${runs[@]}"; do
  read -r id pkg input name <<< "$r"
  java -cp "$OUT/classes:$STDLIB" "$pkg.HarnessKt" < "$input" > "$OUT/$id.out" 2> "$OUT/$id.err"
  echo $? > "$OUT/$id.rc"
  sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$id.err"
  if diff -q "$OUT/$name.expected.out" "$OUT/$id.out" > /dev/null && diff -q "$OUT/$name.expected.err" "$OUT/$id.err" > /dev/null \
     && diff -q "$OUT/$name.expected.rc" "$OUT/$id.rc" > /dev/null; then
    ok=$((ok + 1))
  else
    echo "FAIL  $id: differs from the interpreter"
    diff "$OUT/$name.expected.out" "$OUT/$id.out" | head -n 10
    diff "$OUT/$name.expected.err" "$OUT/$id.err" | head -n 5
    failed=$((failed + 1))
  fi
done
echo "$ok apps behave like the interpreter, $refused refused by fast values (text in variables), $failed failed"

# ---------------------------------------------------------------- one real APK, when the SDK is complete
BT=$(ls -d "$SDK"/build-tools/*/ 2> /dev/null | sort -V | tail -n 1)
if [ -n "$BT" ] && [ -f "$BT/lib/apksigner.jar" ] && [ -z "${ANDROID_JAR:-}" ]; then
  if ANDROID_HOME="$SDK" java -jar "$JAR" --android examples/counters.evia "$OUT/apk" --package am.evia.counters \
        --build > "$OUT/apk.log" 2>&1 && java -jar "$BT/lib/apksigner.jar" verify "$OUT/apk/build/counters-debug.apk"; then
    echo "ok    built and signed $(du -h "$OUT/apk/build/counters-debug.apk" | cut -f1) counters-debug.apk"
  else
    echo "FAIL  the APK was not built"; tail -n 20 "$OUT/apk.log"; failed=$((failed + 1))
  fi
else
  echo "note  no build-tools in $SDK, so no APK is built (Settings > Android > Get Android SDK)"
fi
[ "$failed" -eq 0 ]
