package evia.paradigma;

import java.awt.Color;
import java.awt.Font;

import java.nio.file.*;
import java.util.*;

public class AppChecks {
    static int fails = 0;
    static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fails++;
    }
    static Library newLib() {
        Library lib = new Library();
        lib.main = new FlowCanvas(lib, null);
        return lib;
    }
    static String run(Library lib) {
        StringBuilder sb = new StringBuilder();
        Block start = Interpreter.prepare(lib);
        new Interpreter().run(start, new Interpreter.Host() {
            public void step(Block b) {}
            public void print(String t) { sb.append(t).append('\n'); }
            public String input(String v) { return "5"; }
            public boolean cancelled() { return false; }
        });
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("evia-checks");
        // ---------- bundled example extraction + module load
        Path ex = MainFrame.extractExample("classes.evia");
        Library lib = newLib();
        ProgramIO.load(lib, ex);
        String expected = run(lib);
        check(expected.startsWith("count = 106\ncount = 103\ncount = 100\na is big"), "classes example runs from bundled resources");
        check(lib.byName("Counter").module != null && lib.byName("Account").module == null, "Counter comes from module, Account from program");
        for (String[] e : MainFrame.EXAMPLES) {
            Library l = newLib();
            ProgramIO.load(l, MainFrame.extractExample(e[0]));
            check(l.main.hasStart(), "bundled example " + e[0] + " loads");
        }

        // ---------- source map
        CodeGenerator.Result r = CodeGenerator.generateWithMap(lib);
        String[] lines = r.code().split("\n", -1);
        int withCode = 0, total = 0;
        for (FlowCanvas c : lib.canvases()) for (Block b : c.getBlocks()) {
            total++;
            List<int[]> rg = r.sourceMap().get(b);
            if (rg != null && !rg.isEmpty()) withCode++;
        }
        check(withCode == total, "every block has C++ lines (" + withCode + "/" + total + ")");
        Block setCount = null;
        for (Block b : lib.byName("Counter").method("add").body.getBlocks()) if (b.type == BlockType.SET) setCount = b;
        StringBuilder txt = new StringBuilder();
        for (int[] x : r.sourceMap().get(setCount)) for (int i = x[0]; i < x[1]; i++) txt.append(lines[i]).append('\n');
        check(txt.toString().contains("f_count =") && txt.toString().contains("this.count = this.count"), "Set block maps to its assignment:\n" + txt);
        Block forB = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.FOR) forB = b;
        txt.setLength(0);
        for (int[] x : r.sourceMap().get(forB)) for (int i = x[0]; i < x[1]; i++) txt.append(lines[i]).append('\n');
        check(txt.toString().contains("for (") && !txt.toString().contains("deposit"), "For block maps to the loop header, not its body:\n" + txt);
        List<int[]> mr = r.sourceMap().get(lib.byName("Counter").method("isBig"));
        check(mr != null && lines[mr.get(0)[0]].contains("Counter::m_isBig"), "method maps to its definition: " + (mr == null ? "-" : lines[mr.get(0)[0]]));
        check(r.sourceMap().get(lib.byName("Account")) != null, "class has ranges");

        // ---------- other languages: View source line mapping, targets, compile commands
        for (String key : new String[]{"c11/structured/safe", "c99/goto/safe", "c++98/structured/safe", "c++14/goto/safe"}) {
            Target tg = Target.parse(key);
            CodeGenerator.Result rc = CodeGenerator.generateWithMap(lib, tg);
            String[] cl = rc.code().split("\n", -1);
            StringBuilder st = new StringBuilder();
            for (int[] x : rc.sourceMap().get(setCount)) for (int i = x[0]; i < x[1]; i++) st.append(cl[i]).append('\n');
            boolean okLines = st.toString().contains("this.count = this.count") && st.toString().contains(tg.isC() ? "rt_set(&self->f_count" : "f_count =");
            check(okLines, key + ": Set block maps to its assignment");
            int covered = 0;
            for (FlowCanvas c : lib.canvases()) for (Block b : c.getBlocks()) if (rc.sourceMap().get(b) != null) covered++;
            check(covered == total, key + ": every block has lines (" + covered + "/" + total + ")");
        }
        check(Target.parse("c17/goto/fast").equals(new Target(Target.Lang.C17, Target.Flow.GOTO, Target.Values.FAST))
                && Target.parse(Target.parse("c++11/structured/fast").key()).key().equals("c++11/structured/fast")
                && Target.parse("nonsense").equals(Target.DEFAULT), "target keys round-trip");
        check(Settings.commandFor("clang++ -std=c++20 -O2 -o {exe} {src}", Target.parse("c11"))
                .equals("clang -std=c11 -O2 -o {exe} {src} -lm"), "C compile command from the C++ one");
        check(Settings.commandFor("\"C:/Program Files/llvm/bin/clang++.exe\" -O2 -static -o {exe} {src}", Target.parse("c++14"))
                .equals("\"C:/Program Files/llvm/bin/clang++.exe\" -std=c++14 -O2 -static -o {exe} {src}"), "-std added, quoted path kept");
        try {
            CodeGenerator.generate(lib, Target.parse("c11/structured/fast"));
            check(false, "fast values refuse text in fields");
        } catch (FlowException e) {
            check(e.getMessage().contains("fast values only hold numbers"), "fast values refuse text in fields: " + e.getMessage());
        }

        // ---------- microcontrollers: languages, boards, board blocks
        check(Target.parse("arduino").equals(new Target(Target.Lang.ARDUINO, Target.Flow.STRUCTURED, Target.Values.FAST))
                && Target.parse("avr/goto/safe").values() == Target.Values.FAST
                && Target.parse(Target.parse("avr/goto").key()).lang() == Target.Lang.AVR, "arduino / avr targets (always fast values)");
        check(Board.parse("esp32-s3") == Board.ESP32_S3 && Board.parse("arduino:avr:uno") == Board.UNO
                && Board.forLang(Board.ESP32_C6, Target.Lang.AVR) == Board.UNO && Board.forLang(Board.ESP32_C6, Target.Lang.ARDUINO) == Board.ESP32_C6
                && java.util.Arrays.stream(Board.values()).filter(Board::isAvr).count() == 6, "boards");
        Library board = newLib();
        ProgramIO.load(board, Path.of("examples/boards/led_class.evia"));
        String ino = CodeGenerator.generate(board, Target.parse("arduino"));
        check(ino.contains("void setup()") && ino.contains("pinMode((int) (self->f_pin), OUTPUT);")
                && ino.contains("rt_print_line(RT_TEXT(\"done\"));") && !ino.contains("printf"), "Arduino sketch");
        String avr = CodeGenerator.generate(board, Target.parse("avr"));
        check(avr.contains("int main(void)") && avr.contains("static void pinMode(uint8_t pin, uint8_t mode)")
                && avr.contains("ISR(TIMER0_OVF_vect)") && !avr.contains("analogRead"), "bare-metal AVR: only the pieces it uses");
        try {
            CodeGenerator.generate(board, Target.parse("c11"));
            check(false, "desktop C refuses board blocks");
        } catch (FlowException e) {
            check(e.getMessage().contains("runs on a microcontroller"), "desktop C refuses board blocks: " + e.getMessage());
        }
        List<String> events = new ArrayList<>(), printed = new ArrayList<>();
        new Interpreter().run(Interpreter.prepare(board), new Interpreter.Host() {
            @Override public void step(Block b) {}
            @Override public void print(String text) { printed.add(text); }
            @Override public String input(String var) { return null; }
            @Override public boolean cancelled() { return false; }
            @Override public void board(String e) { events.add(e); }
        });
        check(printed.get(0).equals("Led class on pin 13") && printed.get(printed.size() - 1).equals("done")
                && events.contains("[0 ms] pin 13 HIGH") && events.contains("[250 ms] pin 13 LOW"), "simulated board: " + events);
        try {
            Interpreter.compile(new FlowCanvas(newLib(), null).addBlock(BlockType.SET, 0, 0), "HIGH = 2");
            check(false, "board constants can't be assigned");
        } catch (FlowException e) {
            check(e.getMessage().contains("board constant"), "board constants can't be assigned");
        }

        // ---------- tutorial PDFs
        for (String[] t : HelpWindow.TUTORIALS) {
            try (java.io.InputStream in = HelpWindow.class.getResourceAsStream("help/EVIA-Paradigma-Tutorial." + t[0] + ".pdf")) {
                byte[] head = in == null ? new byte[0] : in.readNBytes(5);
                check(new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).equals("%PDF-"), "tutorial PDF " + t[0] + " is bundled");
            }
        }
        check(HelpWindow.TUTORIALS.length == 7, "tutorial menu lists seven languages");

        // ---------- UART / SPI / I2C blocks and PIC32
        check(Target.parse("pic32").lang() == Target.Lang.PIC32 && Target.parse("pic32/goto").values() == Target.Values.FAST
                && Target.parse(Target.parse("pic32").key()).lang() == Target.Lang.PIC32, "pic32 target (always fast values)");
        check(java.util.Arrays.stream(Board.values()).filter(Board::isPic32).count() == 11
                && Board.forLang(Board.UNO, Target.Lang.PIC32).isPic32() && !Board.UNO.supports(Target.Lang.PIC32)
                && Board.parse("pic32mx250f128b").isPic32(), "PIC32 boards");
        check(Board.parse("chipkit-pro-mx7") == Board.CHIPKIT_PRO_MX7 && "RG12".equals(Board.CHIPKIT_PRO_MX7.led)
                && "32MX795F512L".equals(Board.CHIPKIT_PRO_MX7.pic), "chipKIT Pro MX7: MX795F512L, LD1 on RG12");
        check(Board.parse("disco-f429zi") == Board.DISCO_F429ZI && Board.DISCO_F429ZI.stm32.flashK() == 2048
                && Board.DISCO_F429ZI.stm32.ramK() == 192 && Board.DISCO_F429ZI.stm32.usart() == 1
                && "PG13".equals(Board.DISCO_F429ZI.led) && Board.DISCO_F429ZI.supports(Target.Lang.ARDUINO),
                "32F429IDISCOVERY: 2 MB, 192 KB, USART1, LD3 on PG13, Arduino too");
        for (String name : new String[] {"uart_echo", "spi_loopback", "i2c_memory"}) {
            Library bus = newLib();
            ProgramIO.load(bus, Path.of("examples/boards/" + name + ".evia"));
            List<String> out = new ArrayList<>();
            new Interpreter().run(Interpreter.prepare(bus), new Interpreter.Host() {
                @Override public void step(Block b) {}
                @Override public void print(String text) { out.add(text); }
                @Override public String input(String var) { return null; }
                @Override public boolean cancelled() { return false; }
                @Override public void board(String e) {}
            });
            String last = out.get(out.size() - 1);
            check(switch (name) {
                case "uart_echo" -> last.equals("received 72, 105, 10");
                case "spi_loopback" -> last.equals("sent 120, received 120");
                default -> last.equals("device 81: -1");
            }, name + " runs in the simulator: " + out);
            for (String lang : new String[] {"arduino", "avr", "pic32"}) {
                String code = CodeGenerator.generate(bus, Target.parse(lang));
                String call = switch (name) {
                    case "uart_echo" -> "uartOpen(";
                    case "spi_loopback" -> "spiTransfer(";
                    default -> "i2cRead(";
                };
                check(code.contains(call) && code.contains("int main") == !lang.equals("arduino"), name + " generates " + lang + " code");
            }
        }
        {
            String pic = CodeGenerator.generate(board, Target.parse("pic32"));
            check(pic.contains("#pragma config") && pic.contains("int main(void)") && pic.contains("-mprocessor=32MX250F128B")
                    && !pic.contains("i2cRead") && !pic.contains("analogRead"), "PIC32 C: only the pieces it uses");
        }

        // ---------- FPGA: SystemVerilog, Verilog, VHDL for Artix-7 and GateMate
        check(Target.parse("sv").lang() == Target.Lang.SYSTEMVERILOG && Target.parse("verilog/goto").values() == Target.Values.FAST
                && Target.parse(Target.parse("vhdl").key()).lang() == Target.Lang.VHDL
                && Target.Lang.VHDL.fpga() && Target.Lang.VHDL.embedded() && !Target.Lang.VHDL.isC() && Target.Lang.SYSTEMVERILOG.ext().equals(".sv"),
                "FPGA languages (always fast values)");
        check(java.util.Arrays.stream(Board.values()).filter(Board::isFpga).count() == 6 && !Board.UNO.supports(Target.Lang.VHDL)
                && Board.NEXYS_A7_100T.supports(Target.Lang.SYSTEMVERILOG) && !Board.NEXYS_A7_100T.supports(Target.Lang.ARDUINO)
                && Board.forLang(Board.UNO, Target.Lang.VERILOG) == Board.NEXYS_A7_100T && Board.parse("olimex-gatemate").isFpga(),
                "FPGA boards");
        for (Board b : Board.values()) {
            if (!b.isFpga()) continue;
            Fpga.Spec spec = b.fpga();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            java.util.Set<String> locs = new java.util.HashSet<>(), ports = new java.util.HashSet<>();
            boolean unique = spec.pin(0) != null;
            for (Fpga.Pin p : spec.pins()) unique &= seen.add(p.index()) && locs.add(p.loc()) && ports.add(p.port()) && !p.loc().equals(spec.clkLoc());
            check(unique, b.display + ": pins are unique, LED0 exists, none shares the clock pin");
        }
        {
            Library fpga = newLib();
            ProgramIO.load(fpga, Path.of("examples/fpga/uart_hello.evia"));
            Settings.useBoard(Board.NEXYS_A7_100T);
            String sv = CodeGenerator.generate(fpga, Target.parse("sv")), v = CodeGenerator.generate(fpga, Target.parse("verilog")),
                    vhd = CodeGenerator.generate(fpga, Target.parse("vhdl"));
            check(sv.contains("module evia_top") && sv.contains("always_ff") && v.contains("module evia_top") && !v.contains("always_ff")
                    && vhd.contains("entity evia_top") && vhd.contains("rising_edge(clk)"), "the three HDLs are written");
            check(sv.contains("PACKAGE_PIN E3") && sv.contains("PACKAGE_PIN C4") && sv.contains("PACKAGE_PIN D4"), "Nexys A7 constraints (clock, serial port) are in the header");
            check(Fpga.constraintsFromHeader(sv, Rtl.Hdl.SYSTEMVERILOG).contains("create_clock")
                    && Fpga.constraintsFromHeader(vhd, Rtl.Hdl.VHDL).contains("[get_ports { uart_tx }]"), "constraints are read back from the header comment");
            Settings.useBoard(Board.OLIMEX_GATEMATE);
            String gm = CodeGenerator.generate(fpga, Target.parse("sv"));
            check(gm.contains("Pin_in \"clk\" Loc = \"IO_SB_A8\"") && gm.contains("Pin_out \"uart_tx\"") && !gm.contains("PACKAGE_PIN"), "GateMate constraints (CCF)");
            Settings.useBoard(Board.CCGM1A1_EVB);
            try {
                CodeGenerator.generate(fpga, Target.parse("sv"));
                check(false, "a board without a serial port refuses Print and UART");
            } catch (FlowException e) {
                check(e.getMessage().contains("no serial port"), "a board without a serial port refuses Print and UART: " + e.getMessage());
            }
            Settings.useBoard(Board.NEXYS_A7_100T);
            for (String[] bad : new String[][] {{"SET x = 1.5", "whole numbers"}, {"SET x = \"hello\"", "text"}, {"INPUT n", "Input"},
                    {"DIGITAL_WRITE 99, HIGH", "does not exist"}, {"DIGITAL_WRITE SW0, HIGH", "input"}, {"DIGITAL_READ x = LED0", "output"},
                    {"ANALOG_READ x = A0", "Analog Read"}, {"SPI_OPEN 1000", "SPI Open"}, {"DIGITAL_WRITE n, HIGH", "constant"}}) {
                Library one = newLib();
                String[] parts = bad[0].split(" ", 2);
                Block st = one.main.addBlock(BlockType.START, 0, 0), b1 = one.main.addBlock(BlockType.valueOf(parts[0]), 0, 90);
                b1.setCode(parts[1]);
                one.main.connect(st, 0, b1);
                try {
                    CodeGenerator.generate(one, Target.parse("sv"));
                    check(false, "FPGA refuses: " + bad[0]);
                } catch (FlowException e) {
                    check(e.getMessage().toLowerCase().contains(bad[1].toLowerCase()), "FPGA refuses '" + bad[0] + "' (" + e.getMessage() + ")");
                }
            }
            Settings.useBoard(null);
            check(Fpga.buildSteps(Board.NEXYS_A7_100T.fpga(), Rtl.Hdl.VHDL, Settings.fpgaTools(), "t.vhd", "t.xdc").get(0).command().get(0).equals("vivado")
                    && Fpga.buildSteps(Board.OLIMEX_GATEMATE.fpga(), Rtl.Hdl.SYSTEMVERILOG, Settings.fpgaTools(), "t.sv", "t.ccf").size() == 3
                    && Fpga.uploadStep(Board.OLIMEX_GATEMATE.fpga(), Settings.fpgaTools()).command().contains("olimex_gatemateevb"),
                    "FPGA build and upload commands");
        }

        // ---------- MQTT: the blocks, Run's broker, the generated code and the refusals
        {
            Library mq = newLib();
            ProgramIO.load(mq, Path.of("examples/boards/mqtt_sensor.evia"));
            Settings.usePic32Net(false);   // the WiFi module, whatever is saved in the settings of this computer
            String out = run(mq);
            check(out.contains("command 10") && out.contains("command 50") && out.trim().endsWith("done"),
                    "Run answers MQTT Read from its own broker");
            Settings.useBoard(Board.PIC32MX250F128B);
            String c = CodeGenerator.generate(mq, Target.parse("pic32"));
            check(c.contains("mqttConnect(\"home-wifi\", \"secret\", \"test.mosquitto.org\", 1883)")
                            && c.contains("mqttPublishNum(\"evia/temperature\"") && c.contains("mqttSubscribe(\"evia/command\")")
                            && c.contains("mqttHas(\"evia/command\")") && c.contains("mqttRead(\"evia/command\")"),
                    "PIC32 C calls the MQTT functions");
            check(c.contains("AT+CIPSTART") && c.contains("U2BRG") && c.contains("MQTT 3.1.1"),
                    "PIC32 C talks to a WiFi module on UART2");
            Settings.useBoard(Board.ESP32);
            String esp = CodeGenerator.generate(mq, Target.parse("arduino"));
            check(esp.contains("#include <WiFi.h>") && esp.contains("WiFi.begin(network, password)") && esp.contains("mqttConnect("),
                    "the ESP32 sketch joins the network itself");
            Settings.useBoard(Board.UNO);
            try {
                CodeGenerator.generate(mq, Target.parse("arduino"));
                check(false, "an AVR board refuses MQTT");
            } catch (FlowException e) {
                check(e.getMessage().contains("needs a network"), "an AVR board refuses MQTT (" + e.getMessage() + ")");
            }
            Settings.useBoard(null);
            String[][] bad = {
                    {"MQTT_CONNECT", "broker, 1883, \"c\", 1", "fixed text"},
                    {"MQTT_PUBLISH", "\"t\"", "topic"},
                    {"MQTT_READ", "v = topic", "fixed text"},
            };
            for (String[] b : bad) {
                Library one = newLib();
                Block start = one.main.addBlock(BlockType.START, 0, 0);
                Block blk = one.main.addBlock(BlockType.valueOf(b[0]), 0, 90);
                blk.setCode(b[1]);
                one.main.connect(start, 0, blk);
                Settings.useBoard(Board.PIC32MX250F128B);
                try {
                    CodeGenerator.generate(one, Target.parse("pic32"));
                    check(false, "MQTT refuses " + b[0] + " " + b[1]);
                } catch (FlowException e) {
                    check(e.getMessage().toLowerCase().contains(b[2]), "MQTT refuses '" + b[1] + "' (" + e.getMessage() + ")");
                }
                Settings.useBoard(null);
            }
        }

        // ---------- the PIC32 Ethernet Starter Kit II example
        {
            Library esk = newLib();
            ProgramIO.load(esk, Path.of("examples/boards/mqtt_esk2.evia"));
            String out = run(esk);
            check(out.contains("LED1 = 1") && out.trim().endsWith("done"), "the ESK II example follows the button through the broker");
            Settings.useBoard(Board.PIC32_ESK2);
            String c = CodeGenerator.generate(esk, Target.parse("pic32"));
            check(c.contains("#define LED_BUILTIN RD0") && c.contains("#define RD6") && c.contains("32MX795F512L")
                            && c.contains("mqttPublishNum(\"evia/esk2/led\""),
                    "the ESK II board has LED1 on RD0 and builds for the MX795F512L");
            Settings.useBoard(null);
        }

        // ---------- MQTT over the chip's own Ethernet port, as a Harmony application
        {
            Library esk = newLib();
            ProgramIO.load(esk, Path.of("examples/boards/mqtt_esk2.evia"));
            Settings.useBoard(Board.PIC32_ESK2);
            Settings.usePic32Net(true);
            String c = CodeGenerator.generate(esk, Target.parse("pic32"));
            check(c.contains("void app_run(void)") && !c.contains("int main(void)"),
                    "the Harmony application has app_run and no main of its own");
            check(!c.contains("#pragma config") && c.contains("#include \"definitions.h\""),
                    "the device configuration is left to the Harmony project");
            check(c.contains("TCPIP_TCP_ClientOpen") && c.contains("TCPIP_DNS_Resolve") && c.contains("SYS_Tasks()")
                            && !c.contains("AT+CIPSTART"),
                    "the network under MQTT is Harmony's TCP/IP, not a WiFi module");
            Settings.useBoard(Board.PIC32MX250F128B);
            try {
                CodeGenerator.generate(esk, Target.parse("pic32"));
                check(false, "a chip without Ethernet refuses the Ethernet setting");
            } catch (FlowException e) {
                check(e.getMessage().contains("no Ethernet port"),
                        "a chip without Ethernet refuses the Ethernet setting (" + e.getMessage() + ")");
            }
            Settings.usePic32Net(false);
            Settings.useBoard(Board.PIC32_ESK2);
            String at = CodeGenerator.generate(esk, Target.parse("pic32"));
            check(at.contains("AT+CIPSTART") && at.contains("int main(void)") && at.contains("#pragma config"),
                    "the same diagram still builds as a self-contained program for the WiFi module");
            Settings.useBoard(null);
        }

        // ---------- the toolchains EVIA Paradigma can fetch
        {
            Path rel = tmp.resolve("releases");
            Files.createDirectories(rel.resolve("bin"));
            Files.writeString(rel.resolve("bin/arduino-cli"), "#!/bin/sh\necho 0.99.0\n");
            Files.writeString(rel.resolve("bin/arduino-cli.exe"), "stub\n");
            Process tar = new ProcessBuilder("tar", "-czf", tmp.resolve("arduino-cli_0.99.0_Linux_64bit.tar.gz").toString(),
                    "-C", rel.toString(), "bin").start();
            check(tar.waitFor() == 0, "test archive packed");
            Path feed = tmp.resolve("feed.json");
            Files.writeString(feed, "{\"assets\":[{\"browser_download_url\":\"" + tmp.resolve("arduino-cli_0.99.0_Linux_64bit.tar.gz").toUri()
                    + "\"},{\"browser_download_url\":\"" + tmp.resolve("arduino-cli_0.99.0_Windows_64bit.zip").toUri() + "\"}]}");
            System.setProperty("evia.toolchain.releases.arduino-cli", feed.toUri().toString());
            String url = Toolchains.latest(Toolchains.Tool.ARDUINO_CLI);
            check(url.endsWith(ExternalEditor.WINDOWS ? "Windows_64bit.zip" : "Linux_64bit.tar.gz"), "the file for this computer is chosen");
            Path archive = Toolchains.download(url, (bytesDone, bytesTotal) -> { }, () -> false);
            Path into = tmp.resolve("unpacked");
            Files.createDirectories(into);
            Toolchains.unpack(archive, into);
            check(Files.isRegularFile(into.resolve("bin/arduino-cli")), "the archive is unpacked");
            check(Toolchains.Tool.XC32.page.contains("microchip.com") && !Toolchains.Tool.XC32.downloadable(),
                    "XC32 is fetched from Microchip, not from here");
            check(Toolchains.home(Toolchains.Tool.OSS_CAD).toString().contains("oss-cad-suite"), "each tool has its own folder");
        }

        // ---------- compiler family (Clang or GCC)
        check(Settings.family("clang++ -O2") == ToolchainInstaller.Kind.CLANG && Settings.family("\"C:/w/mingw64/bin/g++.exe\" -O2") == ToolchainInstaller.Kind.GCC,
                "compiler family from the command");
        check(Settings.withFamily("clang++ -std=c++20 -O2 -o {exe} {src}", ToolchainInstaller.Kind.GCC).equals("g++ -std=c++20 -O2 -o {exe} {src}")
                && Settings.commandFor("g++ -O2 -o {exe} {src}", Target.parse("c17")).equals("gcc -std=c17 -O2 -o {exe} {src} -lm"),
                "switch to GCC; gcc for C");

        // ---------- export / import modules round trip
        Path prog = tmp.resolve("p/prog.evia");
        Files.createDirectories(prog.getParent());
        ProgramIO.exportModule(lib, List.of(lib.byName("Account")), tmp.resolve("p/modules/account.eviam"));
        ProgramIO.save(lib, prog);
        String saved = Files.readString(prog);
        check(saved.contains("U|modules%2Faccount.eviam") && saved.contains("U|"), "program refers to modules relatively");
        check(saved.contains("counter.eviam"), "a module from another folder stays where it is and is referenced");
        Library l2 = newLib();
        ProgramIO.load(l2, prog);
        check(run(l2).equals(expected), "reloaded program with two modules gives the same output");
        Library l3 = newLib();
        MainFrame.starterProgram(l3.main, "X");
        List<BlockDef> got = ProgramIO.importModule(l3, tmp.resolve("p/modules/account.eviam"));
        check(got.size() == 1 && got.get(0).name.equals("Account") && got.get(0).methods.size() == 2 && got.get(0).fields.size() == 3,
                "import module adds class with methods and fields");
        try {
            ProgramIO.importModule(l3, tmp.resolve("p/modules/account.eviam"));
            check(false, "importing the same class twice is refused");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("already exists"), "importing the same class twice is refused");
        }

        // ---------- project + git
        Project p = Project.create(tmp, "Demo Project");
        check(Files.isDirectory(p.modulesDir()) && Files.exists(p.dir.resolve(".gitignore")) && Files.exists(p.file), "project skeleton");
        Library pl = newLib();
        MainFrame.starterProgram(pl.main, "Demo \"Project\"");
        ProgramIO.save(pl, p.mainProgram());
        check(run(pl).equals("Hello from Demo \"Project\"!\n"), "starter program runs");
        check(Project.ofProgram(p.mainProgram()) != null && Project.inFolder(p.dir).name().equals("Demo Project"), "project found from folder and program");
        check(GitService.available(), "git found: " + GitService.git());
        GitService.init(p.dir);
        check(GitService.isRepo(p.dir) && GitService.branch(p.dir).equals("main"), "git init on main");
        GitService.setLocalConfig(p.dir, "user.name", "Test");
        GitService.setLocalConfig(p.dir, "user.email", "test@example.com");
        String st = GitService.status(p.dir);
        check(st.contains("main.evia") && !st.contains("build"), "status lists files, build/ ignored:\n" + st);
        GitService.commitAll(p.dir, "Create project\n\nwith ünïcode");
        check(GitService.status(p.dir).isBlank(), "clean after commit");
        check(GitService.log(p.dir).contains("Create project"), "history shows commit");
        Path bare = tmp.resolve("remote.git");
        new ProcessBuilder("git", "init", "--bare", "-b", "main", bare.toString()).start().waitFor();
        GitService.setRemote(p.dir, bare.toString());
        check(GitService.remoteUrl(p.dir).equals(bare.toString()), "remote set");
        GitService.push(p.dir);
        check(GitService.hasUpstream(p.dir), "push sets upstream");
        Path clone = tmp.resolve("clone");
        new ProcessBuilder("git", "clone", bare.toString(), clone.toString()).start().waitFor();
        Files.writeString(clone.resolve("README.md"), "changed\n");
        new ProcessBuilder("git", "-c", "user.name=O", "-c", "user.email=o@x", "commit", "-am", "other").directory(clone.toFile()).start().waitFor();
        new ProcessBuilder("git", "push").directory(clone.toFile()).start().waitFor();
        GitService.pull(p.dir);
        check(Files.readString(p.dir.resolve("README.md")).equals("changed\n"), "pull brings remote change");
        GitService.setRemote(p.dir, "https://github.com/x/y.git");
        check(GitService.remoteUrl(p.dir).equals("https://github.com/x/y.git"), "remote replaced");

        // ---------- url helpers + version compare
        check("https://github.com/sbrutyan01/EVIA-Paradigma".equals(GitService.webUrl("git@github.com:sbrutyan01/EVIA-Paradigma.git")), "ssh -> web");
        check("https://github.com/a/b".equals(GitService.webUrl("https://github.com/a/b.git")), "https -> web");
        check(GitService.normalizeRemote("a/b").equals("https://github.com/a/b.git"), "owner/repo -> clone url");
        check(GitService.normalizeRemote("https://github.com/a/b").equals("https://github.com/a/b.git"), "page -> clone url");
        check(GitService.webUrl("https://gitlab.com/a/b.git") == null, "non-GitHub has no web url");
        check(HelpWindow.compare("0.10.0", "0.9.1") > 0 && HelpWindow.compare("0.2.0", "0.2.0") == 0 && HelpWindow.compare("0.1.2", "0.2") < 0, "version compare");

        // ---------- snapshot detects change
        String s1 = ProgramIO.snapshot(lib);
        lib.byName("Account").fields.add(new BlockDef.Field("z", "1"));
        check(!ProgramIO.snapshot(lib).equals(s1), "snapshot changes when a field is added");

        // ---------- guide in three languages, fonts, theme
        String[] anchors = {"start", "blocks", "expressions", "classes", "modules", "projects", "git", "cpp", "boards", "keys", "vim"};
        String[] files = {"guide.html", "guide.ru.html", "guide.hy.html"};
        String en = null;
        for (String f : files) {
            String html;
            try (java.io.InputStream in = HelpWindow.class.getResourceAsStream("help/" + f)) {
                html = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            check(!html.isEmpty(), f + " is bundled");
            if (en == null) en = html;
            for (String an : anchors) check(html.contains("<a name=\"" + an + "\">"), f + " has the topic " + an);
            check(count(html, "<table") == count(en, "<table") && count(html, "<h2") == count(en, "<h2"), f + " has the same tables and sections as the English guide");
            check(count(html, "<code>") == count(en, "<code>"), f + " keeps every code sample");
        }
        check(Fonts.ui(Font.PLAIN, 13).canDisplay('Ա') && Fonts.ui(Font.BOLD, 13).canDisplay('\u0587'), "interface font has the Armenian letters");
        check(Fonts.ui(Font.PLAIN, 13).canDisplay('Ж') && Fonts.mono(Font.PLAIN, 13).canDisplay('Ж'), "fonts have the Russian letters");
        check(Fonts.mono(Font.PLAIN, 13).getFamily().equals("JetBrains Mono"), "code font is JetBrains Mono");
        Theme.apply(Theme.Mode.DARK);
        Color darkBg = Theme.canvas(false), darkKeyword = Theme.keyword();
        check(Theme.dark(), "dark theme on");
        Theme.apply(Theme.Mode.LIGHT);
        check(!Theme.dark() && !Theme.canvas(false).equals(darkBg) && !Theme.keyword().equals(darkKeyword), "light theme has its own colors");
        check(Theme.canvas(false).getRed() > 200 && darkBg.getRed() < 80, "canvas is light in the light theme and dark in the dark theme");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
