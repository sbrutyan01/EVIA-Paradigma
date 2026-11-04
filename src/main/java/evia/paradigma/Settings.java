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

/** User settings (stored with java.util.prefs): Vim keys, external editor, C++ compiler. */
final class Settings {
    private Settings() {}

    /** Latest stable LLVM/Clang major version this release of EVIA Paradigma targets. */
    static final int LATEST_CLANG = 23;
    static final String DEFAULT_COMPILER = "clang++ -std=c++20 -O2 -Wall -Wextra -o {exe} {src}";

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
        JButton defCompiler = new JButton("Default (Clang)");
        defCompiler.addActionListener(e -> {
            String managed = ToolchainInstaller.managedCommand();
            compiler.setText(managed != null ? managed : DEFAULT_COMPILER);
        });
        JButton install = new JButton(ToolchainInstaller.managedClang() == null ? "Install Clang..." : "Update Clang...");
        install.setToolTipText("Download the latest llvm-mingw (Clang with its own C++ library, no Visual Studio needed)");
        install.addActionListener(e -> {
            if (ToolchainInstaller.install(p(compiler))) {
                compiler.setText(ToolchainInstaller.managedCommand());
                checkCompiler(compiler.getText(), compilerStatus);
            }
        });
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
        p.add(new JLabel("Compiler (C++):"), g);
        g.gridx = 1; p.add(compiler, g);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btns.add(defCompiler);
        btns.add(check);
        if (ToolchainInstaller.supported()) btns.add(install);
        g.gridx = 2; p.add(btns, g);
        g.gridx = 1; g.gridy = 5; g.gridwidth = 2;
        p.add(small("{src} = generated file, {exe} = program. For C, the matching C compiler (clang, gcc) and -std= are used."), g);
        g.gridy++;
        p.add(small("Latest stable Clang is " + LATEST_CLANG
                + (ToolchainInstaller.supported() ? ". Windows: \"Install Clang...\" sets up llvm-mingw, no Visual Studio needed." : ".")), g);
        g.gridy++;
        p.add(compilerStatus, g);
        checkCompiler(compiler.getText(), compilerStatus);

        int r = JOptionPane.showConfirmDialog(parent, p, "Settings - EVIA Paradigma",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        PREFS.put("editor", editor.getText().trim());
        PREFS.put("compiler", compiler.getText().trim().isEmpty() ? DEFAULT_COMPILER : compiler.getText().trim());
        PREFS.putBoolean("vimKeys", vim.isSelected());
        PREFS.putBoolean("wheelZooms", wheel.isSelected());
        fire();
    }

    static void checkCompiler(String command, JLabel label) {
        checkCompiler(command, label, false);
    }

    static void checkCompiler(String command, JLabel label, boolean c) {
        label.setText("Checking compiler...");
        label.setForeground(Color.GRAY);
        new Thread(() -> {
            CompilerInfo ci = detectCompiler(command);
            String problem = ci == null ? null : testCompile(command, c);
            SwingUtilities.invokeLater(() -> {
                describe(ci, label);
                if (problem != null) {
                    boolean noStdlib = ToolchainInstaller.looksLikeMissingToolchain(problem);
                    label.setText(ci.display() + (noStdlib
                            ? "  - found, but it has no C++ standard library"
                            + (ToolchainInstaller.supported() ? ": click \"Install Clang...\"" : "")
                            : "  - test compile failed"));
                    label.setToolTipText("<html><pre>" + problem.replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>");
                    label.setForeground(new Color(190, 30, 30));
                } else {
                    label.setToolTipText(null);
                }
            });
        }, "evia-compiler-check").start();
    }

    private static Component p(Component c) {
        return SwingUtilities.getWindowAncestor(c) != null ? SwingUtilities.getWindowAncestor(c) : c;
    }

    /** Shows a compiler's status: green for Clang 23+, orange for older or other compilers, red if missing. */
    static void describe(CompilerInfo ci, JLabel label) {
        if (ci == null) {
            label.setText(ToolchainInstaller.supported()
                    ? "Compiler not found - click \"Install Clang...\""
                    : "Compiler not found - install LLVM/Clang " + LATEST_CLANG + " and put clang++ on PATH");
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
        } else {
            label.setText(ci.display() + "  - Clang " + LATEST_CLANG + " is the recommended compiler");
            label.setForeground(new Color(200, 110, 0));
        }
    }

    private static JLabel small(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(11f));
        l.setForeground(Color.GRAY);
        return l;
    }
}
