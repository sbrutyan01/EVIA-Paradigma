package evia.paradigma;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line mode, for scripts, CI and Vim users:
 * <pre>
 * java -jar evia-paradigma.jar --run program.evia            run with the interpreter (stdin/stdout)
 * java -jar evia-paradigma.jar --gen program.evia [-o x.cpp] [--lang c++20] [--flow structured] [--values safe]
 *                                                             write C or C++ (stdout if no -o)
 * java -jar evia-paradigma.jar --example out.evia             save the built-in example program
 * </pre>
 * Output and errors are identical to the generated program, so the two can be compared.
 */
final class Cli {
    private Cli() {}

    static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        try {
            switch (args[0]) {
                case "--run" -> {
                    need(args, 2);
                    return runProgram(load(args[1]), out, err);
                }
                case "--gen" -> {
                    need(args, 2);
                    Target t = Target.DEFAULT;
                    String output = null;
                    for (int i = 2; i < args.length; i++) {
                        String opt = args[i];
                        if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                        String v = args[++i];
                        switch (opt) {
                            case "-o" -> output = v;
                            case "--lang" -> {
                                Target.Lang l = Target.langOf(v);
                                if (l == null) throw new IllegalArgumentException("unknown language " + v
                                        + " (c99, c11, c17, c++98, c++11, c++14, c++17, c++20)");
                                t = t.withLang(l);
                            }
                            case "--flow" -> t = t.withFlow(switch (v.toLowerCase()) {
                                case "structured" -> Target.Flow.STRUCTURED;
                                case "goto" -> Target.Flow.GOTO;
                                default -> throw new IllegalArgumentException("--flow is structured or goto");
                            });
                            case "--values" -> t = t.withValues(switch (v.toLowerCase()) {
                                case "safe" -> Target.Values.SAFE;
                                case "fast" -> Target.Values.FAST;
                                default -> throw new IllegalArgumentException("--values is safe or fast");
                            });
                            default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                        }
                    }
                    String code = CodeGenerator.generate(load(args[1]), t);
                    if (output != null) Files.writeString(Path.of(output), code, StandardCharsets.UTF_8);
                    else out.print(code);
                    return 0;
                }
                case "--example" -> {
                    need(args, 2);
                    Library lib = newLibrary();
                    ProgramIO.loadExample(lib);
                    ProgramIO.save(lib, Path.of(args[1]));
                    return 0;
                }
                case "--version" -> {
                    out.println("EVIA Paradigma " + App.VERSION + " (Java " + System.getProperty("java.version") + ")");
                    return 0;
                }
                default -> {
                    out.println("""
                            EVIA Paradigma - visual block programming with C and C++ generation

                            Usage:
                              java -jar evia-paradigma.jar                           start the editor
                              java -jar evia-paradigma.jar --run  program.evia       run a program
                                                          (or a project folder / .eviaproj)
                              java -jar evia-paradigma.jar --gen  program.evia [options]
                                                                                     generate C or C++
                                  -o FILE              write to FILE (default: standard output)
                                  --lang LANGUAGE      c99, c11, c17, c++98, c++11, c++14, c++17, c++20 (default)
                                  --flow MODE          structured (default): if/else, loops, switch
                                                       goto: every block a label, wires are jumps
                                  --values MODE        safe (default): numbers and text, the interpreter's checks
                                                       fast: plain double numbers, text only in Print, no checks
                              java -jar evia-paradigma.jar --example out.evia        save the example
                            Build generated code with Clang %d, e.g.:
                              clang++ -std=c++20 -O2 -o program program.cpp
                              clang -std=c11 -O2 -o program program.c -lm""".formatted(Settings.LATEST_CLANG));
                    return args[0].equals("--help") || args[0].equals("-h") ? 0 : 2;
                }
            }
        } catch (FlowException e) {
            out.flush();
            err.println(">> error: " + e.getMessage());
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            err.println("evia-paradigma: " + e.getMessage());
            return 2;
        }
    }

    private static int runProgram(Library lib, PrintStream out, PrintStream err) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            Block start = Interpreter.prepare(lib);
            new Interpreter().run(start, new Interpreter.Host() {
                @Override public void step(Block b) {}
                @Override public void print(String text) { out.println(text); }
                @Override public String input(String var) {
                    out.print("Enter a value for " + var + ": ");
                    out.flush();
                    try {
                        return in.readLine();
                    } catch (IOException e) {
                        return null;
                    }
                }
                @Override public boolean cancelled() { return false; }
            });
            return 0;
        } catch (FlowException e) {
            out.flush();
            err.println(">> error: " + e.getMessage());
            return 1;
        }
    }

    private static Library newLibrary() {
        Library lib = new Library();
        lib.main = new FlowCanvas(lib, null);
        return lib;
    }

    private static Library load(String file) throws IOException {
        Library lib = newLibrary();
        Path path = Path.of(file);
        // a project folder or *.eviaproj file means its main program
        if (java.nio.file.Files.isDirectory(path) || file.endsWith(Project.EXT)) {
            Project p = java.nio.file.Files.isDirectory(path) ? Project.inFolder(path) : Project.load(path);
            if (p == null) throw new IOException(file + " is not an EVIA Paradigma project (no " + Project.EXT + " file)");
            path = p.mainProgram();
        }
        ProgramIO.load(lib, path);
        return lib;
    }

    private static void need(String[] args, int n) {
        if (args.length < n) throw new IllegalArgumentException("missing file name (see --help)");
    }
}
