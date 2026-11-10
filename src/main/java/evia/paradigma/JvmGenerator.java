package evia.paradigma;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Translates a block program into one Java or Kotlin file, or the Kotlin main activity of an Android app.
 *
 * <ul>
 *   <li>A class becomes a class: fields are {@code f_name}, objects used by its methods are members created
 *       on first use, and each method returns the index of the Exit it left through.</li>
 *   <li>Program variables are {@code v_name}: static fields of {@code Program} in Java, top-level properties
 *       in Kotlin. The main program is {@code run()}.</li>
 *   <li>Flow: neither language has goto. STRUCTURED writes nested if/else and while loops where the wiring
 *       allows it; elsewhere, and for GOTO, the graph becomes a small state machine ({@code while (true)}
 *       with one branch per block), which keeps every wire as it is drawn.</li>
 *   <li>Values: SAFE uses the runtime's checked values (Double or String) with the interpreter's exact
 *       messages; FAST uses plain doubles, text only in Print, no checks.</li>
 * </ul>
 * The output, error messages and exit code are the interpreter's, like the C, C++ and Python targets.
 * The runtime lives in resources/evia/paradigma/jvm.
 */
final class JvmGenerator {

    enum Dialect { JAVA, KOTLIN, ANDROID }

    /** Names a class must not take: keywords and the names the generated code and its runtime use. */
    private static final Set<String> RESERVED = Set.of(
            // Java keywords and literals
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var",
            "yield", "record", "sealed", "permits", "_",
            // Kotlin hard keywords
            "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "when",
            // types and packages the generated code names
            "Object", "String", "Double", "Math", "System", "Long", "Integer", "Boolean", "Character", "Exception",
            "RuntimeException", "StackOverflowError", "NumberFormatException", "Throwable", "Error", "Thread",
            "Override", "Any", "Unit", "Int", "Nothing", "Charsets", "StringBuilder", "Runnable",
            "java", "javax", "kotlin", "android", "am",
            "InterruptedException", "CharSequence", "Volatile", "Suppress", "RtScreen", "RtStop",
            "Program", "EviaError", "MainActivity", "main");

    private final Library lib;
    private final Target t;
    private final Dialect dialect;
    private final boolean FAST, KT;
    /** Java ends statements with a semicolon; Kotlin does not need one. */
    private final String END;
    /** The Android app's package, e.g. am.evia.counter. */
    private final String androidPackage, appName;
    private final Map<BlockDef, String> className = new LinkedHashMap<>();
    private final Set<String> globals = new TreeSet<>();
    private final Map<BlockDef, Set<String>> fields = new HashMap<>();
    /** Body lines and the block (or null) each one belongs to, for "View source". */
    private final List<String> lines = new ArrayList<>();
    private final List<Object> owners = new ArrayList<>();
    private final Map<Object, List<int[]>> sourceMap = new HashMap<>();

    private JvmGenerator(Library lib, Target t, Dialect dialect, String androidPackage, String appName) {
        this.lib = lib;
        this.t = t;
        this.dialect = dialect;
        this.FAST = t.fast();
        this.KT = dialect != Dialect.JAVA;
        this.END = KT ? "" : ";";
        this.androidPackage = androidPackage;
        this.appName = appName;
    }

    static CodeGenerator.Result generate(Library lib, Target t) {
        Dialect d = switch (t.lang()) {
            case JAVA -> Dialect.JAVA;
            case KOTLIN -> Dialect.KOTLIN;
            case ANDROID -> Dialect.ANDROID;
            default -> throw new IllegalArgumentException(t.lang() + " is not Java or Kotlin");
        };
        return generate(lib, t, d, Settings.androidPackage(), Settings.androidAppName());
    }

