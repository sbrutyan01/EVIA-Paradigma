package evia.paradigma;

import java.awt.Color;

/** The kinds of blocks. CUSTOM blocks get their name, color and ports from a {@link BlockDef}. */
public enum BlockType {
    //     title    color                      input  outputs code   default code       hint
    START("Start", new Color(126, 200, 128), false, 1, false, "",
            "Program starts here (only one allowed)."),
    SET("Set", new Color(132, 172, 240), true, 1, true, "x = 0",
            "Assign a variable:  name = expression   (inside a custom block: this.field = expression)"),
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

    // ---- only inside custom block definitions
    ENTER("Enter", new Color(112, 204, 196), false, 1, false, "",
            "Where the custom block starts executing (one per definition)."),
    EXIT("Exit", new Color(112, 204, 196), true, 0, true, "next",
            "Leaves the custom block. Each distinct exit name becomes an output port."),

    // ---- an object: an instance of a user-defined block
    CUSTOM("Custom", Color.GRAY, true, -1, true, "",
            "Arguments for the custom block, separated by commas");

    final String title;
    final Color color;
    final boolean hasInput;
    final int outputs;
    final boolean hasCode;
    final String defaultCode;
    final String hint;

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
