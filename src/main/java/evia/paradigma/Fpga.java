package evia.paradigma;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The FPGA boards: which chip, the clock, and where the LEDs, switches, buttons, Pmod pins and the serial port
 * of the board are connected. The pin data comes from Digilent's master constraint files (Nexys A7, Arty A7,
 * Basys 3) and from Olimex's and Cologne Chip's published files (GateMate).
 *
 * <p>Pins are numbered the same on every board so a program is portable:
 * 0..15 LED0..LED15, 16..31 SW0..SW15, 32..36 the buttons (BTNC BTNU BTNL BTNR BTND, or BTN0..BTN3),
 * 40..47 Pmod JA1..JA4 JA7..JA10, 48..55 JB, 56..63 JC, 64..71 JD. A board has only the pins it really has.
 */
final class Fpga {
    private Fpga() {}

    enum Family {
        ARTIX7("Artix-7"), GATEMATE("GateMate");
        final String display;
        Family(String display) { this.display = display; }
    }

    /** dir: 'O' output only (LED), 'I' input only (switch, button), 'B' either (Pmod). */
    record Pin(int index, String port, String loc, String io, char dir) {}

    /**
     * @param part      Vivado part (Artix-7) or the nextpnr device (GateMate)
     * @param loader    openFPGALoader board name
     * @param uartRx    FPGA input fed by the board's USB serial chip, or null; same for uartTx
     * @param uartNote  what to know about the serial port, or ""
     */
    record Spec(Family family, String part, String loader, int clkHz, String clkLoc, String clkIo,
                String uartRx, String uartRxLoc, String uartTx, String uartTxLoc, String uartIo, String uartNote,
                List<Pin> pins) {

        Pin pin(int index) {
            for (Pin p : pins) if (p.index() == index) return p;
            return null;
        }

        /** The first LED: what LED_BUILTIN means. */
        int builtinLed() {
            return 0;
        }

        /** "LED0..LED15, SW0..SW15, JA1..JA4, JA7..JA10 ..." for error messages: runs of pins with consecutive numbers on the board. */
        String describePins() {
            StringBuilder sb = new StringBuilder();
            Pin first = null, last = null;
            for (Pin p : pins) {
                boolean continues = last != null && p.index() == last.index() + 1
                        && p.port().replaceAll("[0-9]+$", "").equals(last.port().replaceAll("[0-9]+$", ""))
                        && Integer.parseInt(p.port().replaceAll("^[A-Za-z]+", "")) == Integer.parseInt(last.port().replaceAll("^[A-Za-z]+", "")) + 1;
                if (!continues && first != null) sb.append(range(first, last)).append(", ");
                if (!continues) first = p;
                last = p;
            }
            if (first != null) sb.append(range(first, last));
            return sb.toString();
        }

        private static String range(Pin a, Pin b) {
            return a == b ? a.port() : a.port() + ".." + b.port();
        }
    }

    // ================================================================ the boards

    private static final String[] PMOD = {"1", "2", "3", "4", "7", "8", "9", "10"};

    private static void leds(List<Pin> p, String... locs) {
        for (int i = 0; i < locs.length; i++) p.add(new Pin(i, "LED" + i, locs[i], "LVCMOS33", 'O'));
    }

    private static void switches(List<Pin> p, String... locs) {
        for (int i = 0; i < locs.length; i++) p.add(new Pin(16 + i, "SW" + i, locs[i], "LVCMOS33", 'I'));
    }

    private static void pmod(List<Pin> p, int slot, String name, String... locs) {
        for (int i = 0; i < locs.length; i++) p.add(new Pin(40 + 8 * slot + i, name + PMOD[i], locs[i], "LVCMOS33", 'B'));
    }