    static CodeGenerator.Result generate(Library lib, Target t, Dialect d, String androidPackage, String appName) {
        Interpreter.prepare(lib);
        JvmGenerator g = new JvmGenerator(lib, t, d, androidPackage, appName);
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

        int in = KT ? 0 : 1;   // Java: everything is inside class Program
        add(null, in, "// ---- program variables");
        if (globals.isEmpty()) add(null, in, "// (none)");
        for (String g : globals) {
            if (KT) add(null, 0, "var " + global(g) + (FAST ? " = 0.0" : ": Any? = null   // not assigned yet"));
            else add(null, 1, "static " + (FAST ? "double " + global(g) + " = 0.0;" : "Object " + global(g) + ";   // not assigned yet"));
        }
        if (dialect == Dialect.ANDROID) {
            add(null, 0, "");
            add(null, 0, "/** Back to the start, for Run Again: every variable unassigned, no method running. */");
            add(null, 0, "fun rt_reset() {");
            for (String g : globals) add(null, 1, global(g) + (FAST ? " = 0.0" : " = null"));
            if (!FAST) add(null, 1, "rt_depth = 0");
            add(null, 0, "}");
        }
        for (BlockDef d : lib.defs()) emitClass(d);
        emitProgram();
        if (dialect == Dialect.ANDROID) emitActivity();
        else emitMain();
        if (!KT) add(null, 0, "}");

        String head = header() + runtime() + "\n";
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
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String gen = "// Generated by EVIA Paradigma " + App.VERSION + " on " + when + "\n"
                + "// Language: " + t.describe() + "\n";
        return switch (dialect) {
            case JAVA -> gen
                    + "// Run:      java Program.java   (JDK 17 or newer, nothing else to install)\n"
                    + "//       or: javac -encoding UTF-8 -d classes Program.java && java -cp classes Program\n\n"
                    + "final class Program {\n\n";
            case KOTLIN -> gen
                    + "// Run:      kotlinc program.kt -include-runtime -d program.jar && java -jar program.jar\n\n"
                    + "@file:Suppress(\"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\", \"LocalVariableName\","
                    + " \"FunctionName\", \"PropertyName\", \"ClassName\", \"RedundantSemicolon\", \"CanBeVal\")\n\n";
            case ANDROID -> gen
                    + "// The main activity of an Android app: the program's Print lines appear on the screen and\n"
                    + "// Input asks in a text field. Build: Language Android, then Build (or Gradle in Android Studio).\n\n"
                    + "@file:Suppress(\"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\", \"LocalVariableName\","
                    + " \"FunctionName\", \"PropertyName\", \"ClassName\", \"CanBeVal\", \"DEPRECATION\")\n\n"
                    + "package " + androidPackage + "\n\n";
        };
    }

    private String runtime() {
        if (!KT) return read("common.java.txt") + read(FAST ? "fast.java.txt" : "safe.java.txt")
                + "    // " + "=".repeat(72) + "\n";
        return read("common.kt") + read(FAST ? "fast.kt" : "safe.kt")
                + read(dialect == Dialect.ANDROID ? "android.kt" : "desktop.kt") + "// " + "=".repeat(76) + "\n";
    }

