package evia.paradigma;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The board blocks when a program runs on the computer: a simulated Arduino Uno.
 *
 * <ul>
 *   <li>Time is virtual: it starts at 0 and only Delay moves it forward, so runs are repeatable.
 *       (The main window also really waits, so a blinking LED can be watched.)</li>
 *   <li>Output pins report changes ("pin 13 HIGH") on the board line of the output, never as program output.</li>
 *   <li>Inputs: a button on every INPUT / INPUT_PULLUP pin is pressed during the second half of each
 *       2-second period (INPUT_PULLUP then reads LOW, INPUT reads HIGH); an output pin reads back
 *       what was written. Analog pins read a slow triangle wave 0..1023..0 (2 seconds up, 2 down).</li>
 *   <li>Buses: the serial port is wired TX to RX (what UART Write sends comes back through UART Read), SPI
 *       has MISO wired to MOSI (Transfer returns the byte it sent) and I2C has one device at address 80
 *       (0x50) with 256 registers, all 0 at the start; any other address does not answer.</li>
 * </ul>
 */
final class BoardSim {

    /** The modes of a Pin Mode block. */
    static final Set<String> MODES = Set.of("OUTPUT", "INPUT", "INPUT_PULLUP");

    /** Names every board knows: HIGH, LOW, LED_BUILTIN (13 on the simulated Uno), A0..A15, RA0..RG15 (PIC32 pins) and PA0..PI15 (STM32 pins). */
    static boolean isConstant(String name) {
        return constant(name) != null;
    }

    /** Value of a board constant on the simulated Uno, or null. */
    static Double constant(String name) {
        switch (name) {
            case "HIGH": return 1.0;
            case "LOW": return 0.0;
            case "LED_BUILTIN": return 13.0;
            default:
                if (name.matches("A(\\d|1[0-5])")) return 14.0 + Integer.parseInt(name.substring(1));
                if (name.matches("R[A-G](\\d|1[0-5])")) return 16.0 * (name.charAt(1) - 'A') + Integer.parseInt(name.substring(2));
                if (name.matches("P[A-I](\\d|1[0-5])")) return 16.0 * (name.charAt(1) - 'A') + Integer.parseInt(name.substring(2));
                return fpgaPin(name);
        }
    }

