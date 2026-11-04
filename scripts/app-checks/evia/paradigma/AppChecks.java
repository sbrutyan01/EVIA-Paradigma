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