    static String read(String name) {
        try (InputStream in = JvmGenerator.class.getResourceAsStream("jvm/" + name)) {
            if (in == null) throw new IllegalStateException("jvm/" + name + " is missing from the application");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /** The type of a value: a double for fast values, Object / Any for safe ones. */
    private String valueType() {
        return FAST ? (KT ? "Double" : "double") : (KT ? "Any" : "Object");
    }

    /** A member or property declaration: "Object f_x = 0.0;" or "var f_x: Any = 0.0". */
    private String decl(String type, String name, String init) {
        return KT ? "var " + name + ": " + type + " = " + init : type + " " + name + " = " + init + ";";
    }

    private String throwError(String messageCode) {
        return "throw " + (KT ? "" : "new ") + "EviaError(" + messageCode + ")" + END;
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

    private String objectDecl(ObjMember o, boolean isStatic) {
        String cn = className.get(o.cls());
        return KT ? "var " + o.member() + ": " + cn + "? = null   // " + o.label()
                : (isStatic ? "static " : "") + cn + " " + o.member() + ";   // " + o.label();
    }

    private void emitClass(BlockDef d) {
        int classFrom = lines.size();
        String cn = className.get(d);
        int c = KT ? 0 : 1;   // indent of the class line
        add(null, 0, "");
        add(null, c, "/** class " + docText(d.name) + (d.module != null ? "   (module " + docText(d.module) + ")" : "")
                + "; each method returns the index of the Exit it left through. */");
        add(null, c, (KT ? "class " : "static final class ") + cn + " {");
        for (String f : fields.get(d)) add(null, c + 1, decl(valueType(), field(f), "0.0"));
        for (ObjMember o : classObjects(d)) add(null, c + 1, objectDecl(o, false));
        add(null, 0, "");
        add(null, c + 1, KT ? "init {" : cn + "() {");
        Method ctor = d.constructor();
        Graph w = new Graph(ctor != null ? ctor : d.methods.get(0), d);
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            Expr e = d.fieldInits.get(i);
            if (FAST) {
                add(null, c + 2, field(f.name()) + " = " + w.fastNum(e, null) + END);
            } else if (e instanceof Expr.Num || e instanceof Expr.Str) {
                add(null, c + 2, field(f.name()) + " = " + w.expr(e, null) + END);   // a constant cannot fail
            } else {
                add(null, c + 2, "try {");
                add(null, c + 3, field(f.name()) + " = " + w.expr(e, null) + END);
                add(null, c + 2, KT ? "} catch (e: EviaError) {" : "} catch (EviaError e) {");
                add(null, c + 3, throwError(string("initial value of field " + d.name + "." + f.name() + ": ")
                        + " + e." + (KT ? "message" : "getMessage()")));
                add(null, c + 2, "}");
            }
        }
        if (ctor != null) add(null, c + 2, methodName(ctor) + "()" + END);
        add(null, c + 1, "}");
        for (Method m : d.methods) emitMethod(m, c + 1);
        add(null, c, "}");
        markRange(d, classFrom, lines.size());
    }

    private void emitMethod(Method m, int in) {
        int from = lines.size();
        add(null, 0, "");
        StringBuilder sig = new StringBuilder(KT ? "fun " + methodName(m) + "(" : "int " + methodName(m) + "(");
        for (int i = 0; i < m.params.size(); i++) {
            String p = param(m.params.get(i));
            sig.append(i == 0 ? "" : ", ").append(KT ? p + ": " + valueType() : valueType() + " " + p);
        }
        sig.append(KT ? "): Int {" : ") {");
        sig.append("   // ").append(comment(m.qualified())).append(m.isConstructor() ? "; constructor" : "")
                .append("; ").append(exitsComment(m));
        add(null, in, sig.toString());
        int body = in + 1;
        if (!FAST) {
            add(null, in + 1, "rt_enter()" + END);
            add(null, in + 1, "try {");
            body = in + 2;
        }
        for (String p : m.params) add(null, body, field(p) + " = " + param(p) + END);
        new Graph(m, m.cls).write(m.enter(), body);
        if (!FAST) {
            add(null, in + 1, "} finally {");
            add(null, in + 2, "rt_depth--" + END);
            add(null, in + 1, "}");
        }
        add(null, in, "}");
        markRange(m, from, lines.size());
    }

    /** Whole classes and methods for "View source": [key, from, to) in body lines; build() adds the header's length. */
    private final List<Object[]> ranges = new ArrayList<>();

    private void markRange(Object key, int from, int to) {
        if (to > from) ranges.add(new Object[]{key, from, to});
    }

    private void emitProgram() {
        add(null, 0, "");
        List<ObjMember> objs = objectMembers(lib.main, "o", new LinkedHashSet<>());
        Block start = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.START) start = b;
        if (KT) {
            add(null, 0, "/** The main program. Owns the objects used on the main canvas. */");
            add(null, 0, "class Program {");
            for (ObjMember o : objs) add(null, 1, objectDecl(o, false));
            if (!objs.isEmpty()) add(null, 0, "");
            add(null, 1, "fun run() {");
            new Graph(null, null).write(start, 2);
            add(null, 1, "}");
            add(null, 0, "}");
        } else {
            add(null, 1, "// ---- the main program and the objects used on the main canvas");
            for (ObjMember o : objs) add(null, 1, objectDecl(o, true));
            if (!objs.isEmpty()) add(null, 0, "");
            add(null, 1, "static void run() {");
            new Graph(null, null).write(start, 2);
            add(null, 1, "}");
        }
    }

    private void emitMain() {
        String overflow = string("too many nested method calls (endless recursion?)");
        add(null, 0, "");
        if (KT) {
            add(null, 0, "fun main() {");
            add(null, 1, "var code = 0");
            add(null, 1, "try {");
            add(null, 2, "Program().run()");
            add(null, 1, "} catch (e: EviaError) {");
            add(null, 2, "rt_error(e.message ?: \"\")");
            add(null, 2, "code = 1");
            add(null, 1, "} catch (e: StackOverflowError) {");
            add(null, 2, "rt_error(" + overflow + ")");
            add(null, 2, "code = 1");
            add(null, 1, "}");
            add(null, 1, "rt_out.flush()");
            add(null, 1, "System.exit(code)");
            add(null, 0, "}");
        } else {
            add(null, 1, "public static void main(String[] args) {");
            add(null, 2, "int code = 0;");
            add(null, 2, "try {");
            add(null, 3, "run();");
            add(null, 2, "} catch (EviaError e) {");
            add(null, 3, "rt_error(e.getMessage());");
            add(null, 3, "code = 1;");
            add(null, 2, "} catch (StackOverflowError e) {");
            add(null, 3, "rt_error(" + overflow + ");");
            add(null, 3, "code = 1;");
            add(null, 2, "}");
            add(null, 2, "rt_out.flush();");
            add(null, 2, "System.exit(code);");
            add(null, 1, "}");
        }
    }

    /** Android: the activity that runs the program on its own thread and shows what it prints. */
    private void emitActivity() {
        String text = read("activity.kt").replace("@APP_NAME@", string(appName));
        add(null, 0, "");
        for (String l : text.split("\n", -1)) add(null, 0, l);
        while (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
            owners.remove(owners.size() - 1);
        }
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
            if (unreachable > 0) out(0, "// (" + unreachable + " unreachable block(s) skipped)");
            for (Block b : reachable) {
                if (b.type == BlockType.CUSTOM && b.next.length > 1) {
                    out(0, (KT ? "var rt_x = 0" : "int rt_x = 0;") + "   // the exit a method left through");
                    break;
                }
            }
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
                out(0, "// (the wiring is not block-structured here, so this is a state machine: one branch per block)");
            }
            stateMachine(entry);
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
         * post-dominator). A loop's body must be a closed region. Same rules as the C, C++ and Python writers.
         *
         * <p>Each step also says whether control can run past what it wrote: Java refuses statements it can
         * prove are never reached (a for loop's step after a body that always returns, for example), so those
         * are left out, and a method whose code could run off its end gets the interpreter's error there.
         */
        private final class Structurer {
            final Block entry;
            final List<Block> nodes;
            final Map<Block, Integer> index = new HashMap<>();
            final Map<Block, Set<Block>> preds = new HashMap<>();
            final Set<Block> emitted = new HashSet<>();
            BitSet[] pdom;
            int indent = 0;

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
                    boolean more = seq(entry, null, null);
                    if (more && owner != null) {
                        stmt(throwError(string("method " + owner.qualified() + ": this path ends without reaching an Exit block")));
                    }
                    return true;
                } catch (Unstructured u) {
                    return false;
                }
            }

