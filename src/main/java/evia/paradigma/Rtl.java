package evia.paradigma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small register-transfer model of a synchronous design, written out as SystemVerilog, Verilog-2001
 * or VHDL. The FPGA generator builds one {@code Rtl} per program, so the three languages describe exactly
 * the same circuit.
 *
 * <p>The model is deliberately narrow: one clock, one clocked process (free-running statements first, then a
 * state machine), registers with initial values instead of a reset, and a few combinational outputs.
 * Values are signed 32-bit numbers (program variables), unsigned vectors (counters, bytes) and single bits.
 */
final class Rtl {

    enum Hdl {
        SYSTEMVERILOG("SystemVerilog", ".sv"), VERILOG("Verilog-2001", ".v"), VHDL("VHDL", ".vhd");

        final String display, ext;

        Hdl(String display, String ext) {
            this.display = display;
            this.ext = ext;
        }

        boolean isVhdl() {
            return this == VHDL;
        }

        String comment() {
            return isVhdl() ? "--" : "//";
        }
    }

    // ================================================================ types and expressions

    /** 'S' signed vector, 'U' unsigned vector, 'B' single bit. */
    record Ty(char kind, int width) {
        static final Ty B = new Ty('B', 1);
        static final Ty S32 = new Ty('S', 32);

        static Ty u(int w) {
            return new Ty('U', w);
        }

        boolean isBit() {
            return kind == 'B';
        }
    }

    sealed interface E {}

    record Lit(long value, Ty ty) implements E {}

    record Ref(Sig sig) implements E {}

    /** + - * on equal types; == != < <= > >= on equal types (a bit); && || on bits. */
    record Bin(String op, E a, E b) implements E {}

    /** "-" on a signed value, "!" on a bit. */
    record Un(String op, E a) implements E {}

    record Mux(E cond, E a, E b) implements E {}

    /** value != 0, as a bit. */
    record ToBit(E a) implements E {}

    /** A bit as 1 or 0 of a signed 32-bit value. */
    record FromBit(E a) implements E {}

    /** An unsigned vector, zero-extended to a signed 32-bit value. */
    record ToS32(E a) implements E {}

    record Bit(Ref a, int index) implements E {}

    record Slice(Ref a, int hi, int lo) implements E {}

    record Cat(List<E> parts) implements E {}

    /** An entry of a lookup table of bytes. */
    record RomAt(String rom, E addr) implements E {}

    // ================================================================ declarations

    enum Kind { IN, OUT, REG, WIRE, CONST }

    static final class Sig {
        final String name;
        final Ty ty;
        final Kind kind;
        final long init;
        /** CONST: an integer expression over the generic CLK_HZ, e.g. "CLK_HZ / 1000". */
        final String intExpr;
        /** WIRE and OUT: the combinational driver. */
        E driver;
        String comment = "";

        Sig(String name, Ty ty, Kind kind, long init, String intExpr) {
            this.name = name;
            this.ty = ty;
            this.kind = kind;
            this.init = init;
            this.intExpr = intExpr;
        }
    }

    sealed interface St {}

    record Assign(Sig reg, E value) implements St {}

    record If(E cond, List<St> then, List<St> otherwise) implements St {}

    record Goto(String state) implements St {}

    record Note(String text) implements St {}

    record State(String name, String comment, List<St> body) {}

    // ================================================================ the design

    final String module;
    final int clkHz;
    final List<Sig> sigs = new ArrayList<>();
    final List<St> always = new ArrayList<>();
    final List<State> states = new ArrayList<>();
    final Map<String, byte[]> roms = new LinkedHashMap<>();
    final List<String> header = new ArrayList<>();

    Rtl(String module, int clkHz) {
        this.module = module;
        this.clkHz = clkHz;
    }

    Sig in(String name, Ty ty) {
        return add(new Sig(name, ty, Kind.IN, 0, null));
    }

    Sig out(String name, E driver) {
        Sig s = add(new Sig(name, Ty.B, Kind.OUT, 0, null));
        s.driver = driver;
        return s;
    }

    Sig reg(String name, Ty ty, long init) {
        return add(new Sig(name, ty, Kind.REG, init, null));
    }

    Sig wire(String name, Ty ty, E driver) {
        Sig s = add(new Sig(name, ty, Kind.WIRE, 0, null));
        s.driver = driver;
        return s;
    }

    Sig constant(String name, Ty ty, String intExpr) {
        return add(new Sig(name, ty, Kind.CONST, 0, intExpr));
    }

