package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Translates a block program into one C or C++ source file (see {@link Target}).
 *
 * <ul>
 *   <li>A class becomes a {@code struct}: its fields (declared, parameters and {@code this.x}) are
 *       members, objects used by its methods are members created on first use, and each method
 *       returns the index of the Exit it left through. C++: member functions; C: functions
 *       {@code Class_m_method(Class *self, ...)}.</li>
 *   <li>The main program: C++ {@code struct Program} with {@code run()}; C {@code run_program()}.</li>
 *   <li>Program variables are globals {@code v_name}.</li>
 *   <li>Flow: STRUCTURED writes nested if/else, loops and switch where the wiring allows it and
 *       labels elsewhere; GOTO writes every block as a label with jumps.</li>
 *   <li>Values: SAFE uses the runtime's dynamic values with the interpreter's exact checks; FAST
 *       uses plain {@code double} (text only in Print, no runtime checks).</li>
 * </ul>
 */
final class CodeGenerator {

    private static final Set<String> RESERVED = Set.of(
            // C and C++ keywords that are valid identifiers here
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break", "case",
            "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept", "const", "consteval",
            "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return", "co_yield", "decltype",
            "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit", "export", "extern",
            "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace",
            "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private", "protected",
            "public", "register", "reinterpret_cast", "requires", "restrict", "return", "short", "signed", "sizeof",
            "static", "static_assert", "static_cast", "struct", "switch", "template", "this", "thread_local",
            "throw", "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual",
            "void", "volatile", "wchar_t", "while", "xor", "xor_eq",
            // names used by the runtimes and generated code
            "Value", "N", "S", "B", "rd", "str", "num", "truthy", "nonZero", "neg", "lnot", "equal", "compare",
            "eq", "ne", "lt", "le", "gt", "ge", "print", "input", "input_num", "parse_num", "read_line", "depth",
            "DepthGuard", "obj", "Program", "main", "std", "evia_rt", "prog", "self", "run_program",
            // C library names a C class (a typedef) must not hide
            "abs", "atof", "atoi", "atol", "calloc", "div", "exit", "fabs", "floor", "fmod", "fopen", "fprintf",
            "fputs", "free", "getchar", "labs", "malloc", "memcpy", "printf", "putchar", "puts", "realloc",
            "snprintf", "sprintf", "strchr", "strcmp", "strcpy", "strlen", "strtod", "system", "time", "FILE",
            "NULL", "EOF", "HUGE_VAL", "stdin", "stdout", "stderr", "size_t",
            // Arduino names a class must not hide (sketches)
            "Serial", "Serial1", "String", "Print", "Stream", "Wire", "SPI", "EEPROM", "setup", "loop", "delay",
            "millis", "micros", "pinMode", "digitalWrite", "digitalRead", "analogRead", "analogWrite", "yield",
            "boolean", "byte", "word", "min", "max", "round", "constrain", "sq", "bit", "radians", "degrees", "F");

    private final Library lib;
    private final Target t;
    private final boolean C, FAST;
    /** Microcontroller target (Arduino sketch or bare-metal AVR C): C code, fast values, Serial for Print and Input. */
    private final boolean EMB;
    private final Map<BlockDef, String> className = new LinkedHashMap<>();
    private final Set<String> globals = new TreeSet<>();
    private final Map<BlockDef, Set<String>> fields = new HashMap<>();
    private final StringBuilder out = new StringBuilder();
    /** 0-based [first, end) line ranges of the code generated for each Block, Method and BlockDef. */
    private final Map<Object, List<int[]>> sourceMap = new HashMap<>();

    /** Generated code plus where each block, method and class ended up in it. */
    record Result(String code, Map<Object, List<int[]>> sourceMap) {}

    private CodeGenerator(Library lib, Target t) {
        this.lib = lib;
        this.t = t;
        this.C = t.isC() || t.embedded();
        this.FAST = t.fast();
        this.EMB = t.embedded();
    }

    /** Checks the program (like Run does) and returns the C++20 source with safe values. */
    static String generate(Library lib) {
        return generate(lib, Target.DEFAULT);
    }

    static String generate(Library lib, Target t) {
        return generateWithMap(lib, t).code();
    }

    static Result generateWithMap(Library lib) {
        return generateWithMap(lib, Target.DEFAULT);
    }

    static Result generateWithMap(Library lib, Target t) {
        Interpreter.prepare(lib);
        CodeGenerator g = new CodeGenerator(lib, t);
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
            // on a microcontroller an all-capitals name could be a register macro (ADC, PORTB ...)
            boolean macroLike = EMB && n.equals(n.toUpperCase(java.util.Locale.ROOT));
            if (RESERVED.contains(n) || macroLike || n.startsWith("rt_") || n.startsWith("RT_") || !usedClassNames.add(n)) n = n + "_" + d.id;
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

        if (C) buildC();
        else buildCpp();

        // header and runtime go in front; shift the source map by their length
        String body = out.toString();
        String head = header() + (EMB ? Runtimes.embedded(t, body) : C ? Runtimes.c(t, body) : Runtimes.cpp(t)) + "\n";
        int shift = (int) head.chars().filter(ch -> ch == '\n').count();
        for (List<int[]> ranges : sourceMap.values()) {
            for (int[] r : ranges) {
                r[0] += shift;
                r[1] += shift;
            }
        }
        return head + body;
    }

