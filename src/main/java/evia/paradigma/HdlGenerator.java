package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import evia.paradigma.Rtl.Assign;
import evia.paradigma.Rtl.Bin;
import evia.paradigma.Rtl.Bit;
import evia.paradigma.Rtl.Cat;
import evia.paradigma.Rtl.E;
import evia.paradigma.Rtl.FromBit;
import evia.paradigma.Rtl.Goto;
import evia.paradigma.Rtl.If;
import evia.paradigma.Rtl.Kind;
import evia.paradigma.Rtl.Mux;
import evia.paradigma.Rtl.Ref;
import evia.paradigma.Rtl.RomAt;
import evia.paradigma.Rtl.Sig;
import evia.paradigma.Rtl.Slice;
import evia.paradigma.Rtl.St;
import evia.paradigma.Rtl.ToBit;
import evia.paradigma.Rtl.Ty;
import evia.paradigma.Rtl.Un;

/**
 * Turns the flowchart into a finite state machine for an FPGA: one state per block, written as SystemVerilog,
 * Verilog-2001 or VHDL (see {@link Rtl}) with a constraint file for the chosen board.
 *
 * <p>What this means for a program:
 * <ul>
 *   <li>Numbers are whole numbers: signed 32-bit, wrapping around; {@code /} and {@code %} truncate toward zero
 *       and give 0 when dividing by 0. (The simulator on the computer uses doubles, so 5 / 2 is 2.5 there.)</li>
 *   <li>Pins are fixed at build time, so a pin must be a constant: a number or a name such as LED0, SW3, BTNC, JA1.</li>
 *   <li>Every block is one clock cycle (Delay counts exact clock cycles; division takes about 35).</li>
 *   <li>Print and the UART blocks share one serial port (115200 baud unless UART Open says otherwise).</li>
 * </ul>
 */
final class HdlGenerator {

    record Result(String hdl, String constraints, String module, List<Fpga.Pin> pins, boolean uart) {}

    static Result generate(Library lib, Rtl.Hdl hdl, Fpga.Spec spec, String boardName, String module) {
        return new HdlGenerator(lib, spec, boardName, module).run(hdl);
    }

    // ================================================================ state

    private final Library lib;
    private final Fpga.Spec spec;
    private final String boardName;
    private final Rtl rtl;

    private final Map<Block, Integer> ids = new LinkedHashMap<>();
    private final Map<String, Sig> vars = new LinkedHashMap<>();
    private final Map<Block, Integer> pinOf = new HashMap<>();

    /** How the program uses one pin. */
    private static final class Use {
        final Fpga.Pin pin;
        boolean wantOut, wantIn, digital, analog;
        Block first;
        Sig level, duty, enable, sync1, sync2, port;

        Use(Fpga.Pin pin, Block first) {
            this.pin = pin;
            this.first = first;
        }
    }

    private final Map<Integer, Use> uses = new TreeMap<>();
    private boolean needTx, needRx, needMillis, needPwm;
    private int baud = 115200;
    private Block baudBlock;

    private Sig clk, txLine, txBusy, txBaud, txBits, txShift, txByte, rxLine, rxS1, rxS2, rxBusy, rxBaud, rxBits, rxShift, rxByte, rxValid;
    private Sig msCnt, msSub, pwmDiv, dlyMs, dlySub, cycMs, baudDiv, baudHalf;
    private Sig dvA, dvB, dvR, dvN, dvSa, dvSb, prIdx, prEnd, pnMag, pnNeg, pnI, pnDig, pnSt;
    private final Map<Integer, Sig> divTemp = new HashMap<>();
    private int pwmBits;
    private boolean needDiv, needPrint;
    private final ByteArrayBuilder strings = new ByteArrayBuilder();

    private static final class ByteArrayBuilder {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int add(byte[] b) {
            int at = out.size();
            out.write(b, 0, b.length);
            return at;
        }
    }

    private HdlGenerator(Library lib, Fpga.Spec spec, String boardName, String module) {
        this.lib = lib;
        this.spec = spec;
        this.boardName = boardName;
        this.rtl = new Rtl(module, spec.clkHz());
    }

    // ================================================================ driver

    private Result run(Rtl.Hdl hdl) {
        if (!lib.defs().isEmpty()) {
            throw new FlowException("custom blocks (classes) are not supported when generating for an FPGA yet");
        }
        Block start = Interpreter.prepare(lib);

        // reachable blocks, numbered in discovery order (Start is 0)
        ArrayDeque<Block> todo = new ArrayDeque<>();
        todo.add(start);
        ids.put(start, 0);
        while (!todo.isEmpty()) {
            Block b = todo.poll();
            for (Block n : b.next) {
                if (n != null && !ids.containsKey(n)) {
                    ids.put(n, ids.size());
                    todo.add(n);
                }
            }
        }

        scan();
        declare();
        for (Block b : ids.keySet()) emit(b);
        rtl.state("HALT", "program finished: stays here");

        List<Fpga.Pin> pins = new ArrayList<>();
        for (Use u : uses.values()) pins.add(u.pin);
        boolean uart = needTx || needRx;
        String xdc = Fpga.constraints(spec, pins, needTx, needRx, "clk");

        rtl.header.add("Generated by EVIA Paradigma - do not edit by hand, change the flowchart instead.");
        rtl.header.add("Board: " + boardName + " (" + spec.part() + "), clock " + spec.clkHz() / 1_000_000.0 + " MHz");
        rtl.header.add("Numbers are signed 32-bit whole numbers that wrap around; / and % truncate toward zero and give 0 for a zero divisor.");
        rtl.header.add("Each block takes one clock cycle; Delay counts exact clock cycles.");
        rtl.header.add("");
        rtl.header.add("Pin constraints (" + (spec.family() == Fpga.Family.ARTIX7 ? "Vivado .xdc" : "GateMate .ccf") + "), also written next to this file by the command line:");
        for (String l : xdc.split("\n")) rtl.header.add("  " + l);
        return new Result(rtl.write(hdl), xdc, rtl.module, pins, uart);
    }

