package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.BitSet;

/**
 * Translates a block program into a single C++20 source file.
 *
 * <ul>
 *   <li>Each custom block definition becomes a {@code struct} (the class). Its parameters and
 *       {@code this.x} fields become members, every custom block placed in its body becomes a
 *       {@code std::unique_ptr} member (an object owned by this object), and the body becomes
 *       {@code int call(...)}, which returns the index of the Exit it left through.</li>
 *   <li>The main program becomes {@code struct Program} with {@code run()}.</li>
 *   <li>Program variables become globals {@code v_name}.</li>
 *   <li>A block graph is emitted as labeled statements: blocks are laid out along the most
 *       likely path so that most connections are plain fall-through, the rest are {@code goto}s.
 *       That handles any wiring, including loops built from back-connections.</li>
 * </ul>
 */
final class CppGenerator {

    private static final Set<String> RESERVED = Set.of(
            // C++ keywords that are valid identifiers here
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break", "case",
            "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept", "const", "consteval",
            "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return", "co_yield", "decltype",
            "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit", "export", "extern",
            "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace",
            "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private", "protected",
            "public", "register", "reinterpret_cast", "requires", "return", "short", "signed", "sizeof",
            "static", "static_assert", "static_cast", "struct", "switch", "template", "this", "thread_local",
            "throw", "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual",
            "void", "volatile", "wchar_t", "while", "xor", "xor_eq",
            // names used by the runtime and generated code
            "Value", "N", "S", "B", "rd", "str", "num", "truthy", "nonZero", "neg", "lnot", "equal", "compare",
            "eq", "ne", "lt", "le", "gt", "ge", "print", "input", "depth", "DepthGuard", "obj", "Program",
            "main", "std", "evia_rt", "prog");

    private final Library lib;
    private final Map<BlockDef, String> className = new LinkedHashMap<>();
    private final Set<String> globals = new TreeSet<>();
    private final Map<BlockDef, Set<String>> fields = new HashMap<>();
    private final StringBuilder out = new StringBuilder();
    /** 0-based [first, end) line ranges of the code generated for each Block, Method and BlockDef. */
    private final Map<Object, List<int[]>> sourceMap = new HashMap<>();

    /** Generated code plus where each block, method and class ended up in it. */
    record Result(String code, Map<Object, List<int[]>> sourceMap) {}

    private CppGenerator(Library lib) {
        this.lib = lib;
    }

    /** Checks the program (like Run does) and returns the C++20 source. */
    static String generate(Library lib) {
        return generateWithMap(lib).code();
    }

    static Result generateWithMap(Library lib) {
        Interpreter.prepare(lib);
        CppGenerator g = new CppGenerator(lib);
        String code = g.build();
        return new Result(code, g.sourceMap);
    }

    private int lineNow() {
        int n = 0;
        for (int i = 0; i < out.length(); i++) if (out.charAt(i) == '\n') n++;
        return n;
    }

    /** Attributes one output line to a block, extending its last range when contiguous. */
    private void markLine(Block b, int line) {
        if (b == null) return;
        List<int[]> r = sourceMap.computeIfAbsent(b, k -> new ArrayList<>());
        if (!r.isEmpty() && r.get(r.size() - 1)[1] == line) r.get(r.size() - 1)[1] = line + 1;
        else r.add(new int[]{line, line + 1});
    }

