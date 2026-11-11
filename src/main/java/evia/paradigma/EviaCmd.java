package evia.paradigma;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code evia} command: EVIA Paradigma's code generators with subcommands, for terminals, scripts and CI.
 * <pre>
 * evia gen app.evia -l python               write Python to standard output
 * evia gen app.evia -l c11,python,java -d out/   one file per language in out/
 * evia run app.evia                          run with the interpreter
 * evia watch app.evia -l python -o app.py    write it again whenever app.evia is saved
 * evia check app.evia                        does it generate for every language?
 * evia langs | boards [LANG] | new FILE | version
 * </pre>
 * It shares the generators, loading and file writing with {@link Cli} (the older {@code --gen} style options,
 * which still work: {@code evia --gen ...}).
 */
final class EviaCmd {
    private EviaCmd() {}

    /** The subcommands: App starts this class instead of the editor when the first argument is one of them. */
    static final Set<String> COMMANDS = Set.of("gen", "run", "watch", "check", "langs", "boards", "new", "native",
            "android", "packages", "install", "remove", "version", "help");

    private static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    static int run(String[] args) {
        if (args.length == 0) return help(null);
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (cmd.startsWith("-") && !cmd.equals("-h") && !cmd.equals("--help") && !cmd.equals("--version")) return Cli.run(args);
        try {
            return switch (cmd) {
                case "gen" -> gen(rest);
                case "run" -> {
                    need(rest, 1, "evia run FILE");
                    yield Cli.runProgram(Cli.load(rest[0]), OUT, ERR);
                }
                case "watch" -> watch(rest);
                case "check" -> check(rest);
                case "langs" -> langs();
                case "boards" -> boards(rest);
                case "new" -> {
                    need(rest, 1, "evia new FILE");
                    Path file = Path.of(rest[0].endsWith(".evia") ? rest[0] : rest[0] + ".evia");
                    if (Files.exists(file)) throw new IllegalArgumentException(file + " already exists");
                    yield Cli.run(new String[] {"--example", file.toString()}) == 0 ? ok("wrote " + file) : 1;
                }
                // the rest are the older options under a new name
                case "native", "android", "packages", "install", "remove" -> Cli.run(prepend("--" + cmd, rest));
                case "version", "--version" -> Cli.run(new String[] {"--version"});
                case "help", "-h", "--help" -> help(rest.length > 0 ? rest[0] : null);
                default -> {
                    ERR.println("evia: unknown command " + cmd + " (see evia help)");
                    yield 2;
                }
            };
        } catch (FlowException e) {
            OUT.flush();
            ERR.println(">> error: " + e.getMessage());
            return 1;
        } catch (java.nio.file.NoSuchFileException e) {
            ERR.println("evia: no such file: " + e.getFile());
            return 2;
        } catch (IOException | IllegalArgumentException e) {
            ERR.println("evia: " + e.getMessage());
            return 2;
        }
    }

    // ---------------------------------------------------------------- gen

    /** What gen and watch were asked for. */
    private record Job(String file, List<Target> targets, Path output, Path dir, boolean quiet) {}