    // ================================================================ analysis

    private void scan() {
        for (Block b : ids.keySet()) {
            switch (b.type) {
                case INPUT -> throw new FlowException(b, "Input needs a keyboard; an FPGA program has none. Use a switch or button (Digital Read) or UART Read instead.");
                case ANALOG_READ -> throw new FlowException(b, "Analog Read is not available on these FPGA boards yet (no ADC is connected).");
                case SPI_OPEN, SPI_TRANSFER, I2C_OPEN, I2C_WRITE, I2C_READ ->
                        throw new FlowException(b, b.type.title + " is not supported when generating for an FPGA yet. UART, digital pins and PWM are.");
                case CUSTOM, ENTER, EXIT -> throw new FlowException(b, "custom blocks are not supported when generating for an FPGA yet");
                default -> { }
            }
        }

        // variables
        for (Block b : ids.keySet()) {
            Interpreter.Compiled c = b.compiled();
            switch (b.type) {
                case SET, DIGITAL_READ, MILLIS, UART_READ -> variable(b, c.var());
                case FOR -> {
                    if (c.init() != null) variable(b, c.init().var());
                    if (c.step() != null) variable(b, c.step().var());
                }
                default -> { }
            }
        }

        // pins and peripherals
        for (Block b : ids.keySet()) {
            Interpreter.Compiled c = b.compiled();
            switch (b.type) {
                case PIN_MODE -> {
                    Use u = use(b, c.expr());
                    if (c.var().equals("OUTPUT")) u.wantOut = true;
                    else u.wantIn = true;
                }
                case DIGITAL_WRITE -> {
                    Use u = use(b, c.expr());
                    u.wantOut = true;
                    u.digital = true;
                }
                case ANALOG_WRITE -> {
                    Use u = use(b, c.expr());
                    u.wantOut = true;
                    u.analog = true;
                    needPwm = true;
                }
                case DIGITAL_READ -> {
                    Use u = use(b, c.expr());
                    u.wantIn = true;
                }
                case DELAY -> { }
                case MILLIS -> needMillis = true;
                case PRINT -> {
                    needTx = true;
                    needPrint = true;
                }
                case UART_WRITE -> needTx = true;
                case UART_READ -> needRx = true;
                case UART_OPEN -> {
                    Long v = fold(c.expr());
                    if (v == null || v < 300 || v > 4_000_000) throw new FlowException(b, "the baud rate must be a constant number from 300 to 4000000, e.g. 115200");
                    if (baudBlock != null && baud != v) throw new FlowException(b, "all UART Open blocks must use the same baud rate (" + baud + " here)");
                    baud = (int) (long) v;
                    baudBlock = b;
                    needTx = true;
                }
                default -> { }
            }
        }
        for (Use u : uses.values()) {
            if (u.wantIn && u.wantOut) {
                throw new FlowException(u.first, "pin " + u.pin.port() + " is used both as an input and as an output - an FPGA pin has one direction in a design");
            }
            if (u.wantOut && u.pin.dir() == 'I') throw new FlowException(u.first, u.pin.port() + " is an input on this board (switch or button), it can't be written");
            if (u.wantIn && u.pin.dir() == 'O') throw new FlowException(u.first, u.pin.port() + " is an output on this board (LED), it can't be read");
        }
        if ((needTx || needRx) && spec.uartTx() == null) {
            throw new FlowException("the board " + boardName + " has no serial port wired in this design; remove Print and the UART blocks");
        }
        if (needRx && spec.uartRx() == null) throw new FlowException("the board " + boardName + " has no serial input");
        if (spec.clkHz() / baud < 4) throw new FlowException("baud rate " + baud + " is too fast for a " + spec.clkHz() / 1_000_000 + " MHz clock");
    }

    private void variable(Block b, String name) {
        if (name.startsWith("this.")) throw new FlowException(b, "this." + name.substring(5) + " only exists inside a class");
        if (vars.containsKey(name)) return;
        for (String o : vars.keySet()) {
            if (o.equalsIgnoreCase(name)) {
                throw new FlowException(b, "variables " + o + " and " + name + " differ only in upper/lower case; VHDL can't tell them apart");
            }
        }
        vars.put(name, null);
    }

    /** Evaluates a pin expression at build time and records the use of that pin. */
    private Use use(Block b, Expr pinExpr) {
        Long v = fold(pinExpr);
        if (v == null) throw new FlowException(b, "the pin must be a constant on an FPGA (a number or a name such as LED0, SW3, BTNC, JA1), not a variable");
        Fpga.Pin pin = spec.pin((int) (long) v);
        if (pin == null) {
            throw new FlowException(b, "pin " + v + " does not exist on " + boardName + ". Available: " + spec.describePins());
        }
        pinOf.put(b, pin.index());
        Use u = uses.get(pin.index());
        if (u == null) {
            u = new Use(pin, b);
            uses.put(pin.index(), u);
        }
        return u;
    }

    // ================================================================ declarations

