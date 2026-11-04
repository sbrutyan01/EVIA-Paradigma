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
 * Editor for the generated C or C++ program. The language and generation mode are chosen in
 * the drop-down lists at the top (changing them regenerates the code). The code can be edited
 * with Vim keys (or in the real Vim via "Edit in Vim"), saved, and compiled and run with Clang or GCC.
 * Arduino and AVR code is built for the chosen board (arduino-cli, or avr-gcc) and uploaded to it.
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
    private final JButton uploadBtn = new JButton("Upload");
    private final VimKeys vim;
    private final Timer rehighlight;
    private String generated = "";
    private String baseName = "program";
    private Target target = Settings.target();
    /** Generates the program again for the current target (set by the main window). */
    private Runnable regenerate;
    private Path savedPath;
    private Process process;
    private final StringBuilder lastOutput = new StringBuilder();
    private Writer stdin;

    CodeViewer() {
        super("Generated code - EVIA Paradigma");
        AppIcon.apply(this);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1060, 860);

        // ---- toolbar
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("Save...", e -> saveAs()));
        tb.add(button("Copy", e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(codePane.getText()), null)));
        tb.add(button("Edit in Vim...", e -> editInVim()));
        tb.add(vimBox);
        tb.addSeparator();
        tb.add(button("Settings...", e -> Settings.showDialog(this)));
        tb.add(Box.createHorizontalStrut(12));
        tb.add(new JLabel("Compiler: "));
        tb.add(compilerLabel);
        tb.add(Box.createHorizontalGlue());
        runBtn.addActionListener(e -> compileAndRun());
        uploadBtn.addActionListener(e -> buildForBoard(true));
        uploadBtn.setToolTipText("Build, then upload to the board on the serial port set in Settings");
        stopBtn.addActionListener(e -> stop());
        stopBtn.setEnabled(false);
        tb.add(runBtn);
        tb.add(uploadBtn);
        tb.add(stopBtn);

        // ---- code editor with Vim keys and a Vim-like mode line
        codePane.setFont(Fonts.mono(Font.PLAIN, 13));
        JScrollPane codeScroll = new JScrollPane(codePane);
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);
        modeLine.setFont(Fonts.mono(Font.BOLD, 12));
        modeLine.setOpaque(true);
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
            if (!Settings.target().equals(target) && isVisible() && regenerate != null) {
                SwingUtilities.invokeLater(regenerate);   // the drop-down lists changed: generate again
            } else {
                checkCompiler();
            }
        });

        rehighlight = new Timer(350, e -> highlight());
        Runnable colors = this::applyTheme;
        colors.run();
        Theme.onChange(colors);
        rehighlight.setRepeats(false);
        codePane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });

        // ---- output + stdin
        output.setEditable(false);
        output.setFont(Fonts.mono(Font.PLAIN, 13));
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
        // second row: language, flow, values (and board)
        JToolBar tb2 = new JToolBar();
        tb2.setFloatable(false);
        tb2.add(new TargetBar());
        tb2.add(Box.createHorizontalGlue());
        JPanel bars = new JPanel(new GridLayout(2, 1));
        bars.add(tb);
        bars.add(tb2);
        getContentPane().add(bars, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        updateButtons();
        checkCompiler();
    }

    /** Desktop languages compile and run; microcontroller languages build and upload. */
    private void updateButtons() {
        runBtn.setText(target.embedded() ? "Build" : "Compile & Run");
        runBtn.setToolTipText(target.embedded() ? "Build for the board chosen in the Board list" : null);
        uploadBtn.setVisible(target.embedded());
    }

    private static JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        return b;
    }

    /** Shows freshly generated code. Asks before throwing away edits made here. */
    void setRegenerator(Runnable r) {
        regenerate = r;
    }

    void show(String cppCode, String name) {
        show(cppCode, name, null);
    }

    void show(String code, String name, java.util.List<int[]> mark) {
        show(code, name, mark, Settings.target());
    }

    /**
     * Shows generated code with the given line ranges ([from, to), 0-based) marked, scrolled to the
     * first one - used by "View C++ source" on a block, method or class.
     */
    void show(String cppCode, String name, java.util.List<int[]> mark, Target t) {
        if (isVisible() && isDirty() && !cppCode.equals(generated)) {
            int r = JOptionPane.showConfirmDialog(this, "You edited the code. Replace it with the newly generated code?",
                    "Generate code", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                toFront();
                return;
            }
        }
        boolean newTarget = !t.equals(target);
        target = t;
        if (newTarget) {
            updateButtons();
            checkCompiler();
            if (process == null) output.setText("");   // the old output belongs to the other language
        }
        generated = cppCode;
        baseName = name;
        savedPath = null;
        if (!cppCode.equals(codePane.getText())) setText(cppCode);
        for (Object tag : marks) codePane.getHighlighter().removeHighlight(tag);
        marks.clear();
        codePane.setCaretPosition(0);
        updateTitle();
        setVisible(true);
        toFront();
        if (mark != null && !mark.isEmpty()) markLines(mark);
        codePane.requestFocusInWindow();
    }

    private void applyTheme() {
        codePane.setBackground(Theme.codeBg());
        codePane.setCaretColor(Theme.plain());
        modeLine.setBackground(Theme.modeLineBg());
        output.setBackground(Theme.consoleBg());
        output.setForeground(Theme.consoleFg());
        output.setCaretColor(Theme.consoleFg());
        SwingUtilities.updateComponentTreeUI(this);
        highlight();
        if (!marks.isEmpty() && lastMark != null) {
            for (Object tag : marks) codePane.getHighlighter().removeHighlight(tag);
            marks.clear();
            markLines(lastMark);
        }
    }

    private java.util.List<int[]> lastMark;
    private final List<Object> marks = new ArrayList<>();

    private void markLines(java.util.List<int[]> ranges) {
        lastMark = ranges;
        javax.swing.text.Element root = codePane.getDocument().getDefaultRootElement();
        javax.swing.text.Highlighter.HighlightPainter painter =
                new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Theme.mark());
        int firstOffset = -1;
        for (int[] r : ranges) {
            int from = Math.min(r[0], root.getElementCount() - 1), to = Math.min(r[1], root.getElementCount()) - 1;
            if (from < 0 || to < from) continue;
            int start = root.getElement(from).getStartOffset(), end = root.getElement(to).getEndOffset();
            try {
                marks.add(codePane.getHighlighter().addHighlight(start, Math.min(end, codePane.getDocument().getLength()), painter));
            } catch (javax.swing.text.BadLocationException ignored) {
                // stale range
            }
            if (firstOffset < 0) firstOffset = start;
        }
        if (firstOffset < 0) return;
        int caret = firstOffset;
        codePane.setCaretPosition(caret);
        SwingUtilities.invokeLater(() -> {
            try {
                java.awt.geom.Rectangle2D r = codePane.modelToView2D(caret);
                if (r == null) return;
                Rectangle vis = codePane.getVisibleRect();
                int top = Math.max(0, (int) r.getY() - vis.height / 4);
                codePane.scrollRectToVisible(new Rectangle(0, top, 1, vis.height));
            } catch (javax.swing.text.BadLocationException ignored) {
                // nothing to scroll to
            }
        });
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
        String file = savedPath != null ? savedPath.getFileName().toString() : baseName + target.ext();
        setTitle(file + (isDirty() ? " [edited]" : "") + " - " + target.describe() + " - EVIA Paradigma");
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
        String s = ExternalEditor.edit(this, codePane.getText(), (savedPath != null ? savedPath.getFileName().toString() : baseName + target.ext()));
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
        SimpleAttributeSet plain = style(Theme.plain(), false, false);
        SimpleAttributeSet comment = style(Theme.comment(), false, true);
        SimpleAttributeSet string = style(Theme.string(), false, false);
        SimpleAttributeSet keyword = style(Theme.keyword(), true, false);
        SimpleAttributeSet pre = style(Theme.preproc(), false, false);
        SimpleAttributeSet label = style(Theme.label(), true, false);
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
        fc.setSelectedFile(savedPath != null ? savedPath.toFile() : new File(baseName + target.ext()));
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
        if (target.embedded()) checkBoardTools();
        else Settings.checkCompiler(Settings.commandFor(target), compilerLabel, target.isC());
    }

    /** Shows whether arduino-cli (Arduino) or avr-gcc (AVR C) is installed. */
    private void checkBoardTools() {
        boolean arduino = target.lang() == Target.Lang.ARDUINO;
        String tool = arduino ? Settings.arduinoCli() : Settings.avrGcc();
        compilerLabel.setText("Checking " + tool + "...");
        compilerLabel.setForeground(Theme.muted());
        Object token = Settings.newCheck(compilerLabel);
        new Thread(() -> {
            String version = null;
            try {
                Process p = new ProcessBuilder(tool, arduino ? "version" : "--version").redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(out);
                version = m.find() ? m.group(1) : "";
            } catch (IOException | InterruptedException e) {
                // not installed
            }
            String v = version;
            SwingUtilities.invokeLater(() -> {
                if (!Settings.isLatestCheck(compilerLabel, token)) return;
                String name = arduino ? "arduino-cli" : "avr-gcc";
                if (v == null) {
                    compilerLabel.setText(name + " not found - see Build for how to install it");
                    compilerLabel.setForeground(Theme.error());
                } else {
                    compilerLabel.setText(name + " " + v + "  -  " + Settings.board(target.lang()).display);
                    compilerLabel.setForeground(Theme.ok());
                }
            });
        }, "evia-board-tools").start();
    }

    /**
     * Builds the code for the chosen board (arduino-cli for sketches, avr-gcc + avr-objcopy for bare-metal C)
     * and, if asked, uploads it (arduino-cli upload, or avrdude).
     */
    private void buildForBoard(boolean upload) {
        if (process != null) return;
        String code = codePane.getText();
        Target tg = target;
        Board board = Settings.board(tg.lang());
        String port = Settings.serialPort();
        if (upload && port.isBlank()) {
            port = JOptionPane.showInputDialog(this, "Serial port of the " + board.display + " (e.g. "
                    + (ExternalEditor.WINDOWS ? "COM3" : "/dev/ttyACM0") + "):", "Upload", JOptionPane.QUESTION_MESSAGE);
            if (port == null || port.isBlank()) return;
            Settings.setSerialPort(port);
        }
        String thePort = port.trim();
        output.setText("");
        runBtn.setEnabled(false);
        uploadBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        Thread t = new Thread(() -> {
            String tool = null;
            try {
                Path dir = Files.createTempDirectory("evia-board");
                List<List<String>> steps = new ArrayList<>();
                if (tg.lang() == Target.Lang.ARDUINO) {
                    Path sketch = dir.resolve("program");
                    Files.createDirectories(sketch);
                    Files.writeString(sketch.resolve("program.ino"), code, StandardCharsets.UTF_8);
                    String cli = Settings.arduinoCli();
                    tool = cli;
                    steps.add(List.of(cli, "compile", "--fqbn", board.fqbn, "--output-dir", dir.resolve("build").toString(), sketch.toString()));
                    if (upload) {
                        steps.add(List.of(cli, "upload", "--fqbn", board.fqbn, "-p", thePort, "--input-dir",
                                dir.resolve("build").toString(), sketch.toString()));
                    }
                } else {
                    Path src = dir.resolve("program.c"), elf = dir.resolve("program.elf"), hex = dir.resolve("program.hex");
                    Files.writeString(src, code, StandardCharsets.UTF_8);
                    String gcc = Settings.avrGcc();
                    tool = gcc;
                    steps.add(List.of(gcc, "-mmcu=" + board.mcu, "-DF_CPU=16000000UL", "-std=gnu11", "-Os", "-Wall", "-Wextra",
                            "-o", elf.toString(), src.toString(), "-lm"));
                    steps.add(List.of(sibling(gcc, "objcopy"), "-O", "ihex", "-R", ".eeprom", elf.toString(), hex.toString()));
                    steps.add(List.of(sibling(gcc, "size"), elf.toString()));
                    if (upload) {
                        List<String> u = new ArrayList<>(List.of(Settings.avrdude(), "-p", board.mcu, "-c"));
                        u.addAll(Settings.split(board.programmer));
                        u.addAll(List.of("-P", thePort, "-D", "-U", "flash:w:" + hex + ":i"));
                        steps.add(u);
                    }
                }
                for (List<String> cmd : steps) {
                    if (cmd.get(0).equals(Settings.avrdude())) tool = Settings.avrdude();
                    append("$ " + String.join(" ", cmd) + "\n");
                    int rc = runProcess(new ProcessBuilder(cmd).directory(dir.toFile()), false);
                    if (rc != 0) {
                        append(">> failed (exit code " + rc + ")\n");
                        if (tg.lang() == Target.Lang.ARDUINO && lastOutput.toString().contains("not found")) append(installHelp(true));
                        return;
                    }
                    lastOutput.setLength(0);
                }
                append(upload ? ">> uploaded to the " + board.display + " on " + thePort
                        + ". Open a serial monitor at 115200 baud to see Print and answer Input.\n"
                        : ">> built for the " + board.display + ". Upload sends it to the board.\n");
            } catch (IOException e) {
                append(">> " + e.getMessage() + "\n" + installHelp(tg.lang() == Target.Lang.ARDUINO && !Settings.avrdude().equals(tool)));
            } catch (InterruptedException e) {
                append(">> stopped\n");
            } finally {
                SwingUtilities.invokeLater(() -> {
                    process = null;
                    runBtn.setEnabled(true);
                    uploadBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        }, "evia-board-build");
        t.setDaemon(true);
        t.start();
    }

    /** avr-gcc -> avr-objcopy / avr-size in the same folder. */
    private static String sibling(String gcc, String tool) {
        int slash = Math.max(gcc.lastIndexOf('/'), gcc.lastIndexOf('\\'));
        String name = gcc.substring(slash + 1).replace("gcc", tool);
        return gcc.substring(0, slash + 1) + name;
    }

    private static String installHelp(boolean arduino) {
        if (arduino) {
            return """
                    >> Arduino sketches build with arduino-cli (https://arduino.github.io/arduino-cli/):
                         arduino-cli core install arduino:avr
                         arduino-cli core install esp32:esp32 --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
                       then set its path in Settings if it isn't on PATH.
                    """;
        }
        return """
                >> Bare-metal AVR C builds with avr-gcc and avr-libc, uploads with avrdude:
                     Linux:   sudo apt install gcc-avr avr-libc avrdude
                     macOS:   brew tap osx-cross/avr && brew install avr-gcc avrdude
                     Windows: an avr-gcc build (e.g. github.com/ZakKemble/avr-gcc-build) and avrdude (github.com/avrdudes/avrdude)
                   then set their paths in Settings if they aren't on PATH.
                """;
    }

    private void compileAndRun() {
        if (process != null) return;
        if (target.embedded()) {
            buildForBoard(false);
            return;
        }
        String code = codePane.getText();
        String command = Settings.commandFor(target);
        output.setText("");
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        Thread t = new Thread(() -> {
            try {
                Path dir = Files.createTempDirectory("evia-cpp");
                Path src = dir.resolve(baseName.replaceAll("[^A-Za-z0-9_-]", "_") + target.ext());
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
