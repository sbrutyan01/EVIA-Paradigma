package evia.paradigma;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line mode, for scripts, CI and Vim users:
 * <pre>
 * java -jar evia-paradigma.jar --run program.evia            run with the interpreter (stdin/stdout)
 * java -jar evia-paradigma.jar --gen program.evia [-o x.cpp]  write C++20 (stdout if no -o)
 * java -jar evia-paradigma.jar --example out.evia             save the built-in example program
 * </pre>
 * Output and errors are identical to the generated C++ program, so the two can be compared.
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
                    String code = CppGenerator.generate(load(args[1]));
                    if (args.length >= 4 && args[2].equals("-o")) Files.writeString(Path.of(args[3]), code, StandardCharsets.UTF_8);
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
                    out.println("EVIA Paradigma 1.0 (Java " + System.getProperty("java.version") + ")");
                    return 0;
                }
                default -> {
                    out.println("""
                            EVIA Paradigma - visual block programming with C++20 generation

                            Usage:
                              java -jar evia-paradigma.jar                           start the editor
                              java -jar evia-paradigma.jar --run  program.evia       run a program
                              java -jar evia-paradigma.jar --gen  program.evia [-o program.cpp]
                                                                                     generate C++20
                              java -jar evia-paradigma.jar --example out.evia        save the example
                            Build generated code with Clang %d:
                              clang++ -std=c++20 -O2 -o program program.cpp""".formatted(Settings.LATEST_CLANG));
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
        ProgramIO.load(lib, Path.of(file));
        return lib;
    }

    private static void need(String[] args, int n) {
        if (args.length < n) throw new IllegalArgumentException("missing file name (see --help)");
    }
}