    private void declare() {
        clk = rtl.in("clk", Ty.B);
        List<St> always = rtl.always;

        // variables
        for (String name : vars.keySet()) {
            Sig s = rtl.reg("v_" + name, Ty.S32, 0);
            s.comment = name;
            vars.put(name, s);
        }

        // the clock helpers
        cycMs = rtl.constant("CYC_MS", Ty.u(32), "CLK_HZ / 1000");
        cycMs.comment = "clock cycles per millisecond";
        boolean anyDelay = ids.keySet().stream().anyMatch(b -> b.type == BlockType.DELAY);
        if (anyDelay) {
            dlyMs = rtl.reg("dly_ms", Ty.S32, 0);
            dlySub = rtl.reg("dly_sub", Ty.u(32), 0);
        }
        if (needMillis) {
            msCnt = rtl.reg("ms_cnt", Ty.S32, 0);
            msSub = rtl.reg("ms_sub", Ty.u(32), 0);
            always.add(new If(Rtl.bin("==", Rtl.ref(msSub), Rtl.bin("-", Rtl.ref(cycMs), Rtl.ulit(1, 32))),
                    List.of(new Assign(msSub, Rtl.ulit(0, 32)), new Assign(msCnt, Rtl.bin("+", Rtl.ref(msCnt), Rtl.lit(1)))),
                    List.of(new Assign(msSub, Rtl.bin("+", Rtl.ref(msSub), Rtl.ulit(1, 32))))));
        }
        if (needPwm) {
            pwmBits = Math.max(8, (int) Math.round(Math.log(spec.clkHz() / 1500.0) / Math.log(2)));
            pwmDiv = rtl.reg("pwm_div", Ty.u(pwmBits), 0);
            pwmDiv.comment = "free-running; the top 8 bits are the PWM phase (about 1.5 kHz)";
            always.add(new Assign(pwmDiv, Rtl.bin("+", Rtl.ref(pwmDiv), Rtl.ulit(1, pwmBits))));
        }

        // pins
        List<Use> outs = new ArrayList<>();
        for (Use u : uses.values()) {
            String p = u.pin.port();
            if (u.wantOut) {
                u.level = rtl.reg("o_" + p, Ty.B, 0);
                if (u.analog) {
                    u.duty = rtl.reg("pd_" + p, Ty.u(8), 0);
                    u.enable = rtl.reg("pe_" + p, Ty.B, 0);
                }
                outs.add(u);
            } else {
                u.port = rtl.in(p, Ty.B);
                u.sync1 = rtl.reg("s1_" + p, Ty.B, 0);
                u.sync2 = rtl.reg("s2_" + p, Ty.B, 0);
                u.sync1.comment = "two flip-flops keep the asynchronous input from upsetting the logic";
                always.add(new Assign(u.sync1, Rtl.ref(u.port)));
                always.add(new Assign(u.sync2, Rtl.ref(u.sync1)));
            }
        }

        // serial port
        if (needTx || needRx) {
            baudDiv = rtl.constant("BAUD_DIV", Ty.u(32), "CLK_HZ / " + baud);
            baudDiv.comment = "clock cycles per serial bit at " + baud + " baud";
            baudHalf = rtl.constant("BAUD_HALF", Ty.u(32), "CLK_HZ / " + baud + " / 2");
        }
        if (needTx) {
            txLine = rtl.reg("tx_line", Ty.B, 1);
            txBusy = rtl.reg("tx_busy", Ty.B, 0);
            txBaud = rtl.reg("tx_baud", Ty.u(32), 0);
            txBits = rtl.reg("tx_bits", Ty.u(4), 0);
            txShift = rtl.reg("tx_shift", Ty.u(10), 1023);
            txByte = rtl.reg("tx_byte", Ty.u(8), 0);
            E last = Rtl.bin("-", Rtl.ref(baudDiv), Rtl.ulit(1, 32));
            List<St> loadBit = List.of(
                    new Assign(txLine, new Bit(r(txShift), 0)),
                    new Assign(txShift, new Cat(List.of(Rtl.bit(true), new Slice(r(txShift), 9, 1)))),
                    new Assign(txBits, Rtl.bin("-", Rtl.ref(txBits), Rtl.ulit(1, 4))));
            always.add(new If(Rtl.ref(txBusy), List.of(
                    new If(Rtl.bin("==", Rtl.ref(txBaud), last),
                            List.of(new Assign(txBaud, Rtl.ulit(0, 32)),
                                    new If(Rtl.bin("==", Rtl.ref(txBits), Rtl.ulit(0, 4)),
                                            List.of(new Assign(txBusy, Rtl.bit(false))),
                                            loadBit)),
                            List.of(new Assign(txBaud, Rtl.bin("+", Rtl.ref(txBaud), Rtl.ulit(1, 32)))))),
                    List.of()));
        }
        if (needRx) {
            rxLine = rtl.in("uart_rx", Ty.B);
            rxS1 = rtl.reg("rx_s1", Ty.B, 1);
            rxS2 = rtl.reg("rx_s2", Ty.B, 1);
            rxBusy = rtl.reg("rx_busy", Ty.B, 0);
            rxBaud = rtl.reg("rx_baud", Ty.u(32), 0);
            rxBits = rtl.reg("rx_bits", Ty.u(4), 0);
            rxShift = rtl.reg("rx_shift", Ty.u(8), 0);
            rxByte = rtl.reg("rx_byte", Ty.u(8), 0);
            rxValid = rtl.reg("rx_valid", Ty.B, 0);
            always.add(new Assign(rxS1, Rtl.ref(rxLine)));
            always.add(new Assign(rxS2, Rtl.ref(rxS1)));
            E limit = new Mux(Rtl.bin("==", Rtl.ref(rxBits), Rtl.ulit(0, 4)),
                    Rtl.bin("-", Rtl.ref(baudHalf), Rtl.ulit(1, 32)), Rtl.bin("-", Rtl.ref(baudDiv), Rtl.ulit(1, 32)));
            List<St> sample = List.of(
                    new Assign(rxBaud, Rtl.ulit(0, 32)),
                    new If(Rtl.bin("==", Rtl.ref(rxBits), Rtl.ulit(0, 4)),
                            List.of(new If(Rtl.ref(rxS2), List.of(new Assign(rxBusy, Rtl.bit(false))),
                                    List.of(new Assign(rxBits, Rtl.ulit(1, 4))))),
                            List.of(new If(Rtl.bin("<", Rtl.ref(rxBits), Rtl.ulit(9, 4)),
                                    List.of(new Assign(rxShift, new Cat(List.of(Rtl.ref(rxS2), new Slice(r(rxShift), 7, 1)))),
                                            new Assign(rxBits, Rtl.bin("+", Rtl.ref(rxBits), Rtl.ulit(1, 4)))),
                                    List.of(new Assign(rxByte, Rtl.ref(rxShift)),
                                            new Assign(rxValid, Rtl.bit(true)),
                                            new Assign(rxBusy, Rtl.bit(false)))))));
            always.add(new If(Rtl.ref(rxBusy),
                    List.of(new If(Rtl.bin("==", Rtl.ref(rxBaud), limit), sample,
                            List.of(new Assign(rxBaud, Rtl.bin("+", Rtl.ref(rxBaud), Rtl.ulit(1, 32)))))),
                    List.of(new If(new Un("!", Rtl.ref(rxS2)),
                            List.of(new Assign(rxBusy, Rtl.bit(true)), new Assign(rxBaud, Rtl.ulit(0, 32)), new Assign(rxBits, Rtl.ulit(0, 4))),
                            List.of()))));
        }

        // outputs last, so the ports read: clk, inputs, outputs
        for (Use u : outs) {
            E driver = Rtl.ref(u.level);
            if (u.analog) {
                E phase = new Slice(r(pwmDiv), pwmBits - 1, pwmBits - 8);
                E on = Rtl.bin("||", Rtl.bin("<", phase, Rtl.ref(u.duty)), Rtl.bin("==", Rtl.ref(u.duty), Rtl.ulit(255, 8)));
                driver = new Mux(Rtl.ref(u.enable), on, Rtl.ref(u.level));
            }
            rtl.out(u.pin.port(), driver);
        }
        if (needTx) rtl.out("uart_tx", Rtl.ref(txLine));

        // the division unit
        needDiv = ids.keySet().stream().anyMatch(b -> divs(b) > 0 || forDivs(b) > 0 || printDivs(b) > 0);
        if (needDiv) {
            dvA = rtl.reg("dv_a", Ty.u(32), 0);
            dvB = rtl.reg("dv_b", Ty.u(32), 0);
            dvR = rtl.reg("dv_r", Ty.u(32), 0);
            dvN = rtl.reg("dv_n", Ty.u(8), 0);
            dvSa = rtl.reg("dv_sa", Ty.B, 0);
            dvSb = rtl.reg("dv_sb", Ty.B, 0);
            dvA.comment = "divider: dividend, becomes the quotient";
        }
        if (needPrint) {
            prIdx = rtl.reg("pr_idx", Ty.u(16), 0);
            prEnd = rtl.reg("pr_end", Ty.u(16), 0);
            pnMag = rtl.reg("pn_mag", Ty.u(32), 0);
            pnNeg = rtl.reg("pn_neg", Ty.B, 0);
            pnI = rtl.reg("pn_i", Ty.u(4), 0);
            pnDig = rtl.reg("pn_dig", Ty.u(8), 0);
            pnSt = rtl.reg("pn_st", Ty.B, 0);
        }
    }

