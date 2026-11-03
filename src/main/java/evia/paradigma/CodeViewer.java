package evia.paradigma;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the generated C++ and can save it, or compile and run it with an
 * external compiler. The program's output appears below; text typed in the
 * input line is sent to the program's stdin (for Input blocks).
 */
final class CodeViewer extends JFrame {
    private static final String DEFAULT_CMD = "g++ -std=c++20 -O2 -Wall -o {exe} {src}";
    private static final Preferences PREFS = Preferences.userNodeForPackage(CodeViewer.class);
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final JTextPane codePane = new JTextPane();
    private final JTextArea output = new JTextArea();
    private final JTextField inputField = new JTextField();
    private final JTextField cmdField = new JTextField(PREFS.get("compileCmd", DEFAULT_CMD), 34);
    private final JButton runBtn = new JButton("Compile & Run");
    private final JButton stopBtn = new JButton("Stop");
    private String code = "";
    private String baseName = "program";
    private Process process;
    private Writer stdin;

    CodeViewer() {
        super("Generated C++20 - EVIA Paradigma");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1000, 820);

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        JButton save = new JButton("Save .cpp...");
        save.addActionListener(e -> save());
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code), null));
        tb.add(save);
        tb.add(copy);
        tb.addSeparator();
        tb.add(new JLabel(" Compiler: "));
        cmdField.setToolTipText("<html>Command used to compile. {src} = source file, {exe} = program file.<br>"
                + "MSVC example: <code>cl /std:c++20 /EHsc /O2 /Fe:{exe} {src}</code></html>");
        tb.add(cmdField);
        JButton reset = new JButton("Default");
        reset.addActionListener(e -> cmdField.setText(DEFAULT_CMD));
        tb.add(reset);
        tb.addSeparator();
        runBtn.addActionListener(e -> compileAndRun());
        stopBtn.addActionListener(e -> stop());
        stopBtn.setEnabled(false);
        tb.add(runBtn);
        tb.add(stopBtn);

        codePane.setEditable(false);
        codePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        codePane.setBackground(new Color(250, 250, 247));
        JScrollPane codeScroll = new JScrollPane(codePane);
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        output.setBackground(new Color(30, 32, 38));
        output.setForeground(new Color(220, 225, 230));
        output.setMargin(new Insets(6, 8, 6, 8));
        inputField.setToolTipText("Type a line and press Enter to send it to the running program");
        inputField.addActionListener(e -> sendInput());
        inputField.setEnabled(false);
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(new JLabel(" Input: "), BorderLayout.WEST);
        inputRow.add(inputField, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createTitledBorder("Compiler / program output"));
        bottom.add(new JScrollPane(output), BorderLayout.CENTER);
        bottom.add(inputRow, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeScroll, bottom);
        split.setResizeWeight(0.7);
        getContentPane().add(tb, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
    }

    void show(String cppCode, String name) {
        code = cppCode;
        baseName = name;
        codePane.setText(code);
        highlight();
        codePane.setCaretPosition(0);
        setVisible(true);
        toFront();
    }

    // ================================================================ syntax highlighting

    private static final Set<String> KEYWORDS = Set.of("auto", "bool", "break", "case", "catch", "char", "class",
            "const", "constexpr", "default", "delete", "double", "else", "for", "goto", "if", "inline", "int",
            "long", "namespace", "new", "return", "static_cast", "struct", "switch", "template", "throw", "try",
            "using", "void", "while", "nullptr", "true", "false", "this", "operator", "sizeof");
    private static final Pattern TOKENS = Pattern.compile(
            "(//[^\\n]*)|(\"(?:\\\\.|[^\"\\\\])*\")|('(?:\\\\.|[^'\\\\])*')|(^\\s*#\\w+)|\\b([A-Za-z_]\\w*)\\b|(^L\\d+:)",
            Pattern.MULTILINE);

    private void highlight() {
        StyledDocument doc = codePane.getStyledDocument();
        SimpleAttributeSet comment = style(new Color(110, 130, 110), false, true);
        SimpleAttributeSet string = style(new Color(160, 80, 30), false, false);
        SimpleAttributeSet keyword = style(new Color(30, 70, 170), true, false);
        SimpleAttributeSet pre = style(new Color(140, 60, 150), false, false);
        SimpleAttributeSet label = style(new Color(190, 30, 60), true, false);
        Matcher m = TOKENS.matcher(code);
        while (m.find()) {
            if (m.group(1) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), comment, false);
            else if (m.group(2) != null || m.group(3) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), string, false);
            else if (m.group(4) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), pre, false);
            else if (m.group(5) != null && KEYWORDS.contains(m.group(5))) doc.setCharacterAttributes(m.start(5), m.group(5).length(), keyword, false);
            else if (m.group(6) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), label, false);
        }
    }

    private static SimpleAttributeSet style(Color c, boolean bold, boolean italic) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, c);
        StyleConstants.setBold(s, bold);
        StyleConstants.setItalic(s, italic);
        return s;
    }

    // ================================================================ save

    private void save() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(baseName + ".cpp"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(fc.getSelectedFile().toPath(), code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================================================================ compile & run

    private void compileAndRun() {
        if (process != null) return;
        PREFS.put("compileCmd", cmdField.getText().trim());
        output.setText("");
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        Thread t = new Thread(() -> {
            try {
                Path dir = Files.createTempDirectory("evia-cpp");
                Path src = dir.resolve("program.cpp");
                Path exe = dir.resolve(WINDOWS ? "program.exe" : "program");
                Files.writeString(src, code, StandardCharsets.UTF_8);

                List<String> cmd = new ArrayList<>();
                for (String tok : cmdField.getText().trim().split("\\s+")) {
                    cmd.add(tok.replace("{src}", src.toString()).replace("{exe}", exe.toString()));
                }
                append("$ " + String.join(" ", cmd) + "\n");
                int rc = runProcess(new ProcessBuilder(cmd).directory(dir.toFile()), false);
                if (rc != 0) {
                    append(">> compilation failed (exit code " + rc + ")\n");
                    return;
                }
                append(">> compiled, running...\n\n");
                rc = runProcess(new ProcessBuilder(exe.toString()).directory(dir.toFile()), true);
                append("\n>> program exited with code " + rc + "\n");
            } catch (IOException e) {
                append(">> " + e.getMessage() + "\n"
                        + ">> Is the compiler installed and on PATH? On Windows install MinGW-w64 (e.g. via MSYS2)\n"
                        + "   or use MSVC from a Developer Command Prompt and change the compiler command.\n");
            } catch (InterruptedException e) {
                append(">> stopped\n");
            } finally {
                SwingUtilities.invokeLater(() -> {
                    process = null;
                    stdin = null;
                    inputField.setEnabled(false);
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        }, "evia-compile-run");
        t.setDaemon(true);
        t.start();
    }

    /** Starts a process, streams its output into the output pane, waits for it. */
    private int runProcess(ProcessBuilder pb, boolean interactive) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        process = p;
        if (interactive) {
            stdin = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            SwingUtilities.invokeLater(() -> {
                inputField.setEnabled(true);
                inputField.requestFocusInWindow();
            });
        } else {
            p.getOutputStream().close();
        }
        try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) append(new String(buf, 0, n));
        }
        return p.waitFor();
    }

    private void sendInput() {
        if (stdin == null) return;
        String line = inputField.getText();
        inputField.setText("");
        append(line + "\n");
        try {
            stdin.write(line + "\n");
            stdin.flush();
        } catch (IOException e) {
            append(">> could not send input: " + e.getMessage() + "\n");
        }
    }

    private void stop() {
        Process p = process;
        if (p != null) p.destroyForcibly();
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            output.append(s);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }
}
