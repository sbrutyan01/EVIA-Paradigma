package evia.paradigma;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The small Python runtime put at the top of every generated Python program: number text and
 * parsing exactly like the interpreter, and for safe values the checked operations and messages.
 * It lives in resources/evia/paradigma/python as plain .py files, so it can be read and tested as Python.
 */
final class PythonRuntime {
    private PythonRuntime() {}

    static String source(boolean fast) {
        return read("common.py") + read(fast ? "fast.py" : "safe.py")
                + "# " + "=".repeat(76) + "\n";
    }

    private static String read(String name) {
        try (InputStream in = PythonRuntime.class.getResourceAsStream("python/" + name)) {
            if (in == null) throw new IllegalStateException("python/" + name + " is missing from the application");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
