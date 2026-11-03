package evia.paradigma;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A user-defined block — the "class". Its body is an ordinary block graph
 * (edited on its own canvas) that starts at an Enter block and leaves through
 * Exit blocks. Every Exit name becomes an output port of the instances.
 *
 * <p>Each instance placed on a canvas is an "object": parameters and
 * {@code this.field} variables live in that object and keep their values
 * between calls during one run.
 */
final class BlockDef {
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    final int id;
    String name;
    Color color;
    List<String> params;
    final FlowCanvas body;

    BlockDef(Library lib, int id, String name, Color color, List<String> params) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.params = List.copyOf(params);
        this.body = new FlowCanvas(lib, this);
    }

    /** The Enter block of the body, or null if it is missing. */
    Block enter() {
        for (Block b : body.getBlocks()) if (b.type == BlockType.ENTER) return b;
        return null;
    }

    /** Output port names: distinct Exit names, ordered left to right as they sit in the body. */
    List<String> exitNames() {
        return body.getBlocks().stream()
                .filter(b -> b.type == BlockType.EXIT)
                .sorted(Comparator.comparingInt(Block::getX).thenComparingInt(b -> b.id))
                .map(b -> b.getCode().trim())
                .distinct()
                .toList();
    }

    String signature() {
        return name + "(" + String.join(", ", params) + ")";
    }

    /** Parses "a, b, c" into a parameter list; throws IllegalArgumentException with a readable message. */
    static List<String> parseParams(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split(",")) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (!IDENT.matcher(s).matches()) throw new IllegalArgumentException("'" + s + "' is not a valid parameter name");
            if (out.contains(s)) throw new IllegalArgumentException("parameter '" + s + "' appears twice");
            out.add(s);
        }
        return out;
    }

    static boolean isIdentifier(String s) {
        return IDENT.matcher(s).matches();
    }
}
