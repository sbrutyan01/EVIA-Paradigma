package evia.paradigma;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C++ editor for the generated program. The code can be edited with Vim keys
 * (or in the real Vim via "Edit in Vim"), saved, and compiled and run with Clang.
 *
 * <p>Vim commands: {@code :w} save, {@code :w file.cpp} save as, {@code :q} close,
 * {@code :wq}/{@code :x} save and close, {@code :make} or {@code :run} compile and run,
 * {@code :vim} open in real Vim.
 */
final class CodeViewer extends JFrame {
    private final JTextPane codePane = new JTextPane();
    private final JLabel modeLine = new JLabel(" ");
    private final JLabel compilerLabel = new JLabel(" ");
    private final JCheckBox vimBox = new JCheckBox("Vim keys", Settings.vimKeys());
    private final JTextArea output = new JTextArea();
    private final JTextField inputField = new JTextField();
    private final JButton runBtn = new JButton("Compile & Run");
    private final JButton stopBtn = new JButton("Stop");
    private final VimKeys vim;
    private final Timer rehighlight;
    private String generated = "";
    private String baseName = "program";
    private Path savedPath;
    private Process process;
    private final StringBuilder lastOutput = new StringBuilder();
    private Writer stdin;

    CodeViewer() {
        super("C++20 - EVIA Paradigma");
        AppIcon.apply(this);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1060, 860);