    /**
     * The pin names of the FPGA boards, numbered the same on every board: LED0..LED15 = 0..15, SW0..SW15 = 16..31,
     * BTNC BTNU BTNL BTNR BTND = 32..36 (BTN0..BTN3 = 32..35), Pmod JA JB JC JD = 40.. in steps of 8
     * (pins 1 2 3 4 7 8 9 10 of the connector).
     */
    static Double fpgaPin(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(LED|SW|BTN|J[A-D])(\\d+)").matcher(name);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2));
            switch (m.group(1)) {
                case "LED": return n <= 15 ? (double) n : null;
                case "SW": return n <= 15 ? 16.0 + n : null;
                case "BTN": return n <= 3 ? 32.0 + n : null;
                default: {
                    int slot = m.group(1).charAt(1) - 'A';
                    int i = java.util.Arrays.binarySearch(new int[] {1, 2, 3, 4, 7, 8, 9, 10}, n);
                    return i < 0 ? null : 40.0 + 8 * slot + i;
                }
            }
        }
        switch (name) {
            case "BTNC": return 32.0;
            case "BTNU": return 33.0;
            case "BTNL": return 34.0;
            case "BTNR": return 35.0;
            case "BTND": return 36.0;
            default: return null;
        }
    }

    /** What a Phone Sensor block can read. */
    static final java.util.List<String> SENSORS = java.util.List.of("ACCEL_X", "ACCEL_Y", "ACCEL_Z", "GYRO_X", "GYRO_Y",
            "GYRO_Z", "LATITUDE", "LONGITUDE", "ALTITUDE", "GPS_ACCURACY", "GPS_FIX");

    /** The GPS readings need the location permission on the phone. */
    static boolean isGps(String sensor) {
        return sensor.startsWith("LAT") || sensor.startsWith("LON") || sensor.startsWith("ALT") || sensor.startsWith("GPS");
    }

    /**
     * A simulated phone lying on a table in Yerevan that rocks gently: the values follow the simulated clock, so
     * Run gives the same numbers every time. Rounded as the app rounds the real ones: 0.01 m/s\u00b2, 0.001 rad/s,
     * 6 decimals of a degree, 0.1 m.
     */
    double sensor(String name) {
        double t = clock / 1000.0;
        return switch (name) {
            case "ACCEL_X" -> round(0.6 * Math.sin(t), 2);
            case "ACCEL_Y" -> round(0.4 * Math.cos(t), 2);
            case "ACCEL_Z" -> round(9.81 + 0.05 * Math.sin(3 * t), 2);
            case "GYRO_X" -> round(0.02 * Math.cos(t), 3);
            case "GYRO_Y" -> round(-0.02 * Math.sin(t), 3);
            case "GYRO_Z" -> round(0.01, 3);
            case "LATITUDE" -> round(40.1772 + 0.0002 * Math.sin(t / 10), 6);
            case "LONGITUDE" -> round(44.5126 + 0.0002 * Math.cos(t / 10), 6);
            case "ALTITUDE" -> 990.0;
            case "GPS_ACCURACY" -> 5.0;
            case "GPS_FIX" -> 1.0;
            default -> throw new FlowException("unknown sensor " + name);
        };
    }

    static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    interface Events {
        void board(String event);
    }

    private final Events events;
    private final java.util.function.IntConsumer tx;
    private long clock;
    private final Map<Integer, String> mode = new HashMap<>();
    private final Map<Integer, Integer> level = new HashMap<>();

    BoardSim(Events events, java.util.function.IntConsumer tx) {
        this.events = events;
        this.tx = tx;
    }

    long millis() {
        return clock;
    }

    void delay(long ms) {
        if (ms > 0) clock += ms;
    }

    static int pin(double v) {
        if (Double.isNaN(v) || v < 0 || v > 255 || v != Math.rint(v)) {
            throw new FlowException("pin must be a whole number 0..255, got " + Expr.str(v));
        }
        return (int) v;
    }

    private static String pinName(int p) {
        return p >= 14 && p <= 21 ? "A" + (p - 14) + " (pin " + p + ")" : "pin " + p;
    }

    private void event(String s) {
        events.board("[" + clock + " ms] " + s);
    }

    void pinMode(int p, String m) {
        if (!m.equals(mode.put(p, m))) event(pinName(p) + " is " + m);
        if (!m.equals("OUTPUT")) level.remove(p);
    }

    void digitalWrite(int p, double v) {
        int l = v != 0 ? 1 : 0;
        Integer old = level.put(p, l);
        if (old == null || old != l) event(pinName(p) + (l == 1 ? " HIGH" : " LOW"));
    }

    void analogWrite(int p, double v) {
        int duty = (int) Math.max(0, Math.min(255, Math.rint(v)));
        Integer old = level.put(p, duty);
        if (old == null || old != duty) event(pinName(p) + " PWM " + duty + " (" + Math.round(duty * 100 / 255.0) + "%)");
    }

    double digitalRead(int p) {
        String m = mode.getOrDefault(p, "INPUT");
        if (m.equals("OUTPUT")) return level.getOrDefault(p, 0) != 0 ? 1 : 0;
        boolean pressed = clock % 2000 >= 1000;
        if (m.equals("INPUT_PULLUP")) return pressed ? 0 : 1;
        return pressed ? 1 : 0;
    }

    double analogRead(int p) {
        long t = clock % 4096;
        return t < 2048 ? t / 2 : (4095 - t) / 2;
    }

    // ---------------------------------------------------------------- buses

    private boolean uartOn, spiOn, i2cOn;
    private final java.util.ArrayDeque<Integer> rx = new java.util.ArrayDeque<>();
    private final int[] i2cDevice = new int[256];

    /** The address of the simulated I2C device. */
    static final int I2C_DEVICE = 80;

    private static int whole(double v, int max, String what) {
        if (Double.isNaN(v) || v < 0 || v > max || v != Math.rint(v)) {
            throw new FlowException(what + " must be a whole number 0.." + max + ", got " + Expr.str(v));
        }
        return (int) v;
    }

    private static String hex(int v) {
        return String.format("0x%02X", v);
    }

    private static int rate(double v, String what) {
        if (Double.isNaN(v) || v < 1 || v > 100_000_000 || v != Math.rint(v)) {
            throw new FlowException(what + " must be a whole number of Hz, 1..100000000, got " + Expr.str(v));
        }
        return (int) v;
    }

    void uartOpen(double baud) {
        int b = rate(baud, "baud");
        uartOn = true;
        event("UART open " + b + " baud");
    }

    void uartWrite(double v) {
        int b = whole(v, 255, "UART byte");
        rx.addLast(b);
        tx.accept(b);
        event("UART send " + b + (b >= 32 && b < 127 ? " '" + (char) b + "'" : ""));
    }

    /** The next received byte, or -1. */
    double uartRead() {
        Integer b = rx.pollFirst();
        return b == null ? -1 : b;
    }

    void spiOpen(double hz) {
        int f = rate(hz, "SPI clock");
        spiOn = true;
        event("SPI open " + f + " Hz, mode 0");
    }

    double spiTransfer(double v) {
        if (!spiOn) throw new FlowException("SPI Transfer needs an SPI Open block before it");
        int b = whole(v, 255, "SPI byte");
        event("SPI send " + b + ", received " + b);
        return b;
    }

    void i2cOpen(double hz) {
        int f = rate(hz, "I2C clock");
        i2cOn = true;
        event("I2C open " + f + " Hz");
    }

    void i2cWrite(double addr, double reg, double v) {
        if (!i2cOn) throw new FlowException("I2C Write needs an I2C Open block before it");
        int a = whole(addr, 127, "I2C address"), r = whole(reg, 255, "I2C register"), b = whole(v, 255, "I2C value");
        if (a != I2C_DEVICE) {
            event("I2C " + hex(a) + " does not answer");
            return;
        }
        i2cDevice[r] = b;
        event("I2C write " + hex(a) + "[" + r + "] = " + b);
    }

    // ---------------------------------------------------------------- MQTT

    private boolean mqttOn;
    private final java.util.LinkedHashMap<String, java.util.ArrayDeque<Double>> topics = new java.util.LinkedHashMap<>();

    private static String topic(String t) {
        String s = t == null ? "" : t.trim();
        if (s.isEmpty()) throw new FlowException("the topic must be a text, e.g. \"evia/value\"");
        return s;
    }

    /** Joins the network and connects to the broker. The simulated broker always answers. */
    void mqttConnect(String network, String password, String broker, double port) {
        int p = whole(port, 65535, "MQTT port");
        if (broker == null || broker.isBlank()) throw new FlowException("the broker must be a text, e.g. \"broker.example.com\"");
        mqttOn = true;
        event("MQTT connect " + broker + ":" + p + " over " + (network == null || network.isBlank() ? "the network" : network));
    }

    /** Sends a message. A number is sent as text, as Print writes it; a subscribed topic receives it back. */
    void mqttPublish(String t, Object value) {
        if (!mqttOn) throw new FlowException("MQTT Publish needs an MQTT Connect block before it");
        String name = topic(t), text = Expr.str(value);
        java.util.ArrayDeque<Double> q = topics.get(name);
        if (q != null) q.addLast(value instanceof Double d ? d : parse(text));
        event("MQTT publish " + name + " = " + text);
    }

    void mqttSubscribe(String t) {
        if (!mqttOn) throw new FlowException("MQTT Subscribe needs an MQTT Connect block before it");
        String name = topic(t);
        topics.computeIfAbsent(name, k -> new java.util.ArrayDeque<>());
        event("MQTT subscribe " + name);
    }

    /** 1 when a message of that topic is waiting, 0 when none is. */
    double mqttHas(String t) {
        if (!mqttOn) throw new FlowException("MQTT Has Message needs an MQTT Connect block before it");
        java.util.ArrayDeque<Double> q = topics.get(topic(t));
        return q != null && !q.isEmpty() ? 1 : 0;
    }

    /** The number in the next message of that topic, or -1 when none has arrived. */
    double mqttRead(String t) {
        if (!mqttOn) throw new FlowException("MQTT Read needs an MQTT Connect block before it");
        String name = topic(t);
        java.util.ArrayDeque<Double> q = topics.get(name);
        if (q == null) throw new FlowException("MQTT Read needs an MQTT Subscribe block for " + name + " before it");
        Double v = q.pollFirst();
        if (v == null) return -1;
        event("MQTT message " + name + " = " + Expr.str(v));
        return v;
    }

    /** The number in a message's text, or -1 when it does not start with one (the boards do the same). */
    private static double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A register of the device, or -1 if the address does not answer. */
    double i2cRead(double addr, double reg) {
        if (!i2cOn) throw new FlowException("I2C Read needs an I2C Open block before it");
        int a = whole(addr, 127, "I2C address"), r = whole(reg, 255, "I2C register");
        if (a != I2C_DEVICE) {
            event("I2C " + hex(a) + " does not answer");
            return -1;
        }
        event("I2C read " + hex(a) + "[" + r + "] = " + i2cDevice[r]);
        return i2cDevice[r];
    }
}