    private Sig add(Sig s) {
        for (Sig o : sigs) if (o.name.equals(s.name)) throw new IllegalStateException("signal " + s.name + " declared twice");
        sigs.add(s);
        return s;
    }

    State state(String name, String comment) {
        State s = new State(name, comment, new ArrayList<>());
        states.add(s);
        return s;
    }

    // ---- expression helpers

    static E ref(Sig s) {
        return new Ref(s);
    }

    static E lit(long v) {
        return new Lit(v, Ty.S32);
    }

    static E ulit(long v, int w) {
        return new Lit(v, Ty.u(w));
    }

    static E bit(boolean v) {
        return new Lit(v ? 1 : 0, Ty.B);
    }

    static E bin(String op, E a, E b) {
        return new Bin(op, a, b);
    }

    static Ty tyOf(E e) {
        return switch (e) {
            case Lit l -> l.ty();
            case Ref r -> r.sig().ty;
            case Bin b -> switch (b.op()) {
                case "+", "-", "*" -> tyOf(b.a());
                default -> Ty.B;
            };
            case Un u -> u.op().equals("-") ? Ty.S32 : Ty.B;
            case Mux m -> tyOf(m.a());
            case ToBit t -> Ty.B;
            case FromBit f -> Ty.S32;
            case ToS32 t -> Ty.S32;
            case Bit b -> Ty.B;
            case Slice s -> Ty.u(s.hi() - s.lo() + 1);
            case Cat c -> {
                int w = 0;
                for (E p : c.parts()) w += tyOf(p).width();
                yield Ty.u(w);
            }
            case RomAt r -> Ty.u(8);
        };
    }

    // ================================================================ writing

    String write(Hdl hdl) {
        return new Writer(hdl).run();
    }

    private final class Writer {
        final Hdl h;
        final boolean vhdl, sv;
        final StringBuilder o = new StringBuilder();
        final Map<String, Integer> stateIndex = new LinkedHashMap<>();
        int ind;

        Writer(Hdl h) {
            this.h = h;
            this.vhdl = h.isVhdl();
            this.sv = h == Hdl.SYSTEMVERILOG;
            for (State s : states) stateIndex.put(s.name(), stateIndex.size());
        }

        String run() {
            for (String l : header) o.append(h.comment()).append(' ').append(l).append('\n');
            o.append('\n');
            if (vhdl) vhdl();
            else verilog();
            return o.toString();
        }

        // ---------------------------------------------------------------- helpers

        void line(String s) {
            o.append("    ".repeat(ind)).append(s).append('\n');
        }

        int stateWidth() {
            int n = Math.max(2, states.size());
            return 32 - Integer.numberOfLeadingZeros(n - 1);
        }

        static String q(String s) {
            return s;
        }

        List<Sig> of(Kind k) {
            List<Sig> r = new ArrayList<>();
            for (Sig s : sigs) if (s.kind == k) r.add(s);
            return r;
        }

        // ---------------------------------------------------------------- expressions

        String ex(E e) {
            return vhdl ? exVhdl(e) : exVerilog(e);
        }

        String exVerilog(E e) {
            return switch (e) {
                case Lit l -> {
                    if (l.ty().isBit()) yield "1'b" + (l.value() != 0 ? 1 : 0);
                    if (l.ty().kind() == 'S') {
                        long v = l.value();
                        yield v < 0 ? "(-" + l.ty().width() + "'sd" + (-v) + ")" : l.ty().width() + "'sd" + v;
                    }
                    yield l.ty().width() + "'d" + l.value();
                }
                case Ref r -> r.sig().name;
                case Bin b -> "(" + ex(b.a()) + " " + b.op() + " " + ex(b.b()) + ")";
                case Un u -> "(" + u.op() + ex(u.a()) + ")";
                case Mux m -> "(" + ex(m.cond()) + " ? " + ex(m.a()) + " : " + ex(m.b()) + ")";
                case ToBit t -> "(" + ex(t.a()) + " != 0)";
                case FromBit f -> "(" + ex(f.a()) + " ? 32'sd1 : 32'sd0)";
                case ToS32 t -> {
                    int w = tyOf(t.a()).width();
                    yield "$signed({" + (32 - w) + "'b0, " + ex(t.a()) + "})";
                }
                case Bit b -> b.a().sig().name + "[" + b.index() + "]";
                case Slice s -> s.a().sig().name + "[" + s.hi() + ":" + s.lo() + "]";
                case Cat c -> {
                    StringBuilder sb = new StringBuilder("{");
                    for (int i = 0; i < c.parts().size(); i++) sb.append(i > 0 ? ", " : "").append(ex(c.parts().get(i)));
                    yield sb.append('}').toString();
                }
                case RomAt r -> r.rom() + "(" + ex(r.addr()) + ")";
            };
        }