    private static Job parseJob(String[] a, String usage) {
        String file = null, out = null, dir = null;
        String langs = "c++20";
        Target base = Target.DEFAULT;
        boolean quiet = false;
        Board board = null;
        for (int i = 0; i < a.length; i++) {
            String opt = a[i];
            if (!opt.startsWith("-")) {
                if (file != null) throw new IllegalArgumentException("one program at a time (" + file + ", " + opt + ")");
                file = opt;
                continue;
            }
            if (opt.equals("-q") || opt.equals("--quiet")) {
                quiet = true;
                continue;
            }
            if (i + 1 >= a.length) throw new IllegalArgumentException(opt + " needs a value (see evia help gen)");
            String v = a[++i];
            switch (opt) {
                case "-l", "--lang" -> langs = v;
                case "-o", "--out" -> out = v;
                case "-d", "--dir" -> dir = v;
                case "-b", "--board" -> {
                    board = board(v);
                    Settings.useBoard(board);
                }
                case "--flow" -> base = base.withFlow(switch (v.toLowerCase(Locale.ROOT)) {
                    case "structured" -> Target.Flow.STRUCTURED;
                    case "goto" -> Target.Flow.GOTO;
                    default -> throw new IllegalArgumentException("--flow is structured or goto");
                });
                case "--values" -> base = base.withValues(switch (v.toLowerCase(Locale.ROOT)) {
                    case "safe" -> Target.Values.SAFE;
                    case "fast" -> Target.Values.FAST;
                    default -> throw new IllegalArgumentException("--values is safe or fast");
                });
                case "--net" -> Settings.usePic32Net(switch (v.toLowerCase(Locale.ROOT)) {
                    case "uart", "at", "wifi" -> false;
                    case "ethernet", "eth", "harmony" -> true;
                    default -> throw new IllegalArgumentException("--net is uart or ethernet");
                });
                default -> throw new IllegalArgumentException("unknown option " + opt + " (see evia help gen)");
            }
        }
        if (file == null) throw new IllegalArgumentException("missing program: " + usage);
        List<Target> targets = new ArrayList<>();
        for (String l : langs.split(",")) {
            if (l.isBlank()) continue;
            if (l.trim().equalsIgnoreCase("all")) {
                for (Target.Lang x : Target.Lang.values()) if (!x.embedded() && x != Target.Lang.ANDROID) targets.add(base.withLang(x));
                continue;
            }
            Target.Lang x = Target.langOf(l);
            if (x == null) throw new IllegalArgumentException("unknown language " + l.trim() + " (see evia langs)");
            targets.add(base.withLang(x));
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("no language given");
        for (Target t : targets) {
            if (board != null && t.embedded() && !board.supports(t.lang())) {
                throw new IllegalArgumentException(board.key() + " is not a board for " + t.lang().key() + " (see evia boards " + t.lang().key() + ")");
            }
        }
        if (out != null && dir != null) throw new IllegalArgumentException("-o and -d don't go together");
        if (out != null && targets.size() > 1) throw new IllegalArgumentException("more than one language: use -d DIR instead of -o FILE");
        // several languages and no -d: next to each other in the current folder
        Path d = dir != null ? Path.of(dir) : out == null && targets.size() > 1 ? Path.of(".") : null;
        return new Job(file, targets, out == null ? null : Path.of(out), d, quiet);
    }

    private static int gen(String[] a) throws IOException {
        Job job = parseJob(a, "evia gen FILE [-l LANG[,LANG...]] [-o FILE | -d DIR]");
        return generate(job, Cli.load(job.file()));
    }

    /** Generates every target of the job; 1 when one of them failed (the others are still written). */
    private static int generate(Job job, Library program) throws IOException {
        Map<Target, Path> files = outputs(job);
        int rc = 0;
        for (Target t : job.targets()) {
            String code;
            try {
                code = CodeGenerator.generate(program, t);
            } catch (FlowException e) {
                ERR.println(">> error (" + t.lang().display + "): " + e.getMessage());
                rc = 1;
                continue;
            }
            Path f = files.get(t);
            if (f == null) {
                OUT.print(code);
                continue;
            }
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Cli.writeGenerated(program, t, code, f);
            if (!job.quiet()) ERR.println("wrote " + f + "  (" + t.lang().display + ")");
        }
        return rc;
    }

    /**
     * Where each target goes: -o FILE, standard output (null), or DIR/NAME.EXT, where NAME is the program's.
     * Two targets with the same extension (c11 and c17) get the language in the name: app-c11.c, app-c17.c.
     */
    private static Map<Target, Path> outputs(Job job) {
        Map<Target, Path> m = new LinkedHashMap<>();
        if (job.dir() == null) {
            for (Target t : job.targets()) m.put(t, job.output());
            return m;
        }
        String name = Path.of(job.file()).getFileName().toString().replaceFirst("\\.(evia|eviaproj)$", "");
        if (Files.isDirectory(Path.of(job.file())) || name.isEmpty()) name = "program";
        for (Target t : job.targets()) {
            long same = job.targets().stream().filter(o -> o.ext().equals(t.ext())).count();
            String suffix = same > 1 ? "-" + t.lang().key().replace("+", "p") : "";
            m.put(t, job.dir().resolve(name + suffix + t.ext()));
        }
        return m;
    }

    // ---------------------------------------------------------------- watch

    private static int watch(String[] a) throws IOException {
        Job job = parseJob(a, "evia watch FILE [-l LANG[,LANG...]] [-o FILE | -d DIR]");
        if (job.output() == null && job.dir() == null) {
            throw new IllegalArgumentException("watch writes files: give -o FILE or -d DIR");
        }
        Path src = Path.of(job.file());
        FileTime seen = null;
        ERR.println("watching " + src + " (Ctrl+C to stop)");
        while (true) {
            FileTime now = lastChange(src);
            if (now != null && !now.equals(seen)) {
                seen = now;
                try {
                    int rc = generate(job, Cli.load(job.file()));
                    ERR.println(rc == 0 ? "ok" : "done, with errors");
                } catch (FlowException e) {
                    ERR.println(">> error: " + e.getMessage());
                } catch (IOException | RuntimeException e) {
                    // half-saved file: try again on the next change
                    ERR.println("evia: " + e.getMessage());
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
    }

    /** The newest change of a program file, or of any file in a project folder. */
    private static FileTime lastChange(Path p) throws IOException {
        if (!Files.exists(p)) return null;
        if (!Files.isDirectory(p)) return Files.getLastModifiedTime(p);
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).map(f -> {
                try {
                    return Files.getLastModifiedTime(f);
                } catch (IOException e) {
                    return FileTime.fromMillis(0);
                }
            }).max(FileTime::compareTo).orElse(null);
        }
    }

    // ---------------------------------------------------------------- check

    /** Loads the program and generates it for each language (default: every one but boards), without writing anything. */
    private static int check(String[] a) throws IOException {
        List<String> args = new ArrayList<>(Arrays.asList(a));
        if (!args.contains("-l") && !args.contains("--lang")) args.addAll(List.of("-l", "all"));
        Job job = parseJob(args.toArray(String[]::new), "evia check FILE [-l LANG[,LANG...]]");
        Library program = Cli.load(job.file());
        int bad = 0;
        for (Target t : job.targets()) {
            try {
                CodeGenerator.generate(program, t);
                OUT.println("  ok   " + t.lang().key());
            } catch (FlowException e) {
                OUT.printf("  !!   %-14s %s%n", t.lang().key(), e.getMessage());
                bad++;
            }
        }
        OUT.println(bad == 0 ? job.file() + ": ok" : job.file() + ": " + bad + " of " + job.targets().size() + " languages failed");
        return bad == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------- lists

    private static int langs() {
        OUT.printf("%-10s %-16s %-6s %s%n", "LANG", "NAME", "FILE", "NOTE");
        for (Target.Lang l : Target.Lang.values()) {
            OUT.printf("%-10s %-16s %-6s %s%n", l.key(), l.display, l.ext(), l.note);
        }
        OUT.println("\nAlso accepted: py, python3, kt, cpp20, cxx17 ...; all = every language that is not for a board.");
        return 0;
    }

    private static int boards(String[] a) {
        Target.Lang only = null;
        if (a.length > 0) {
            only = Target.langOf(a[0]);
            if (only == null) throw new IllegalArgumentException("unknown language " + a[0] + " (see evia langs)");
        }
        OUT.printf("%-18s %-46s %s%n", "BOARD", "NAME", "LANGUAGES");
        for (Board b : Board.values()) {
            if (only != null && !b.supports(only)) continue;
            List<String> ls = new ArrayList<>();
            for (Target.Lang l : Target.Lang.values()) if (b.supports(l)) ls.add(l.key());
            OUT.printf("%-18s %-46s %s%n", b.key(), b.display, String.join(", ", ls));
        }
        return 0;
    }

    // ---------------------------------------------------------------- help

    private static int help(String topic) {
        String text = switch (topic == null ? "" : topic) {
            case "gen", "watch" -> """
                    evia gen   FILE [options]     generate code (to standard output, -o FILE or -d DIR)
                    evia watch FILE [options]     the same, again every time FILE is saved (needs -o or -d)

                      FILE                 a program (.evia), a project folder or a .eviaproj file
                      -l, --lang LANGS     one or more, comma separated (default c++20); see evia langs
                                           all = c99 ... c++20, python, java, kotlin
                      -o, --out FILE       write one language to FILE
                      -d, --dir DIR        write each language to DIR/NAME.EXT (app.py, app.java, app-c11.c ...)
                      -b, --board BOARD    for arduino, avr, pic32, stm32, sv, verilog, vhdl; see evia boards
                      --flow MODE          structured (default) or goto
                      --values MODE        safe (default) or fast (numbers only, no checks)
                      --net WHERE          PIC32 MQTT: uart (default) or ethernet
                      -q, --quiet          don't list the files written

                    Examples:
                      evia gen app.evia -l python | python3 -
                      evia gen app.evia -l c11 -o app.c && cc -O2 -o app app.c -lm
                      evia gen app.evia -l python,java,kotlin -d out/
                      evia gen blink.evia -l arduino -b uno -o blink/blink.ino
                      evia gen top.evia -l sv -b basys3 -o top.sv     (also writes top.xdc)
                      evia watch app.evia -l python -o app.py
                    """;
            case "check" -> """
                    evia check FILE [-l LANGS] [--flow MODE] [--values MODE]

                    Loads the program and generates it for each language (default: all) without writing
                    anything. Exit code 0 when every language worked, 1 otherwise.
                    """;
            default -> """
                    evia - EVIA Paradigma's code generator on the command line

                    Usage: evia COMMAND [arguments]

                      gen FILE [options]       generate C, C++, Python, Java, Kotlin, Arduino, AVR, PIC32, STM32, HDL
                      watch FILE [options]     regenerate whenever the program is saved
                      check FILE [-l LANGS]    does the program generate for every language?
                      run FILE                 run with the interpreter (stdin/stdout)
                      native FILE [-o EXE]     a Linux x86-64 executable, no compiler needed
                      android FILE DIR [...]   an Android Studio project (--build, --install)
                      new FILE                 start a program from the example
                      langs                    the languages and their file types
                      boards [LANG]            the boards and chips
                      packages | install ID | remove ID    toolchains from the EVIA package server
                      version                  versions and the toolchains found
                      help [gen|watch|check]   more about a command

                    The older options still work: evia --gen ..., evia --linux ... (evia --help lists them).
                    """;
        };
        OUT.print(text);
        return 0;
    }

    // ---------------------------------------------------------------- helpers

    private static Board board(String v) {
        Board b = Board.parse(v);
        if (!b.key().equals(v.trim().toLowerCase(Locale.ROOT).replace('_', '-')) && !b.fqbn.equalsIgnoreCase(v.trim())) {
            throw new IllegalArgumentException("unknown board " + v + " (see evia boards)");
        }
        return b;
    }

    private static int ok(String msg) {
        ERR.println(msg);
        return 0;
    }

    private static void need(String[] a, int n, String usage) {
        if (a.length < n) throw new IllegalArgumentException("usage: " + usage);
    }

    private static String[] prepend(String first, String[] rest) {
        String[] r = new String[rest.length + 1];
        r[0] = first;
        System.arraycopy(rest, 0, r, 1, rest.length);
        return r;
    }
}
