package evia.paradigma;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The toolchains that come inside the installers: avr-gcc with avrdude, and the OSS CAD Suite
 * (yosys, nextpnr, gmpack, openFPGALoader). They sit in a folder "toolchains" inside the
 * application: {@code /opt/evia-paradigma/lib/toolchains} on Linux, {@code C:\Program Files\EVIA
 * Paradigma\app\toolchains} on Windows, {@code EVIA Paradigma.app/Contents/Resources/toolchains} on macOS.
 *
 * <p>They are only defaults: a path set in Settings always wins, and the jar on its own (or a
 * build from source) has none, so the tools on PATH are used as before.
 */
final class Bundled {
    private Bundled() {}

    private static Path root;
    private static boolean looked;

    /** The toolchains folder, or null when this copy of the application has none. */
    static synchronized Path root() {
        if (looked) return root;
        looked = true;
        String override = System.getProperty("evia.toolchains");   // for checks and odd layouts
        if (override != null && Files.isDirectory(Path.of(override))) return root = Path.of(override);
        // jpackage launchers say where they are; from the jar otherwise
        String launcher = System.getProperty("jpackage.app-path");
        Path start = null;
        if (launcher != null) start = Path.of(launcher).toAbsolutePath().getParent();
        if (start == null) {
            try {
                start = Path.of(Bundled.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            } catch (Exception e) {
                return null;
            }
        }
        for (Path p = start; p != null && start.getNameCount() - p.getNameCount() <= 3; p = p.getParent()) {
            // lib/ on Linux and app/ on Windows are the folders jpackage's installers keep; Resources/ on macOS
            for (String sub : new String[]{"toolchains", "lib/toolchains", "app/toolchains", "Resources/toolchains"}) {
                if (Files.isDirectory(p.resolve(sub))) return root = p.resolve(sub);
            }
        }
        return null;
    }

    /**
     * A program of a toolchain, e.g. tool("avr-gcc", "avr-gcc"): from the installed package of that name
     * (Settings, Packages) first, then from the folder that came inside the installer; null when neither has it.
     */
    static Path tool(String toolchain, String program) {
        Path fromPackage = in(Packages.dir(toolchain), program);
        if (fromPackage != null) return fromPackage;
        Path r = root();
        return r == null ? null : in(r.resolve(toolchain), program);
    }

    /** The program in the bin/ folder of a toolchain's folder, or null. */
    private static Path in(Path toolchainDir, String program) {
        Path exe = toolchainDir.resolve("bin").resolve(ExternalEditor.WINDOWS ? program + ".exe" : program);
        if (Files.isRegularFile(exe)) return exe;
        // the OSS CAD Suite on Windows keeps some programs as .bat wrappers
        if (ExternalEditor.WINDOWS) {
            Path bat = exe.resolveSibling(program + ".bat");
            if (Files.isRegularFile(bat)) return bat;
        }
        return null;
    }

    /** The bundled program as a path for a settings default, or the plain name to find it on PATH. */
    static String or(String toolchain, String program) {
        Path p = tool(toolchain, program);
        return p != null ? p.toString() : program;
    }

    /** The toolchains EVIA Paradigma knows by folder name. */
    static final String[] TOOLCHAINS = {"avr-gcc", "arm-none-eabi-gcc", "openocd", "pic32prog", "arduino-cli", "oss-cad-suite"};

    /** What is inside, one line each, for About and the Settings pages; empty when nothing is bundled or installed. */
    static String describe() {
        StringBuilder sb = new StringBuilder();
        Path r = root();
        for (String name : TOOLCHAINS) {
            Path v = Packages.dir(name).resolve("EVIA-VERSION");
            if (!Files.isRegularFile(v) && r != null) v = r.resolve(name).resolve("EVIA-VERSION");
            try {
                if (Files.isRegularFile(v)) sb.append(Files.readString(v).strip()).append('\n');
            } catch (Exception ignored) {
                // a missing note is not an error
            }
        }
        return sb.toString().strip();
    }
}
