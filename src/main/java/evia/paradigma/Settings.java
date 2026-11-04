package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User settings (stored with java.util.prefs): Vim keys, external editor, compiler (Clang or GCC), boards. */
final class Settings {
    private Settings() {}

    /** Latest stable LLVM/Clang major version this release of EVIA Paradigma targets. */
    static final int LATEST_CLANG = 23;
    static final String DEFAULT_COMPILER = "clang++ -std=c++20 -O2 -Wall -Wextra -o {exe} {src}";
    static final String DEFAULT_GCC = "g++ -std=c++20 -O2 -Wall -Wextra -o {exe} {src}";
    /** GCC versions from this one on compile everything EVIA Paradigma generates (C++20 with from_chars). */
    static final int MIN_GCC = 13;

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);
    private static final List<Runnable> listeners = new ArrayList<>();

    static boolean vimKeys() {
        return PREFS.getBoolean("vimKeys", true);
    }

    static void setVimKeys(boolean on) {
        PREFS.putBoolean("vimKeys", on);
        fire();
    }

    static String editorCommand() {
        return PREFS.get("editor", ExternalEditor.defaultCommand());
    }

    /**
     * The compile command. Unless the user chose another one, the toolchain that
     * EVIA Paradigma installed itself (llvm-mingw on Windows) is preferred.
     */
    static String compilerCommand() {
        String saved = PREFS.get("compiler", null);
        String managed = ToolchainInstaller.managedCommand();
        if ((saved == null || saved.equals(DEFAULT_COMPILER)) && managed != null) return managed;
        return saved != null ? saved : DEFAULT_COMPILER;
    }

    static void setCompilerCommand(String command) {
        PREFS.put("compiler", command);
        fire();
    }

    /** Clang or GCC: which compiler family a compile command uses. */
    static ToolchainInstaller.Kind family(String command) {
        List<String> t = split(command);
        String exe = t.isEmpty() ? "" : t.get(0).replace('\\', '/');
        String name = exe.substring(exe.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("clang")) return ToolchainInstaller.Kind.CLANG;   // "clang++" contains "g++" too
        return name.contains("g++") || name.contains("gcc") ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
    }

    /** The same command with the other compiler: its managed copy on Windows if installed, else clang++ / g++ from PATH. */
    static String withFamily(String command, ToolchainInstaller.Kind k) {
        if (family(command) == k) return command;
        String managed = ToolchainInstaller.managedCommand(k);
        if (managed != null) return managed;
        List<String> t = split(command);
        if (t.isEmpty()) return k == ToolchainInstaller.Kind.GCC ? DEFAULT_GCC : DEFAULT_COMPILER;
        StringBuilder sb = new StringBuilder(k == ToolchainInstaller.Kind.GCC ? "g++" : "clang++");
        for (String tok : t.subList(1, t.size())) sb.append(' ').append(tok.contains(" ") ? "\"" + tok + "\"" : tok);
        return sb.toString();
    }

    // ================================================================ boards

    /** The board that Build and Upload use for Arduino and AVR code. */
    static Board board() {
        return Board.parse(PREFS.get("board", "uno"));
    }

    static void setBoard(Board b) {
        if (b == board()) return;
        PREFS.put("board", b.key());
        fire();
    }

    static String arduinoCli() {
        return PREFS.get("arduinoCli", "arduino-cli");
    }

    static String avrGcc() {
        return PREFS.get("avrGcc", "avr-gcc");
    }

    static String avrdude() {
        return PREFS.get("avrdude", "avrdude");
    }

    /** The board's serial port for Upload, e.g. COM3 or /dev/ttyACM0 (empty: ask). */
    static String serialPort() {
        return PREFS.get("serialPort", "");
    }

    static void setSerialPort(String port) {
        PREFS.put("serialPort", port.trim());
    }

    /** True (default): the mouse wheel zooms block diagrams; false: it scrolls and Ctrl+wheel zooms. */
    static boolean wheelZooms() {
        return PREFS.getBoolean("wheelZooms", true);
    }

    /** The language and generation mode used by Generate code (and by projects that don't set their own). */
    static Target target() {
        return Target.parse(PREFS.get("target", null));
    }

    static void setTarget(Target t) {
        if (t.equals(target())) return;
        PREFS.put("target", t.key());
        fire();
    }

    /**
     * The compile command for a target, derived from the C++ compiler setting: the matching
     * C compiler for C (clang++ -> clang, g++ -> gcc, c++ -> cc), the target's -std= flag,
     * and -lm for C's math library.
     */
    static String commandFor(Target t) {
        return commandFor(compilerCommand(), t);
    }

    static String commandFor(String cxxCommand, Target t) {
        List<String> tokens = split(cxxCommand);
        if (tokens.isEmpty()) return cxxCommand;
        List<String> out = new ArrayList<>();
        String exe = tokens.get(0);
        if (t.isC()) {
            int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
            String dir = exe.substring(0, slash + 1), name = exe.substring(slash + 1);
            if (name.contains("clang++")) name = name.replace("clang++", "clang");
            else if (name.contains("g++")) name = name.replace("g++", "gcc");
            else if (name.contains("c++")) name = name.replace("c++", "cc");
            exe = dir + name;
        }
        out.add(exe);
        boolean std = false;
        for (String tok : tokens.subList(1, tokens.size())) {
            if (tok.startsWith("-std=")) {
                out.add("-std=" + t.lang().std);
                std = true;
            } else {
                out.add(tok);
            }
        }
        if (!std) out.add(1, "-std=" + t.lang().std);
        if (t.isC() && !out.contains("-lm")) out.add("-lm");
        StringBuilder sb = new StringBuilder();
        for (String tok : out) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(tok.contains(" ") ? "\"" + tok + "\"" : tok);
        }
        return sb.toString();
    }

    static void onChange(Runnable r) {
        listeners.add(r);
    }

    private static void fire() {
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    /** Splits a command line into tokens; "double quotes" group a token containing spaces. */
    static List<String> split(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (char c : cmd.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    // ================================================================ compiler version

    /** What --version reports for the compiler of a command, e.g. "Clang 23.1.4". */
    record CompilerInfo(String name, int major, String version, String target) {
        boolean isClang() { return name.equals("Clang"); }
        /** Clang that targets MSVC needs Visual Studio's C++ library; EVIA Paradigma avoids that. */
        boolean needsVisualStudio() { return target != null && target.contains("windows-msvc"); }
        boolean isLatestClang() { return isClang() && major >= LATEST_CLANG; }
        String display() { return name + " " + version; }
    }

    private static final Pattern CLANG = Pattern.compile("clang version (\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern GCC = Pattern.compile("(?:g\\+\\+|gcc)[^\\n]*?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Runs "&lt;compiler&gt; --version"; returns null if the compiler can't be started. */
    static CompilerInfo detectCompiler(String command) {
        List<String> tokens = split(command);
        if (tokens.isEmpty()) return null;
        String exe = tokens.get(0);
        try {
            Process p = new ProcessBuilder(List.of(exe, "--version"))
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 4000) sb.append(line).append('\n');
            }
            p.waitFor(10, TimeUnit.SECONDS);
            String out = sb.toString();
            Matcher m;
            Matcher t = Pattern.compile("(?m)^Target:\\s*(\\S+)").matcher(out);
            String target = t.find() ? t.group(1) : null;
            if ((m = CLANG.matcher(out)).find()) return info("Clang", m, target);
            if ((m = GCC.matcher(out)).find()) return info("GCC", m, target);
            return new CompilerInfo(exe, 0, "(unknown version)", target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compiles the C++ runtime with {@code command} to make sure the compiler and its
     * C++ standard library work. Returns null on success, otherwise the compiler output.
     */
    static String testCompile(String command) {
        return testCompile(command, false);
    }

    /** Test-compiles a tiny program: the C++ runtime, or for C a program using the C library and math. */
    static String testCompile(String command, boolean c) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("evia-check");
            java.nio.file.Path src = dir.resolve(c ? "check.c" : "check.cpp");
            java.nio.file.Path exe = dir.resolve(ExternalEditor.WINDOWS ? "check.exe" : "check");
            java.nio.file.Files.writeString(src, c
                    ? "#include <math.h>\n#include <stdio.h>\nint main(void) { printf(\"%g\\n\", fmod(7.5, 2)); return 0; }\n"
                    : CppRuntime.SOURCE + "\nint main() { evia_rt::print(evia_rt::S(\"ok\")); }\n");
            List<String> cmd = new ArrayList<>();
            for (String t : split(command)) cmd.add(t.replace("{src}", src.toString()).replace("{exe}", exe.toString()));
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "compiler timed out";
            }
            return p.exitValue() == 0 ? null : out.isBlank() ? "exit code " + p.exitValue() : out.strip();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static CompilerInfo info(String name, Matcher m, String target) {
        String v = m.group(1) + "." + m.group(2) + (m.group(3) != null ? "." + m.group(3) : "");
        return new CompilerInfo(name, Integer.parseInt(m.group(1)), v, target);
    }

    // ================================================================ dialog

    static void showDialog(Component parent) {
        JCheckBox vim = new JCheckBox("Vim keys in code editors (NORMAL / INSERT / VISUAL modes)", vimKeys());
        JCheckBox wheel = new JCheckBox("<html>Mouse wheel zooms block diagrams<br><font size='-2' color='#777777'>"
                + "Shift+wheel scrolls sideways, Ctrl+wheel up and down. Off: the wheel scrolls and Ctrl+wheel zooms.</font></html>",
                wheelZooms());
        JTextField editor = new JTextField(editorCommand(), 42);
        JTextField compiler = new JTextField(compilerCommand(), 42);
        JLabel compilerStatus = new JLabel(" ");
        JButton detectEditor = new JButton("Detect");
        detectEditor.addActionListener(e -> editor.setText(ExternalEditor.defaultCommand()));
        JComboBox<String> familyBox = new JComboBox<>(new String[]{"Clang (LLVM)", "GCC (GNU)"});
        familyBox.setToolTipText("Which compiler builds generated C and C++ (the C compiler follows: clang / gcc)");
        familyBox.setSelectedIndex(family(compiler.getText()) == ToolchainInstaller.Kind.GCC ? 1 : 0);
        JButton install = new JButton();
        Runnable familyChanged = () -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            install.setText((ToolchainInstaller.managed(k) == null ? "Install " : "Update ") + k.display + "...");
            install.setToolTipText(k == ToolchainInstaller.Kind.GCC
                    ? "Download the latest WinLibs GCC (MinGW-w64, no Visual Studio needed)"
                    : "Download the latest llvm-mingw (Clang with its own C++ library, no Visual Studio needed)");
        };
        familyChanged.run();
        familyBox.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            compiler.setText(withFamily(compiler.getText(), k));
            familyChanged.run();
            checkCompiler(compiler.getText(), compilerStatus);
        });
        JButton defCompiler = new JButton("Default");
        defCompiler.setToolTipText("The default command for the chosen compiler");
        defCompiler.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            String managed = ToolchainInstaller.managedCommand(k);
            compiler.setText(managed != null ? managed : k == ToolchainInstaller.Kind.GCC ? DEFAULT_GCC : DEFAULT_COMPILER);
            checkCompiler(compiler.getText(), compilerStatus);
        });
        install.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            if (ToolchainInstaller.install(p(compiler), k)) {
                compiler.setText(ToolchainInstaller.managedCommand(k));
                familyChanged.run();
                checkCompiler(compiler.getText(), compilerStatus);
            }
        });
        JComboBox<Board> boardBox = new JComboBox<>(Board.values());
        boardBox.setSelectedItem(board());
        JTextField port = new JTextField(serialPort(), 14);
        port.setToolTipText(ExternalEditor.WINDOWS ? "e.g. COM3 (Device Manager, Ports)" : "e.g. /dev/ttyACM0, /dev/ttyUSB0 or /dev/cu.usbmodem1101");
        JTextField cli = new JTextField(arduinoCli(), 30);
        JTextField avrGcc = new JTextField(avrGcc(), 30);
        JTextField avrdude = new JTextField(avrdude(), 30);
        JButton check = new JButton("Check");
        check.addActionListener(e -> checkCompiler(compiler.getText(), compilerStatus));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 4);
        g.gridx = 0; g.gridy = 0; g.gridwidth = 3;
        p.add(vim, g);
        g.gridy = 1;
        p.add(wheel, g);
        g.gridy = 2;
        g.gridwidth = 1;
        p.add(new JLabel("External editor (Vim):"), g);
        g.gridx = 1; p.add(editor, g);
        g.gridx = 2; p.add(detectEditor, g);
        g.gridx = 1; g.gridy = 3; g.gridwidth = 2;
        p.add(small("{file} = file to edit. gVim: gvim -f {file}   Terminal Vim on Windows: cmd /c start \"Vim\" /wait vim {file}"), g);
        g.gridx = 0; g.gridy = 4; g.gridwidth = 1;
        p.add(new JLabel("Compiler:"), g);
        g.gridx = 1; p.add(familyBox, g);
        g.gridx = 0; g.gridy = 5;
        p.add(new JLabel("Command (C++):"), g);
        g.gridx = 1; p.add(compiler, g);
        g.gridy = 4;
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btns.add(defCompiler);
        btns.add(check);
        if (ToolchainInstaller.supported()) btns.add(install);
        g.gridx = 2; g.gridy = 5; p.add(btns, g);
        g.gridx = 1; g.gridy = 6; g.gridwidth = 2;
        p.add(small("{src} = generated file, {exe} = program. For C, the matching C compiler (clang, gcc) and -std= are used."), g);
        g.gridy++;
        p.add(small("Clang " + LATEST_CLANG + " is the latest stable; GCC " + MIN_GCC + " or newer works too"
                + (ToolchainInstaller.supported() ? ". Windows: Install sets up llvm-mingw or WinLibs GCC, no Visual Studio needed." : ".")), g);
        g.gridy++;
        p.add(compilerStatus, g);

        g.gridy++; g.gridx = 0; g.gridwidth = 3;
        p.add(new JSeparator(), withFill(g));
        g.fill = GridBagConstraints.NONE;
        g.gridy++; g.gridwidth = 1;
        p.add(new JLabel("Board:"), g);
        JPanel boardRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        boardRow.add(boardBox);
        boardRow.add(new JLabel("  Serial port:"));
        boardRow.add(port);
        g.gridx = 1; g.gridwidth = 2; p.add(boardRow, g);
        g.gridy++; g.gridx = 0; g.gridwidth = 1;
        p.add(new JLabel("arduino-cli:"), g);
        g.gridx = 1; p.add(cli, g);
        g.gridy++; g.gridx = 0;
        p.add(new JLabel("avr-gcc:"), g);
        g.gridx = 1; p.add(avrGcc, g);
        g.gridy++; g.gridx = 0;
        p.add(new JLabel("avrdude:"), g);
        g.gridx = 1; p.add(avrdude, g);
        g.gridy++; g.gridx = 1; g.gridwidth = 2;
        p.add(small("<html>Arduino sketches build with arduino-cli (cores: arduino:avr, esp32:esp32); bare-metal AVR C with avr-gcc,<br>"
                + "uploaded with avrdude. Leonardo / Micro: press reset just before uploading bare-metal code.</html>"), g);
        checkCompiler(compiler.getText(), compilerStatus);

        int r = JOptionPane.showConfirmDialog(parent, p, "Settings - EVIA Paradigma",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        PREFS.put("editor", editor.getText().trim());
        PREFS.put("compiler", compiler.getText().trim().isEmpty() ? DEFAULT_COMPILER : compiler.getText().trim());
        PREFS.putBoolean("vimKeys", vim.isSelected());
        PREFS.put("board", ((Board) boardBox.getSelectedItem()).key());
        PREFS.put("serialPort", port.getText().trim());
        PREFS.put("arduinoCli", cli.getText().trim().isEmpty() ? "arduino-cli" : cli.getText().trim());
        PREFS.put("avrGcc", avrGcc.getText().trim().isEmpty() ? "avr-gcc" : avrGcc.getText().trim());
        PREFS.put("avrdude", avrdude.getText().trim().isEmpty() ? "avrdude" : avrdude.getText().trim());
        PREFS.putBoolean("wheelZooms", wheel.isSelected());
        fire();
    }

    static void checkCompiler(String command, JLabel label) {
        checkCompiler(command, label, false);
    }

    static void checkCompiler(String command, JLabel label, boolean c) {
        label.setText("Checking compiler...");
        label.setForeground(Color.GRAY);
        Object token = newCheck(label);
        new Thread(() -> {
            CompilerInfo ci = detectCompiler(command);
            String problem = ci == null ? null : testCompile(command, c);
            SwingUtilities.invokeLater(() -> {
                if (!isLatestCheck(label, token)) return;   // a newer check started meanwhile
                describe(ci, label);
                if (problem != null) {
                    boolean noStdlib = ToolchainInstaller.looksLikeMissingToolchain(problem);
                    label.setText(ci.display() + (noStdlib
                            ? "  - found, but it has no C++ standard library"
                            + (ToolchainInstaller.supported() ? ": click \"Install " + family(command).display + "...\"" : "")
                            : "  - test compile failed"));
                    label.setToolTipText("<html><pre>" + problem.replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>");
                    label.setForeground(new Color(190, 30, 30));
                } else {
                    label.setToolTipText(null);
                }
            });
        }, "evia-compiler-check").start();
    }

    /** Marks the newest check shown on a label, so a slower earlier check can't overwrite it. */
    static Object newCheck(JComponent label) {
        Object token = new Object();
        label.putClientProperty("evia.check", token);
        return token;
    }

    static boolean isLatestCheck(JComponent label, Object token) {
        return label.getClientProperty("evia.check") == token;
    }

    private static Component p(Component c) {
        return SwingUtilities.getWindowAncestor(c) != null ? SwingUtilities.getWindowAncestor(c) : c;
    }

    /** Shows a compiler's status: green for Clang 23+, orange for older or other compilers, red if missing. */
    static void describe(CompilerInfo ci, JLabel label) {
        if (ci == null) {
            label.setText(ToolchainInstaller.supported()
                    ? "Compiler not found - click \"Install ...\" next to the compiler"
                    : "Compiler not found - install Clang " + LATEST_CLANG + " (clang++) or GCC " + MIN_GCC + "+ (g++) and put it on PATH");
            label.setForeground(new Color(190, 30, 30));
        } else if (ci.needsVisualStudio()) {
            label.setText(ci.display() + " (" + ci.target() + ") - needs Visual Studio's C++ library"
                    + (ToolchainInstaller.supported() ? ": click \"Install Clang...\" for llvm-mingw" : ""));
            label.setForeground(new Color(190, 30, 30));
        } else if (ci.isLatestClang()) {
            label.setText(ci.display() + "  (latest stable)");
            label.setForeground(new Color(30, 130, 50));
        } else if (ci.isClang()) {
            label.setText(ci.display() + "  - latest stable is Clang " + LATEST_CLANG);
            label.setForeground(new Color(200, 110, 0));
        } else if (ci.name().equals("GCC") && ci.major() >= MIN_GCC) {
            label.setText(ci.display() + "  (GNU)");
            label.setForeground(new Color(30, 130, 50));
        } else if (ci.name().equals("GCC")) {
            label.setText(ci.display() + "  - GCC " + MIN_GCC + " or newer is needed for C++20");
            label.setForeground(new Color(200, 110, 0));
        } else {
            label.setText(ci.display() + "  - Clang " + LATEST_CLANG + " is the recommended compiler");
            label.setForeground(new Color(200, 110, 0));
        }
    }

    private static GridBagConstraints withFill(GridBagConstraints g) {
        g.fill = GridBagConstraints.HORIZONTAL;
        return g;
    }

    private static JLabel small(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(11f));
        l.setForeground(Color.GRAY);
        return l;
    }
}
