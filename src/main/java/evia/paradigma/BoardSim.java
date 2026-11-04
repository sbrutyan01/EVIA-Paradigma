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
 * </ul>
 */
final class BoardSim {

    /** The modes of a Pin Mode block. */
    static final Set<String> MODES = Set.of("OUTPUT", "INPUT", "INPUT_PULLUP");

    /** Names every board knows: HIGH, LOW, LED_BUILTIN (13 on the simulated Uno) and A0..A15. */
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
                return null;
        }
    }

    interface Events {
        void board(String event);
    }

    private final Events events;
    private long clock;
    private final Map<Integer, String> mode = new HashMap<>();
    private final Map<Integer, Integer> level = new HashMap<>();

    BoardSim(Events events) {
        this.events = events;
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
}