    private String header() {
        String stamp = "// Generated by EVIA Paradigma " + App.VERSION + " on "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n"
                + "// Language: " + t.describe() + "\n";
        if (t.lang() == Target.Lang.ARDUINO) {
            return stamp
                    + "// Boards:   Arduino Uno, Nano, Mega 2560, Leonardo, Micro, Pro Mini; ESP32, ESP32-S2, -S3, -C3, -C6, -H2\n"
                    + "// Build:    arduino-cli compile --fqbn arduino:avr:uno program   (the sketch is program/program.ino)\n"
                    + "// Upload:   arduino-cli upload --fqbn arduino:avr:uno -p /dev/ttyACM0 program\n"
                    + "// Print and Input use the serial port at 115200 baud (Serial Monitor).\n\n";
        }
        if (t.lang() == Target.Lang.AVR) {
            return stamp
                    + "// Chips:    ATmega328P (Uno, Nano, Pro Mini), ATmega2560 (Mega 2560), ATmega32U4 (Leonardo, Micro)\n"
                    + "// Build:    " + Board.UNO.avrGcc("program.c", "program.elf") + "\n"
                    + "//           avr-objcopy -O ihex -R .eeprom program.elf program.hex\n"
                    + "// Upload:   avrdude -p atmega328p -c arduino -b 115200 -P /dev/ttyACM0 -U flash:w:program.hex:i\n"
                    + "// Print and Input use the UART at 115200 baud (USART0; USART1 = pins 0 and 1 on the ATmega32U4).\n"
                    + "// No Arduino core: pins, timers, PWM, the ADC and the UART are driven through the registers below.\n\n";
        }
        String cc = C ? "clang" : "clang++", gcc = C ? "gcc" : "g++";
        String file = "program" + t.ext() + (C ? " -lm" : "");
        return "// Generated by EVIA Paradigma " + App.VERSION + " on "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n"
                + "// Language: " + t.describe() + "\n"
                + "// Build:   " + cc + " -std=" + t.lang().std + " -O2 -o program " + file
                + "   (made for Clang " + Settings.LATEST_CLANG + ")\n"
                + "// Windows: Clang from llvm-mingw, add -static so the program runs without extra DLLs\n"
                + "// Also:    " + gcc + " -std=" + t.lang().std + " -O2 -o program " + file + "\n\n";
    }

    private String valueType() {
        return FAST ? "double" : "Value";
    }

    // ================================================================ C++

    private void buildCpp() {
        out.append("namespace prog {\n");
        out.append("using namespace evia_rt;\n\n");

        out.append("// ---- program variables\n");
        if (globals.isEmpty()) out.append("// (none)\n");
        for (String g : globals) out.append(valueType()).append(' ').append(global(g)).append(FAST ? " = 0;\n" : ";\n");
        out.append('\n');

        if (!lib.defs().isEmpty()) {
            out.append("// ---- classes\n");
            for (BlockDef d : lib.defs()) out.append("struct ").append(className.get(d)).append(";\n");
            out.append('\n');
            for (BlockDef d : lib.defs()) emitStruct(d);
        }
        emitProgramStruct();

        for (BlockDef d : lib.defs()) emitConstructor(d);
        emitProgramConstructor();

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
    }

    /** Object members of a canvas: anonymous objects (one per calling block) and named objects (one per name). */
    private record ObjMember(String member, BlockDef cls, String label) {}