    static Spec nexysA7100T() {
        List<Pin> p = new ArrayList<>();
        leds(p, "H17", "K15", "J13", "N14", "R18", "V17", "U17", "U16", "V16", "T15", "U14", "T16", "V15", "V14", "V12", "V11");
        switches(p, "J15", "L16", "M13", "R15", "R17", "T18", "U18", "R13", "T8", "U8", "R16", "T13", "H6", "U12", "U11", "V10");
        // SW8 and SW9 are on a 1.8 V bank of this board
        p.set(p.indexOf(p.stream().filter(x -> x.port().equals("SW8")).findFirst().get()), new Pin(24, "SW8", "T8", "LVCMOS18", 'I'));
        p.set(p.indexOf(p.stream().filter(x -> x.port().equals("SW9")).findFirst().get()), new Pin(25, "SW9", "U8", "LVCMOS18", 'I'));
        String[] btn = {"BTNC", "BTNU", "BTNL", "BTNR", "BTND"};
        String[] btnLoc = {"N17", "M18", "P17", "M17", "P18"};
        for (int i = 0; i < 5; i++) p.add(new Pin(32 + i, btn[i], btnLoc[i], "LVCMOS33", 'I'));
        pmod(p, 0, "JA", "C17", "D18", "E18", "G17", "D17", "E17", "F18", "G18");
        pmod(p, 1, "JB", "D14", "F16", "G16", "H14", "E16", "F13", "G13", "H16");
        pmod(p, 2, "JC", "K1", "F6", "J2", "G6", "E7", "J3", "J4", "E6");
        pmod(p, 3, "JD", "H4", "H1", "G1", "G3", "H2", "G4", "G2", "F3");
        return new Spec(Family.ARTIX7, "xc7a100tcsg324-1", "nexys_a7_100", 100_000_000, "E3", "LVCMOS33",
                "UART_TXD_IN", "C4", "UART_RXD_OUT", "D4", "LVCMOS33", "USB-UART of the board (the COM port that appears when you plug it in)", p);
    }

    private static Spec arty(String part, String loader) {
        List<Pin> p = new ArrayList<>();
        leds(p, "H5", "J5", "T9", "T10");
        switches(p, "A8", "C11", "C10", "A10");
        String[] btnLoc = {"D9", "C9", "B9", "B8"};
        for (int i = 0; i < 4; i++) p.add(new Pin(32 + i, "BTN" + i, btnLoc[i], "LVCMOS33", 'I'));
        pmod(p, 0, "JA", "G13", "B11", "A11", "D12", "D13", "B18", "A18", "K16");
        pmod(p, 1, "JB", "E15", "E16", "D15", "C15", "J17", "J18", "K15", "J15");
        pmod(p, 2, "JC", "U12", "V12", "V10", "V11", "U14", "V14", "T13", "U13");
        pmod(p, 3, "JD", "D4", "D3", "F4", "F3", "E2", "D2", "H2", "G2");
        return new Spec(Family.ARTIX7, part, loader, 100_000_000, "E3", "LVCMOS33",
                "uart_txd_in", "A9", "uart_rxd_out", "D10", "LVCMOS33", "USB-UART of the board (the COM port that appears when you plug it in)", p);
    }

    static Spec artyA7_35() {
        return arty("xc7a35ticsg324-1L", "arty_a7_35t");
    }

    static Spec artyA7_100() {
        return arty("xc7a100tcsg324-1", "arty_a7_100t");
    }

    static Spec basys3() {
        List<Pin> p = new ArrayList<>();
        leds(p, "U16", "E19", "U19", "V19", "W18", "U15", "U14", "V14", "V13", "V3", "W3", "U3", "P3", "N3", "P1", "L1");
        switches(p, "V17", "V16", "W16", "W17", "W15", "V15", "W14", "W13", "V2", "T3", "T2", "R3", "W2", "U1", "T1", "R2");
        String[] btn = {"BTNC", "BTNU", "BTNL", "BTNR", "BTND"};
        String[] btnLoc = {"U18", "T18", "W19", "T17", "U17"};
        for (int i = 0; i < 5; i++) p.add(new Pin(32 + i, btn[i], btnLoc[i], "LVCMOS33", 'I'));
        pmod(p, 0, "JA", "J1", "L2", "J2", "G2", "H1", "K2", "H2", "G3");
        pmod(p, 1, "JB", "A14", "A16", "B15", "B16", "A15", "A17", "C15", "C16");
        pmod(p, 2, "JC", "K17", "M18", "N17", "P18", "L17", "M19", "P17", "R18");
        return new Spec(Family.ARTIX7, "xc7a35tcpg236-1", "basys3", 100_000_000, "W5", "LVCMOS33",
                "RsRx", "B18", "RsTx", "A18", "LVCMOS33", "USB-UART of the board (the COM port that appears when you plug it in)", p);
    }

    /** Olimex GateMateA1-EVB: 10 MHz oscillator, one user LED, one FPGA button; pins from the board's KiCad design. */
    static Spec olimexGateMate() {
        List<Pin> p = new ArrayList<>();
        p.add(new Pin(0, "LED0", "IO_SB_B6", "", 'O'));
        p.add(new Pin(32, "BTN0", "IO_SB_B7", "", 'I'));
        return new Spec(Family.GATEMATE, "CCGM1A1", "olimex_gatemateevb", 10_000_000, "IO_SB_A8", "",
                "UART_RX", "IO_SA_A6", "UART_TX", "IO_SA_B6", "",
                "the RP2040's debug UART (GPIO12/GPIO13); whether it reaches the USB port depends on the RP2040 firmware", p);
    }