        // ---- toolbar
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("Save .cpp...", e -> saveAs()));
        tb.add(button("Copy", e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(codePane.getText()), null)));
        tb.add(button("Edit in Vim...", e -> editInVim()));
        tb.add(vimBox);
        tb.addSeparator();
        tb.add(new JLabel(" Compiler: "));
        tb.add(compilerLabel);
        tb.add(Box.createHorizontalStrut(8));
        tb.add(button("Settings...", e -> Settings.showDialog(this)));
        tb.add(Box.createHorizontalGlue());
        runBtn.addActionListener(e -> compileAndRun());
        stopBtn.addActionListener(e -> stop());
        stopBtn.setEnabled(false);
        tb.add(runBtn);
        tb.add(stopBtn);

        // ---- code editor with Vim keys and a Vim-like mode line
        codePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        codePane.setBackground(new Color(250, 250, 247));
        JScrollPane codeScroll = new JScrollPane(codePane);
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);
        modeLine.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        modeLine.setOpaque(true);
        modeLine.setBackground(new Color(235, 236, 240));
        modeLine.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.add(codeScroll, BorderLayout.CENTER);
        codePanel.add(modeLine, BorderLayout.SOUTH);

        vim = VimKeys.install(codePane, new VimKeys.Host() {
            @Override public boolean ex(String cmd) { return exCommand(cmd); }
            @Override public void status(String text) { modeLine.setText(text.isEmpty() ? " " : text); }
        }, false);
        vim.setEnabled(Settings.vimKeys());
        vimBox.addActionListener(e -> Settings.setVimKeys(vimBox.isSelected()));
        Settings.onChange(() -> {
            vimBox.setSelected(Settings.vimKeys());
            vim.setEnabled(Settings.vimKeys());
            modeLine.setText(Settings.vimKeys() ? "NORMAL" : " ");
            checkCompiler();
        });

        rehighlight = new Timer(350, e -> highlight());
        rehighlight.setRepeats(false);
        codePane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });

        // ---- output + stdin
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

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codePanel, bottom);
        split.setResizeWeight(0.72);
        getContentPane().add(tb, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        checkCompiler();
    }

    private static JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        return b;
    }

    /** Shows freshly generated code. Asks before throwing away edits made here. */
    void show(String cppCode, String name) {
        if (isVisible() && isDirty()) {
            int r = JOptionPane.showConfirmDialog(this, "You edited the C++ code. Replace it with the newly generated code?",
                    "Generate C++", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                toFront();
                return;
            }
        }
        generated = cppCode;
        baseName = name;
        savedPath = null;
        setText(cppCode);
        codePane.setCaretPosition(0);
        updateTitle();
        setVisible(true);
        toFront();
        codePane.requestFocusInWindow();
    }

    private boolean isDirty() {
        return !codePane.getText().equals(generated);
    }

    private void setText(String s) {
        vim.withoutUndo(() -> codePane.setText(s));
        vim.resetUndo();
        highlight();
    }

    private void edited() {
        rehighlight.restart();
        SwingUtilities.invokeLater(this::updateTitle);
    }

    private void updateTitle() {
        String file = savedPath != null ? savedPath.getFileName().toString() : baseName + ".cpp";
        setTitle(file + (isDirty() ? " [edited]" : "") + " - C++20 - EVIA Paradigma");
    }

    // ================================================================ ex commands (:w :q :make ...)

    private boolean exCommand(String cmd) {
        if (cmd.equals("w") || cmd.equals("w!")) {
            save();
        } else if (cmd.startsWith("w ")) {
            saveTo(Path.of(cmd.substring(2).trim()));
        } else if (cmd.equals("q") || cmd.equals("q!") || cmd.equals("close")) {
            setVisible(false);
        } else if (cmd.equals("wq") || cmd.equals("x")) {
            if (save()) setVisible(false);
        } else if (cmd.equals("make") || cmd.equals("run") || cmd.equals("make!")) {
            compileAndRun();
        } else if (cmd.equals("vim") || cmd.equals("gvim")) {
            SwingUtilities.invokeLater(this::editInVim);
        } else {
            return false;
        }
        return true;
    }

    // ================================================================ real Vim

    private void editInVim() {
        String s = ExternalEditor.edit(this, codePane.getText(), (savedPath != null ? savedPath.getFileName().toString() : baseName + ".cpp"));
        if (s != null && !s.equals(codePane.getText())) {
            int caret = Math.min(codePane.getCaretPosition(), s.length());
            vim.withoutUndo(() -> codePane.setText(s));
            vim.resetUndo();
            highlight();
            codePane.setCaretPosition(caret);
            updateTitle();
        }
    }

    // ================================================================ syntax highlighting

    private static final Set<String> KEYWORDS = Set.of("auto", "bool", "break", "case", "catch", "char", "class",
            "const", "constexpr", "default", "delete", "double", "else", "for", "goto", "if", "inline", "int",
            "long", "namespace", "new", "return", "static_cast", "struct", "switch", "template", "throw", "try",
            "using", "void", "while", "nullptr", "true", "false", "this", "operator", "sizeof", "noexcept");
    private static final Pattern TOKENS = Pattern.compile(
            "(//[^\\n]*)|(\"(?:\\\\.|[^\"\\\\\\n])*\")|('(?:\\\\.|[^'\\\\\\n])*')|(^\\s*#\\w+)|\\b([A-Za-z_]\\w*)\\b|(^L\\d+:)",
            Pattern.MULTILINE);

    private void highlight() {
        String code = codePane.getText();
        StyledDocument doc = codePane.getStyledDocument();
        SimpleAttributeSet plain = style(new Color(25, 25, 30), false, false);
        SimpleAttributeSet comment = style(new Color(110, 130, 110), false, true);
        SimpleAttributeSet string = style(new Color(160, 80, 30), false, false);
        SimpleAttributeSet keyword = style(new Color(30, 70, 170), true, false);
        SimpleAttributeSet pre = style(new Color(140, 60, 150), false, false);
        SimpleAttributeSet label = style(new Color(190, 30, 60), true, false);
        vim.withoutUndo(() -> {
            doc.setCharacterAttributes(0, doc.getLength(), plain, true);
            Matcher m = TOKENS.matcher(code);
            while (m.find()) {
                if (m.group(1) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), comment, false);
                else if (m.group(2) != null || m.group(3) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), string, false);
                else if (m.group(4) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), pre, false);
                else if (m.group(5) != null && KEYWORDS.contains(m.group(5))) doc.setCharacterAttributes(m.start(5), m.group(5).length(), keyword, false);
                else if (m.group(6) != null) doc.setCharacterAttributes(m.start(), m.end() - m.start(), label, false);
            }
        });
    }

    private static SimpleAttributeSet style(Color c, boolean bold, boolean italic) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, c);
        StyleConstants.setBold(s, bold);
        StyleConstants.setItalic(s, italic);
        return s;
    }

    // ================================================================ save

    private boolean save() {
        if (savedPath == null) return saveAs();
        return saveTo(savedPath);
    }

    private boolean saveAs() {
        JFileChooser fc = new JFileChooser(savedPath != null ? savedPath.toFile() : null);
        fc.setSelectedFile(savedPath != null ? savedPath.toFile() : new File(baseName + ".cpp"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        return saveTo(fc.getSelectedFile().toPath());
    }

    private boolean saveTo(Path p) {
        try {
            Files.writeString(p, codePane.getText(), StandardCharsets.UTF_8);
            savedPath = p.toAbsolutePath();
            updateTitle();
            modeLine.setText("\"" + p.getFileName() + "\" written");
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // ================================================================ compile & run

    private void checkCompiler() {
        Settings.checkCompiler(Settings.compilerCommand(), compilerLabel);
    }

    private void compileAndRun() {
        if (process != null) return;
        String code = codePane.getText();
        String command = Settings.compilerCommand();
        output.setText("");
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        Thread t = new Thread(() -> {
            try {
                Path dir = Files.createTempDirectory("evia-cpp");
                Path src = dir.resolve(baseName.replaceAll("[^A-Za-z0-9_-]", "_") + ".cpp");
                Path exe = dir.resolve(ExternalEditor.WINDOWS ? "program.exe" : "program");
                Files.writeString(src, code, StandardCharsets.UTF_8);

                List<String> cmd = new ArrayList<>();
                for (String tok : Settings.split(command)) {
                    cmd.add(tok.replace("{src}", src.toString()).replace("{exe}", exe.toString()));
                }
                append("$ " + String.join(" ", cmd) + "\n");
                lastOutput.setLength(0);
                int rc = runProcess(new ProcessBuilder(cmd).directory(dir.toFile()), false);
                if (rc != 0) {
                    append(">> compilation failed (exit code " + rc + ")\n");
                    if (ToolchainInstaller.looksLikeMissingToolchain(lastOutput.toString())) toolchainMissing();
                    return;
                }
                append(">> compiled, running...\n\n");
                rc = runProcess(new ProcessBuilder(exe.toString()).directory(dir.toFile()), true);
                append("\n>> program exited with code " + rc + "\n");
            } catch (IOException e) {
                append(">> " + e.getMessage() + "\n");
                if (ToolchainInstaller.supported()) toolchainMissing();
                else append(">> Install LLVM/Clang " + Settings.LATEST_CLANG + " and put clang++ on PATH,\n"
                        + "   or change the compiler in Settings.\n");
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
            while ((n = r.read(buf)) > 0) {
                String chunk = new String(buf, 0, n);
                if (!interactive && lastOutput.length() < 100_000) lastOutput.append(chunk);
                append(chunk);
            }
        }
        return p.waitFor();
    }

    /**
     * The compiler or its C++ standard library is missing. On Windows, offer to set up
     * llvm-mingw (Clang with its own C++ library, no Visual Studio) and compile again.
     */
    private void toolchainMissing() {
        if (!ToolchainInstaller.supported()) {
            append(">> Clang found no C++ standard library. Install libc++ or libstdc++ (e.g. g++) for your system.\n");
            return;
        }
        append("\n>> Clang can't find a C++ standard library. On Windows Clang needs one:\n"
                + "   EVIA Paradigma can set up llvm-mingw (Clang + libc++ + Windows headers,\n"
                + "   no Visual Studio needed) in " + ToolchainInstaller.home() + "\n");
        SwingUtilities.invokeLater(() -> {
            int r = JOptionPane.showConfirmDialog(this,
                    "<html>Clang can't find a C++ standard library.<br><br>"
                            + "Download and set up <b>llvm-mingw</b> now?<br>"
                            + "It's Clang with its own C++ library and Windows headers. No Visual Studio needed,<br>"
                            + "no admin rights, and it doesn't change your PATH.</html>",
                    "Set up Clang", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.YES_OPTION && ToolchainInstaller.install(this)) {
                checkCompiler();
                compileAndRun();
            }
        });
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