    private static Ref r(Sig s) {
        return new Ref(s);
    }

    // ================================================================ constants

    /** The value of a constant expression with 32-bit wrap-around, or null when it isn't constant (or isn't a whole number). */
    private Long fold(Expr e) {
        return switch (e) {
            case Expr.Num n -> n.value() == Math.rint(n.value()) && Math.abs(n.value()) < 1e10 ? (Long) (long) (int) (long) n.value() : null;
            case Expr.Str s -> null;
            case Expr.Var v -> {
                if (vars.containsKey(v.name())) yield null;
                if (v.name().equals("LED_BUILTIN")) yield (long) spec.builtinLed();
                Double d = BoardSim.constant(v.name());
                yield d == null ? null : (Long) (long) (double) d;
            }
            case Expr.Unary u -> {
                Long x = fold(u.operand());
                if (x == null) yield null;
                yield u.op().equals("-") ? (Long) (long) (int) -x : (Long) (x == 0 ? 1L : 0L);
            }
            case Expr.Binary b -> {
                Long x = fold(b.left()), y = fold(b.right());
                if (x == null || y == null) yield null;
                int p = (int) (long) x, q = (int) (long) y;
                yield switch (b.op()) {
                    case "+" -> (Long) (long) (p + q);
                    case "-" -> (Long) (long) (p - q);
                    case "*" -> (Long) (long) (p * q);
                    case "/" -> (Long) (q == 0 ? 0L : (long) (p / q));
                    case "%" -> (Long) (q == 0 ? 0L : (long) (p % q));
                    case "==" -> (Long) (p == q ? 1L : 0L);
                    case "!=" -> (Long) (p != q ? 1L : 0L);
                    case "<" -> (Long) (p < q ? 1L : 0L);
                    case "<=" -> (Long) (p <= q ? 1L : 0L);
                    case ">" -> (Long) (p > q ? 1L : 0L);
                    case ">=" -> (Long) (p >= q ? 1L : 0L);
                    case "&&" -> (Long) (p != 0 && q != 0 ? 1L : 0L);
                    case "||" -> (Long) (p != 0 || q != 0 ? 1L : 0L);
                    default -> null;
                };
            }
        };
    }