    /** Cologne Chip's GateMate evaluation board: the 10 MHz oscillator and one LED are the pins known from the published example. */
    static Spec cologneChipGateMate() {
        List<Pin> p = new ArrayList<>();
        p.add(new Pin(0, "LED0", "IO_EB_B1", "", 'O'));
        return new Spec(Family.GATEMATE, "CCGM1A1", "gatemate_evb_jtag", 10_000_000, "IO_SB_A8", "",
                null, null, null, null, "", "", p);
    }

    // ================================================================ constraint files

    /** The constraints for the ports a program uses: Vivado XDC, or the GateMate CCF. */
    static String constraints(Spec s, List<Pin> used, boolean tx, boolean rx, String clkPort) {
        StringBuilder o = new StringBuilder();
        if (s.family() == Family.ARTIX7) {
            o.append("## Constraints for ").append(s.part()).append("\n");
            o.append("set_property CFGBVS VCCO [current_design]\n");
            o.append("set_property CONFIG_VOLTAGE 3.3 [current_design]\n");
            o.append(String.format(Locale.ROOT, "set_property -dict { PACKAGE_PIN %s IOSTANDARD %s } [get_ports { %s }]\n", s.clkLoc(), s.clkIo(), clkPort));
            o.append(String.format(Locale.ROOT, "create_clock -add -name sys_clk_pin -period %.2f -waveform {0 %.2f} [get_ports { %s }]\n",
                    1e9 / s.clkHz(), 5e8 / s.clkHz(), clkPort));
            for (Pin p : used) {
                o.append(String.format(Locale.ROOT, "set_property -dict { PACKAGE_PIN %s IOSTANDARD %s } [get_ports { %s }]\n", p.loc(), p.io(), p.port()));
            }
            if (rx) o.append(String.format(Locale.ROOT, "set_property -dict { PACKAGE_PIN %s IOSTANDARD %s } [get_ports { %s }]\n", s.uartRxLoc(), s.uartIo(), "uart_rx"));
            if (tx) o.append(String.format(Locale.ROOT, "set_property -dict { PACKAGE_PIN %s IOSTANDARD %s } [get_ports { %s }]\n", s.uartTxLoc(), s.uartIo(), "uart_tx"));
        } else {
            o.append("# Constraints for the GateMate ").append(s.part()).append("\n");
            o.append("Pin_in \"").append(clkPort).append("\" Loc = \"").append(s.clkLoc()).append("\" | SCHMITT_TRIGGER=true;\n");
            for (Pin p : used) {
                o.append(p.dir() == 'I' ? "Pin_in \"" : "Pin_out \"").append(p.port()).append("\" Loc = \"").append(p.loc()).append("\";\n");
            }
            if (rx) o.append("Pin_in \"uart_rx\" Loc = \"").append(s.uartRxLoc()).append("\";\n");
            if (tx) o.append("Pin_out \"uart_tx\" Loc = \"").append(s.uartTxLoc()).append("\";\n");
        }
        return o.toString();
    }