            void stmt(String s) {
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

            /** Writes blocks from b until stop; true when control can run past the end of what was written. */
            boolean seq(Block b, Block stop, Block loop) {
                while (b != null && b != stop) {
                    if (loop != null && b != loop && !loop.loopBody.contains(b)) throw new Unstructured();
                    if (!emitted.add(b)) throw new Unstructured();
                    current = b;
                    Interpreter.Compiled c = b.compiled();
                    stmt("// " + comment(describe(b)));
                    switch (b.type) {
                        case START, ENTER -> { }
                        case SET -> stmt(assign(c.var(), c.expr(), b));
                        case PRINT -> stmt(print(c.expr(), b));
                        case INPUT -> stmt(input(c.var(), b));
                        case END -> {
                            stmt("return" + END);
                            return false;
                        }
                        case EXIT -> {
                            stmt(exitStatement(c.var()));
                            return false;
                        }
                        case IF -> {
                            Block join = ipdom(b);
                            boolean more = ifElse(b, c, join, stop, loop);
                            if (join == null) return more;
                            b = join;
                            continue;
                        }
                        case CUSTOM -> {
                            if (b.next.length > 1) {
                                Block join = ipdom(b);
                                boolean more = multiExit(b, c, join, stop, loop);
                                if (join == null) return more;
                                b = join;
                                continue;
                            }
                            stmt(call(b, c) + END);
                        }
                        case FOR, WHILE, DO_WHILE -> {
                            loopBlock(b, c);
                            if (b.next[1] == null) {
                                for (String s : missing(b, 1)) stmt(s);
                                return false;
                            }
                            b = b.next[1];
                            continue;
                        }
                        default -> throw board(b);
                    }
                    if (b.next.length == 0 || b.next[0] == null) {
                        for (String s : missing(b, 0)) stmt(s);
                        return false;
                    }
                    b = b.next[0];
                }
                return true;
            }

            /** True when control can continue after the if statement. */
            boolean ifElse(Block b, Interpreter.Compiled c, Block join, Block stop, Block loop) {
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                String cond = cond(c.expr(), b);
                Block yes = b.next[0], no = b.next[1];
                boolean yesEmpty = yes != null && yes == inner, noEmpty = no != null && no == inner;
                if (yesEmpty && noEmpty) {
                    stmt("if (" + cond + ") { }   // evaluated for its errors, like the interpreter");
                    return true;
                }
                if (yesEmpty) {
                    stmt("if (!" + cond + ") {");
                    branch(b, 1, inner, loop);
                    stmt("}");
                    return true;
                }
                stmt("if (" + cond + ") {");
                boolean more = branch(b, 0, inner, loop);
                if (noEmpty) {
                    stmt("}");
                    return true;
                }
                stmt("} else {");
                more |= branch(b, 1, inner, loop);
                stmt("}");
                return more;
            }

            boolean multiExit(Block b, Interpreter.Compiled c, Block join, Block stop, Block loop) {
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                stmt("rt_x = " + call(b, c) + END);
                boolean more = false;
                for (int i = 0; i < b.next.length; i++) {
                    stmt((i == 0 ? "if" : "} else if") + " (rt_x == " + i + ") {   // exit '" + comment(b.portName(i)) + "'");
                    more |= branch(b, i, inner, loop);
                }
                stmt("} else {");
                indent++;
                stmt(throwError("\"bad exit index\""));
                indent--;
                stmt("}");
                return more;
            }

            boolean branch(Block from, int port, Block stop, Block loop) {
                indent++;
                Block start = from.next[port];
                boolean more;
                if (start == null) {
                    for (String s : missing(from, port)) stmt(s);
                    more = false;
                } else {
                    more = seq(start, stop, loop);
                }
                indent--;
                current = from;
                return more;
            }

            /**
             * for and while become while loops; do-while is a loop with the test at its end. A loop with no
             * condition tests rt_forever, which is always true but not a constant, so Java still counts the
             * code after the loop as reachable, as the wiring does.
             */
            void loopBlock(Block l, Interpreter.Compiled c) {
                Set<Block> body = l.loopBody;
                if (l.next[1] != null && body.contains(l.next[1])) throw new Unstructured();
                for (Block x : body) {
                    for (Block n : x.next) if (n != null && n != l && !body.contains(n)) throw new Unstructured();
                    for (Block p : preds.getOrDefault(x, Set.of())) if (p != l && !body.contains(p)) throw new Unstructured();
                }
                String cond = c.expr() == null ? "rt_forever" : cond(c.expr(), l);
                Block first = l.next[0];
                switch (l.type) {
                    case FOR -> {
                        if (c.init() != null) stmt(assign(c.init(), l));
                        stmt("while (" + cond + ") {");
                        indent++;
                        boolean more = first == null || first == l || seq(first, l, l);
                        current = l;
                        if (more && c.step() != null) stmt(assign(c.step(), l));
                        indent--;
                        stmt("}");
                    }
                    case WHILE -> {
                        stmt("while (" + cond + ") {");
                        indent++;
                        if (first != null && first != l) seq(first, l, l);
                        indent--;
                        stmt("}");
                    }
                    default -> {
                        stmt("while (rt_forever) {   // do ... while");
                        indent++;
                        boolean more = first == null || first == l || seq(first, l, l);
                        current = l;
                        if (more) {
                            stmt("if (!" + cond + ") {");
                            indent++;
                            stmt("break" + END);
                            indent--;
                            stmt("}");
                        }
                        indent--;
                        stmt("}");
                    }
                }
                current = l;
            }

            void checkJoin(Block join, Block loop) {
                if (loop != null && join != null && join != loop && !loop.loopBody.contains(join)) throw new Unstructured();
            }
        }