    private void mark(Object key, int from, int to) {
        if (to > from) sourceMap.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{from, to});
    }

    // ================================================================ top level

    private String build() {
        Set<String> usedClassNames = new HashSet<>();
        for (BlockDef d : lib.defs()) {
            String n = sanitize(d.name);
            if (RESERVED.contains(n) || !usedClassNames.add(n)) n = n + "_" + d.id;
            usedClassNames.add(n);
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

        out.append("// Generated by EVIA Paradigma ").append(App.VERSION).append(" on ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        out.append("// Build:  clang++ -std=c++20 -O2 -o program program.cpp   (made for Clang ")
                .append(Settings.LATEST_CLANG).append(")\n");
        out.append("// Windows: Clang from llvm-mingw, add -static so the program runs without extra DLLs\n");
        out.append("// Also:   g++ -std=c++20 -O2 -o program program.cpp\n\n");
        out.append(CppRuntime.SOURCE).append('\n');

        out.append("namespace prog {\n");
        out.append("using namespace evia_rt;\n\n");

        out.append("// ---- program variables\n");
        if (globals.isEmpty()) out.append("// (none)\n");
        for (String g : globals) out.append("Value ").append(global(g)).append(";\n");
        out.append('\n');

        if (!lib.defs().isEmpty()) {
            out.append("// ---- classes\n");
            for (BlockDef d : lib.defs()) out.append("struct ").append(className.get(d)).append(";\n");
            out.append('\n');
            for (BlockDef d : lib.defs()) emitStruct(d);
        }
        emitProgramStruct();

        for (BlockDef d : lib.defs()) emitConstructor(d);
        out.append("Program::~Program() = default;\n\n");

        for (BlockDef d : lib.defs()) for (Method m : d.methods) emitMethod(m);
        emitRun();

        out.append("}  // namespace prog\n\n");
        out.append("""
                int main() {
                    try {
                        prog::Program program;
                        program.run();
                    } catch (const std::exception& e) {
                        std::cout.flush();
                        std::cerr << ">> error: " << e.what() << '\\n';
                        return 1;
                    }
                    return 0;
                }
                """);
        return out.toString();
    }

    private void emitStruct(BlockDef d) {
        int from = lineNow();
        String cn = className.get(d);
        out.append("/// class ").append(comment(d.name)).append(d.module != null ? "   (module " + comment(d.module) + ")" : "").append('\n');
        out.append("struct ").append(cn).append(" {\n");
        for (String f : fields.get(d)) out.append("    Value ").append(field(f)).append(" = N(0);\n");
        Set<String> named = new LinkedHashSet<>();
        for (Method m : d.methods) emitObjectMembers(m.body, "o_" + sanitize(m.name) + "_", named);
        out.append("    ").append(cn).append("();\n");
        out.append("    ~").append(cn).append("();\n");
        for (Method m : d.methods) {
            List<String> exits = m.exitNames();
            out.append("    int ").append(methodName(m)).append("(");
            for (int i = 0; i < m.params.size(); i++) out.append(i == 0 ? "" : ", ").append("Value ").append(param(m.params.get(i)));
            out.append(");   // ").append(m.isConstructor() ? "constructor; " : "").append("exits: ");
            for (int i = 0; i < exits.size(); i++) out.append(i == 0 ? "" : ", ").append(i).append(" = ").append(exits.get(i));
            out.append('\n');
        }
        out.append("};\n\n");
        mark(d, from, lineNow());
    }

    private void emitProgramStruct() {
        out.append("/// The main program. Owns the objects used on the main canvas.\n");
        out.append("struct Program {\n");
        emitObjectMembers(lib.main, "o", new LinkedHashSet<>());
        out.append("    ~Program();\n    void run();\n};\n\n");
    }

    /** Anonymous objects: one member per calling block. Named objects: one member per name. */
    private void emitObjectMembers(FlowCanvas c, String anonPrefix, Set<String> named) {
        for (Block b : c.getBlocks()) {
            if (b.def == null) continue;
            if (b.objectName().isEmpty()) {
                out.append("    std::unique_ptr<").append(className.get(b.def)).append("> ").append(anonPrefix).append(b.id)
                        .append(";   // object of ").append(comment(b.def.name)).append('\n');
            } else if (named.add(b.objectName())) {
                out.append("    std::unique_ptr<").append(className.get(b.def)).append("> ").append(objectMember(b.objectName()))
                        .append(";   // object ").append(comment(b.objectName())).append(" : ").append(comment(b.def.name)).append('\n');
            }
        }
    }

    /** Constructor: declared fields get their initial values in order, then init() runs. */
    private void emitConstructor(BlockDef d) {
        int from = lineNow();
        String cn = className.get(d);
        out.append(cn).append("::").append(cn).append("() {\n");
        Method ctor = d.constructor();
        GraphWriter w = new GraphWriter(ctor != null ? ctor : d.methods.get(0));
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            String prefix = "initial value of field " + d.name + "." + f.name() + ": ";
            out.append("    try { ").append(field(f.name())).append(" = ").append(w.expr(d.fieldInits.get(i), null)).append("; }\n");
            out.append("    catch (const std::runtime_error& e) { throw std::runtime_error(std::string(")
                    .append(cppString(prefix)).append(") + e.what()); }\n");
        }
        if (ctor != null) out.append("    ").append(methodName(ctor)).append("();\n");
        out.append("}\n");
        out.append(cn).append("::~").append(cn).append("() = default;\n\n");
        mark(d, from, lineNow());
    }

    private void emitMethod(Method m) {
        int from = lineNow();
        String cn = className.get(m.cls);
        out.append("int ").append(cn).append("::").append(methodName(m)).append("(");
        for (int i = 0; i < m.params.size(); i++) out.append(i == 0 ? "" : ", ").append("Value ").append(param(m.params.get(i)));
        out.append(") {   // ").append(comment(m.qualified())).append('\n');
        out.append("    DepthGuard guard;\n");
        for (String p : m.params) out.append("    ").append(field(p)).append(" = std::move(").append(param(p)).append(");\n");
        new GraphWriter(m).write(m.enter());
        out.append("}\n\n");
        mark(m, from, lineNow());
        mark(m.cls, from, lineNow());
    }

    private void emitRun() {
        out.append("void Program::run() {\n");
        Block start = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.START) start = b;
        new GraphWriter(null).write(start);
        out.append("}\n\n");
    }

    private static String methodName(Method m) {
        return "m_" + m.name;
    }

    private static String objectMember(String name) {
        return "obj_" + name;
    }

    // ================================================================ graphs

    private final class GraphWriter {
        final Method owner;                      // null = main program
        final List<String> lines = new ArrayList<>();
        final Set<String> targets = new HashSet<>();
        final List<Block> lineOwners = new ArrayList<>();
        /** The block whose code is being written; every emitted line is attributed to it (View C++ source). */
        Block current;

        GraphWriter(Method owner) {
            this.owner = owner;
        }

        /** Structured C++ when the wiring allows it, otherwise labels and goto. */
        void write(Block entry) {
            Set<Block> reachable = reachable(entry);
            int unreachable = (owner == null ? lib.main : owner.body).getBlocks().size() - reachable.size();
            if (unreachable > 0) out.append("    // (").append(unreachable).append(" unreachable block(s) skipped)\n");
            Structurer st = new Structurer(entry, reachable);
            List<String> structured = st.emit();
            if (structured != null) {
                int ln = lineNow();
                for (int i = 0; i < structured.size(); i++, ln++) {
                    out.append(structured.get(i)).append('\n');
                    markLine(st.outOwners.get(i), ln);
                }
                return;
            }
            out.append("    // (wiring is not block-structured here, so this uses labels)\n");
            List<Block> order = layout(entry);
            for (int i = 0; i < order.size(); i++) {
                Block b = order.get(i);
                Block after = i + 1 < order.size() ? order.get(i + 1) : null;
                current = b;
                label("L" + b.id);
                line("// " + comment(describe(b)));
                emitBlock(b, after);
            }
            int ln = lineNow();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("\u0000")) {
                    String name = l.substring(1);
                    if (!targets.contains(name)) continue;
                    out.append(name).append(":\n");
                } else {
                    out.append(l).append('\n');
                }
                markLine(lineOwners.get(i), ln++);
            }
        }

        // ============================================================ structured mode

        /** Thrown when the graph can't be written as nested if/else, loops and switch. */
        private static final class Unstructured extends RuntimeException {
            Unstructured() {
                super(null, null, false, false);
            }
        }

        /**
         * Writes a graph as nested C++ statements. If/else and multi-exit custom blocks continue at
         * their immediate post-dominator (the join point). A loop block's body must be a closed
         * region: nothing jumps into it except through the loop block, nothing jumps out of it
         * except back to the loop block (or a return/throw). Anything else is "unstructured".
         */
        private final class Structurer {
            final Block entry;
            final List<Block> nodes;
            final Map<Block, Integer> index = new HashMap<>();
            final Map<Block, Set<Block>> preds = new HashMap<>();
            BitSet[] pdom;
            final Set<Block> emitted = new HashSet<>();
            final List<String> outLines = new ArrayList<>();
            final List<Block> outOwners = new ArrayList<>();
            int indent = 1;

            Structurer(Block entry, Set<Block> reachable) {
                this.entry = entry;
                this.nodes = new ArrayList<>(reachable);
                for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i), i);
                for (Block b : nodes) {
                    for (Block n : b.next) if (n != null) preds.computeIfAbsent(n, k -> new HashSet<>()).add(b);
                }
            }

            List<String> emit() {
                try {
                    postDominators();
                    seq(entry, null, null);
                    return outLines;
                } catch (Unstructured u) {
                    return null;
                }
            }

            /** Successor indices; nodes.size() stands for "leaves the function". */
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

            /** Immediate post-dominator, or null when the paths only meet at the end of the function. */
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

            void add(String s) {
                outLines.add("    ".repeat(indent) + s);
                outOwners.add(current);
            }

            /** Emits blocks from {@code b} until {@code stop} (a join point or the enclosing loop block). */
            void seq(Block b, Block stop, Block loop) {
                while (b != null && b != stop) {
                    if (loop != null && b != loop && !loop.loopBody.contains(b)) throw new Unstructured();
                    if (!emitted.add(b)) throw new Unstructured();
                    current = b;
                    Interpreter.Compiled c = b.compiled();
                    add("// " + comment(describe(b)));
                    switch (b.type) {
                        case START, ENTER -> { }
                        case SET -> add(target(c.var(), b) + " = " + expr(c.expr(), b) + ";");
                        case PRINT -> add("print(" + expr(c.expr(), b) + ");");
                        case INPUT -> add(target(c.var(), b) + " = input(" + cppString(c.var()) + ");");
                        case END -> {
                            add("return;");
                            return;
                        }
                        case EXIT -> {
                            add("return " + owner.exitNames().indexOf(c.var()) + ";   // exit '" + c.var() + "'");
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
                            add(call(b, c) + ";");
                        }
                        case FOR, WHILE, DO_WHILE -> {
                            loopBlock(b, c);
                            if (b.next[1] == null) {
                                add(missing(b, 1));
                                return;
                            }
                            b = b.next[1];
                            continue;
                        }
                    }
                    if (b.next.length == 0 || b.next[0] == null) {
                        add(missing(b, 0));
                        return;
                    }
                    b = b.next[0];
                }
            }

            /** if (cond) { then } else { else }, continuing at the join point. */
            Block ifElse(Block b, Interpreter.Compiled c, Block stop, Block loop) {
                Block join = ipdom(b);
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                String cond = "truthy(" + expr(c.expr(), b) + ")";
                Block yes = b.next[0], no = b.next[1];
                boolean yesEmpty = yes != null && yes == inner, noEmpty = no != null && no == inner;
                if (yesEmpty && noEmpty) {
                    add("(void) " + cond + ";");
                } else if (yesEmpty) {
                    add("if (!" + cond + ") {");
                    branch(b, 1, inner, loop);
                    add("}");
                } else {
                    add("if (" + cond + ") {");
                    branch(b, 0, inner, loop);
                    if (!noEmpty) {
                        add("} else {");
                        branch(b, 1, inner, loop);
                    }
                    add("}");
                }
                return join;
            }

            /** A custom block with several exits: switch on the exit index. */
            Block multiExit(Block b, Interpreter.Compiled c, Block stop, Block loop) {
                Block join = ipdom(b);
                checkJoin(join, loop);
                Block inner = join != null ? join : stop;
                add("switch (" + call(b, c) + ") {");
                for (int i = 0; i < b.next.length; i++) {
                    add("    case " + i + ": {   // " + comment(b.portName(i)));
                    indent++;
                    branch(b, i, inner, loop);
                    indent--;
                    add("        break;");
                    add("    }");
                }
                add("    default: throw std::logic_error(\"bad exit index\");");
                add("}");
                return join;
            }

            /** One branch of an if/else or switch: output {@code port} of {@code from}. */
            void branch(Block from, int port, Block stop, Block loop) {
                indent++;
                Block start = from.next[port];
                if (start == null) add(missing(from, port));
                else seq(start, stop, loop);
                indent--;
                current = from;
            }

            /** for / while / do-while; the body must be a closed region of the graph. */
            void loopBlock(Block l, Interpreter.Compiled c) {
                Set<Block> body = l.loopBody;
                if (l.next[1] != null && body.contains(l.next[1])) throw new Unstructured();
                for (Block x : body) {
                    for (Block n : x.next) if (n != null && n != l && !body.contains(n)) throw new Unstructured();
                    for (Block p : preds.getOrDefault(x, Set.of())) if (p != l && !body.contains(p)) throw new Unstructured();
                }
                String cond = c.expr() == null ? "" : "truthy(" + expr(c.expr(), l) + ")";
                switch (l.type) {
                    case FOR -> add("for (" + assign(c.init(), l) + "; " + cond + "; " + assign(c.step(), l) + ") {");
                    case WHILE -> add("while (" + cond + ") {");
                    default -> add("do {");
                }
                Block first = l.next[0];
                if (first != null && first != l) {
                    indent++;
                    seq(first, l, l);
                    indent--;
                }
                current = l;
                add(l.type == BlockType.DO_WHILE ? "} while (" + cond + ");" : "}");
            }

            void checkJoin(Block join, Block loop) {
                if (loop != null && join != null && join != loop && !loop.loopBody.contains(join)) throw new Unstructured();
            }
        }

        String assign(Interpreter.Assign a, Block b) {
            return a == null ? "" : target(a.var(), b) + " = " + expr(a.expr(), b);
        }

        String call(Block b, Interpreter.Compiled c) {
            String member = !b.objectName().isEmpty() ? objectMember(b.objectName())
                    : owner == null ? "o" + b.id : "o_" + sanitize(owner.name) + "_" + b.id;
            StringBuilder call = new StringBuilder("obj(" + member + ")." + methodName(b.method()) + "(");
            for (int i = 0; i < c.args().size(); i++) call.append(i == 0 ? "" : ", ").append(expr(c.args().get(i), b));
            return call.append(')').toString();
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

        // ============================================================ label (goto) mode

        /** Orders blocks so that each block is followed by its first successor whenever possible. */
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

        void label(String name) {
            lines.add("\u0000" + name);
            lineOwners.add(current);
        }

        void emitBlock(Block b, Block after) {
            Interpreter.Compiled c = b.compiled();
            switch (b.type) {
                case START, ENTER -> line(go(b, 0, after));
                case SET -> {
                    line(target(c.var(), b) + " = " + expr(c.expr(), b) + ";");
                    line(go(b, 0, after));
                }
                case PRINT -> {
                    line("print(" + expr(c.expr(), b) + ");");
                    line(go(b, 0, after));
                }
                case INPUT -> {
                    line(target(c.var(), b) + " = input(" + cppString(c.var()) + ");");
                    line(go(b, 0, after));
                }
                case IF -> {
                    String cond = "truthy(" + expr(c.expr(), b) + ")";
                    if (b.next[0] != null && b.next[0] == after && !isBackEdge(b, after)) {
                        line("if (!" + cond + ") " + go(b, 1, null));
                    } else {
                        line("if (" + cond + ") " + go(b, 0, null));
                        line(go(b, 1, after));
                    }
                }
                case FOR, WHILE, DO_WHILE -> loopLabels(b, c, after);
                case END -> line("return;");
                case EXIT -> line("return " + owner.exitNames().indexOf(c.var()) + ";   // exit '" + c.var() + "'");
                case CUSTOM -> {
                    String call = call(b, c);
                    if (b.next.length == 1) {
                        line(call + ";");
                        line(go(b, 0, after));
                    } else {
                        line("switch (" + call + ") {");
                        for (int i = 0; i < b.next.length; i++) {
                            String g = go(b, i, after);
                            line("    case " + i + ": " + (g.isEmpty() ? "break;" : g)
                                    + "   // " + comment(b.portName(i)));
                        }
                        line("    default: throw std::logic_error(\"bad exit index\");");
                        line("}");
                    }
                }
            }
        }

        /**
         * A loop block as labels: Ln = entering the loop, Ln_next = the next pass (wires coming
         * back from the body), Ln_test = the condition.
         */
        void loopLabels(Block l, Interpreter.Compiled c, Block after) {
            String id = "L" + l.id;
            String cond = c.expr() == null ? null : "truthy(" + expr(c.expr(), l) + ")";
            String toBody = l.next[0] == null || l.next[0] == l ? jump(id + "_next") : go(l, 0, null);
            switch (l.type) {
                case FOR -> {
                    if (c.init() != null) line(assign(c.init(), l) + ";");
                    line(jump(id + "_test"));
                    label(id + "_next");
                    if (c.step() != null) line(assign(c.step(), l) + ";");
                    label(id + "_test");
                    if (cond != null) line("if (!" + cond + ") " + go(l, 1, null));
                    line(toBody);
                }
                case WHILE -> {
                    label(id + "_next");
                    line("if (!" + cond + ") " + go(l, 1, null));
                    line(toBody);
                }
                default -> {
                    line(toBody);
                    label(id + "_next");
                    line("if (" + cond + ") " + toBody);
                    line(go(l, 1, after));
                }
            }
        }

        String jump(String label) {
            targets.add(label);
            return "goto " + label + ";";
        }

        boolean isBackEdge(Block from, Block to) {
            return to != null && to.type.isLoop() && (to.loopBody.contains(from) || from == to);
        }

        /** Code to continue from output {@code port} of {@code b}; empty when it simply falls through. */
        String go(Block b, int port, Block after) {
            Block t = b.next[port];
            if (t == null) return missing(b, port);
            if (isBackEdge(b, t)) return jump("L" + t.id + "_next");
            if (t == after) return "";
            return jump("L" + t.id);
        }

        /** Same behaviour as the interpreter for an unconnected output. */
        String missing(Block b, int port) {
            if (owner != null) {
                return "throw std::runtime_error(" + cppString("method " + owner.qualified()
                        + ": this path ends without reaching an Exit block") + ");";
            }
            String msg = b.def != null
                    ? "(warning: exit '" + b.portName(port) + "' of " + b.method().qualified() + " is not connected - program ends)"
                    : "(warning: " + b.title() + " block has no outgoing connection - program ends)";
            return "{ print(S(" + cppString(msg) + ")); return; }";
        }

        void line(String s) {
            if (s.isEmpty()) return;
            lines.add("    " + s);
            lineOwners.add(current);
        }

        // ---- expressions

        String expr(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> "N(" + number(n.value()) + ")";
                case Expr.Str s -> "S(" + cppString(s.value()) + ")";
                case Expr.Var v -> read(v.name(), b);
                case Expr.Unary u -> (u.op().equals("-") ? "neg(" : "lnot(") + expr(u.operand(), b) + ")";
                case Expr.Binary x -> binary(x, b);
            };
        }

        String binary(Expr.Binary x, Block b) {
            String l = expr(x.left(), b), r = expr(x.right(), b);
            return switch (x.op()) {
                case "&&" -> "B(truthy(" + l + ") && truthy(" + r + "))";
                case "||" -> "B(truthy(" + l + ") || truthy(" + r + "))";
                case "+", "-", "*", "/", "%" -> "(" + l + " " + x.op() + " " + r + ")";
                case "==" -> "eq(" + l + ", " + r + ")";
                case "!=" -> "ne(" + l + ", " + r + ")";
                case "<" -> "lt(" + l + ", " + r + ")";
                case "<=" -> "le(" + l + ", " + r + ")";
                case ">" -> "gt(" + l + ", " + r + ")";
                case ">=" -> "ge(" + l + ", " + r + ")";
                default -> throw new FlowException(b, "unknown operator " + x.op());
            };
        }

        String read(String name, Block b) {
            if (name.startsWith("this.")) return target(name, b);
            return "rd(" + global(name) + ", " + cppString(name) + ")";
        }

        String target(String name, Block b) {
            if (name.startsWith("this.")) {
                if (owner == null) throw new FlowException(b, "'this' can only be used inside a class method");
                return field(name.substring(5));
            }
            return global(name);
        }
    }

    // ================================================================ name collection

    private void collectNames(List<Block> blocks, BlockDef owner) {
        for (Block b : blocks) {
            Interpreter.Compiled c = b.compiled();
            if ((b.type == BlockType.SET || b.type == BlockType.INPUT) && c.var() != null) addName(c.var(), owner, b);
            if (c.expr() != null) collectExpr(c.expr(), owner, b);
            if (c.args() != null) for (Expr a : c.args()) collectExpr(a, owner, b);
        }
    }

    private void collectExpr(Expr e, BlockDef owner, Block b) {
        switch (e) {
            case Expr.Var v -> addName(v.name(), owner, b);
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

    private static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    /** C++ string literal; non-ASCII is written as octal UTF-8 bytes so the source encoding doesn't matter. */
    static String cppString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            int c = by & 0xFF;
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '?' -> sb.append("\\?");           // avoid trigraph-looking sequences
                default -> {
                    if (c < 0x20 || c >= 0x7F) sb.append(String.format("\\%03o", c));
                    else sb.append((char) c);
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

    /** Text safe to put after // on one line. */
    private static String comment(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace("\\", "/");
    }
}