    // ================================================================ expressions

    /** A division waiting for its states. */
    private record Div(E a, E b, boolean mod, Sig result) {}

    private final List<Div> pending = new ArrayList<>();

    private Sig divTemp(int k) {
        return divTemp.computeIfAbsent(k, i -> rtl.reg("dq" + i, Ty.S32, 0));
    }

    /** How many divisions an expression needs (the same rule {@link #lower} follows). */
    private int divCount(Expr e) {
        if (e == null || fold(e) != null) return 0;
        return switch (e) {
            case Expr.Unary u -> divCount(u.operand());
            case Expr.Binary b -> divCount(b.left()) + divCount(b.right()) + (b.op().equals("/") || b.op().equals("%") ? 1 : 0);
            default -> 0;
        };
    }

    /** Divisions in the main unit of a block (the states in front of its main state). */
    private int divs(Block b) {
        Interpreter.Compiled c = b.compiled();
        return switch (b.type) {
            case SET, IF, WHILE, DO_WHILE, DELAY, UART_WRITE -> divCount(c.expr());
            case FOR -> divCount(c.expr());
            case DIGITAL_WRITE, ANALOG_WRITE -> divCount(c.args().get(0));
            default -> 0;
        };
    }

    /** Divisions inside the number parts of a Print. */
    private int printDivs(Block b) {
        if (b.type != BlockType.PRINT) return 0;
        List<Part> ps = new ArrayList<>();
        parts(b.compiled().expr(), ps, b);
        int n = 0;
        for (Part p : ps) if (p.number() != null) n += divCount(p.number());
        return n;
    }

    private int forDivs(Block b) {
        if (b.type != BlockType.FOR) return 0;
        Interpreter.Compiled c = b.compiled();
        return Math.max(c.init() == null ? 0 : divCount(c.init().expr()), c.step() == null ? 0 : divCount(c.step().expr()));
    }

    private E lower(Expr e, Block b) {
        Long c = fold(e);
        if (c != null) {
            if (c == Integer.MIN_VALUE) throw new FlowException(b, "-2147483648 is out of range here, use -2147483647");
            return Rtl.lit(c);
        }
        return switch (e) {
            case Expr.Num n -> throw new FlowException(b, "an FPGA program uses whole numbers; " + Expr.str(n.value()) + " is not one");
            case Expr.Str s -> throw new FlowException(b, "text can only be printed; it can't be stored or compared on an FPGA");
            case Expr.Var v -> {
                Sig s = vars.get(v.name());
                if (s == null) throw new FlowException(b, "'" + v.name() + "' is used but never set (and is not a board name like LED0 or HIGH)");
                yield Rtl.ref(s);
            }
            case Expr.Unary u -> u.op().equals("-") ? new Un("-", lower(u.operand(), b)) : new FromBit(lowerBit(e, b));
            case Expr.Binary x -> switch (x.op()) {
                case "+", "-", "*" -> Rtl.bin(x.op(), lower(x.left(), b), lower(x.right(), b));
                case "/", "%" -> {
                    E left = lower(x.left(), b), right = lower(x.right(), b);
                    Sig t = divTemp(pending.size());
                    pending.add(new Div(left, right, x.op().equals("%"), t));
                    yield Rtl.ref(t);
                }
                default -> new FromBit(lowerBit(e, b));
            };
        };
    }

    /** The expression as a single bit (a condition). */
    private E lowerBit(Expr e, Block b) {
        Long c = fold(e);
        if (c != null) return Rtl.bit(c != 0);
        if (e instanceof Expr.Unary u && u.op().equals("!")) return new Un("!", lowerBit(u.operand(), b));
        if (e instanceof Expr.Binary x) {
            switch (x.op()) {
                case "&&", "||" -> {
                    return Rtl.bin(x.op(), lowerBit(x.left(), b), lowerBit(x.right(), b));
                }
                case "==", "!=", "<", "<=", ">", ">=" -> {
                    return Rtl.bin(x.op(), lower(x.left(), b), lower(x.right(), b));
                }
                default -> { }
            }
        }
        return new ToBit(lower(e, b));
    }

    /** Chains the states of the divisions lowered into {@code pending} in front of state {@code main}. */
    private void flushDivs(String base, String main, int expected) {
        if (pending.size() != expected) throw new IllegalStateException("division count " + pending.size() + " != " + expected + " in " + base);
        for (int k = 0; k < pending.size(); k++) {
            Div d = pending.get(k);
            String a = base + "_d" + k + "a", n = base + "_d" + k + "b";
            String after = k + 1 < pending.size() ? base + "_d" + (k + 1) + "a" : main;
            Rtl.State s = rtl.state(a, "division " + (d.mod() ? "%" : "/") + ": load");
            s.body().add(new Assign(dvA, new Mux(Rtl.bin("<", d.a(), Rtl.lit(0)), new Un("-", d.a()), d.a())));
            s.body().add(new Assign(dvB, new Mux(Rtl.bin("<", d.b(), Rtl.lit(0)), new Un("-", d.b()), d.b())));
            s.body().add(new Assign(dvR, Rtl.ulit(0, 32)));
            s.body().add(new Assign(dvN, Rtl.ulit(32, 8)));
            s.body().add(new Assign(dvSa, Rtl.bin("<", d.a(), Rtl.lit(0))));
            s.body().add(new Assign(dvSb, Rtl.bin("<", d.b(), Rtl.lit(0))));
            s.body().add(new Goto(n));

            Rtl.State it = rtl.state(n, "division " + (d.mod() ? "%" : "/") + ": 32 shift-and-subtract steps");
            E shifted = new Cat(List.of(new Slice(r(dvR), 30, 0), new Bit(r(dvA), 31)));
            E shiftedA = new Slice(r(dvA), 30, 0);
            List<St> step = List.of(
                    new If(Rtl.bin(">=", shifted, Rtl.ref(dvB)),
                            List.of(new Assign(dvR, Rtl.bin("-", shifted, Rtl.ref(dvB))),
                                    new Assign(dvA, new Cat(List.of(shiftedA, Rtl.bit(true))))),
                            List.of(new Assign(dvR, shifted),
                                    new Assign(dvA, new Cat(List.of(shiftedA, Rtl.bit(false)))))),
                    new Assign(dvN, Rtl.bin("-", Rtl.ref(dvN), Rtl.ulit(1, 8))));
            E mag = d.mod() ? Rtl.ref(dvR) : Rtl.ref(dvA);
            E neg = d.mod() ? Rtl.ref(dvSa) : Rtl.bin("!=", Rtl.ref(dvSa), Rtl.ref(dvSb));
            E signed = new Mux(neg, Rtl.bin("-", Rtl.ulit(0, 32), mag), mag);
            E result = new Mux(Rtl.bin("==", Rtl.ref(dvB), Rtl.ulit(0, 32)), Rtl.ulit(0, 32), signed);
            it.body().add(new If(Rtl.bin("==", Rtl.ref(dvN), Rtl.ulit(0, 8)),
                    List.of(new Assign(d.result(), result), new Goto(after)),
                    step));
        }
        pending.clear();
    }