    private List<ObjMember> objectMembers(FlowCanvas c, String anonPrefix, Set<String> named) {
        List<ObjMember> r = new ArrayList<>();
        for (Block b : c.getBlocks()) {
            if (b.def == null) continue;
            if (b.objectName().isEmpty()) {
                r.add(new ObjMember(anonPrefix + b.id, b.def, "object of " + comment(b.def.name)));
            } else if (named.add(b.objectName())) {
                r.add(new ObjMember(objectMember(b.objectName()), b.def,
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

    private String ptrType(BlockDef d) {
        String cn = className.get(d);
        if (C) return cn + " *";
        return t.lang().cpp() < 11 ? cn + "* " : "std::unique_ptr<" + cn + "> ";
    }

    private void emitStruct(BlockDef d) {
        int from = lineNow();
        String cn = className.get(d);
        boolean cpp98 = t.lang().cpp() < 11;
        out.append("/// class ").append(comment(d.name)).append(d.module != null ? "   (module " + comment(d.module) + ")" : "").append('\n');
        out.append("struct ").append(cn).append(" {\n");
        for (String f : fields.get(d)) {
            out.append("    ").append(valueType()).append(' ').append(field(f));
            if (!cpp98) out.append(FAST ? " = 0" : " = N(0)");
            out.append(";\n");
        }
        for (ObjMember o : classObjects(d)) {
            out.append("    ").append(ptrType(o.cls())).append(o.member()).append(";   // ").append(o.label()).append('\n');
        }
        out.append("    ").append(cn).append("();\n");
        out.append("    ~").append(cn).append("();\n");
        for (Method m : d.methods) {
            out.append("    int ").append(methodName(m)).append("(").append(params(m)).append(");   // ")
                    .append(m.isConstructor() ? "constructor; " : "").append(exitsComment(m)).append('\n');
        }
        if (cpp98) {
            out.append("private:   // not copyable (owns its objects)\n");
            out.append("    ").append(cn).append("(const ").append(cn).append("&);\n");
            out.append("    ").append(cn).append("& operator=(const ").append(cn).append("&);\n");
        }
        out.append("};\n\n");
        mark(d, from, lineNow());
    }

    private String params(Method m) {
        StringBuilder s = new StringBuilder();
        if (C) s.append(className.get(m.cls)).append(" *self");
        for (int i = 0; i < m.params.size(); i++) {
            if (C || i > 0) s.append(", ");
            s.append(valueType()).append(' ').append(param(m.params.get(i)));
        }
        if (C && s.isEmpty()) s.append("void");
        return s.toString();
    }

    private static String exitsComment(Method m) {
        List<String> exits = m.exitNames();
        StringBuilder s = new StringBuilder("exits: ");
        for (int i = 0; i < exits.size(); i++) s.append(i == 0 ? "" : ", ").append(i).append(" = ").append(comment(exits.get(i)));
        return s.toString();
    }

    private void emitProgramStruct() {
        boolean cpp98 = t.lang().cpp() < 11;
        out.append("/// The main program. Owns the objects used on the main canvas.\n");
        out.append("struct Program {\n");
        for (ObjMember o : objectMembers(lib.main, "o", new LinkedHashSet<>())) {
            out.append("    ").append(ptrType(o.cls())).append(o.member()).append(";   // ").append(o.label()).append('\n');
        }
        if (cpp98) out.append("    Program();\n");
        out.append("    ~Program();\n    void run();\n");
        if (cpp98) out.append("private:\n    Program(const Program&);\n    Program& operator=(const Program&);\n");
        out.append("};\n\n");
    }

    /** C++98: pointers start null, fields start at 0 (later standards use member initializers). */
    private String cpp98Init(List<String> fieldNames, List<ObjMember> objs) {
        List<String> parts = new ArrayList<>();
        for (String f : fieldNames) parts.add(field(f) + (FAST ? "(0)" : "(N(0))"));
        for (ObjMember o : objs) parts.add(o.member() + "(0)");
        return parts.isEmpty() ? "" : " : " + String.join(", ", parts);
    }

    private String cpp98Delete(List<ObjMember> objs) {
        StringBuilder s = new StringBuilder();
        for (ObjMember o : objs) s.append(" delete ").append(o.member()).append(';');
        return s.isEmpty() ? " }" : s + " }";
    }

    /** Constructor: declared fields get their initial values in order, then init() runs. */
    private void emitConstructor(BlockDef d) {
        int from = lineNow();
        String cn = className.get(d);
        boolean cpp98 = t.lang().cpp() < 11;
        out.append(cn).append("::").append(cn).append("()");
        if (cpp98) out.append(cpp98Init(new ArrayList<>(fields.get(d)), classObjects(d)));
        out.append(" {\n");
        Method ctor = d.constructor();
        GraphWriter w = new GraphWriter(ctor != null ? ctor : d.methods.get(0));
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            Expr e = d.fieldInits.get(i);
            if (FAST) {
                out.append("    ").append(field(f.name())).append(" = ").append(w.fastNum(e, null)).append(";\n");
            } else {
                String prefix = "initial value of field " + d.name + "." + f.name() + ": ";
                out.append("    try { ").append(field(f.name())).append(" = ").append(w.expr(e, null)).append("; }\n");
                out.append("    catch (const std::runtime_error& e) { throw std::runtime_error(std::string(")
                        .append(cString(prefix)).append(") + e.what()); }\n");
            }
        }
        if (ctor != null) out.append("    ").append(methodName(ctor)).append("();\n");
        out.append("}\n");
        if (cpp98) out.append(cn).append("::~").append(cn).append("() {").append(cpp98Delete(classObjects(d))).append("\n\n");
        else out.append(cn).append("::~").append(cn).append("() = default;\n\n");
        mark(d, from, lineNow());
    }

    private void emitProgramConstructor() {
        if (t.lang().cpp() < 11) {
            List<ObjMember> objs = objectMembers(lib.main, "o", new LinkedHashSet<>());
            out.append("Program::Program()").append(cpp98Init(List.of(), objs)).append(" {}\n");
            out.append("Program::~Program() {").append(cpp98Delete(objs)).append("\n\n");
        } else {
            out.append("Program::~Program() = default;\n\n");
        }
    }

    private void emitMethod(Method m) {
        int from = lineNow();
        String cn = className.get(m.cls);
        if (C) {
            out.append("int ").append(cn).append("_").append(methodName(m)).append("(").append(params(m)).append(") {   // ")
                    .append(comment(m.qualified())).append('\n');
            if (!usesSelf(m)) out.append("    (void) self;\n");
            if (!FAST) out.append("    rt_enter();\n");
            for (String p : m.params) {
                out.append("    ").append(FAST ? "self->" + field(p) + " = " + param(p) + ";"
                        : "rt_set(&self->" + field(p) + ", " + param(p) + ");").append('\n');
            }
        } else {
            out.append("int ").append(cn).append("::").append(methodName(m)).append("(").append(params(m)).append(") {   // ")
                    .append(comment(m.qualified())).append('\n');
            if (!FAST) out.append("    DepthGuard guard;\n");
            for (String p : m.params) {
                out.append("    ").append(field(p)).append(" = ")
                        .append(FAST || t.lang().cpp() < 11 ? param(p) : "std::move(" + param(p) + ")").append(";\n");
            }
        }
        new GraphWriter(m).write(m.enter());
        if (t.lang() == Target.Lang.C99) out.append("    return -1;   // not reached (C99 has no _Noreturn for rt_fail)\n");
        out.append("}\n\n");
        mark(m, from, lineNow());
        mark(m.cls, from, lineNow());
    }

    /** Does a C method touch its object (fields, parameters, or objects it calls)? */
    private static boolean usesSelf(Method m) {
        if (!m.params.isEmpty()) return true;
        for (Block b : m.body.getBlocks()) {
            if (b.def != null) return true;
            Interpreter.Compiled c = b.compiled();
            if (c == null) continue;
            if (c.var() != null && c.var().startsWith("this.")) return true;
            if (c.expr() != null && usesThis(c.expr())) return true;
            if (c.args() != null) for (Expr a : c.args()) if (usesThis(a)) return true;
            for (Interpreter.Assign as : new Interpreter.Assign[]{c.init(), c.step()}) {
                if (as != null && (as.var().startsWith("this.") || usesThis(as.expr()))) return true;
            }
        }
        return false;
    }

    private static boolean usesThis(Expr e) {
        return switch (e) {
            case Expr.Var v -> v.name().startsWith("this.");
            case Expr.Unary u -> usesThis(u.operand());
            case Expr.Binary b -> usesThis(b.left()) || usesThis(b.right());
            case Expr.Num n -> false;
            case Expr.Str s -> false;
        };
    }

    private void emitRun() {
        out.append(C ? "static void run_program(void) {\n" : "void Program::run() {\n");
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

    // ================================================================ C

    private void buildC() {
        out.append("// ---- program variables\n");
        if (globals.isEmpty()) out.append("// (none)\n");
        for (String g : globals) out.append("static ").append(valueType()).append(' ').append(global(g)).append(";\n");
        out.append('\n');

        if (!lib.defs().isEmpty()) {
            out.append("// ---- classes\n");
            for (BlockDef d : lib.defs()) {
                String cn = className.get(d);
                out.append("typedef struct ").append(cn).append(' ').append(cn).append(";\n");
            }
            out.append('\n');
            for (BlockDef d : lib.defs()) {
                int from = lineNow();
                String cn = className.get(d);
                out.append("/* class ").append(cComment(d.name)).append(d.module != null ? "   (module " + cComment(d.module) + ")" : "")
                        .append(" */\n");
                out.append("struct ").append(cn).append(" {\n");
                int members = 0;
                for (String f : fields.get(d)) {
                    out.append("    ").append(valueType()).append(' ').append(field(f)).append(";\n");
                    members++;
                }
                for (ObjMember o : classObjects(d)) {
                    out.append("    ").append(ptrType(o.cls())).append(o.member()).append(";   // ").append(o.label()).append('\n');
                    members++;
                }
                if (members == 0) out.append("    char unused_;   // ISO C needs at least one member\n");
                out.append("};\n");
                out.append(cn).append(" *").append(cn).append("_new(void);\n");
                out.append(cn).append(" *").append(cn).append("_obj(").append(cn).append(" **slot);\n");
                for (Method m : d.methods) {
                    out.append("int ").append(cn).append("_").append(methodName(m)).append("(").append(params(m)).append(");   // ")
                            .append(m.isConstructor() ? "constructor; " : "").append(exitsComment(m)).append('\n');
                }
                out.append('\n');
                mark(d, from, lineNow());
            }
        }

        List<ObjMember> mainObjs = objectMembers(lib.main, "o", new LinkedHashSet<>());
        if (!mainObjs.isEmpty()) {
            out.append("// ---- objects of the main program\n");
            for (ObjMember o : mainObjs) {
                out.append("static ").append(ptrType(o.cls())).append("g_").append(o.member()).append(";   // ").append(o.label()).append('\n');
            }
            out.append('\n');
        }

        for (BlockDef d : lib.defs()) emitCConstructor(d);
        for (BlockDef d : lib.defs()) for (Method m : d.methods) emitMethod(m);
        emitRun();
        if (t.lang() == Target.Lang.ARDUINO) {
            out.append("""
                    void setup() {
                        rt_begin();
                        run_program();
                        rt_end();
                    }

                    void loop() {
                        // the program ran once in setup(); pins keep their last state
                    }
                    """);
        } else if (EMB) {
            out.append("""
                    int main(void) {
                        rt_begin();
                        run_program();
                        rt_end();   /* waits for the UART, then sleeps; pins keep their last state */
                    }
                    """);
        } else {
            out.append("""
                    int main(void) {
                        run_program();
                        return 0;
                    }
                    """);
        }
    }

    /** {@code Class_new}: fields start at 0, declared fields get their initial values, then init() runs. */
    private void emitCConstructor(BlockDef d) {
        int from = lineNow();
        String cn = className.get(d);
        out.append(cn).append(" *").append(cn).append("_new(void) {\n");
        out.append("    ").append(cn).append(" *self = (").append(cn).append(" *) rt_alloc(sizeof *self);\n");
        Method ctor = d.constructor();
        GraphWriter w = new GraphWriter(ctor != null ? ctor : d.methods.get(0));
        if (!FAST) for (String f : fields.get(d)) out.append("    self->").append(field(f)).append(" = rt_N(0);\n");
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            Expr e = d.fieldInits.get(i);
            if (FAST) {
                out.append("    self->").append(field(f.name())).append(" = ").append(w.fastNum(e, null)).append(";\n");
            } else {
                out.append("    rt_context = ").append(cString("initial value of field " + d.name + "." + f.name() + ": ")).append(";\n");
                out.append("    rt_set(&self->").append(field(f.name())).append(", ").append(w.resetWrap(w.expr(e, null), e)).append(");\n");
            }
        }
        if (!FAST && !d.fields.isEmpty()) out.append("    rt_context = \"\";\n");
        if (ctor != null) out.append("    ").append(cn).append("_").append(methodName(ctor)).append("(self);\n");
        out.append("    return self;\n}\n\n");
        out.append("/* Objects are created on first use. */\n");
        out.append(cn).append(" *").append(cn).append("_obj(").append(cn).append(" **slot) {\n");
        out.append("    if (!*slot) *slot = ").append(cn).append("_new();\n");
        out.append("    return *slot;\n}\n\n");
        mark(d, from, lineNow());
    }

    // ================================================================ graphs

    private final class GraphWriter {
        final Method owner;                      // null = main program
        final List<String> lines = new ArrayList<>();
        final Set<String> targets = new HashSet<>();
        final List<Block> lineOwners = new ArrayList<>();
        /** The block whose code is being written; every emitted line is attributed to it (View source). */
        Block current;

        GraphWriter(Method owner) {
            this.owner = owner;
        }

        /** Structured code when wanted and the wiring allows it, otherwise labels and goto. */
        void write(Block entry) {
            Set<Block> reachable = reachable(entry);
            int unreachable = (owner == null ? lib.main : owner.body).getBlocks().size() - reachable.size();
            if (unreachable > 0) out.append("    // (").append(unreachable).append(" unreachable block(s) skipped)\n");
            if (t.flow() == Target.Flow.STRUCTURED) {
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
            }
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
         * Writes a graph as nested statements. If/else and multi-exit calls continue at their
         * immediate post-dominator (the join point). A loop block's body must be a closed region:
         * nothing jumps into it except through the loop block, nothing jumps out of it except
         * back to the loop block (or a return/error). Anything else is "unstructured".
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
                        case SET -> add(assign(c.var(), c.expr(), b) + ";");
                        case PRINT -> add(print(c.expr(), b));
                        case INPUT -> add(input(c.var(), b));
                        case PIN_MODE, DIGITAL_WRITE, DIGITAL_READ, ANALOG_READ, ANALOG_WRITE, DELAY, MILLIS,
                             UART_OPEN, UART_WRITE, UART_READ, SPI_OPEN, SPI_TRANSFER, I2C_OPEN, I2C_WRITE, I2C_READ -> add(board(b, c));
                        case END -> {
                            add("return;");
                            return;
                        }
                        case EXIT -> {
                            add(exitStatement(c.var()));
                            return;
                        }
                        case IF -> {
                            b = ifElse(b, c, stop, loop);
                            continue;
                        }
                        case CUSTOM -> {
                            for (String p : callPrelude(b, c)) add(p);
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
                String cond = cond(c.expr(), b);
                Block yes = b.next[0], no = b.next[1];
                boolean yesEmpty = yes != null && yes == inner, noEmpty = no != null && no == inner;
                if (yesEmpty && noEmpty) {
                    add("(void) " + cond + ";");
                } else if (yesEmpty) {
                    add("if (!" + cond + ") {");
                    branch(b, 1, inner, loop);
                    add("}");
                } else {
                    add("if (" + unwrap(cond) + ") {");
                    branch(b, 0, inner, loop);
                    if (!noEmpty) {
                        add("} else {");
                        branch(b, 1, inner, loop);
                    }
                    add("}");
                }
                return join;
            }

            /** A call with several exits: switch on the exit index. */
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
                add("    default: " + badExit());
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
                String cond = c.expr() == null ? "" : cond(c.expr(), l);
                switch (l.type) {
                    case FOR -> add("for (" + assign(c.init(), l) + "; " + unwrap(cond) + "; " + assign(c.step(), l) + ") {");
                    case WHILE -> add("while (" + unwrap(cond) + ") {");
                    default -> add("do {");
                }
                Block first = l.next[0];
                if (first != null && first != l) {
                    indent++;
                    seq(first, l, l);
                    indent--;
                }
                current = l;
                add(l.type == BlockType.DO_WHILE ? "} while (" + unwrap(cond) + ");" : "}");
            }

            void checkJoin(Block join, Block loop) {
                if (loop != null && join != null && join != loop && !loop.loopBody.contains(join)) throw new Unstructured();
            }
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
                    line(assign(c.var(), c.expr(), b) + ";");
                    line(go(b, 0, after));
                }
                case PRINT -> {
                    line(print(c.expr(), b));
                    line(go(b, 0, after));
                }
                case INPUT -> {
                    line(input(c.var(), b));
                    line(go(b, 0, after));
                }
                case PIN_MODE, DIGITAL_WRITE, DIGITAL_READ, ANALOG_READ, ANALOG_WRITE, DELAY, MILLIS,
                             UART_OPEN, UART_WRITE, UART_READ, SPI_OPEN, SPI_TRANSFER, I2C_OPEN, I2C_WRITE, I2C_READ -> {
                    line(board(b, c));
                    line(go(b, 0, after));
                }
                case IF -> {
                    String cond = cond(c.expr(), b);
                    if (b.next[0] != null && b.next[0] == after && !isBackEdge(b, after)) {
                        line("if (!" + cond + ") " + go(b, 1, null));
                    } else {
                        line("if (" + unwrap(cond) + ") " + go(b, 0, null));
                        line(go(b, 1, after));
                    }
                }
                case FOR, WHILE, DO_WHILE -> loopLabels(b, c, after);
                case END -> line("return;");
                case EXIT -> line(exitStatement(c.var()));
                case CUSTOM -> {
                    for (String p : callPrelude(b, c)) line(p);
                    String call = call(b, c);
                    if (b.next.length == 1) {
                        line(call + ";");
                        line(go(b, 0, after));
                    } else {
                        line("switch (" + call + ") {");
                        for (int i = 0; i < b.next.length; i++) {
                            String g = go(b, i, after);
                            line("    case " + i + ": " + (g.isEmpty() ? "break;" : g) + "   // " + comment(b.portName(i)));
                        }
                        line("    default: " + badExit());
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
            String cond = c.expr() == null ? null : cond(c.expr(), l);
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
                    line("if (" + unwrap(cond) + ") " + toBody);
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
            Block tg = b.next[port];
            if (tg == null) return missing(b, port);
            if (isBackEdge(b, tg)) return jump("L" + tg.id + "_next");
            if (tg == after) return "";
            return jump("L" + tg.id);
        }

        /** Same behaviour as the interpreter for an unconnected output. */
        String missing(Block b, int port) {
            if (owner != null) {
                String msg = cString("method " + owner.qualified() + ": this path ends without reaching an Exit block");
                return EMB ? "rt_fail(RT_TEXT(" + msg + "));" : C ? "rt_fail(" + msg + ");" : "throw std::runtime_error(" + msg + ");";
            }
            String msg = cString(b.def != null
                    ? "(warning: exit '" + b.portName(port) + "' of " + b.method().qualified() + " is not connected - program ends)"
                    : "(warning: " + b.title() + " block has no outgoing connection - program ends)");
            if (EMB) return "{ rt_print_line(RT_TEXT(" + msg + ")); return; }";
            if (C) return "{ puts(" + msg + "); return; }";
            if (FAST) return "{ std::cout << " + msg + " << '\\n'; return; }";
            return "{ print(S(" + msg + ")); return; }";
        }

        String badExit() {
            return EMB ? "rt_fail(RT_TEXT(\"bad exit index\"));" : C ? "rt_fail(\"bad exit index\");" : "throw std::logic_error(\"bad exit index\");";
        }

        String exitStatement(String exitName) {
            String ret = "return " + owner.exitNames().indexOf(exitName) + ";   // exit '" + comment(exitName) + "'";
            return C && !FAST ? "rt_leave(); " + ret : ret;
        }

        void line(String s) {
            if (s.isEmpty()) return;
            lines.add("    " + s);
            lineOwners.add(current);
        }

        // ============================================================ statements

        /** {@code var = expr} without the semicolon (also used in for-loop headers). */
        String assign(String var, Expr e, Block b) {
            String tg = target(var, b);
            if (FAST) return tg + " = " + fastNum(e, b);
            if (C) return "rt_set(&" + tg + ", " + resetWrap(expr(e, b), e) + ")";
            return tg + " = " + expr(e, b);
        }

        String assign(Interpreter.Assign a, Block b) {
            return a == null ? "" : assign(a.var(), a.expr(), b);
        }

        String print(Expr e, Block b) {
            if (!FAST) return C ? "rt_print(" + resetWrap(expr(e, b), e) + ");" : "print(" + expr(e, b) + ");";
            F f = fast(e, b);
            List<F> parts = f.kind == TEXT ? mergeText(f.parts) : List.of(f);
            StringBuilder s = new StringBuilder();
            if (EMB) {
                if (parts.size() == 1 && parts.get(0).kind == TEXT) return "rt_print_line(RT_TEXT(" + cString(parts.get(0).text) + "));";
                for (F p : parts) s.append(p.kind == TEXT ? "rt_put_text(RT_TEXT(" + cString(p.text) + ")); " : "rt_put_num(" + unwrap(p.code) + "); ");
                return s.append("rt_newline();").toString();
            }
            if (C) {
                if (parts.size() == 1 && parts.get(0).kind == TEXT) return "puts(" + cString(parts.get(0).text) + ");";
                for (F p : parts) s.append(p.kind == TEXT ? "fputs(" + cString(p.text) + ", stdout); " : "rt_put_num(" + unwrap(p.code) + "); ");
                return s.append("putchar('\\n');").toString();
            }
            s.append("std::cout");
            for (F p : parts) s.append(" << ").append(p.kind == TEXT ? cString(p.text) : "str(" + unwrap(p.code) + ")");
            return s.append(" << '\\n';").toString();
        }

        String input(String var, Block b) {
            String tg = target(var, b);
            if (EMB) return tg + " = rt_input_num(RT_TEXT(" + cString(var) + "));";
            if (FAST) return tg + " = " + (C ? "rt_input_num(" : "input_num(") + cString(var) + ");";
            if (C) return "rt_set(&" + tg + ", (rt_reset(), rt_input(" + cString(var) + ")));";
            return tg + " = input(" + cString(var) + ");";
        }

        /** A condition, fully parenthesized where needed (see {@link #unwrap}). */
        String cond(Expr e, Block b) {
            if (FAST) return boolCode(fast(e, b), b);
            if (C) return "rt_truthy(" + resetWrap(expr(e, b), e) + ")";
            return "truthy(" + expr(e, b) + ")";
        }

        /** C, safe values: release the scratch text of earlier expressions first when this one makes text. */
        String resetWrap(String code, Expr e) {
            return makesText(e) ? "(rt_reset(), " + code + ")" : code;
        }

        /** Statements that must come before a call: create the object first (as the interpreter does), then the arguments. */
        List<String> callPrelude(Block b, Interpreter.Compiled c) {
            if (c.args().isEmpty() || !(C || t.cppBefore17())) return List.of();
            List<String> r = new ArrayList<>();
            r.add(C ? className.get(b.def) + "_obj(" + slot(b) + ");   // create the object before the arguments"
                    : "obj(" + member(b) + ");   // create the object before the arguments");
            if (C && !FAST && c.args().stream().anyMatch(CodeGenerator::makesText)) r.add("rt_reset();");
            return r;
        }

        String member(Block b) {
            return !b.objectName().isEmpty() ? objectMember(b.objectName())
                    : owner == null ? "o" + b.id : "o_" + sanitize(owner.name) + "_" + b.id;
        }

        String slot(Block b) {
            return (owner == null ? "&g_" : "&self->") + member(b);
        }

        String call(Block b, Interpreter.Compiled c) {
            StringBuilder s = new StringBuilder();
            if (C) {
                String cn = className.get(b.def);
                s.append(cn).append('_').append(methodName(b.method())).append('(').append(cn).append("_obj(").append(slot(b)).append(')');
                for (Expr a : c.args()) s.append(", ").append(FAST ? fastNum(a, b) : expr(a, b));
            } else {
                s.append("obj(").append(member(b)).append(").").append(methodName(b.method())).append('(');
                for (int i = 0; i < c.args().size(); i++) {
                    s.append(i == 0 ? "" : ", ").append(FAST ? fastNum(c.args().get(i), b) : expr(c.args().get(i), b));
                }
            }
            return s.append(')').toString();
        }

        // ============================================================ board blocks

        /** A pin, time or bus (UART, SPI, I2C) block: Arduino's functions (the bare-metal AVR runtime has the same ones). */
        String board(Block b, Interpreter.Compiled c) {
            if (!EMB) {
                throw new FlowException(b, "the " + b.type.title + " block runs on a microcontroller: choose Language Arduino or AVR C"
                        + " (Run simulates the board on the computer)");
            }
            return switch (b.type) {
                case PIN_MODE -> "pinMode(" + pin(c.expr(), b) + ", " + c.var() + ");";
                case DIGITAL_WRITE -> "digitalWrite(" + pin(c.expr(), b) + ", " + level(c.args().get(0), b) + ");";
                case ANALOG_WRITE -> {
                    Expr v = c.args().get(0);
                    String duty = v instanceof Expr.Num n && n.value() == Math.rint(n.value()) && n.value() >= 0 && n.value() <= 255
                            ? Long.toString((long) n.value()) : "rt_duty(" + fastNum(v, b) + ")";
                    yield "analogWrite(" + pin(c.expr(), b) + ", " + duty + ");";
                }
                case DIGITAL_READ -> target(c.var(), b) + " = digitalRead(" + pin(c.expr(), b) + ") == HIGH ? 1.0 : 0.0;";
                case ANALOG_READ -> target(c.var(), b) + " = analogRead(" + pin(c.expr(), b) + ");";
                case DELAY -> {
                    Expr v = c.expr();
                    String ms = v instanceof Expr.Num n && n.value() == Math.rint(n.value()) && n.value() >= 0 && n.value() < 4e9
                            ? Long.toString((long) n.value()) : "rt_ms(" + fastNum(v, b) + ")";
                    yield "delay(" + ms + ");";
                }
                case MILLIS -> target(c.var(), b) + " = (double) millis();";
                case UART_OPEN -> "uartOpen(" + hz(c.expr(), b) + ");";
                case UART_WRITE -> "uartWrite(" + byteOf(c.expr(), b) + ");";
                case UART_READ -> target(c.var(), b) + " = (double) uartRead();";
                case SPI_OPEN -> "spiOpen(" + hz(c.expr(), b) + ");";
                case SPI_TRANSFER -> target(c.var(), b) + " = (double) spiTransfer(" + byteOf(c.expr(), b) + ");";
                case I2C_OPEN -> "i2cOpen(" + hz(c.expr(), b) + ");";
                case I2C_WRITE -> "i2cWrite(" + byteOf(c.expr(), b) + ", " + byteOf(c.args().get(0), b) + ", "
                        + byteOf(c.args().get(1), b) + ");";
                case I2C_READ -> target(c.var(), b) + " = (double) i2cRead(" + byteOf(c.expr(), b) + ", "
                        + byteOf(c.args().get(0), b) + ");";
                default -> throw new IllegalStateException();
            };
        }

        /** A byte for a bus function: a whole number 0..255 as it is, anything else converted (and cut to 8 bits). */
        String byteOf(Expr e, Block b) {
            if (e instanceof Expr.Num n && n.value() == Math.rint(n.value()) && n.value() >= 0 && n.value() <= 255) {
                return Long.toString((long) n.value());
            }
            return "(uint8_t) (long) (" + fastNum(e, b) + ")";
        }

        /** A clock or baud rate in Hz for a bus function. */
        String hz(Expr e, Block b) {
            if (e instanceof Expr.Num n && n.value() == Math.rint(n.value()) && n.value() >= 1 && n.value() <= 100_000_000) {
                return Long.toString((long) n.value()) + "UL";
            }
            return "rt_hz(" + fastNum(e, b) + ")";
        }

        /** A pin: a board name (LED_BUILTIN, A0), a whole number, or a number expression. */
        String pin(Expr e, Block b) {
            if (e instanceof Expr.Var v && BoardSim.isConstant(v.name())) return v.name();
            if (e instanceof Expr.Num n && n.value() == Math.rint(n.value()) && n.value() >= 0 && n.value() <= 255) {
                return Long.toString((long) n.value());
            }
            return "(int) (" + fastNum(e, b) + ")";
        }

        /** HIGH / LOW for Digital Write; any other value is HIGH unless it is 0. */
        String level(Expr e, Block b) {
            if (e instanceof Expr.Var v && (v.name().equals("HIGH") || v.name().equals("LOW"))) return v.name();
            if (e instanceof Expr.Num n) return n.value() != 0 ? "HIGH" : "LOW";
            return "(" + fastNum(e, b) + ") != 0 ? HIGH : LOW";
        }

        // ============================================================ safe values

        String expr(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> (C ? "rt_N(" : "N(") + number(n.value()) + ")";
                case Expr.Str s -> (C ? "rt_S(" : "S(") + cString(s.value()) + ")";
                case Expr.Var v -> read(v.name(), b);
                case Expr.Unary u -> (u.op().equals("-") ? (C ? "rt_neg(" : "neg(") : (C ? "rt_not(" : "lnot(")) + expr(u.operand(), b) + ")";
                case Expr.Binary x -> binary(x, b);
            };
        }

        String binary(Expr.Binary x, Block b) {
            String l = expr(x.left(), b), r = expr(x.right(), b);
            if (C) {
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
            Double board = BoardSim.constant(name);
            if (board != null) {
                // a board's own name on a microcontroller; the simulated Uno's value on the computer
                if (EMB) return name;
                return FAST ? fastLiteral(board) : (C ? "rt_N(" : "N(") + number(board) + ")";
            }
            if (FAST) return global(name);
            return (C ? "rt_rd(&" : "rd(") + global(name) + ", " + cString(name) + ")";
        }

        String target(String name, Block b) {
            if (name.startsWith("this.")) {
                if (owner == null) throw new FlowException(b, "'this' can only be used inside a class method");
                return (C ? "self->" : "") + field(name.substring(5));
            }
            return global(name);
        }

        // ============================================================ fast values

        static final int NUM = 0, BOOL = 1, TEXT = 2;

        /** Fast-mode expression: C code, its kind, and for text the pieces to print. */
        record F(String code, int kind, List<F> parts, String text) {}

        F fast(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> new F(fastLiteral(n.value()), NUM, null, null);
                case Expr.Str s -> new F(cString(s.value()), TEXT,
                        List.of(new F(cString(s.value()), TEXT, List.of(), s.value())), s.value());
                case Expr.Var v -> new F(read(v.name(), b), NUM, null, null);
                case Expr.Unary u -> {
                    F x = fast(u.operand(), b);
                    needNumber(x, b);
                    yield u.op().equals("-") ? new F("(-" + x.code + ")", NUM, null, null)
                            : new F("!" + boolCode(x, b), BOOL, null, null);
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
                case "+", "-", "*", "/" -> new F("(" + l.code + " " + op + " " + r.code + ")", NUM, null, null);
                case "%" -> new F((C ? "fmod(" : "std::fmod(") + unwrap(l.code) + ", " + unwrap(r.code) + ")", NUM, null, null);
                case "==", "!=", "<", "<=", ">", ">=" -> new F("(" + l.code + " " + op + " " + r.code + ")", BOOL, null, null);
                default -> throw new FlowException(b, "unknown operator " + op);
            };
        }

        /** Fast values: a number expression (text is only allowed in Print). */
        String fastNum(Expr e, Block b) {
            F f = fast(e, b);
            needNumber(f, b);
            return unwrap(f.code);
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
                    r.add(new F(cString(text), TEXT, null, text));
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

    /** Can evaluating {@code e} make new text? (Only "+" does; in C that text lives in scratch memory.) */
    private static boolean makesText(Expr e) {
        return switch (e) {
            case Expr.Binary x -> x.op().equals("+") || makesText(x.left()) || makesText(x.right());
            case Expr.Unary u -> makesText(u.operand());
            default -> false;
        };
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

    /** A number literal; whole numbers outside int range get ".0" (a long long literal isn't C++98, and long is 32-bit on Windows). */
    private static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) return Long.toString((long) d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        return Double.toString(d);
    }

    /** A double literal (fast values): "3.0", "0.25", "1.0E-5". */
    private static String fastLiteral(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        return Double.toString(d);
    }

    /** Removes one pair of parentheses around the whole expression ("(a < b)" -> "a < b"). */
    static String unwrap(String code) {
        if (code == null || code.length() < 2 || code.charAt(0) != '(' || code.charAt(code.length() - 1) != ')') return code;
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (inString) {
                if (ch == '\\') i++;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') inString = true;
            else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0 && i < code.length() - 1) return code;   // first group closes early
        }
        return code.substring(1, code.length() - 1);
    }

    /** C/C++ string literal; non-ASCII is written as octal UTF-8 bytes so the source encoding doesn't matter. */
    static String cString(String s) {
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

    /** Kept for callers of the C++-only generator. */
    static String cppString(String s) {
        return cString(s);
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

    /** Text safe inside a C block comment. */
    private static String cComment(String s) {
        return comment(s).replace("*/", "* /");
    }
}