        String exVhdl(E e) {
            return switch (e) {
                case Lit l -> {
                    if (l.ty().isBit()) yield l.value() != 0 ? "'1'" : "'0'";
                    if (l.ty().kind() == 'S') yield "to_signed(" + l.value() + ", " + l.ty().width() + ")";
                    yield "to_unsigned(" + l.value() + ", " + l.ty().width() + ")";
                }
                case Ref r -> r.sig().name;
                case Bin b -> {
                    String a = ex(b.a()), c = ex(b.b());
                    Ty t = tyOf(b.a());
                    yield switch (b.op()) {
                        case "+", "-" -> "(" + a + " " + b.op() + " " + c + ")";
                        case "*" -> "resize(" + a + " * " + c + ", " + t.width() + ")";
                        case "==" -> "b2l(" + a + " = " + c + ")";
                        case "!=" -> "b2l(" + a + " /= " + c + ")";
                        case "<", "<=", ">", ">=" -> "b2l(" + a + " " + b.op() + " " + c + ")";
                        case "&&" -> "(" + a + " and " + c + ")";
                        case "||" -> "(" + a + " or " + c + ")";
                        default -> throw new IllegalStateException("operator " + b.op());
                    };
                }
                case Un u -> u.op().equals("-") ? "(-" + ex(u.a()) + ")" : "(not " + ex(u.a()) + ")";
                case Mux m -> "mux(" + ex(m.cond()) + ", " + ex(m.a()) + ", " + ex(m.b()) + ")";
                case ToBit t -> "b2l(" + ex(t.a()) + " /= 0)";
                case FromBit f -> "mux(" + ex(f.a()) + ", to_signed(1, 32), to_signed(0, 32))";
                case ToS32 t -> "signed(resize(" + ex(t.a()) + ", 32))";
                case Bit b -> b.a().sig().name + "(" + b.index() + ")";
                case Slice s -> s.a().sig().name + "(" + s.hi() + " downto " + s.lo() + ")";
                case Cat c -> {
                    StringBuilder sb = new StringBuilder("(");
                    for (int i = 0; i < c.parts().size(); i++) sb.append(i > 0 ? " & " : "").append(ex(c.parts().get(i)));
                    yield sb.append(')').toString();
                }
                case RomAt r -> r.rom() + "(" + ex(r.addr()) + ")";
            };
        }

        /** The value converted to the type of the register it is assigned to. */
        String conv(Ty to, E e) {
            Ty from = tyOf(e);
            if (to.equals(from)) return ex(e);
            if (vhdl) {
                if (to.isBit()) return "b2l(" + ex(e) + " /= 0)";
                if (from.isBit()) {
                    return to.kind() == 'S' ? "mux(" + ex(e) + ", to_signed(1, " + to.width() + "), to_signed(0, " + to.width() + "))"
                            : "mux(" + ex(e) + ", to_unsigned(1, " + to.width() + "), to_unsigned(0, " + to.width() + "))";
                }
                if (to.kind() == 'U' && from.kind() == 'S') return "resize(unsigned(" + ex(e) + "), " + to.width() + ")";
                if (to.kind() == 'S' && from.kind() == 'U') return "signed(resize(" + ex(e) + ", " + to.width() + "))";
                if (to.kind() == 'U') return "resize(" + ex(e) + ", " + to.width() + ")";
                return "resize(" + ex(e) + ", " + to.width() + ")";
            }
            if (to.isBit()) return "(" + ex(e) + " != 0)";
            if (from.isBit()) return "(" + ex(e) + " ? " + to.width() + "'d1 : " + to.width() + "'d0)";
            if (to.kind() == 'S' && from.kind() == 'U') {
                return "$signed({" + Math.max(1, to.width() - from.width()) + "'b0, " + ex(e) + "})";
            }
            return ex(e);   // truncation or zero extension by assignment
        }

        // ---------------------------------------------------------------- statements

        void stmts(List<St> list) {
            for (St s : list) stmt(s);
        }