    // ================================================================ states

    private String name(Block b) {
        return "B" + ids.get(b);
    }

    /** The first state of a unit named {@code base} that needs {@code n} divisions before its main state. */
    private static String unit(String base, int n) {
        return n > 0 ? base + "_d0a" : base;
    }

    /** Where control goes when it reaches {@code to} from {@code from}. */
    private String entry(Block to, Block from) {
        if (to == null || to.type == BlockType.END) return "HALT";
        String n = name(to);
        switch (to.type) {
            case FOR -> {
                Interpreter.Compiled c = to.compiled();
                boolean again = to.loopBody.contains(from);
                if (again) return c.step() == null ? unit(n, divs(to)) : unit(n + "s", divCount(c.step().expr()));
                return c.init() == null ? unit(n, divs(to)) : unit(n + "i", divCount(c.init().expr()));
            }
            case DO_WHILE -> {
                if (to.loopBody.contains(from) || to.next[0] == null || to.next[0] == to) return unit(n, divs(to));
                return entry(to.next[0], to);
            }
            default -> {
                return unit(n, divs(to));
            }
        }
    }

    /** A two-way branch; a condition that is a constant becomes a plain jump. */
    private St branch(Block b, E cond) {
        if (cond instanceof Rtl.Lit l) return new Goto(go(b, l.value() != 0 ? 0 : 1));
        return new If(cond, List.of(new Goto(go(b, 0))), List.of(new Goto(go(b, 1))));
    }

    private String go(Block from, int port) {
        return entry(from.next[port], from);
    }

    private String label(Block b) {
        String code = b.type.hasCode ? b.getCode().trim().replace('\n', ' ') : "";
        return b.type.title + (code.isEmpty() ? "" : ": " + code);
    }