        // ============================================================ state machine

        /** A state: 3 per block, L&lt;id&gt; itself, L&lt;id&gt;_next (a wire back from a loop body) and L&lt;id&gt;_test (a for loop's test). */
        String state(Block b, int which) {
            return String.valueOf(b.id * 3 + which);
        }

        /**
         * Every block is a state; a loop block also has a "next" state (a wire coming back from its body)
         * and, for a for loop, a "test" state. Each branch sets the next state.
         */
        void stateMachine(Block entry) {
            List<Block> order = layout(entry);
            out(0, (KT ? "var rt_s = " : "int rt_s = ") + state(entry, 0) + END + "   // the block to run next: 3 * its number (+1 next pass, +2 test)");
            out(0, "while (true) {");
            boolean first = true;
            for (Block b : order) {
                current = b;
                Interpreter.Compiled c = b.compiled();
                String test = b.type == BlockType.WHILE ? "rt_s == " + state(b, 0) + " || rt_s == " + state(b, 1) : "rt_s == " + state(b, 0);
                out(1, (first ? "if (" : "} else if (") + test + ") {   // " + comment(describe(b)));
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
                        out(2, "if (" + cond(c.expr(), b) + ") {");
                        go(b, 0, 3);
                        out(2, "} else {");
                        go(b, 1, 3);
                        out(2, "}");
                    }
                    case END -> out(2, "return" + END);
                    case EXIT -> out(2, exitStatement(c.var()));
                    case CUSTOM -> {
                        if (b.next.length == 1) {
                            out(2, call(b, c) + END);
                            go(b, 0, 2);
                        } else {
                            out(2, "rt_x = " + call(b, c) + END);
                            for (int i = 0; i < b.next.length; i++) {
                                out(2, (i == 0 ? "if" : "} else if") + " (rt_x == " + i + ") {   // exit '" + comment(b.portName(i)) + "'");
                                go(b, i, 3);
                            }
                            out(2, "} else {");
                            out(3, throwError("\"bad exit index\""));
                            out(2, "}");
                        }
                    }
                    case FOR, WHILE, DO_WHILE -> loopStates(b, c);
                    default -> throw board(b);
                }
            }
            current = null;
            out(1, "}");
            out(0, "}");
        }

        void loopStates(Block l, Interpreter.Compiled c) {
            String cond = c.expr() == null ? null : cond(c.expr(), l);
            boolean emptyBody = l.next[0] == null || l.next[0] == l;
            switch (l.type) {
                case FOR -> {
                    if (c.init() != null) out(2, assign(c.init(), l));
                    out(2, "rt_s = " + state(l, 2) + END);
                    out(1, "} else if (rt_s == " + state(l, 1) + ") {");
                    if (c.step() != null) out(2, assign(c.step(), l));
                    out(2, "rt_s = " + state(l, 2) + END);
                    out(1, "} else if (rt_s == " + state(l, 2) + ") {");
                    testThen(l, cond, emptyBody);
                }
                case WHILE -> testThen(l, cond, emptyBody);
                default -> {
                    toBody(l, emptyBody, 2);
                    out(1, "} else if (rt_s == " + state(l, 1) + ") {");
                    out(2, "if (" + cond + ") {");
                    toBody(l, emptyBody, 3);
                    out(2, "} else {");
                    go(l, 1, 3);
                    out(2, "}");
                }
            }
        }

        void testThen(Block l, String cond, boolean emptyBody) {
            if (cond == null) {
                toBody(l, emptyBody, 2);
                return;
            }
            out(2, "if (" + cond + ") {");
            toBody(l, emptyBody, 3);
            out(2, "} else {");
            go(l, 1, 3);
            out(2, "}");
        }

        void toBody(Block l, boolean emptyBody, int indent) {
            if (emptyBody) out(indent, "rt_s = " + state(l, 1) + END);
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
            out(indent, "rt_s = " + state(tg, back ? 1 : 0) + END);
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
                return List.of(throwError(string("method " + owner.qualified()
                        + ": this path ends without reaching an Exit block")));
            }
            String msg = string(b.def != null
                    ? "(warning: exit '" + b.portName(port) + "' of " + b.method().qualified() + " is not connected - program ends)"
                    : "(warning: " + b.title() + " block has no outgoing connection - program ends)");
            return List.of("rt_print_line(" + msg + ")" + END, "return" + END);
        }

        String exitStatement(String exitName) {
            return "return " + owner.exitNames().indexOf(exitName) + END + "   // exit '" + comment(exitName) + "'";
        }

        FlowException board(Block b) {
            return new FlowException(b, "the " + b.type.title + " block runs on a microcontroller: choose Language Arduino or AVR C"
                    + " (Run simulates the board on the computer)");
        }

        String assign(String var, Expr e, Block b) {
            return target(var, b) + " = " + (FAST ? fastNum(e, b) : expr(e, b)) + END;
        }

        String assign(Interpreter.Assign a, Block b) {
            return assign(a.var(), a.expr(), b);
        }

        String print(Expr e, Block b) {
            if (!FAST) return "rt_print(" + expr(e, b) + ")" + END;
            F f = fast(e, b);
            List<F> parts = f.kind == TEXT ? mergeText(f.parts) : List.of(f);
            List<String> pieces = new ArrayList<>();
            for (F p : parts) pieces.add(p.kind == TEXT ? string(p.text) : "rt_text(" + numCode(p) + ")");
            return "rt_print_line(" + String.join(" + ", pieces) + ")" + END;
        }

        String input(String var, Block b) {
            return target(var, b) + " = " + (FAST ? "rt_input_num(" : "rt_input(") + string(var) + ")" + END;
        }

        String cond(Expr e, Block b) {
            if (FAST) return boolCode(fast(e, b), b);
            return "rt_truthy(" + expr(e, b) + ")";
        }

        /** The object is created on first use, before the arguments are evaluated (as the interpreter does). */
        String call(Block b, Interpreter.Compiled c) {
            String member = !b.objectName().isEmpty() ? "obj_" + b.objectName()
                    : owner == null ? "o" + b.id : "o_" + sanitize(owner.name) + "_" + b.id;
            String cn = className.get(b.def);
            String obj = KT ? "(" + member + " ?: " + cn + "().also { " + member + " = it })"
                    : "(" + member + " != null ? " + member + " : (" + member + " = new " + cn + "()))";
            StringBuilder s = new StringBuilder(obj + "." + methodName(b.method()) + "(");
            for (int i = 0; i < c.args().size(); i++) {
                s.append(i == 0 ? "" : ", ").append(FAST ? fastNum(c.args().get(i), b) : expr(c.args().get(i), b));
            }
            return s.append(')').toString();
        }

        // ============================================================ safe values

        String expr(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> number(n.value());
                case Expr.Str s -> string(s.value());
                case Expr.Var v -> read(v.name(), b);
                case Expr.Unary u -> (u.op().equals("-") ? "rt_neg(" : "rt_not(") + expr(u.operand(), b) + ")";
                case Expr.Binary x -> binary(x, b);
            };
        }

        String binary(Expr.Binary x, Block b) {
            String l = expr(x.left(), b), r = expr(x.right(), b);
            return switch (x.op()) {
                case "&&" -> "rt_B(rt_truthy(" + l + ") && rt_truthy(" + r + "))";
                case "||" -> "rt_B(rt_truthy(" + l + ") || rt_truthy(" + r + "))";
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
            if (board != null) return number(board);
            if (FAST) return global(name);
            return "rt_rd(" + global(name) + ", " + string(name) + ")";
        }

        String target(String name, Block b) {
            if (name.startsWith("this.")) {
                if (owner == null && cls == null) throw new FlowException(b, "'this' can only be used inside a class method");
                return field(name.substring(5));
            }
            return global(name);
        }

        // ============================================================ fast values

        static final int NUM = 0, BOOL = 1, TEXT = 2;

        /** Fast-value expression: code, its kind (a double, a boolean or text to print) and the text pieces. */
        record F(String code, int kind, List<F> parts, String text) {}

        F fast(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> new F(number(n.value()), NUM, null, null);
                case Expr.Str s -> new F(string(s.value()), TEXT,
                        List.of(new F(string(s.value()), TEXT, List.of(), s.value())), s.value());
                case Expr.Var v -> new F(read(v.name(), b), NUM, null, null);
                case Expr.Unary u -> {
                    F x = fast(u.operand(), b);
                    needNumber(x, b);
                    yield u.op().equals("-") ? new F("(-" + numCode(x) + ")", NUM, null, null)
                            : new F("(!" + boolCode(x, b) + ")", BOOL, null, null);
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
                case "&&", "||" -> new F("(" + boolCode(l, b) + " " + op + " " + boolCode(r, b) + ")", BOOL, null, null);
                case "+", "-", "*", "/", "%" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", NUM, null, null);
                case "==", "!=", "<", "<=", ">", ">=" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", BOOL, null, null);
                default -> throw new FlowException(b, "unknown operator " + op);
            };
        }

        /** A double: a comparison becomes 1.0 or 0.0, as in C. */
        String numCode(F f) {
            if (f.kind != BOOL) return f.code;
            return KT ? "(if " + f.code + " 1.0 else 0.0)" : "(" + f.code + " ? 1.0 : 0.0)";
        }

        String fastNum(Expr e, Block b) {
            F f = fast(e, b);
            needNumber(f, b);
            return CodeGenerator.unwrap(numCode(f));
        }

        String boolCode(F f, Block b) {
            needNumber(f, b);
            return f.kind == BOOL ? f.code : "(" + f.code + " != 0.0)";
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
                    r.add(new F(string(text), TEXT, null, text));
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

    /** A double literal: "3.0", "0.25", "1.0E-5"; the constants of Double for infinity and NaN. */
    static String number(double d) {
        if (Double.isNaN(d)) return "Double.NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d < 0 ? "(" + (long) d + ".0)" : (long) d + ".0";
        String s = Double.toString(d);
        return d < 0 ? "(" + s + ")" : s;
    }

    /**
     * A string literal that reads the same in Java and Kotlin: only ASCII, everything else as a \\u escape
     * (so the file compiles whatever encoding javac assumes), and $ escaped for Kotlin's templates.
     */
    private String string(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '$' -> sb.append(KT ? "\\$" : "$");
                default -> {
                    if (c < 0x20 || c >= 0x7F) sb.append(String.format("\\u%04x", (int) c));
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

    /**
     * Text safe after // on one line. Java reads \\u escapes even inside comments, so a backslash before
     * a u gets a space; line separators end a comment too.
     */
    private static String comment(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace(' ', ' ').replace(' ', ' ').replace('\u0085', ' ')
                .replace("\\u", "\\ u");
    }

    /** Text safe inside a doc comment. */
    private static String docText(String s) {
        return comment(s).replace("*/", "* /");
    }
}