        void stmt(St s) {
            switch (s) {
                case Note n -> line(h.comment() + " " + n.text());
                case Assign a -> line(a.reg().name + (vhdl ? " <= " : " <= ") + conv(a.reg().ty, a.value()) + ";");
                case Goto g -> line("st " + (vhdl ? "<=" : "<=") + " " + stateName(g.state()) + ";");
                case If i -> {
                    if (vhdl) line("if " + (i.cond() instanceof Lit ? "std_logic'(" + ex(i.cond()) + ")" : ex(i.cond())) + " = '1' then");
                    else line("if (" + ex(i.cond()) + ") begin");
                    ind++;
                    stmts(i.then());
                    ind--;
                    if (!i.otherwise().isEmpty()) {
                        line(vhdl ? "else" : "end else begin");
                        ind++;
                        stmts(i.otherwise());
                        ind--;
                    }
                    line(vhdl ? "end if;" : "end");
                }
            }
        }

        String stateName(String s) {
            if (!stateIndex.containsKey(s)) throw new IllegalStateException("no state " + s);
            return "S_" + s;
        }

        // ---------------------------------------------------------------- Verilog / SystemVerilog

        String vtype(Sig s) {
            if (s.ty.isBit()) return "";
            return (s.ty.kind() == 'S' ? "signed " : "") + "[" + (s.ty.width() - 1) + ":0] ";
        }

        void verilog() {
            if (sv) o.append("`default_nettype none\n\n");
            line("module " + module + " #(");
            line("    parameter CLK_HZ = " + clkHz + "   // frequency of clk in Hz; a testbench may lower it to simulate faster");
            line(") (");
            ind++;
            List<String> ports = new ArrayList<>();
            for (Sig s : sigs) {
                if (s.kind == Kind.IN) ports.add("input  wire " + vtype(s) + s.name);
                else if (s.kind == Kind.OUT) ports.add("output wire " + vtype(s) + s.name);
            }
            for (int i = 0; i < ports.size(); i++) line(ports.get(i) + (i + 1 < ports.size() ? "," : ""));
            ind--;
            line(");");
            ind++;
            o.append('\n');
            for (Sig s : of(Kind.CONST)) {
                line("localparam " + vtype(s) + s.name + " = " + s.intExpr + ";" + (s.comment.isEmpty() ? "" : "   // " + s.comment));
            }
            int sw = stateWidth();
            if (sv) {
                StringBuilder names = new StringBuilder();
                for (State s : states) names.append(names.length() > 0 ? ", " : "").append("S_").append(s.name());
                line("typedef enum logic [" + (sw - 1) + ":0] {" + names + "} state_t;");
                line("state_t st = S_" + states.get(0).name() + ";");
            } else {
                for (State s : states) line("localparam [" + (sw - 1) + ":0] S_" + s.name() + " = " + sw + "'d" + stateIndex.get(s.name()) + ";");
                line("reg [" + (sw - 1) + ":0] st = S_" + states.get(0).name() + ";");
            }
            o.append('\n');
            for (Sig s : of(Kind.REG)) {
                line((sv ? "logic " : "reg ") + vtype(s) + s.name + " = " + initVerilog(s) + ";" + (s.comment.isEmpty() ? "" : "   // " + s.comment));
            }
            for (Sig s : of(Kind.WIRE)) {
                line((sv ? "logic " : "wire ") + vtype(s) + s.name + ";" + (s.comment.isEmpty() ? "" : "   // " + s.comment));
            }
            o.append('\n');
            for (Map.Entry<String, byte[]> r : roms.entrySet()) {
                line("function [7:0] " + r.getKey() + "(input [15:0] a);");
                ind++;
                line("case (a)");
                ind++;
                byte[] b = r.getValue();
                for (int i = 0; i < b.length; i++) line("16'd" + i + ": " + r.getKey() + " = 8'd" + (b[i] & 255) + ";");
                line("default: " + r.getKey() + " = 8'd0;");
                ind--;
                line("endcase");
                ind--;
                line("endfunction");
                o.append('\n');
            }
            for (Sig s : sigs) {
                if ((s.kind == Kind.WIRE || s.kind == Kind.OUT) && s.driver != null) line("assign " + s.name + " = " + conv(s.ty, s.driver) + ";");
            }
            o.append('\n');
            line(sv ? "always_ff @(posedge clk) begin" : "always @(posedge clk) begin");
            ind++;
            stmts(always);
            line("case (st)");
            ind++;
            for (State s : states) {
                line("S_" + s.name() + ": begin" + (s.comment().isEmpty() ? "" : "   // " + s.comment()));
                ind++;
                stmts(s.body());
                ind--;
                line("end");
            }
            line("default: st <= S_" + states.get(0).name() + ";");
            ind--;
            line("endcase");
            ind--;
            line("end");
            ind--;
            o.append("\nendmodule\n");
        }

        String initVerilog(Sig s) {
            if (s.ty.isBit()) return "1'b" + (s.init != 0 ? 1 : 0);
            return s.ty.kind() == 'S' ? s.ty.width() + "'sd" + s.init : s.ty.width() + "'d" + s.init;
        }