    private void emit(Block b) {
        Interpreter.Compiled c = b.compiled();
        String n = name(b);
        switch (b.type) {
            case START -> rtl.state(n, "Start").body().add(new Goto(go(b, 0)));
            case END -> { }
            case SET -> {
                Rtl.State s = rtl.state(n, label(b));
                E v = lower(c.expr(), b);
                s.body().add(new Assign(vars.get(c.var()), v));
                s.body().add(new Goto(go(b, 0)));
                flushDivs(n, n, divCount(c.expr()));
            }
            case IF -> {
                Rtl.State s = rtl.state(n, label(b));
                E cond = lowerBit(c.expr(), b);
                s.body().add(branch(b, cond));
                flushDivs(n, n, divCount(c.expr()));
            }
            case WHILE, DO_WHILE -> {
                Rtl.State s = rtl.state(n, label(b));
                E cond = lowerBit(c.expr(), b);
                s.body().add(branch(b, cond));
                flushDivs(n, n, divCount(c.expr()));
            }
            case FOR -> {
                if (c.init() != null) {
                    Rtl.State s = rtl.state(n + "i", "For init: " + label(b));
                    E v = lower(c.init().expr(), b);
                    s.body().add(new Assign(vars.get(c.init().var()), v));
                    s.body().add(new Goto(unit(n, divCount(c.expr()))));
                    flushDivs(n + "i", n + "i", divCount(c.init().expr()));
                }
                if (c.step() != null) {
                    Rtl.State s = rtl.state(n + "s", "For step");
                    E v = lower(c.step().expr(), b);
                    s.body().add(new Assign(vars.get(c.step().var()), v));
                    s.body().add(new Goto(unit(n, divCount(c.expr()))));
                    flushDivs(n + "s", n + "s", divCount(c.step().expr()));
                }
                Rtl.State s = rtl.state(n, "For test");
                if (c.expr() == null) {
                    s.body().add(new Goto(go(b, 0)));
                } else {
                    E cond = lowerBit(c.expr(), b);
                    s.body().add(branch(b, cond));
                    flushDivs(n, n, divCount(c.expr()));
                }
            }
            case PIN_MODE -> rtl.state(n, label(b)).body().add(new Goto(go(b, 0)));
            case DIGITAL_WRITE -> {
                Use u = uses.get(pinOf.get(b));
                Rtl.State s = rtl.state(n, label(b));
                E v = lowerBit(c.args().get(0), b);
                s.body().add(new Assign(u.level, v));
                if (u.analog) s.body().add(new Assign(u.enable, Rtl.bit(false)));
                s.body().add(new Goto(go(b, 0)));
                flushDivs(n, n, divCount(c.args().get(0)));
            }
            case ANALOG_WRITE -> {
                Use u = uses.get(pinOf.get(b));
                Rtl.State s = rtl.state(n, label(b));
                E v = lower(c.args().get(0), b);
                E clamp = new Mux(Rtl.bin("<", v, Rtl.lit(0)), Rtl.lit(0), new Mux(Rtl.bin(">", v, Rtl.lit(255)), Rtl.lit(255), v));
                s.body().add(new Assign(u.duty, clamp));
                s.body().add(new Assign(u.enable, Rtl.bit(true)));
                s.body().add(new Goto(go(b, 0)));
                flushDivs(n, n, divCount(c.args().get(0)));
            }
            case DIGITAL_READ -> {
                Use u = uses.get(pinOf.get(b));
                Rtl.State s = rtl.state(n, label(b));
                s.body().add(new Assign(vars.get(c.var()), new FromBit(Rtl.ref(u.sync2))));
                s.body().add(new Goto(go(b, 0)));
            }
            case MILLIS -> {
                Rtl.State s = rtl.state(n, label(b));
                s.body().add(new Assign(vars.get(c.var()), Rtl.ref(msCnt)));
                s.body().add(new Goto(go(b, 0)));
            }
            case DELAY -> {
                Rtl.State s = rtl.state(n, label(b));
                E v = lower(c.expr(), b);
                s.body().add(new Assign(dlyMs, v));
                s.body().add(new Assign(dlySub, Rtl.ulit(0, 32)));
                s.body().add(new Goto(n + "w"));
                flushDivs(n, n, divCount(c.expr()));
                Rtl.State w = rtl.state(n + "w", "waiting");
                w.body().add(new If(Rtl.bin("<=", Rtl.ref(dlyMs), Rtl.lit(0)),
                        List.of(new Goto(go(b, 0))),
                        List.of(new If(Rtl.bin("==", Rtl.ref(dlySub), Rtl.bin("-", Rtl.ref(cycMs), Rtl.ulit(1, 32))),
                                List.of(new Assign(dlySub, Rtl.ulit(0, 32)), new Assign(dlyMs, Rtl.bin("-", Rtl.ref(dlyMs), Rtl.lit(1)))),
                                List.of(new Assign(dlySub, Rtl.bin("+", Rtl.ref(dlySub), Rtl.ulit(1, 32))))))));
            }
            case UART_OPEN -> rtl.state(n, label(b) + " (baud rate " + baud + " is set for the whole design)").body().add(new Goto(go(b, 0)));
            case UART_WRITE -> {
                Rtl.State s = rtl.state(n, label(b));
                E v = lower(c.expr(), b);
                s.body().add(new Assign(txByte, v));
                s.body().add(new Goto(n + "s"));
                flushDivs(n, n, divCount(c.expr()));
                Rtl.State w = rtl.state(n + "s", "send when the transmitter is free");
                List<St> send = new ArrayList<>();
                txStart(send, new Cat(List.of(Rtl.bit(true), Rtl.ref(txByte), Rtl.bit(false))));
                send.add(new Goto(go(b, 0)));
                w.body().add(new If(new Un("!", Rtl.ref(txBusy)), send, List.of()));
            }
            case UART_READ -> {
                Rtl.State s = rtl.state(n, label(b));
                s.body().add(new If(Rtl.ref(rxValid),
                        List.of(new Assign(vars.get(c.var()), new Rtl.ToS32(Rtl.ref(rxByte))), new Assign(rxValid, Rtl.bit(false))),
                        List.of(new Assign(vars.get(c.var()), Rtl.lit(-1)))));
                s.body().add(new Goto(go(b, 0)));
            }
            case PRINT -> emitPrint(b, c);
            default -> throw new FlowException(b, b.type.title + " is not supported when generating for an FPGA");
        }
    }

    // ================================================================ serial port and Print

    private void txStart(List<St> to, E frame) {
        to.add(new Assign(txShift, frame));
        to.add(new Assign(txBaud, Rtl.bin("-", Rtl.ref(baudDiv), Rtl.ulit(1, 32))));
        to.add(new Assign(txBits, Rtl.ulit(10, 4)));
        to.add(new Assign(txBusy, Rtl.bit(true)));
    }

    /** A piece of a Print: text, or a number. */
    private record Part(String text, Expr number) {}

    private static boolean textual(Expr e) {
        return e instanceof Expr.Str || (e instanceof Expr.Binary b && b.op().equals("+") && (textual(b.left()) || textual(b.right())));
    }

    private void parts(Expr e, List<Part> out, Block blk) {
        if (e instanceof Expr.Str s) {
            out.add(new Part(s.value(), null));
        } else if (e instanceof Expr.Binary b && b.op().equals("+") && textual(e)) {
            parts(b.left(), out, blk);
            parts(b.right(), out, blk);
        } else {
            Long c = fold(e);
            if (c != null) out.add(new Part(Long.toString(c), null));
            else out.add(new Part(null, e));
        }
    }

    private String partEntry(String n, List<Part> ps, int j) {
        Part p = ps.get(j);
        return unit(n + "p" + j, p.text() != null ? 0 : divCount(p.number()));
    }

