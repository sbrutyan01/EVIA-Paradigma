package evia.paradigma;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Translates a block program into one Python 3 file (Python 3.8 or newer, standard library only).
 *
 * <ul>
 *   <li>A class becomes a Python class: fields are attributes {@code self.f_name}, objects used by its
 *       methods are attributes created on first use, and each method returns the index of the Exit it
 *       left through.</li>
 *   <li>The main program is {@code class Program} with {@code run()}; program variables are module
 *       globals {@code v_name}.</li>
 *   <li>Flow: Python has no goto. STRUCTURED writes nested if/else and while loops where the wiring
 *       allows it; elsewhere, and for GOTO, the graph becomes a small state machine
 *       ({@code while True} with one branch per block), which keeps every wire as it is drawn.</li>
 *   <li>Values: SAFE uses the runtime's checked values (float or str) with the interpreter's exact
 *       messages; FAST uses plain floats, text only in Print, no checks.</li>
 * </ul>
 * The output, error messages and exit code are the interpreter's, like the C and C++ targets.
 */
final class PythonGenerator {

    /** Names a class must not take: keywords, the builtins the runtime uses, and the runtime itself. */
    private static final Set<String> RESERVED = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
            "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
            "match", "case", "type", "self",
            "abs", "bool", "dict", "Exception", "float", "getattr", "int", "isinstance", "len", "list", "object",
            "print", "range", "repr", "setattr", "str", "super", "tuple", "ValueError", "ZeroDivisionError",
            "RecursionError", "KeyboardInterrupt", "AttributeError", "math", "re", "sys", "decimal",
            "Program", "EviaError", "main");

    private final Library lib;
    private final Target t;
    private final boolean FAST;
    private final Map<BlockDef, String> className = new LinkedHashMap<>();
    private final Set<String> globals = new TreeSet<>();
    private final Map<BlockDef, Set<String>> fields = new HashMap<>();
    /** Body lines and the block (or null) each one belongs to, for "View source". */
    private final List<String> lines = new ArrayList<>();
    private final List<Object> owners = new ArrayList<>();
    private final Map<Object, List<int[]>> sourceMap = new HashMap<>();

    private PythonGenerator(Library lib, Target t) {
        this.lib = lib;
        this.t = t;
        this.FAST = t.fast();
    }

    static CodeGenerator.Result generate(Library lib, Target t) {
        Interpreter.prepare(lib);
        PythonGenerator g = new PythonGenerator(lib, t);
        String code = g.build();
        return new CodeGenerator.Result(code, g.sourceMap);
    }

    // ================================================================ top level

    private String build() {
        Set<String> used = new HashSet<>();
        for (BlockDef d : lib.defs()) {
            String n = sanitize(d.name);
            if (RESERVED.contains(n) || n.startsWith("rt_") || n.startsWith("v_") || !used.add(n)) n = n + "_" + d.id;
            used.add(n);
            className.put(d, n);
        }
        collectNames(lib.main.getBlocks(), null);
        for (BlockDef d : lib.defs()) {
            Set<String> f = new LinkedHashSet<>();
            for (BlockDef.Field x : d.fields) f.add(x.name());
            Set<String> rest = new TreeSet<>();
            for (Method m : d.methods) rest.addAll(m.params);
            fields.put(d, rest);
            for (Method m : d.methods) collectNames(m.body.getBlocks(), d);
            for (Expr e : d.fieldInits) collectExpr(e, d, null);
            rest.removeAll(f);
            f.addAll(rest);
            fields.put(d, f);
        }

        add(null, 0, "# ---- program variables");
        if (globals.isEmpty()) add(null, 0, "# (none)");
        for (String g : globals) add(null, 0, global(g) + (FAST ? " = 0.0" : " = None   # not assigned yet"));
        add(null, 0, "");
        for (BlockDef d : lib.defs()) emitClass(d);
        emitProgram();
        add(null, 0, "");
        add(null, 0, "def main():");
        add(null, 1, "try:");
        add(null, 2, "Program().run()");
        add(null, 1, "except EviaError as e:");
        add(null, 2, "sys.stdout.flush()");
        add(null, 2, "sys.stderr.write(\">> error: \" + str(e) + \"\\n\")");
        add(null, 2, "return 1");
        add(null, 1, "except RecursionError:");
        add(null, 2, "sys.stdout.flush()");
        add(null, 2, "sys.stderr.write(\">> error: too many nested method calls (endless recursion?)\\n\")");
        add(null, 2, "return 1");
        add(null, 1, "except KeyboardInterrupt:");
        add(null, 2, "return 130");
        add(null, 1, "sys.stdout.flush()");
        add(null, 1, "return 0");
        add(null, 0, "");
        add(null, 0, "");
        add(null, 0, "if __name__ == \"__main__\":");
        add(null, 1, "sys.exit(main())");

        String head = header() + PythonRuntime.source(FAST) + "\n";
        int shift = (int) head.chars().filter(ch -> ch == '\n').count();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            body.append(lines.get(i)).append('\n');
            Object o = owners.get(i);
            if (o != null) markLine(o, shift + i);
        }
        for (Object[] r : ranges) {
            sourceMap.computeIfAbsent(r[0], k -> new ArrayList<>()).add(new int[]{shift + (int) r[1], shift + (int) r[2]});
        }
        return head + body;
    }

    private String header() {
        return "#!/usr/bin/env python3\n"
                + "# Generated by EVIA Paradigma " + App.VERSION + " on "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n"
                + "# Language: " + t.describe() + "\n"
                + "# Run:      python3 program.py   (Python 3.8 or newer, nothing to install;"
                + " Windows: py program.py)\n\n";
    }

    private void add(Object owner, int indent, String s) {
        lines.add(s.isEmpty() ? "" : "    ".repeat(indent) + s);
        owners.add(owner);
    }

    private void markLine(Object o, int line) {
        List<int[]> r = sourceMap.computeIfAbsent(o, k -> new ArrayList<>());
        if (!r.isEmpty() && r.get(r.size() - 1)[1] == line) r.get(r.size() - 1)[1] = line + 1;
        else r.add(new int[]{line, line + 1});
    }

    // ================================================================ classes

    private record ObjMember(String member, BlockDef cls, String label) {}

    private List<ObjMember> objectMembers(FlowCanvas c, String anonPrefix, Set<String> named) {
        List<ObjMember> r = new ArrayList<>();
        for (Block b : c.getBlocks()) {
            if (b.def == null) continue;
            if (b.objectName().isEmpty()) {
                r.add(new ObjMember(anonPrefix + b.id, b.def, "object of " + comment(b.def.name)));
            } else if (named.add(b.objectName())) {
                r.add(new ObjMember("obj_" + b.objectName(), b.def,
                        "object " + comment(b.objectName()) + " : " + comment(b.def.name)));
            }
        }
        return r;
    }

    private List<ObjMember> classObjects(BlockDef d) {
        List<ObjMember> r = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (Method m : d.methods) r.addAll(objectMembers(m.body, "o_" + sanitize(m.name) + "_", named));
        return r;
    }

    private void emitClass(BlockDef d) {
        int classFrom = lines.size();
        String cn = className.get(d);
        add(null, 0, "");
        add(null, 0, "");
        add(null, 0, "class " + cn + ":");
        add(null, 1, "\"\"\"class " + docText(d.name) + (d.module != null ? "   (module " + docText(d.module) + ")" : "")
                + "; each method returns the index of the Exit it left through.\"\"\"");
        add(null, 0, "");
        add(null, 1, "def __init__(self):");
        for (String f : fields.get(d)) add(null, 2, "self." + field(f) + " = 0.0");
        for (ObjMember o : classObjects(d)) add(null, 2, "self." + o.member() + " = None   # " + o.label());
        Method ctor = d.constructor();
        Graph w = new Graph(ctor != null ? ctor : d.methods.get(0), d);
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            Expr e = d.fieldInits.get(i);
            if (FAST) {
                add(null, 2, "self." + field(f.name()) + " = " + w.fastNum(e, null));
            } else if (e instanceof Expr.Num || e instanceof Expr.Str) {
                add(null, 2, "self." + field(f.name()) + " = " + w.expr(e, null));   // a constant cannot fail
            } else {
                add(null, 2, "try:");
                add(null, 3, "self." + field(f.name()) + " = " + w.expr(e, null));
                add(null, 2, "except EviaError as e:");
                add(null, 3, "raise EviaError(" + pyString("initial value of field " + d.name + "." + f.name() + ": ")
                        + " + str(e)) from None");
            }
        }
        if (ctor != null) add(null, 2, "self." + methodName(ctor) + "()");
        if (lines.get(lines.size() - 1).endsWith("def __init__(self):")) add(null, 2, "pass");
        for (Method m : d.methods) emitMethod(m);
        markRange(d, classFrom, lines.size());
    }

    private void emitMethod(Method m) {
        int from = lines.size();
        add(null, 0, "");
        if (!FAST) add(null, 1, "@rt_method");
        StringBuilder sig = new StringBuilder("def " + methodName(m) + "(self");
        for (String p : m.params) sig.append(", ").append(param(p));
        sig.append("):   # ").append(comment(m.qualified())).append(m.isConstructor() ? "; constructor" : "")
                .append("; ").append(exitsComment(m));
        add(null, 1, sig.toString());
        for (String p : m.params) add(null, 2, "self." + field(p) + " = " + param(p));
        new Graph(m, m.cls).write(m.enter(), 2);
        markRange(m, from, lines.size());
    }

    /** Whole classes and methods for "View source": [key, from, to) in body lines; build() adds the header's length. */
    private final List<Object[]> ranges = new ArrayList<>();

    private void markRange(Object key, int from, int to) {
        if (to > from) ranges.add(new Object[]{key, from, to});
    }

    private void emitProgram() {
        add(null, 0, "");
        add(null, 0, "");
        add(null, 0, "class Program:");
        add(null, 1, "\"\"\"The main program. Owns the objects used on the main canvas.\"\"\"");
        add(null, 0, "");
        add(null, 1, "def __init__(self):");
        List<ObjMember> objs = objectMembers(lib.main, "o", new LinkedHashSet<>());
        if (objs.isEmpty()) add(null, 2, "pass");
        for (ObjMember o : objs) add(null, 2, "self." + o.member() + " = None   # " + o.label());
        add(null, 0, "");
        add(null, 1, "def run(self):");
        Block start = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.START) start = b;
        new Graph(null, null).write(start, 2);
    }

    private static String exitsComment(Method m) {
        List<String> exits = m.exitNames();
        StringBuilder s = new StringBuilder("exits: ");
        for (int i = 0; i < exits.size(); i++) s.append(i == 0 ? "" : ", ").append(i).append(" = ").append(comment(exits.get(i)));
        return s.toString();
    }

    private static String methodName(Method m) {
        return "m_" + m.name;
    }

    // ================================================================ graphs

    /** Writes one graph: the main program or a method body. */
    private final class Graph {
        final Method owner;          // null = main program
        final BlockDef cls;
        Block current;
        int base;

        Graph(Method owner, BlockDef cls) {
            this.owner = owner;
            this.cls = cls;
        }

        void out(int indent, String s) {
            add(current, base + indent, s);
        }

        void write(Block entry, int indent) {
            base = indent;
            Set<Block> reachable = reachable(entry);
            int unreachable = (owner == null ? lib.main : owner.body).getBlocks().size() - reachable.size();
            if (unreachable > 0) out(0, "# (" + unreachable + " unreachable block(s) skipped)");
            Set<String> assigned = assignedGlobals(reachable);
            if (!assigned.isEmpty()) out(0, "global " + String.join(", ", assigned));
            if (t.flow() == Target.Flow.STRUCTURED) {
                Structurer st = new Structurer(entry, reachable);
                int mark = lines.size();
                if (st.emit()) return;
                // roll back what the attempt wrote, then use the state machine
                while (lines.size() > mark) {
                    lines.remove(lines.size() - 1);
                    owners.remove(owners.size() - 1);
                }
                current = null;
                out(0, "# (the wiring is not block-structured here, so this is a state machine: one branch per block)");
            }
            stateMachine(entry);
        }

        Set<String> assignedGlobals(Set<Block> blocks) {
            Set<String> r = new TreeSet<>();
            for (Block b : blocks) {
                Interpreter.Compiled c = b.compiled();
                if (c == null) continue;
                if ((b.type == BlockType.SET || b.type == BlockType.INPUT) && c.var() != null && !c.var().startsWith("this.")) {
                    r.add(global(c.var()));
                }
                for (Interpreter.Assign a : new Interpreter.Assign[]{c.init(), c.step()}) {
                    if (a != null && !a.var().startsWith("this.")) r.add(global(a.var()));
                }
            }
            return r;
        }

        Set<Block> reachable(Block entry) {
            Set<Block> seen = new LinkedHashSet<>();
            Deque<Block> todo = new ArrayDeque<>();
            todo.push(entry);
            while (!todo.isEmpty()) {
                Block b = todo.pop();
                if (!seen.add(b)) continue;
                for (Block n : b.next) if (n != null && !seen.contains(n)) todo.push(n);
            }
            return seen;
        }

        // ============================================================ structured

        private static final class Unstructured extends RuntimeException {
            Unstructured() {
                super(null, null, false, false);
            }
        }

        /**
         * Nested if/else and while loops, continuing after a branch at its join point (the immediate
         * post-dominator). A loop's body must be a closed region. Same rules as the C and C++ writer.
         */
        private final class Structurer {
            final Block entry;
            final List<Block> nodes;
            final Map<Block, Integer> index = new HashMap<>();
            final Map<Block, Set<Block>> preds = new HashMap<>();
            final Set<Block> emitted = new HashSet<>();
            BitSet[] pdom;
            int indent = 0;
            /** Statements (not comments) written so far, so an empty branch can get "pass". */
            int statements = 0;

            Structurer(Block entry, Set<Block> reachable) {
                this.entry = entry;
                this.nodes = new ArrayList<>(reachable);
                for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i), i);
                for (Block b : nodes) {
                    for (Block n : b.next) if (n != null) preds.computeIfAbsent(n, k -> new HashSet<>()).add(b);
                }
            }

            boolean emit() {
                try {
                    postDominators();
                    seq(entry, null, null);
                    if (statements == 0) stmt("pass");
                    return true;
                } catch (Unstructured u) {
                    return false;
                }
            }

            void stmt(String s) {
                out(indent, s);
                statements++;
            }

            void remark(String s) {
                out(indent, s);
            }

            List<Integer> succ(Block b) {
                List<Integer> r = new ArrayList<>();
                int exit = nodes.size();
                if (b.type == BlockType.END || b.type == BlockType.EXIT) {
                    r.add(exit);
                    return r;
                }
                for (Block n : b.next) r.add(n == null ? exit : index.get(n));
                if (b.next.length == 0) r.add(exit);
                return r;
            }

            void postDominators() {
                int n = nodes.size(), exit = n;
                pdom = new BitSet[n + 1];
                for (int i = 0; i <= n; i++) {
                    pdom[i] = new BitSet(n + 1);
                    if (i == exit) pdom[i].set(exit);
                    else pdom[i].set(0, n + 1);
                }
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (int i = 0; i < n; i++) {
                        BitSet nw = null;
                        for (int s : succ(nodes.get(i))) {
                            if (nw == null) nw = (BitSet) pdom[s].clone();
                            else nw.and(pdom[s]);
                        }
                        if (nw == null) nw = new BitSet(n + 1);
                        nw.set(i);
                        if (!nw.equals(pdom[i])) {
                            pdom[i] = nw;
                            changed = true;
                        }
                    }
                }
            }

            Block ipdom(Block b) {
                int i = index.get(b);
                BitSet strict = (BitSet) pdom[i].clone();
                strict.clear(i);
                int want = strict.cardinality();
                for (int d = strict.nextSetBit(0); d >= 0; d = strict.nextSetBit(d + 1)) {
                    if (pdom[d].cardinality() == want) return d == nodes.size() ? null : nodes.get(d);
                }
                return null;
            }

            void seq(Block b, Block stop, Block loop) {
                while (b != null && b != stop) {
                    if (loop != null && b != loop && !loop.loopBody.contains(b)) throw new Unstructured();
                    if (!emitted.add(b)) throw new Unstructured();
                    current = b;
                    Interpreter.Compiled c = b.compiled();
                    remark("# " + comment(describe(b)));
                    switch (b.type) {
                        case START, ENTER -> { }
                        case SET -> stmt(assign(c.var(), c.expr(), b));
                        case PRINT -> stmt(print(c.expr(), b));
                        case INPUT -> stmt(input(c.var(), b));
                        case END -> {
                            stmt("return");
                            return;
                        }
                        case EXIT -> {
                            stmt(exitStatement(c.var()));
                            return;
                        }
                        case IF -> {
                            b = ifElse(b, c, stop, loop);
                            continue;
                        }
                        case CUSTOM -> {
                            if (b.next.length > 1) {
                                b = multiExit(b, c, stop, loop);
                                continue;
                            }
                            stmt(call(b, c));
                        }
                        case FOR, WHILE, DO_WHILE -> {
                            loopBlock(b, c);
                            if (b.next[1] == null) {
                                for (String s : missing(b, 1)) stmt(s);
                                return;
                            }
                            b = b.next[1];
                            continue;
                        }
                        default -> throw board(b);
                    }
                    if (b.next.length == 0 || b.next[0] == null) {
                        for (String s : missing(b, 0)) stmt(s);
                        return;
                    }
                    b = b.next[0];
                }
            }

            Block ifElse(Block b, Interpreter.Compiled c, Block stop, Block loop) {
                Block join = ipdom(b);
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                String cond = cond(c.expr(), b);
                Block yes = b.next[0], no = b.next[1];
                boolean yesEmpty = yes != null && yes == inner, noEmpty = no != null && no == inner;
                if (yesEmpty && noEmpty) {
                    stmt(cond);   // evaluated for its errors, like the interpreter
                } else if (yesEmpty) {
                    stmt("if not " + cond + ":");
                    branch(b, 1, inner, loop);
                } else {
                    stmt("if " + cond + ":");
                    branch(b, 0, inner, loop);
                    if (!noEmpty) {
                        stmt("else:");
                        branch(b, 1, inner, loop);
                    }
                }
                return join;
            }

            Block multiExit(Block b, Interpreter.Compiled c, Block stop, Block loop) {
                Block join = ipdom(b);
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                stmt("rt_x = " + call(b, c));
                for (int i = 0; i < b.next.length; i++) {
                    stmt((i == 0 ? "if" : "elif") + " rt_x == " + i + ":   # exit '" + comment(b.portName(i)) + "'");
                    branch(b, i, inner, loop);
                }
                stmt("else:");
                indent++;
                stmt("raise EviaError(\"bad exit index\")");
                indent--;
                return join;
            }

            void branch(Block from, int port, Block stop, Block loop) {
                indent++;
                int before = statements;
                Block start = from.next[port];
                if (start == null) for (String s : missing(from, port)) stmt(s);
                else seq(start, stop, loop);
                if (statements == before) stmt("pass");
                indent--;
                current = from;
            }

            /** for and while become while loops; do-while is "while True" with the test at the end. */
            void loopBlock(Block l, Interpreter.Compiled c) {
                Set<Block> body = l.loopBody;
                if (l.next[1] != null && body.contains(l.next[1])) throw new Unstructured();
                for (Block x : body) {
                    for (Block n : x.next) if (n != null && n != l && !body.contains(n)) throw new Unstructured();
                    for (Block p : preds.getOrDefault(x, Set.of())) if (p != l && !body.contains(p)) throw new Unstructured();
                }
                String cond = c.expr() == null ? "True" : cond(c.expr(), l);
                Block first = l.next[0];
                switch (l.type) {
                    case FOR -> {
                        if (c.init() != null) stmt(assign(c.init(), l));
                        stmt("while " + cond + ":");
                        indent++;
                        int before = statements;
                        if (first != null && first != l) seq(first, l, l);
                        current = l;
                        if (c.step() != null) stmt(assign(c.step(), l));
                        if (statements == before) stmt("pass");
                        indent--;
                    }
                    case WHILE -> {
                        stmt("while " + cond + ":");
                        indent++;
                        int before = statements;
                        if (first != null && first != l) seq(first, l, l);
                        if (statements == before) stmt("pass");
                        indent--;
                    }
                    default -> {
                        stmt("while True:   # do ... while");
                        indent++;
                        if (first != null && first != l) seq(first, l, l);
                        current = l;
                        stmt("if not " + cond + ":");
                        indent++;
                        stmt("break");
                        indent -= 2;
                    }
                }
                current = l;
            }

            void checkJoin(Block join, Block loop) {
                if (loop != null && join != null && join != loop && !loop.loopBody.contains(join)) throw new Unstructured();
            }
        }

        // ============================================================ state machine

        /**
         * Every block is a state "L&lt;id&gt;"; a loop block also has "L&lt;id&gt;_next" (a wire coming
         * back from its body) and, for a for loop, "L&lt;id&gt;_test". Each branch sets the next state.
         */
        void stateMachine(Block entry) {
            List<Block> order = layout(entry);
            out(0, "rt_s = \"L" + entry.id + "\"");
            out(0, "while True:");
            boolean first = true;
            for (Block b : order) {
                current = b;
                Interpreter.Compiled c = b.compiled();
                String id = "L" + b.id;
                String test = b.type == BlockType.WHILE ? "rt_s in (\"" + id + "\", \"" + id + "_next\")" : "rt_s == \"" + id + "\"";
                out(1, (first ? "if " : "elif ") + test + ":   # " + comment(describe(b)));
                first = false;
                switch (b.type) {
                    case START, ENTER -> go(b, 0, 2);
                    case SET -> {
                        out(2, assign(c.var(), c.expr(), b));
                        go(b, 0, 2);
                    }
                    case PRINT -> {
                        out(2, print(c.expr(), b));
                        go(b, 0, 2);
                    }
                    case INPUT -> {
                        out(2, input(c.var(), b));
                        go(b, 0, 2);
                    }
                    case IF -> {
                        out(2, "if " + cond(c.expr(), b) + ":");
                        go(b, 0, 3);
                        out(2, "else:");
                        go(b, 1, 3);
                    }
                    case END -> out(2, "return");
                    case EXIT -> out(2, exitStatement(c.var()));
                    case CUSTOM -> {
                        if (b.next.length == 1) {
                            out(2, call(b, c));
                            go(b, 0, 2);
                        } else {
                            out(2, "rt_x = " + call(b, c));
                            for (int i = 0; i < b.next.length; i++) {
                                out(2, (i == 0 ? "if" : "elif") + " rt_x == " + i + ":   # exit '" + comment(b.portName(i)) + "'");
                                go(b, i, 3);
                            }
                            out(2, "else:");
                            out(3, "raise EviaError(\"bad exit index\")");
                        }
                    }
                    case FOR, WHILE, DO_WHILE -> loopStates(b, c);
                    default -> throw board(b);
                }
            }
            current = null;
        }

        void loopStates(Block l, Interpreter.Compiled c) {
            String id = "L" + l.id;
            String cond = c.expr() == null ? null : cond(c.expr(), l);
            boolean emptyBody = l.next[0] == null || l.next[0] == l;
            switch (l.type) {
                case FOR -> {
                    if (c.init() != null) out(2, assign(c.init(), l));
                    out(2, "rt_s = \"" + id + "_test\"");
                    out(1, "elif rt_s == \"" + id + "_next\":");
                    if (c.step() != null) out(2, assign(c.step(), l));
                    out(2, "rt_s = \"" + id + "_test\"");
                    out(1, "elif rt_s == \"" + id + "_test\":");
                    testThen(l, cond, emptyBody, id);
                }
                case WHILE -> testThen(l, cond, emptyBody, id);
                default -> {
                    toBody(l, emptyBody, id, 2);
                    out(1, "elif rt_s == \"" + id + "_next\":");
                    out(2, "if " + cond + ":");
                    toBody(l, emptyBody, id, 3);
                    out(2, "else:");
                    go(l, 1, 3);
                }
            }
        }

        void testThen(Block l, String cond, boolean emptyBody, String id) {
            if (cond == null) {
                toBody(l, emptyBody, id, 2);
                return;
            }
            out(2, "if " + cond + ":");
            toBody(l, emptyBody, id, 3);
            out(2, "else:");
            go(l, 1, 3);
        }

        void toBody(Block l, boolean emptyBody, String id, int indent) {
            if (emptyBody) out(indent, "rt_s = \"" + id + "_next\"");
            else go(l, 0, indent);
        }

        /** Sets the next state for output {@code port} of {@code b}. */
        void go(Block b, int port, int indent) {
            Block tg = b.next[port];
            if (tg == null) {
                for (String s : missing(b, port)) out(indent, s);
                return;
            }
            boolean back = tg.type.isLoop() && (tg.loopBody.contains(b) || b == tg);
            out(indent, "rt_s = \"L" + tg.id + (back ? "_next" : "") + "\"");
        }

        List<Block> layout(Block entry) {
            List<Block> order = new ArrayList<>();
            Set<Block> seen = new HashSet<>();
            Deque<Block> pending = new ArrayDeque<>();
            pending.push(entry);
            while (!pending.isEmpty()) {
                Block cur = pending.pop();
                while (cur != null && seen.add(cur)) {
                    order.add(cur);
                    Block primary = null;
                    for (int i = cur.next.length - 1; i >= 0; i--) {
                        Block n = cur.next[i];
                        if (n == null || seen.contains(n)) continue;
                        if (primary != null) pending.push(primary);
                        primary = n;
                    }
                    cur = primary;
                }
            }
            return order;
        }

        // ============================================================ statements

        /** Same behaviour as the interpreter for an unconnected output. */
        List<String> missing(Block b, int port) {
            if (owner != null) {
                return List.of("raise EviaError(" + pyString("method " + owner.qualified()
                        + ": this path ends without reaching an Exit block") + ")");
            }
            String msg = pyString(b.def != null
                    ? "(warning: exit '" + b.portName(port) + "' of " + b.method().qualified() + " is not connected - program ends)"
                    : "(warning: " + b.title() + " block has no outgoing connection - program ends)");
            return List.of("rt_print_line(" + msg + ")", "return");
        }

        String exitStatement(String exitName) {
            return "return " + owner.exitNames().indexOf(exitName) + "   # exit '" + comment(exitName) + "'";
        }

        FlowException board(Block b) {
            if (b.type == BlockType.SENSOR) return new FlowException(b, "the Phone Sensor block reads a phone's sensors: choose Language Android (Run simulates them on the computer)");
            return new FlowException(b, "the " + b.type.title + " block runs on a microcontroller: choose Language Arduino or AVR C"
                    + " (Run simulates the board on the computer)");
        }

        String assign(String var, Expr e, Block b) {
            return target(var, b) + " = " + (FAST ? fastNum(e, b) : expr(e, b));
        }

        String assign(Interpreter.Assign a, Block b) {
            return assign(a.var(), a.expr(), b);
        }

        String print(Expr e, Block b) {
            if (!FAST) return "rt_print(" + expr(e, b) + ")";
            F f = fast(e, b);
            List<F> parts = f.kind == TEXT ? mergeText(f.parts) : List.of(f);
            List<String> pieces = new ArrayList<>();
            for (F p : parts) pieces.add(p.kind == TEXT ? pyString(p.text) : "rt_text(" + numCode(p) + ")");
            return "rt_print_line(" + String.join(" + ", pieces) + ")";
        }

        String input(String var, Block b) {
            return target(var, b) + " = " + (FAST ? "rt_input_num(" : "rt_input(") + pyString(var) + ")";
        }

        String cond(Expr e, Block b) {
            if (FAST) return boolCode(fast(e, b), b);
            return "rt_truthy(" + expr(e, b) + ")";
        }

        /** {@code rt_obj(...)} creates the object on first use, before the arguments are evaluated (as the interpreter does). */
        String call(Block b, Interpreter.Compiled c) {
            String member = !b.objectName().isEmpty() ? "obj_" + b.objectName()
                    : owner == null ? "o" + b.id : "o_" + sanitize(owner.name) + "_" + b.id;
            StringBuilder s = new StringBuilder("rt_obj(self, \"" + member + "\", " + className.get(b.def) + ")."
                    + methodName(b.method()) + "(");
            for (int i = 0; i < c.args().size(); i++) {
                s.append(i == 0 ? "" : ", ").append(FAST ? fastNum(c.args().get(i), b) : expr(c.args().get(i), b));
            }
            return s.append(')').toString();
        }

        // ============================================================ safe values

        String expr(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> pyNumber(n.value());
                case Expr.Str s -> pyString(s.value());
                case Expr.Var v -> read(v.name(), b);
                case Expr.Unary u -> (u.op().equals("-") ? "rt_neg(" : "rt_not(") + expr(u.operand(), b) + ")";
                case Expr.Binary x -> binary(x, b);
            };
        }

        String binary(Expr.Binary x, Block b) {
            String l = expr(x.left(), b), r = expr(x.right(), b);
            return switch (x.op()) {
                case "&&" -> "rt_B(rt_truthy(" + l + ") and rt_truthy(" + r + "))";
                case "||" -> "rt_B(rt_truthy(" + l + ") or rt_truthy(" + r + "))";
                case "+" -> "rt_add(" + l + ", " + r + ")";
                case "-" -> "rt_sub(" + l + ", " + r + ")";
                case "*" -> "rt_mul(" + l + ", " + r + ")";
                case "/" -> "rt_div(" + l + ", " + r + ")";
                case "%" -> "rt_mod(" + l + ", " + r + ")";
                case "==" -> "rt_eq(" + l + ", " + r + ")";
                case "!=" -> "rt_ne(" + l + ", " + r + ")";
                case "<" -> "rt_lt(" + l + ", " + r + ")";
                case "<=" -> "rt_le(" + l + ", " + r + ")";
                case ">" -> "rt_gt(" + l + ", " + r + ")";
                case ">=" -> "rt_ge(" + l + ", " + r + ")";
                default -> throw new FlowException(b, "unknown operator " + x.op());
            };
        }

        String read(String name, Block b) {
            if (name.startsWith("this.")) return target(name, b);
            Double board = BoardSim.constant(name);
            if (board != null) return pyNumber(board);
            if (FAST) return global(name);
            return "rt_rd(" + global(name) + ", " + pyString(name) + ")";
        }

        String target(String name, Block b) {
            if (name.startsWith("this.")) {
                if (owner == null && cls == null) throw new FlowException(b, "'this' can only be used inside a class method");
                return "self." + field(name.substring(5));
            }
            return global(name);
        }

        // ============================================================ fast values

        static final int NUM = 0, BOOL = 1, TEXT = 2;

        /** Fast-value expression: Python code, its kind (a float, a bool or text to print) and the text pieces. */
        record F(String code, int kind, List<F> parts, String text) {}

        F fast(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> new F(pyNumber(n.value()), NUM, null, null);
                case Expr.Str s -> new F(pyString(s.value()), TEXT,
                        List.of(new F(pyString(s.value()), TEXT, List.of(), s.value())), s.value());
                case Expr.Var v -> new F(read(v.name(), b), NUM, null, null);
                case Expr.Unary u -> {
                    F x = fast(u.operand(), b);
                    needNumber(x, b);
                    yield u.op().equals("-") ? new F("(-" + numCode(x) + ")", NUM, null, null)
                            : new F("(not " + boolCode(x, b) + ")", BOOL, null, null);
                }
                case Expr.Binary x -> fastBinary(x, b);
            };
        }

        F fastBinary(Expr.Binary x, Block b) {
            F l = fast(x.left(), b), r = fast(x.right(), b);
            String op = x.op();
            if (op.equals("+") && (l.kind == TEXT || r.kind == TEXT)) {
                List<F> parts = new ArrayList<>();
                parts.addAll(l.kind == TEXT ? l.parts : List.of(l));
                parts.addAll(r.kind == TEXT ? r.parts : List.of(r));
                return new F(null, TEXT, parts, null);
            }
            needNumber(l, b);
            needNumber(r, b);
            return switch (op) {
                case "&&" -> new F("(" + boolCode(l, b) + " and " + boolCode(r, b) + ")", BOOL, null, null);
                case "||" -> new F("(" + boolCode(l, b) + " or " + boolCode(r, b) + ")", BOOL, null, null);
                case "+", "-", "*" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", NUM, null, null);
                case "/" -> new F("rt_div(" + numCode(l) + ", " + numCode(r) + ")", NUM, null, null);
                case "%" -> new F("rt_mod(" + numCode(l) + ", " + numCode(r) + ")", NUM, null, null);
                case "==", "!=", "<", "<=", ">", ">=" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", BOOL, null, null);
                default -> throw new FlowException(b, "unknown operator " + op);
            };
        }

        /** A float: a comparison becomes 1.0 or 0.0, as in C. */
        String numCode(F f) {
            return f.kind == BOOL ? "(1.0 if " + f.code + " else 0.0)" : f.code;
        }

        String fastNum(Expr e, Block b) {
            F f = fast(e, b);
            needNumber(f, b);
            return CodeGenerator.unwrap(numCode(f));
        }

        String boolCode(F f, Block b) {
            needNumber(f, b);
            return f.kind == BOOL ? f.code : "(" + f.code + " != 0)";
        }

        void needNumber(F f, Block b) {
            if (f.kind != TEXT) return;
            String sample = f.parts.isEmpty() || f.parts.get(0).text == null ? "text" : "\"" + f.parts.get(0).text + "\"";
            throw new FlowException(b, "fast values only hold numbers; " + sample
                    + " can only be printed. Choose Values: Safe to use text in variables, arguments, conditions or comparisons");
        }

        List<F> mergeText(List<F> parts) {
            List<F> r = new ArrayList<>();
            for (F p : parts) {
                if (p.kind == TEXT && !r.isEmpty() && r.get(r.size() - 1).kind == TEXT) {
                    String text = r.remove(r.size() - 1).text + p.text;
                    r.add(new F(pyString(text), TEXT, null, text));
                } else {
                    r.add(p);
                }
            }
            return r;
        }
    }

    // ================================================================ name collection

    private void collectNames(List<Block> blocks, BlockDef owner) {
        for (Block b : blocks) {
            Interpreter.Compiled c = b.compiled();
            boolean assigns = switch (b.type) {
                case SET, INPUT, DIGITAL_READ, ANALOG_READ, MILLIS, UART_READ, SPI_TRANSFER, I2C_READ -> true;
                default -> false;
            };
            if (assigns && c.var() != null) addName(c.var(), owner, b);
            if (c.expr() != null) collectExpr(c.expr(), owner, b);
            if (c.args() != null) for (Expr a : c.args()) collectExpr(a, owner, b);
            for (Interpreter.Assign a : new Interpreter.Assign[]{c.init(), c.step()}) {
                if (a == null) continue;
                addName(a.var(), owner, b);
                collectExpr(a.expr(), owner, b);
            }
        }
    }

    private void collectExpr(Expr e, BlockDef owner, Block b) {
        switch (e) {
            case Expr.Var v -> {
                if (!BoardSim.isConstant(v.name())) addName(v.name(), owner, b);
            }
            case Expr.Unary u -> collectExpr(u.operand(), owner, b);
            case Expr.Binary x -> {
                collectExpr(x.left(), owner, b);
                collectExpr(x.right(), owner, b);
            }
            case Expr.Num n -> { }
            case Expr.Str s -> { }
        }
    }

    private void addName(String name, BlockDef owner, Block b) {
        if (name.startsWith("this.")) {
            if (owner == null) throw new FlowException(b, "'this' can only be used inside a class method");
            fields.get(owner).add(name.substring(5));
        } else {
            globals.add(name);
        }
    }

    // ================================================================ helpers

    private static String global(String name) { return "v_" + name; }
    private static String field(String name)  { return "f_" + name; }
    private static String param(String name)  { return "p_" + name; }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) sb.append(ch < 128 && Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) sb.insert(0, "Block_");
        return sb.toString();
    }

    /** A float literal: "3.0", "0.25", "1e-05" (Python reads Java's "1.0E-5" too), math.inf for overflow. */
    static String pyNumber(double d) {
        if (Double.isNaN(d)) return "math.nan";
        if (Double.isInfinite(d)) return d > 0 ? "math.inf" : "(-math.inf)";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        String s = Double.toString(d);
        return d < 0 ? "(" + s + ")" : s;
    }

    /** A Python string literal; non-ASCII stays as it is (the file is UTF-8). */
    static String pyString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String describe(Block b) {
        if (b.def != null) {
            String obj = b.objectName().isEmpty() ? b.def.name : b.objectName() + " : " + b.def.name;
            return "[" + obj + "] ." + (b.methodName() == null ? "?" : b.methodName()) + "(" + b.getCode() + ")";
        }
        return ("[" + b.type.title + "] " + b.getCode()).strip();
    }

    /** Text safe after # on one line. */
    private static String comment(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** Text safe inside a """docstring""". */
    private static String docText(String s) {
        return comment(s).replace("\\", "/").replace("\"\"\"", "\"\" \"");
    }
}