    /** The constraints that {@link HdlGenerator} wrote into the header comment of the generated code. */
    static String constraintsFromHeader(String code, Rtl.Hdl hdl) {
        String c = hdl.comment();
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : code.split("\n")) {
            if (!in) {
                in = line.startsWith(c + " Pin constraints");
            } else if (line.startsWith(c + "   ")) {
                out.append(line.substring(c.length() + 3)).append('\n');
            } else {
                break;
            }
        }
        return out.toString();
    }

    /** The constraint file's extension. */
    static String constraintExt(Spec s) {
        return s.family() == Family.ARTIX7 ? ".xdc" : ".ccf";
    }

    // ================================================================ build and upload

    /** Paths of the programs a build uses (Settings). */
    record Tools(boolean openSourceArtix, String vivado, String yosys, String nextpnrGateMate, String gmpack, String loader,
                 String nextpnrXilinx, String chipdbDir, String prjxrayDb, String fasm2frames, String frames2bit) {}

    /** The part name the open-source Artix flow knows: the low-power -1L Arty A7-35 is the same die as the plain -1 part. */
    static String openPart(Spec s) {
        return s.part().equals("xc7a35ticsg324-1L") ? "xc7a35tcsg324-1" : s.part();
    }

    /** What a build step needs: its command line, and files to create first (name, content) in the build folder. */
    record Step(List<String> command, String writeFile, String writeContent) {
        Step(List<String> command) {
            this(command, null, null);
        }
    }

    /**
     * The commands that turn the generated design into {@code top.bit}, run in a folder that holds the HDL file
     * and the constraint file. Artix-7: Vivado in batch mode, or yosys + nextpnr-xilinx + Project X-Ray (openXC7).
     * GateMate: yosys + nextpnr-himbaechel + gmpack.
     */
    static List<Step> buildSteps(Spec s, Rtl.Hdl hdl, Tools t, String hdlFile, String constraintFile) {
        String top = "evia_top";
        List<Step> steps = new ArrayList<>();
        String synth = switch (s.family()) {
            case ARTIX7 -> "synth_xilinx -flatten -abc9 -arch xc7 -top " + top;
            case GATEMATE -> "synth_gatemate -top " + top + " -luttree -nomx8";
        };
        if (s.family() == Family.ARTIX7 && !t.openSourceArtix()) {
            steps.add(new Step(List.of(t.vivado(), "-mode", "batch", "-source", "build.tcl", "-nojournal", "-nolog"),
                    "build.tcl", vivadoScript(s, hdl, top, hdlFile, constraintFile)));
            return steps;
        }
        String read = switch (hdl) {
            case SYSTEMVERILOG -> "read_verilog -sv " + hdlFile;
            case VERILOG -> "read_verilog " + hdlFile;
            case VHDL -> "ghdl --std=08 " + hdlFile + " -e " + top;
        };
        List<String> yosys = new ArrayList<>(List.of(t.yosys(), "-q"));
        if (hdl == Rtl.Hdl.VHDL) yosys.addAll(List.of("-m", "ghdl"));
        yosys.addAll(List.of("-p", read + "; " + synth + "; write_json top.json"));
        steps.add(new Step(yosys));
        if (s.family() == Family.GATEMATE) {
            steps.add(new Step(List.of(t.nextpnrGateMate(), "--device", s.part(), "--json", "top.json", "--vopt", "ccf=" + constraintFile,
                    "--vopt", "out=top_out.txt", "--router", "router2", "--freq", Integer.toString(s.clkHz() / 1_000_000))));
            steps.add(new Step(List.of(t.gmpack(), "top_out.txt", "top.bit")));
        } else {
            String part = openPart(s);
            String db = t.prjxrayDb() + "/artix7";
            steps.add(new Step(List.of(t.nextpnrXilinx(), "--chipdb", t.chipdbDir() + "/" + part + ".bin", "--xdc", constraintFile,
                    "--json", "top.json", "--fasm", "top.fasm", "--quiet")));
            steps.add(new Step(List.of(t.fasm2frames(), "--part", part, "--db-root", db, "top.fasm", "top.frames")));
            steps.add(new Step(List.of(t.frames2bit(), "--part_file", db + "/" + part + "/part.yaml", "--part_name", part,
                    "--frm_file", "top.frames", "--output_file", "top.bit")));
        }
        return steps;
    }

    /** Loads top.bit into the FPGA (volatile: gone at power off). */
    static Step uploadStep(Spec s, Tools t) {
        if (s.family() == Family.ARTIX7 && !t.openSourceArtix()) {
            return new Step(List.of(t.vivado(), "-mode", "batch", "-source", "program.tcl", "-nojournal", "-nolog"),
                    "program.tcl", vivadoProgramScript("top.bit"));
        }
        return new Step(List.of(t.loader(), "--board", s.loader(), "top.bit"));
    }

    /** Vivado batch script: reads the design and constraints, builds top.bit. */
    static String vivadoScript(Spec s, Rtl.Hdl hdl, String top, String src, String xdc) {
        String read = switch (hdl) {
            case SYSTEMVERILOG -> "read_verilog -sv " + src;
            case VERILOG -> "read_verilog " + src;
            case VHDL -> "read_vhdl -vhdl2008 " + src;
        };
        return read + "\nread_xdc " + xdc + "\n"
                + "synth_design -top " + top + " -part " + s.part() + "\n"
                + "opt_design\nplace_design\nroute_design\n"
                + "write_bitstream -force top.bit\n";
    }

    /** Vivado batch script: loads top.bit through the Hardware Manager. */
    static String vivadoProgramScript(String bit) {
        return "open_hw_manager\nconnect_hw_server\nopen_hw_target\n"
                + "set dev [lindex [get_hw_devices] 0]\ncurrent_hw_device $dev\n"
                + "set_property PROGRAM.FILE " + bit + " $dev\nprogram_hw_devices $dev\nclose_hw_manager\n";
    }
}