    private void emitPrint(Block b, Interpreter.Compiled c) {
        String n = name(b);
        List<Part> raw = new ArrayList<>();
        parts(c.expr(), raw, b);
        raw.add(new Part("\r\n", null));
        List<Part> ps = new ArrayList<>();
        for (Part p : raw) {
            if (p.text() != null && !ps.isEmpty() && ps.get(ps.size() - 1).text() != null) {
                ps.set(ps.size() - 1, new Part(ps.get(ps.size() - 1).text() + p.text(), null));
            } else {
                ps.add(p);
            }
        }
        Rtl.State s = rtl.state(n, label(b));
        s.body().add(new Goto(partEntry(n, ps, 0)));
        for (int j = 0; j < ps.size(); j++) {
            String q = n + "p" + j;
            String after = j + 1 < ps.size() ? partEntry(n, ps, j + 1) : go(b, 0);
            Part p = ps.get(j);
            if (p.text() != null) {
                for (char ch : p.text().toCharArray()) {
                    if (ch > 126 || (ch < 32 && ch != '\r' && ch != '\n' && ch != '\t')) {
                        throw new FlowException(b, "only plain ASCII text can be sent over the serial port (found character code " + (int) ch + ")");
                    }
                }
                byte[] bytes = p.text().getBytes(StandardCharsets.US_ASCII);
                int off = strings.add(bytes);
                if (off + bytes.length > 65535) throw new FlowException(b, "too much text to print");
                Rtl.State a = rtl.state(q, "text \"" + p.text().replace("\r", "\\r").replace("\n", "\\n") + "\"");
                a.body().add(new Assign(prIdx, Rtl.ulit(off, 16)));
                a.body().add(new Assign(prEnd, Rtl.ulit(off + bytes.length, 16)));
                a.body().add(new Goto(q + "b"));
                Rtl.State w = rtl.state(q + "b", "send one character at a time");
                List<St> send = new ArrayList<>();
                txStart(send, new Cat(List.of(Rtl.bit(true), new RomAt("msg", Rtl.ref(prIdx)), Rtl.bit(false))));
                send.add(new Assign(prIdx, Rtl.bin("+", Rtl.ref(prIdx), Rtl.ulit(1, 16))));
                w.body().add(new If(Rtl.bin("==", Rtl.ref(prIdx), Rtl.ref(prEnd)),
                        List.of(new Goto(after)),
                        List.of(new If(new Un("!", Rtl.ref(txBusy)), send, List.of()))));
            } else {
                Rtl.State a = rtl.state(q, "number");
                E v = lower(p.number(), b);
                a.body().add(new Assign(pnNeg, Rtl.bin("<", v, Rtl.lit(0))));
                a.body().add(new Assign(pnMag, new Mux(Rtl.bin("<", v, Rtl.lit(0)), new Un("-", v), v)));
                a.body().add(new Assign(pnI, Rtl.ulit(0, 4)));
                a.body().add(new Assign(pnDig, Rtl.ulit(0, 8)));
                a.body().add(new Assign(pnSt, Rtl.bit(false)));
                a.body().add(new Goto(q + "s"));
                flushDivs(q, q, divCount(p.number()));

                Rtl.State sign = rtl.state(q + "s", "minus sign");
                List<St> minus = new ArrayList<>();
                txStart(minus, new Cat(List.of(Rtl.bit(true), Rtl.ulit('-', 8), Rtl.bit(false))));
                minus.add(new Assign(pnNeg, Rtl.bit(false)));
                minus.add(new Goto(q + "x"));
                sign.body().add(new If(Rtl.ref(pnNeg),
                        List.of(new If(new Un("!", Rtl.ref(txBusy)), minus, List.of())),
                        List.of(new Goto(q + "x"))));

                E pow = Rtl.ulit(1, 32);
                for (int k = 8; k >= 0; k--) {
                    long p10 = 1;
                    for (int t = 0; t < 9 - k; t++) p10 *= 10;
                    pow = new Mux(Rtl.bin("==", Rtl.ref(pnI), Rtl.ulit(k, 4)), Rtl.ulit(p10, 32), pow);
                }
                Rtl.State ex = rtl.state(q + "x", "count how many times this power of ten fits");
                ex.body().add(new If(Rtl.bin(">=", Rtl.ref(pnMag), pow),
                        List.of(new Assign(pnMag, Rtl.bin("-", Rtl.ref(pnMag), pow)),
                                new Assign(pnDig, Rtl.bin("+", Rtl.ref(pnDig), Rtl.ulit(1, 8)))),
                        List.of(new Goto(q + "e"))));

                Rtl.State em = rtl.state(q + "e", "send the digit (no leading zeros)");
                List<St> advance = List.of(new If(Rtl.bin("==", Rtl.ref(pnI), Rtl.ulit(9, 4)),
                        List.of(new Goto(after)),
                        List.of(new Assign(pnI, Rtl.bin("+", Rtl.ref(pnI), Rtl.ulit(1, 4))),
                                new Assign(pnDig, Rtl.ulit(0, 8)),
                                new Goto(q + "x"))));
                List<St> digit = new ArrayList<>();
                txStart(digit, new Cat(List.of(Rtl.bit(true), Rtl.bin("+", Rtl.ref(pnDig), Rtl.ulit(48, 8)), Rtl.bit(false))));
                digit.add(new Assign(pnSt, Rtl.bit(true)));
                digit.addAll(advance);
                E emit = Rtl.bin("||", Rtl.bin("||", Rtl.bin("!=", Rtl.ref(pnDig), Rtl.ulit(0, 8)), Rtl.ref(pnSt)),
                        Rtl.bin("==", Rtl.ref(pnI), Rtl.ulit(9, 4)));
                em.body().add(new If(emit, List.of(new If(new Un("!", Rtl.ref(txBusy)), digit, List.of())), advance));
            }
        }
        if (!strings.out.toString().isEmpty() || needPrint) rtl.roms.put("msg", strings.out.toByteArray());
    }
}
