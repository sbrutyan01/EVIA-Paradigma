#!/usr/bin/env bash
# The generated MQTT client against a real broker on this computer.
#
#  The chip's network functions are replaced by an ordinary socket (scripts/mqtt/harness.c), so what is
#  checked is the part that is the same on every board: the MQTT packets it writes and the messages it
#  reads back. The WiFi module and the chip's UART are not part of this: those need hardware.
#
# Usage: scripts/check-mqtt.sh [jar]
set -uo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
OUT=build/mqtt
CC="${CC:-cc}"
rm -rf "$OUT" && mkdir -p "$OUT"
fails=0

java -jar "$JAR" --gen examples/boards/mqtt_sensor.evia --lang pic32 --board pic32mx250f128b --net uart -o "$OUT/program.c" > /dev/null
python3 scripts/mqtt/extract.py "$OUT/program.c" > "$OUT/core.c"
cp scripts/mqtt/harness.c "$OUT/harness.c"
"$CC" -std=c11 -Wall -Wextra -Werror -I"$OUT" -o "$OUT/harness" "$OUT/harness.c" -lm || { echo "FAIL  the MQTT client does not compile"; exit 1; }

python3 scripts/mqtt/broker.py > "$OUT/broker.log" 2>&1 &
broker=$!
for _ in $(seq 50); do grep -q "^port " "$OUT/broker.log" && break; sleep 0.1; done
port=$(awk '/^port /{print $2; exit}' "$OUT/broker.log")
[ -n "$port" ] || { echo "FAIL  the test broker did not start"; kill $broker 2>/dev/null; exit 1; }

"$OUT/harness" "$port" > "$OUT/client.log" 2>&1
sleep 0.3
kill $broker 2>/dev/null
wait $broker 2>/dev/null

check() {   # a line that must be in a log
  if grep -qF "$2" "$OUT/$1.log"; then echo "ok    $2"; else echo "FAIL  $1: $2"; fails=$((fails + 1)); fi
}
check broker "connect MQTT level 4 client evia-paradigma"
check broker "subscribe evia/command qos 0"
check broker "publish evia/greeting = hello"
check broker "publish evia/temperature = 21.5"
check broker "publish evia/count = -7"
check client "connected"
check client "command 42"
check client "command -3.5"
check client "missing -1"

# 2. the ESP32 sketch: C++ against stub headers, so a mistake shows up without an ESP32 or its board core
java -jar "$JAR" --gen examples/boards/mqtt_sensor.evia --lang arduino --board esp32 -o "$OUT/sketch.cpp" > /dev/null
if "${CXX:-c++}" -std=gnu++17 -I scripts/mqtt/arduino-stubs -include scripts/mqtt/arduino-stubs/Arduino.h \
      -Wall -Wextra -fsyntax-only "$OUT/sketch.cpp" 2> "$OUT/sketch.log"; then
  echo "ok    the ESP32 sketch compiles as C++"
else
  echo "FAIL  the ESP32 sketch does not compile"; head -10 "$OUT/sketch.log"; fails=$((fails + 1))
fi

# 3. the Harmony application for the Ethernet path: C against our own stub headers, since Microchip's
#    are not redistributable. It catches our mistakes, not a mismatch with the real Harmony API.
java -jar "$JAR" --gen examples/boards/mqtt_esk2.evia --lang pic32 --board pic32-esk2 --net ethernet \
     -o "$OUT/harmony.c" > /dev/null
if "${CC:-cc}" -std=gnu99 -I scripts/mqtt/harmony-stubs -Wall -Wextra -fsyntax-only "$OUT/harmony.c" 2> "$OUT/harmony.log"; then
  echo "ok    the Harmony application compiles"
else
  echo "FAIL  the Harmony application does not compile"; head -10 "$OUT/harmony.log"; fails=$((fails + 1))
fi
for want in 'void app_run(void)' 'TCPIP_TCP_ClientOpen' 'SYS_Tasks()'; do
  if grep -qF "$want" "$OUT/harmony.c"; then echo "ok    the Harmony application has $want"
  else echo "FAIL  the Harmony application is missing $want"; fails=$((fails + 1)); fi
done
if grep -q '#pragma config' "$OUT/harmony.c"; then
  echo "FAIL  the Harmony application sets the device configuration, which belongs to the project"; fails=$((fails + 1))
else
  echo "ok    the device configuration is left to the Harmony project"
fi

if [ "$fails" = 0 ]; then echo "all MQTT checks passed"; else echo "$fails MQTT check(s) failed"; cat "$OUT/broker.log" "$OUT/client.log"; fi
exit $((fails > 0))
