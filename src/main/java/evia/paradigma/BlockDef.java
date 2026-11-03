package evia.paradigma;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A class: fields with initial values, and named methods whose bodies are block graphs.
 *
 * <p>Every custom block placed on a canvas calls one method of one object of the class.
 * Blocks with the same object name share that object; a block without an object name is an
 * object of its own. An object is created the first time it is used: its fields get their
 * initial values (undeclared {@code this.x} fields start at 0), then the constructor - a method
 * named {@code init}, if there is one - runs.
 */
final class BlockDef {
    static final String CONSTRUCTOR = "init";
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    /** A declared field and the expression that initialises it. */
    record Field(String name, String init) {}

    final int id;
    String name;
    Color color;
    /** Module file (relative to the program file) this class is stored in, or null if it lives in the program. */
    String module;
    final List<Field> fields = new ArrayList<>();
    final List<Method> methods = new ArrayList<>();
    /** Parsed initial values of {@link #fields}, same order; filled by Interpreter.prepare. */
    final List<Expr> fieldInits = new ArrayList<>();
    private final Library lib;

    BlockDef(Library lib, int id, String name, Color color) {
        this.lib = lib;
        this.id = id;
        this.name = name;
        this.color = color;
    }

    Method addMethod(String name, List<String> params) {
        Method m = new Method(lib, this, name, params);
        methods.add(m);
        return m;
    }

    Method method(String name) {
        for (Method m : methods) if (m.name.equals(name)) return m;
        return null;
    }

    /** Methods a block can call: all except the constructor. */
    List<Method> callable() {
        return methods.stream().filter(m -> !m.isConstructor()).toList();
    }

    /** The method a new block calls: the first callable one. */
    Method defaultMethod() {
        List<Method> c = callable();
        return c.isEmpty() ? null : c.get(0);
    }

    Method constructor() {
        return method(CONSTRUCTOR);
    }

    /** Short description used in the palette and tooltips, e.g. "Average(x)" or "Counter". */
    String signature() {
        List<Method> c = callable();
        if (c.size() == 1 && c.get(0).name.equals("run")) return name + "(" + String.join(", ", c.get(0).params) + ")";
        return name;
    }

    boolean hasField(String f) {
        for (Field x : fields) if (x.name().equals(f)) return true;
        return false;
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
        return s != null && IDENT.matcher(s).matches();
    }
}
