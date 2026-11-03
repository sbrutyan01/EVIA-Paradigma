package evia.paradigma;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a block program. Runs on a background thread; all UI interaction goes through {@link Host}.
 *
 * <p>Variables:
 * <ul>
 *   <li>{@code name} — program variable, visible everywhere (including inside custom blocks).</li>
 *   <li>{@code this.name} — field of the current object (custom block instance). Parameters are
 *       fields too. Fields start at 0 and keep their value between calls during one run, and every
 *       instance has its own fields — like objects of a class.</li>
 * </ul>
 */
final class Interpreter {

    /** What the interpreter needs from the outside world (the UI). */
    interface Host {
        void step(Block b);           // before each top-level block executes (highlight, delay)
        void print(String text);
        String input(String varName); // null = user cancelled
        boolean cancelled();
    }

    /**
     * Pre-parsed block contents: var for SET/INPUT/EXIT; expr for SET/PRINT and the condition of
     * IF/FOR/WHILE/DO_WHILE (null in a FOR means "always"); args for CUSTOM; init and step for FOR.
     */
    record Compiled(String var, Expr expr, List<Expr> args, Assign init, Assign step) {
        Compiled(String var, Expr expr, List<Expr> args) {
            this(var, expr, args, null, null);
        }
    }

    /** name = expression (the init and step parts of a For block). */
    record Assign(String var, Expr expr) {}

    static final int MAX_STEPS = 2_000_000;
    static final int MAX_DEPTH = 400;

    private static final String NAME = "(?:this\\.)?[A-Za-z_]\\w*";
    private static final Pattern ASSIGN = Pattern.compile("\\s*(" + NAME + ")\\s*=(?!=)(.*)", Pattern.DOTALL);
    private static final Pattern VAR = Pattern.compile(NAME);

    // ================================================================ compile

    static Compiled compile(Block b, String code) {
        return switch (b.type) {
            case START, END, ENTER -> new Compiled(null, null, null);
            case SET -> {
                Matcher m = ASSIGN.matcher(code);
                if (!m.matches()) throw new FlowException("expected  name = expression");
                yield new Compiled(m.group(1), ExprParser.parse(m.group(2)), null);
            }
            case INPUT -> {
                String name = code.trim();
                if (!VAR.matcher(name).matches()) throw new FlowException("expected a variable name");
                yield new Compiled(name, null, null);
            }
            case PRINT, IF, WHILE, DO_WHILE -> new Compiled(null, ExprParser.parse(code), null);
            case FOR -> {
                List<String> parts = splitTopLevel(code, ';');
                if (parts.size() != 3) {
                    throw new FlowException("expected  init; condition; step   e.g.  i = 1; i <= 10; i = i + 1");
                }
                Assign init = assignOrNull(parts.get(0), "init");
                Expr cond = parts.get(1).isBlank() ? null : ExprParser.parse(parts.get(1));
                Assign step = assignOrNull(parts.get(2), "step");
                yield new Compiled(null, cond, null, init, step);
            }
            case EXIT -> {
                String name = code.trim();
                if (!BlockDef.isIdentifier(name)) throw new FlowException("exit name must be a simple name, e.g. next, done, yes");
                yield new Compiled(name, null, null);
            }
            case CUSTOM -> {
                List<Expr> args = ExprParser.parseList(code);
                int want = b.def.params.size();
                if (args.size() != want) {
                    throw new FlowException(b.def.signature() + " expects " + want
                            + " argument" + (want == 1 ? "" : "s") + ", got " + args.size());
                }
                yield new Compiled(null, null, args);
            }
        };
    }

    private static Assign assignOrNull(String part, String what) {
        if (part.isBlank()) return null;
        Matcher m = ASSIGN.matcher(part);
        if (!m.matches()) throw new FlowException("the " + what + " part must look like  name = expression");
        return new Assign(m.group(1), ExprParser.parse(m.group(2)));
    }

