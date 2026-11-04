package evia.paradigma;

import java.awt.Color;

/** The kinds of blocks. CUSTOM blocks get their name, color and ports from a {@link BlockDef}. */
public enum BlockType {
    //     title    color                      input  outputs code   default code       hint
    START("Start", new Color(126, 200, 128), false, 1, false, "",
            "Program starts here (only one allowed)."),
    SET("Set", new Color(132, 172, 240), true, 1, true, "x = 0",
            "Assign a variable:  name = expression   (inside a method: this.field = expression)"),
    INPUT("Input", new Color(196, 160, 230), true, 1, true, "n",
            "Ask the user for a value and store it in a variable:  name"),
    PRINT("Print", new Color(242, 202, 112), true, 1, true, "\"Hello\"",
            "Print an expression, e.g.  \"x = \" + x"),
    IF("If / Else", new Color(240, 142, 130), true, 2, true, "x < 10",
            "Condition. 'then' runs when it is true, 'else' when false. Wire both branches to the same block to join them."),

    // ---- loops: wire the end of the body back into the loop block; 'done' continues after the loop
    FOR("For", new Color(250, 168, 92), true, 2, true, "i = 1; i <= 10; i = i + 1",
            "init; condition; step   e.g.  i = 1; i <= 10; i = i + 1. Wire the end of the body back into this block."),
    WHILE("While", new Color(244, 186, 104), true, 2, true, "x < 10",
            "Condition, checked before every pass. Wire the end of the body back into this block."),
    DO_WHILE("Do-While", new Color(236, 154, 112), true, 2, true, "x < 10",
            "The body runs first; the condition, checked after every pass, decides whether to repeat. Wire the end of the body back."),
    END("End", new Color(165, 165, 170), true, 0, false, "",
            "Program stops here."),

    // ---- board: pins and time of a microcontroller (simulated when run on the computer)
    PIN_MODE("Pin Mode", new Color(110, 190, 150), true, 1, true, "LED_BUILTIN, OUTPUT",
            "pin, OUTPUT | INPUT | INPUT_PULLUP   e.g.  LED_BUILTIN, OUTPUT   or   4, INPUT_PULLUP"),
    DIGITAL_WRITE("Digital Write", new Color(96, 182, 142), true, 1, true, "LED_BUILTIN, HIGH",
            "pin, value   Sets a pin HIGH (any non-zero value) or LOW (0), e.g.  LED_BUILTIN, HIGH"),
    DIGITAL_READ("Digital Read", new Color(132, 202, 166), true, 1, true, "pressed = 4",
            "name = pin   Stores 1 if the pin is HIGH, 0 if LOW, e.g.  button = 4"),
    ANALOG_READ("Analog Read", new Color(142, 206, 186), true, 1, true, "level = A0",
            "name = pin   Stores the voltage on an analog pin: 0..1023 on AVR, 0..4095 on ESP32, e.g.  level = A0"),
    ANALOG_WRITE("Analog Write", new Color(104, 188, 164), true, 1, true, "5, 128",
            "pin, value   PWM output 0..255 (0 = off, 255 = always on), e.g.  5, brightness"),
    DELAY("Delay", new Color(162, 202, 140), true, 1, true, "500",
            "Waits this many milliseconds, e.g.  500"),
    MILLIS("Millis", new Color(178, 212, 150), true, 1, true, "t",
            "name   Stores the milliseconds since the program started, e.g.  t"),

    // ---- only inside custom block definitions
    ENTER("Enter", new Color(112, 204, 196), false, 1, false, "",
            "Where the method starts executing (one per method)."),
    EXIT("Exit", new Color(112, 204, 196), true, 0, true, "next",
            "Leaves the method. Each distinct exit name becomes an output port of the calling block."),

    // ---- an object: an instance of a user-defined block
    CUSTOM("Custom", Color.GRAY, true, -1, true, "",
            "Arguments for the method, separated by commas");

    final String title;
    final Color color;
    final boolean hasInput;
    final int outputs;
    final boolean hasCode;
    final String defaultCode;
    final String hint;

    /** Pin and time blocks (the Board group of the palette). */
    boolean isBoard() {
        return switch (this) {
            case PIN_MODE, DIGITAL_WRITE, DIGITAL_READ, ANALOG_READ, ANALOG_WRITE, DELAY, MILLIS -> true;
            default -> false;
        };
    }

    boolean isLoop() {
        return this == FOR || this == WHILE || this == DO_WHILE;
    }

    BlockType(String title, Color color, boolean hasInput, int outputs,
              boolean hasCode, String defaultCode, String hint) {
        this.title = title;
        this.color = color;
        this.hasInput = hasInput;
        this.outputs = outputs;
        this.hasCode = hasCode;
        this.defaultCode = defaultCode;
        this.hint = hint;
    }
}