        // ---------------------------------------------------------------- VHDL

        String vhType(Ty t) {
            if (t.isBit()) return "std_logic";
            return (t.kind() == 'S' ? "signed" : "unsigned") + "(" + (t.width() - 1) + " downto 0)";
        }

        String initVhdl(Sig s) {
            if (s.ty.isBit()) return s.init != 0 ? "'1'" : "'0'";
            return (s.ty.kind() == 'S' ? "to_signed(" : "to_unsigned(") + s.init + ", " + s.ty.width() + ")";
        }

        void vhdl() {
            line("library ieee;");
            line("use ieee.std_logic_1164.all;");
            line("use ieee.numeric_std.all;");
            o.append('\n');
            line("entity " + module + " is");
            ind++;
            line("generic (CLK_HZ : integer := " + clkHz + ");   -- frequency of clk in Hz; a testbench may lower it to simulate faster");
            List<String> ports = new ArrayList<>();
            for (Sig s : sigs) {
                if (s.kind == Kind.IN) ports.add(s.name + " : in " + vhType(s.ty));
                else if (s.kind == Kind.OUT) ports.add(s.name + " : out " + vhType(s.ty));
            }
            line("port (");
            ind++;
            for (int i = 0; i < ports.size(); i++) line(ports.get(i) + (i + 1 < ports.size() ? ";" : ""));
            ind--;
            line(");");
            ind--;
            line("end entity;");
            o.append('\n');
            line("architecture rtl of " + module + " is");
            ind++;
            line("function b2l(b : boolean) return std_logic is begin if b then return '1'; else return '0'; end if; end function;");
            line("function mux(c : std_logic; a, b : signed) return signed is begin if c = '1' then return a; else return b; end if; end function;");
            line("function mux(c : std_logic; a, b : unsigned) return unsigned is begin if c = '1' then return a; else return b; end if; end function;");
            line("function mux(c : std_logic; a, b : std_logic) return std_logic is begin if c = '1' then return a; else return b; end if; end function;");
            for (Map.Entry<String, byte[]> r : roms.entrySet()) {
                line("function " + r.getKey() + "(a : unsigned(15 downto 0)) return unsigned is");
                line("begin");
                ind++;
                line("case to_integer(a) is");
                ind++;
                byte[] b = r.getValue();
                for (int i = 0; i < b.length; i++) line("when " + i + " => return to_unsigned(" + (b[i] & 255) + ", 8);");
                line("when others => return to_unsigned(0, 8);");
                ind--;
                line("end case;");
                ind--;
                line("end function;");
            }
            for (Sig s : of(Kind.CONST)) {
                line("constant " + s.name + " : " + vhType(s.ty) + " := to_unsigned(" + s.intExpr + ", " + s.ty.width() + ");"
                        + (s.comment.isEmpty() ? "" : "   -- " + s.comment));
            }
            StringBuilder names = new StringBuilder();
            for (State s : states) names.append(names.length() > 0 ? ", " : "").append("S_").append(s.name());
            line("type state_t is (" + names + ");");
            line("signal st : state_t := S_" + states.get(0).name() + ";");
            for (Sig s : of(Kind.REG)) {
                line("signal " + s.name + " : " + vhType(s.ty) + " := " + initVhdl(s) + ";" + (s.comment.isEmpty() ? "" : "   -- " + s.comment));
            }
            for (Sig s : of(Kind.WIRE)) line("signal " + s.name + " : " + vhType(s.ty) + ";" + (s.comment.isEmpty() ? "" : "   -- " + s.comment));
            ind--;
            line("begin");
            ind++;
            for (Sig s : sigs) {
                if ((s.kind == Kind.WIRE || s.kind == Kind.OUT) && s.driver != null) line(s.name + " <= " + conv(s.ty, s.driver) + ";");
            }
            o.append('\n');
            line("process (clk)");
            line("begin");
            ind++;
            line("if rising_edge(clk) then");
            ind++;
            stmts(always);
            line("case st is");
            ind++;
            for (State s : states) {
                line("when S_" + s.name() + " =>" + (s.comment().isEmpty() ? "" : "   -- " + s.comment()));
                ind++;
                stmts(s.body());
                if (s.body().isEmpty()) line("null;");
                ind--;
            }
            line("when others => st <= S_" + states.get(0).name() + ";");
            ind--;
            line("end case;");
            ind--;
            line("end if;");
            ind--;
            line("end process;");
            ind--;
            line("end architecture;");
        }
    }
}