    /** Splits on {@code sep}, ignoring separators inside "string literals". */
    static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && quoted && i + 1 < s.length()) {
                cur.append(ch).append(s.charAt(++i));
                continue;
            }
            if (ch == '"') quoted = !quoted;
            if (ch == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Returns an error message, or null if the code is valid for this block. */
    static String check(Block b, String code) {
        try {
            compile(b, code);
            return null;
        } catch (FlowException e) {
            return e.getMessage();
        }
    }

    /**
     * Checks the whole program before running, like a compiler pass:
     * every block of the main program and of every definition must compile,
     * every definition needs one Enter and at least one Exit.
     * Returns the Start block.
     */
    static Block prepare(Library lib) {
        for (BlockDef d : lib.defs()) {
            List<Block> enters = d.body.getBlocks().stream().filter(b -> b.type == BlockType.ENTER).toList();
            if (enters.size() != 1) {
                throw new FlowException(enters.isEmpty() ? null : enters.get(1),
                        "custom block '" + d.name + "' needs exactly one Enter block");
            }
            if (d.exitNames().isEmpty()) {
                throw new FlowException(enters.get(0), "custom block '" + d.name + "' has no Exit block");
            }
            compileAll(d.body.getBlocks());
            computeLoopBodies(d.body.getBlocks());
        }
        compileAll(lib.main.getBlocks());
        computeLoopBodies(lib.main.getBlocks());

        Block start = null;
        for (Block b : lib.main.getBlocks()) {
            if (b.type == BlockType.START) {
                if (start != null) throw new FlowException(b, "more than one Start block");
                start = b;
            }
        }
        if (start == null) throw new FlowException("no Start block - drag one from the palette");
        return start;
    }

    /**
     * For every loop block: the blocks reachable from its 'body' port without passing through
     * the loop block itself. Arriving at the loop from one of these is "the next pass";
     * arriving from anywhere else is entering the loop (For runs its init, Do-While its body).
     */
    static void computeLoopBodies(List<Block> blocks) {
        for (Block loop : blocks) {
            if (!loop.type.isLoop()) continue;
            Set<Block> body = new HashSet<>();
            Deque<Block> todo = new ArrayDeque<>();
            if (loop.next[0] != null && loop.next[0] != loop) todo.push(loop.next[0]);
            while (!todo.isEmpty()) {
                Block b = todo.pop();
                if (b == loop || !body.add(b)) continue;
                for (Block n : b.next) if (n != null) todo.push(n);
            }
            loop.loopBody = body;
        }
    }

    private static void compileAll(List<Block> blocks) {
        for (Block b : blocks) {
            try {
                b.compiled();
            } catch (FlowException e) {
                throw new FlowException(b, e.getMessage());
            }
        }
    }

    // ================================================================ runtime

    /** An object: the fields of one custom block instance, plus the objects nested inside it. */
    private static final class Obj {
        final Map<String, Object> fields = new HashMap<>();
        final Map<Block, Obj> children = new HashMap<>();
    }

    /** Thrown to stop the program (End block or Stop button). */
    private static final class Halt extends RuntimeException {
        Halt() {
            super(null, null, false, false);
        }
    }

    /** Variable scope while executing one graph: program variables + the current object's fields. */
    private final class Scope implements Expr.Env {
        final Obj obj;
        final BlockDef def;   // null at program level

        Scope(Obj obj, BlockDef def) {
            this.obj = obj;
            this.def = def;
        }

        @Override
        public Object get(String name) {
            if (name.startsWith("this.")) {
                requireObject();
                return obj.fields.getOrDefault(name.substring(5), 0.0);
            }
            Object v = globals.get(name);
            if (v == null) throw new FlowException("undefined variable '" + name + "'");
            return v;
        }

        void set(String name, Object value) {
            if (name.startsWith("this.")) {
                requireObject();
                obj.fields.put(name.substring(5), value);
            } else {
                globals.put(name, value);
            }
        }

        private void requireObject() {
            if (def == null) throw new FlowException("'this' can only be used inside a custom block");
        }
    }

    private final Map<String, Object> globals = new LinkedHashMap<>();
    private int steps;

    void run(Block start, Host host) {
        try {
            runGraph(start, new Scope(new Obj(), null), 0, host);
        } catch (Halt ignored) {
            // normal stop
        }
    }

    /**
     * Executes blocks from {@code cur} until an End (halts), an Exit (returns its name)
     * or a missing connection.
     */
    private String runGraph(Block cur, Scope scope, int depth, Host host) {
        Block prev = null;
        boolean selfPass = false;          // a loop with an empty body continues with itself
        while (true) {
            if (host.cancelled()) throw new Halt();
            if (++steps > MAX_STEPS) throw new FlowException(cur, "step limit exceeded (infinite loop?)");
            if (depth == 0) host.step(cur);

            Block nxt;
            try {
                Compiled c = cur.compiled();
                if (cur.type == BlockType.END) throw new Halt();
                if (cur.type == BlockType.EXIT) return c.var();

                boolean nextPass = selfPass || (prev != null && cur.loopBody.contains(prev));
                selfPass = false;
                nxt = switch (cur.type) {
                    case START, ENTER -> cur.next[0];
                    case FOR -> {
                        Assign a = nextPass ? c.step() : c.init();
                        if (a != null) scope.set(a.var(), Expr.eval(a.expr(), scope));
                        yield test(cur, c, scope);
                    }
                    case WHILE -> test(cur, c, scope);
                    case DO_WHILE -> nextPass ? test(cur, c, scope) : body(cur);
                    case SET -> {
                        scope.set(c.var(), Expr.eval(c.expr(), scope));
                        yield cur.next[0];
                    }
                    case PRINT -> {
                        host.print(Expr.str(Expr.eval(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case INPUT -> {
                        String s = host.input(c.var());
                        if (s == null) {
                            if (host.cancelled()) throw new Halt();
                            throw new FlowException("input cancelled");
                        }
                        Object v;
                        try {
                            v = Double.parseDouble(s.trim());
                        } catch (NumberFormatException e) {
                            v = s;
                        }
                        scope.set(c.var(), v);
                        yield cur.next[0];
                    }
                    case IF -> Expr.truthy(Expr.eval(c.expr(), scope)) ? cur.next[0] : cur.next[1];
                    case CUSTOM -> call(cur, c, scope, depth, host);
                    case END, EXIT -> throw new IllegalStateException();
                };
            } catch (FlowException e) {
                if (e.block == null) e.block = cur;
                throw e;
            }

            if (nxt == null) {
                if (scope.def == null) {
                    host.print("(warning: " + cur.title() + " block has no outgoing connection - program ends)");
                    return null;
                }
                throw new FlowException(cur, "custom block '" + scope.def.name
                        + "': this path ends without reaching an Exit block");
            }
            if (nxt == cur && cur.type.isLoop()) selfPass = true;
            prev = cur;
            cur = nxt;
        }
    }

    /** Loop condition: 'body' if true (or the loop itself if the body is empty), else 'done'. */
    private Block test(Block loop, Compiled c, Scope scope) {
        boolean go = c.expr() == null || Expr.truthy(Expr.eval(c.expr(), scope));
        return go ? body(loop) : loop.next[1];
    }

    private static Block body(Block loop) {
        return loop.next[0] != null ? loop.next[0] : loop;
    }

    /** Calls a custom block: evaluate arguments, bind them to the object's fields, run the body. */
    private Block call(Block inst, Compiled c, Scope scope, int depth, Host host) {
        if (depth + 1 > MAX_DEPTH) {
            throw new FlowException(inst, "too many nested calls of custom blocks (endless recursion?)");
        }
        List<Object> values = new ArrayList<>();
        for (Expr arg : c.args()) values.add(Expr.eval(arg, scope));

        // every instance (per enclosing object) is its own object with its own fields
        Obj obj = scope.obj.children.computeIfAbsent(inst, k -> new Obj());
        List<String> params = inst.def.params;
        for (int i = 0; i < params.size(); i++) obj.fields.put(params.get(i), values.get(i));

        String exit = runGraph(inst.def.enter(), new Scope(obj, inst.def), depth + 1, host);
        int port = inst.portIndex(exit);
        if (port < 0) throw new FlowException(inst, "unknown exit '" + exit + "'");
        Block nxt = inst.next[port];
        if (nxt == null && scope.def == null) {
            host.print("(warning: exit '" + exit + "' of " + inst.def.name + " is not connected - program ends)");
            throw new Halt();
        }
        return nxt;
    }

    Map<String, Object> variables() {
        return globals;
    }
}
