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
        /** A change on the simulated board (pin levels, modes); not program output. */
        default void board(String event) { }
        /** A Delay block: the main window really waits; tests and the command line don't. */
        default void sleep(long ms) { }
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
                yield new Compiled(variable(m.group(1)), ExprParser.parse(m.group(2)), null);
            }
            case INPUT, MILLIS -> {
                String name = code.trim();
                if (!VAR.matcher(name).matches()) throw new FlowException("expected a variable name");
                yield new Compiled(variable(name), null, null);
            }
            case DIGITAL_READ, ANALOG_READ -> {
                Matcher m = ASSIGN.matcher(code);
                if (!m.matches()) throw new FlowException("expected  name = pin   e.g.  " + b.type.defaultCode);
                yield new Compiled(variable(m.group(1)), ExprParser.parse(m.group(2)), null);
            }
            case PIN_MODE -> {
                List<String> parts = splitTopLevel(code, ',');
                String mode = parts.size() == 2 ? parts.get(1).trim() : "";
                if (parts.size() != 2 || !BoardSim.MODES.contains(mode)) {
                    throw new FlowException("expected  pin, OUTPUT   or  pin, INPUT   or  pin, INPUT_PULLUP");
                }
                yield new Compiled(mode, ExprParser.parse(parts.get(0)), null);
            }
            case DIGITAL_WRITE, ANALOG_WRITE -> {
                List<String> parts = splitTopLevel(code, ',');
                if (parts.size() != 2) throw new FlowException("expected  pin, value   e.g.  " + b.type.defaultCode);
                yield new Compiled(null, ExprParser.parse(parts.get(0)), List.of(ExprParser.parse(parts.get(1))));
            }
            case DELAY, UART_OPEN, UART_WRITE, SPI_OPEN, I2C_OPEN -> new Compiled(null, ExprParser.parse(code), null);
            case UART_READ -> {
                String name = code.trim();
                if (!VAR.matcher(name).matches()) throw new FlowException("expected a variable name");
                yield new Compiled(variable(name), null, null);
            }
            case SPI_TRANSFER -> {
                Matcher m = ASSIGN.matcher(code);
                if (!m.matches()) throw new FlowException("expected  name = byte   e.g.  " + b.type.defaultCode);
                yield new Compiled(variable(m.group(1)), ExprParser.parse(m.group(2)), null);
            }
            case I2C_WRITE -> {
                List<String> parts = splitTopLevel(code, ',');
                if (parts.size() != 3) throw new FlowException("expected  address, register, value   e.g.  " + b.type.defaultCode);
                yield new Compiled(null, ExprParser.parse(parts.get(0)),
                        List.of(ExprParser.parse(parts.get(1)), ExprParser.parse(parts.get(2))));
            }
            case I2C_READ -> {
                Matcher m = ASSIGN.matcher(code);
                List<String> parts = m.matches() ? splitTopLevel(m.group(2), ',') : List.of();
                if (parts.size() != 2) throw new FlowException("expected  name = address, register   e.g.  " + b.type.defaultCode);
                yield new Compiled(variable(m.group(1)), ExprParser.parse(parts.get(0)), List.of(ExprParser.parse(parts.get(1))));
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
                Method m = b.method();
                if (m == null) throw new FlowException("class " + b.def.name + " has no method to call");
                if (b.methodName() != null && b.def.method(b.methodName()) == null) {
                    throw new FlowException("class " + b.def.name + " has no method '" + b.methodName() + "'");
                }
                if (m.isConstructor()) throw new FlowException("the constructor " + m.qualified() + " runs by itself when the object is created; it can't be called");
                if (!b.objectName().isEmpty() && !BlockDef.isIdentifier(b.objectName())) {
                    throw new FlowException("object name '" + b.objectName() + "' must be a simple name");
                }
                List<Expr> args = ExprParser.parseList(code);
                int want = m.params.size();
                if (args.size() != want) {
                    throw new FlowException(m.qualified() + "(" + String.join(", ", m.params) + ") expects " + want
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
        return new Assign(variable(m.group(1)), ExprParser.parse(m.group(2)));
    }

    /** A name that can be assigned: not a board constant such as HIGH or A0. */
    private static String variable(String name) {
        if (BoardSim.isConstant(name)) throw new FlowException(name + " is a board constant; choose another variable name");
        return name;
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
            if (d.callable().isEmpty()) throw new FlowException("class " + d.name + " has no method that can be called");
            Set<String> names = new HashSet<>();
            for (Method m : d.methods) {
                if (!BlockDef.isIdentifier(m.name)) throw new FlowException("method name '" + m.name + "' of class " + d.name + " must be a simple name");
                if (!names.add(m.name)) throw new FlowException("class " + d.name + " has two methods named " + m.name);
                if (m.isConstructor() && !m.params.isEmpty()) {
                    throw new FlowException("the constructor " + m.qualified() + " can't have parameters");
                }
                List<Block> enters = m.body.getBlocks().stream().filter(b -> b.type == BlockType.ENTER).toList();
                if (enters.size() != 1) {
                    throw new FlowException(enters.isEmpty() ? null : enters.get(1),
                            "method " + m.qualified() + " needs exactly one Enter block");
                }
                if (m.exitNames().isEmpty()) {
                    throw new FlowException(enters.get(0), "method " + m.qualified() + " has no Exit block");
                }
                compileAll(m.body.getBlocks());
                computeLoopBodies(m.body.getBlocks());
            }
            d.fieldInits.clear();
            Set<String> fieldNames = new HashSet<>();
            for (BlockDef.Field f : d.fields) {
                if (!BlockDef.isIdentifier(f.name())) throw new FlowException("field name '" + f.name() + "' of class " + d.name + " must be a simple name");
                if (!fieldNames.add(f.name())) throw new FlowException("class " + d.name + " declares field " + f.name() + " twice");
                try {
                    d.fieldInits.add(ExprParser.parse(f.init().isBlank() ? "0" : f.init()));
                } catch (FlowException e) {
                    throw new FlowException("initial value of field " + d.name + "." + f.name() + ": " + e.getMessage());
                }
            }
            List<Block> all = new ArrayList<>();
            for (Method m : d.methods) all.addAll(m.body.getBlocks());
            checkObjectNames(all);
        }
        compileAll(lib.main.getBlocks());
        computeLoopBodies(lib.main.getBlocks());
        checkObjectNames(lib.main.getBlocks());

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

    /** Within one program (or one class), an object name always refers to an object of the same class. */
    private static void checkObjectNames(List<Block> blocks) {
        Map<String, BlockDef> classOf = new HashMap<>();
        for (Block b : blocks) {
            if (b.def == null || b.objectName().isEmpty()) continue;
            BlockDef prev = classOf.putIfAbsent(b.objectName(), b.def);
            if (prev != null && prev != b.def) {
                throw new FlowException(b, "object " + b.objectName() + " is a " + prev.name + " elsewhere, not a " + b.def.name);
            }
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
        final Map<Object, Obj> children = new HashMap<>();   // key: the calling block, or "obj:" + object name
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
        final Method method;   // null at program level

        Scope(Obj obj, Method method) {
            this.obj = obj;
            this.method = method;
        }

        @Override
        public Object get(String name) {
            if (name.startsWith("this.")) {
                requireObject();
                return obj.fields.getOrDefault(name.substring(5), 0.0);
            }
            Object v = globals.get(name);
            if (v == null) v = BoardSim.constant(name);
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
            if (method == null) throw new FlowException("'this' can only be used inside a class method");
        }
    }

    private final Map<String, Object> globals = new LinkedHashMap<>();
    private int steps;
    private BoardSim board;

    /**
     * The serial port is also the console: bytes sent by UART Write show up in the output as lines
     * (a line ends at byte 10; 13 is dropped), in order with what Print writes.
     */
    private static final class SerialHost implements Host {
        private final Host inner;
        private final StringBuilder line = new StringBuilder();

        SerialHost(Host inner) { this.inner = inner; }

        void tx(int b) {
            if (b == 10) flush();
            else if (b != 13) line.append(b < 32 || b > 126 ? '.' : (char) b);
        }

        void flush() {
            if (line.length() > 0) {
                inner.print(line.toString());
                line.setLength(0);
            }
        }

        @Override public void step(Block b) { inner.step(b); }
        @Override public void print(String text) { flush(); inner.print(text); }
        @Override public String input(String varName) { flush(); return inner.input(varName); }
        @Override public boolean cancelled() { return inner.cancelled(); }
        @Override public void board(String event) { inner.board(event); }
        @Override public void sleep(long ms) { inner.sleep(ms); }
    }

    void run(Block start, Host real) {
        SerialHost host = new SerialHost(real);
        board = new BoardSim(host::board, host::tx);
        try {
            runGraph(start, new Scope(new Obj(), null), 0, host);
        } catch (Halt ignored) {
            // normal stop
        } finally {
            host.flush();
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
                    case PIN_MODE -> {
                        board.pinMode(pinOf(c.expr(), scope), c.var());
                        yield cur.next[0];
                    }
                    case DIGITAL_WRITE -> {
                        board.digitalWrite(pinOf(c.expr(), scope), Expr.num(Expr.eval(c.args().get(0), scope)));
                        yield cur.next[0];
                    }
                    case ANALOG_WRITE -> {
                        board.analogWrite(pinOf(c.expr(), scope), Expr.num(Expr.eval(c.args().get(0), scope)));
                        yield cur.next[0];
                    }
                    case DIGITAL_READ -> {
                        scope.set(c.var(), board.digitalRead(pinOf(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case ANALOG_READ -> {
                        scope.set(c.var(), board.analogRead(pinOf(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case DELAY -> {
                        double ms = Expr.num(Expr.eval(c.expr(), scope));
                        if (ms > 0) {
                            board.delay((long) ms);
                            host.sleep((long) ms);
                        }
                        yield cur.next[0];
                    }
                    case MILLIS -> {
                        scope.set(c.var(), (double) board.millis());
                        yield cur.next[0];
                    }
                    case UART_OPEN -> {
                        board.uartOpen(Expr.num(Expr.eval(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case UART_WRITE -> {
                        board.uartWrite(Expr.num(Expr.eval(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case UART_READ -> {
                        scope.set(c.var(), board.uartRead());
                        yield cur.next[0];
                    }
                    case SPI_OPEN -> {
                        board.spiOpen(Expr.num(Expr.eval(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case SPI_TRANSFER -> {
                        scope.set(c.var(), board.spiTransfer(Expr.num(Expr.eval(c.expr(), scope))));
                        yield cur.next[0];
                    }
                    case I2C_OPEN -> {
                        board.i2cOpen(Expr.num(Expr.eval(c.expr(), scope)));
                        yield cur.next[0];
                    }
                    case I2C_WRITE -> {
                        board.i2cWrite(Expr.num(Expr.eval(c.expr(), scope)), Expr.num(Expr.eval(c.args().get(0), scope)),
                                Expr.num(Expr.eval(c.args().get(1), scope)));
                        yield cur.next[0];
                    }
                    case I2C_READ -> {
                        scope.set(c.var(), board.i2cRead(Expr.num(Expr.eval(c.expr(), scope)), Expr.num(Expr.eval(c.args().get(0), scope))));
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
                if (scope.method == null) {
                    host.print("(warning: " + cur.title() + " block has no outgoing connection - program ends)");
                    return null;
                }
                throw new FlowException(cur, "method " + scope.method.qualified()
                        + ": this path ends without reaching an Exit block");
            }
            if (nxt == cur && cur.type.isLoop()) selfPass = true;
            prev = cur;
            cur = nxt;
        }
    }

    private static int pinOf(Expr e, Scope scope) {
        return BoardSim.pin(Expr.num(Expr.eval(e, scope)));
    }

    /** Loop condition: 'body' if true (or the loop itself if the body is empty), else 'done'. */
    private Block test(Block loop, Compiled c, Scope scope) {
        boolean go = c.expr() == null || Expr.truthy(Expr.eval(c.expr(), scope));
        return go ? body(loop) : loop.next[1];
    }

    private static Block body(Block loop) {
        return loop.next[0] != null ? loop.next[0] : loop;
    }

    /**
     * Calls a method: find or create the object (fields get their initial values, then the
     * constructor runs), evaluate the arguments, bind them to the parameters and run the body.
     */
    private Block call(Block inst, Compiled c, Scope scope, int depth, Host host) {
        if (depth + 1 > MAX_DEPTH) {
            throw new FlowException(inst, "too many nested method calls (endless recursion?)");
        }
        Method m = inst.method();
        // a named object is shared by all blocks with that name; otherwise every block has its own
        Object key = inst.objectName().isEmpty() ? inst : "obj:" + inst.objectName();
        Obj obj = scope.obj.children.get(key);
        if (obj == null) {
            obj = new Obj();
            scope.obj.children.put(key, obj);
            construct(inst, obj, depth, host);
        }

        List<Object> values = new ArrayList<>();
        for (Expr arg : c.args()) values.add(Expr.eval(arg, scope));
        for (int i = 0; i < m.params.size(); i++) obj.fields.put(m.params.get(i), values.get(i));

        String exit = runGraph(m.enter(), new Scope(obj, m), depth + 1, host);
        int port = inst.portIndex(exit);
        if (port < 0) throw new FlowException(inst, "unknown exit '" + exit + "'");
        Block nxt = inst.next[port];
        if (nxt == null && scope.method == null) {
            host.print("(warning: exit '" + exit + "' of " + m.qualified() + " is not connected - program ends)");
            throw new Halt();
        }
        return nxt;
    }

    /** A new object: declared fields get their initial values in order, then the constructor runs. */
    private void construct(Block inst, Obj obj, int depth, Host host) {
        BlockDef d = inst.def;
        Method ctor = d.constructor();
        Scope self = new Scope(obj, ctor != null ? ctor : inst.method());
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            try {
                obj.fields.put(f.name(), Expr.eval(d.fieldInits.get(i), self));
            } catch (FlowException e) {
                throw new FlowException(inst, "initial value of field " + d.name + "." + f.name() + ": " + e.getMessage());
            }
        }
        if (ctor != null) {
            if (depth + 1 > MAX_DEPTH) throw new FlowException(inst, "too many nested method calls (endless recursion?)");
            runGraph(ctor.enter(), new Scope(obj, ctor), depth + 1, host);
        }
    }

    Map<String, Object> variables() {
        return globals;
    }
}
