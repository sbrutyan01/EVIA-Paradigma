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

    static String compilerCommand() {
        return PREFS.get("compiler", DEFAULT_COMPILER);
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
    record CompilerInfo(String name, int major, String version) {
        boolean isClang() { return name.equals("Clang"); }
        boolean isLatestClang() { return isClang() && major >= LATEST_CLANG; }
        String display() { return name + " " + version; }
    }

    private static final Pattern CLANG = Pattern.compile("clang version (\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern GCC = Pattern.compile("(?:g\\+\\+|gcc)[^\\n]*?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern MSVC = Pattern.compile("Version (\\d+)\\.(\\d+)\\.(\\d+)");

    /** Runs "&lt;compiler&gt; --version"; returns null if the compiler can't be started. */
    static CompilerInfo detectCompiler(String command) {
        List<String> tokens = split(command);
        if (tokens.isEmpty()) return null;
        String exe = tokens.get(0);
        boolean msvc = exe.toLowerCase().matches(".*(^|[\\\\/])cl(\\.exe)?$");
        try {
            Process p = new ProcessBuilder(msvc ? List.of(exe) : List.of(exe, "--version"))
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
            if ((m = CLANG.matcher(out)).find()) return info("Clang", m);
            if ((m = GCC.matcher(out)).find()) return info("GCC", m);
            if (msvc && (m = MSVC.matcher(out)).find()) return info("MSVC", m);
            return new CompilerInfo(exe, 0, "(unknown version)");
        } catch (Exception e) {
            return null;
        }
    }

    private static CompilerInfo info(String name, Matcher m) {
        String v = m.group(1) + "." + m.group(2) + (m.group(3) != null ? "." + m.group(3) : "");
        return new CompilerInfo(name, Integer.parseInt(m.group(1)), v);
    }

    // ================================================================ dialog

    static void showDialog(Component parent) {
        JCheckBox vim = new JCheckBox("Vim keys in code editors (NORMAL / INSERT / VISUAL modes)", vimKeys());
        JTextField editor = new JTextField(editorCommand(), 42);
        JTextField compiler = new JTextField(compilerCommand(), 42);
        JLabel compilerStatus = new JLabel(" ");
        JButton detectEditor = new JButton("Detect");
        detectEditor.addActionListener(e -> editor.setText(ExternalEditor.defaultCommand()));
        JButton defCompiler = new JButton("Default (Clang)");
        defCompiler.addActionListener(e -> compiler.setText(DEFAULT_COMPILER));
        JButton check = new JButton("Check");
        check.addActionListener(e -> checkCompiler(compiler.getText(), compilerStatus));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 4);
        g.gridx = 0; g.gridy = 0; g.gridwidth = 3;
        p.add(vim, g);
        g.gridy = 1; g.gridwidth = 1;
        p.add(new JLabel("External editor (Vim):"), g);
        g.gridx = 1; p.add(editor, g);
        g.gridx = 2; p.add(detectEditor, g);
        g.gridx = 1; g.gridy = 2; g.gridwidth = 2;
        p.add(small("{file} = file to edit. gVim: gvim -f {file}   Terminal Vim on Windows: cmd /c start \"Vim\" /wait vim {file}"), g);
        g.gridx = 0; g.gridy = 3; g.gridwidth = 1;
        p.add(new JLabel("C++ compiler:"), g);
        g.gridx = 1; p.add(compiler, g);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btns.add(defCompiler);
        btns.add(check);
        g.gridx = 2; p.add(btns, g);
        g.gridx = 1; g.gridy = 4; g.gridwidth = 2;
        p.add(small("{src} = generated .cpp, {exe} = program. Latest stable Clang is " + LATEST_CLANG
                + ". MSVC: cl /std:c++20 /EHsc /O2 /Fe:{exe} {src}"), g);
        g.gridy = 5;
        p.add(compilerStatus, g);
        checkCompiler(compiler.getText(), compilerStatus);

        int r = JOptionPane.showConfirmDialog(parent, p, "Settings - EVIA Paradigma",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        PREFS.put("editor", editor.getText().trim());
        PREFS.put("compiler", compiler.getText().trim().isEmpty() ? DEFAULT_COMPILER : compiler.getText().trim());
        PREFS.putBoolean("vimKeys", vim.isSelected());
        fire();
    }

    static void checkCompiler(String command, JLabel label) {
        label.setText("Checking compiler...");
        label.setForeground(Color.GRAY);
        new Thread(() -> {
            CompilerInfo ci = detectCompiler(command);
            SwingUtilities.invokeLater(() -> describe(ci, label));
        }, "evia-compiler-check").start();
    }

    /** Shows a compiler's status: green for Clang 23+, orange for older or other compilers, red if missing. */
    static void describe(CompilerInfo ci, JLabel label) {
        if (ci == null) {
            label.setText("Compiler not found - install LLVM/Clang " + LATEST_CLANG + " and put clang++ on PATH");
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
