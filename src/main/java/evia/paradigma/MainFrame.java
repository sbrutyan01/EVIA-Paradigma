package evia.paradigma;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Main window: toolbar, palette, main program canvas and output console. */
final class MainFrame extends JFrame {
    static final String APP = "EVIA Paradigma";

    private final Library lib = new Library();
    private final FlowCanvas canvas = new FlowCanvas(lib, null);
    private final Map<BlockDef, DefinitionEditor> editors = new HashMap<>();
    private final JTextArea console = new JTextArea();
    private final JSlider speed = new JSlider(0, 1000, 300);
    private final JButton runBtn = new JButton("Run");
    private final JButton stopBtn = new JButton("Stop");
    private final JLabel status = new JLabel(" ");
    private SwingWorker<Void, Void> worker;
    private File currentFile;
    private CodeViewer codeViewer;

    MainFrame() {
        super(APP);
        lib.main = canvas;
        lib.editorOpener = this::openEditor;
        lib.addListener(this::closeEditorsOfDeletedDefs);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1250, 880);
        setLocationRelativeTo(null);

        // ---- toolbar
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("New", e -> newProgram()));
        tb.add(button("Open...", e -> open()));
        tb.add(button("Save...", e -> save()));
        tb.add(button("Example", e -> {
            if (worker == null) {
                closeEditors();
                ProgramIO.loadExample(lib);
                console.setText("");
                currentFile = null;
                setTitle(APP);
            }
        }));
        tb.addSeparator();
        tb.add(button("New custom block...", e -> { if (worker == null) PalettePanel.newDefinition(this, lib); }));
        tb.add(button("Group selection (Ctrl+G)", e -> canvas.groupSelection()));
        tb.addSeparator();
        tb.add(button("Generate C++", e -> generateCpp()));
        tb.addSeparator();
        runBtn.addActionListener(e -> run());
        stopBtn.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        stopBtn.setEnabled(false);
        tb.add(runBtn);
        tb.add(stopBtn);
        tb.addSeparator();
        tb.add(new JLabel(" Step delay: "));
        speed.setMaximumSize(new Dimension(160, 30));
        speed.setToolTipText("Delay between blocks (0 = full speed)");
        speed.addChangeListener(e -> status.setText("Step delay: " + speed.getValue() + " ms"));
        tb.add(speed);
        tb.addSeparator();
        tb.add(button("Clear output", e -> console.setText("")));

        // ---- console
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        console.setBackground(new Color(30, 32, 38));
        console.setForeground(new Color(220, 225, 230));
        console.setCaretColor(Color.WHITE);
        console.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createTitledBorder("Output"));

        // ---- layout
        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        canvasScroll.getHorizontalScrollBar().setUnitIncrement(16);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvasScroll, consoleScroll);
        right.setResizeWeight(0.78);

        JScrollPane paletteScroll = new JScrollPane(new PalettePanel(lib, canvas));
        paletteScroll.setBorder(BorderFactory.createEmptyBorder());
        paletteScroll.setPreferredSize(new Dimension(220, 100));

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paletteScroll, right);
        main.setDividerLocation(220);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.setText("Drag blocks from the palette, connect them, press Run. "
                + "Select several blocks and press Ctrl+G to make a custom block.");

        getContentPane().add(tb, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);

        ProgramIO.loadExample(lib);
    }

    private static JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        return b;
    }

    // ================================================================ definition editors

    private void openEditor(BlockDef d) {
        DefinitionEditor ed = editors.computeIfAbsent(d, k -> {
            DefinitionEditor e = new DefinitionEditor(lib, k);
            e.setLocationRelativeTo(this);
            return e;
        });
        ed.refresh();
        ed.setVisible(true);
        ed.toFront();
    }

    private void closeEditorsOfDeletedDefs() {
        editors.entrySet().removeIf(en -> {
            if (lib.defs().contains(en.getKey())) return false;
            en.getValue().dispose();
            return true;
        });
    }

    private void closeEditors() {
        editors.values().forEach(Window::dispose);
        editors.clear();
    }

    // ================================================================ run

    private void run() {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        console.setText("");

        Block start;
        try {
            start = Interpreter.prepare(lib);   // compiles every block of every definition
        } catch (FlowException e) {
            reportError(e);
            return;
        }

        int delay = speed.getValue();
        lib.setLocked(true);
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        status.setText("Running...");

        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                new Interpreter().run(start, new Interpreter.Host() {
                    @Override public void step(Block b) {
                        if (delay <= 0) return;               // full speed: don't flood the EDT
                        SwingUtilities.invokeLater(() -> canvas.setActive(b));
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override public void print(String text) {
                        SwingUtilities.invokeLater(() -> console.append(text + "\n"));
                    }

                    @Override public String input(String var) {
                        String[] result = new String[1];
                        try {
                            SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showInputDialog(
                                    MainFrame.this, "Enter a value for  " + var + " :", "Input",
                                    JOptionPane.QUESTION_MESSAGE));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        } catch (InvocationTargetException e) {
                            throw new RuntimeException(e.getCause());
                        }
                        if (result[0] != null) print("> " + var + " = " + result[0]);
                        return result[0];
                    }

                    @Override public boolean cancelled() {
                        return isCancelled() || Thread.currentThread().isInterrupted();
                    }
                });
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    console.append(">> finished\n");
                    status.setText("Finished.");
                } catch (CancellationException e) {
                    console.append(">> stopped\n");
                    status.setText("Stopped.");
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof FlowException fe) {
                        reportError(fe);
                    } else {
                        console.append(">> internal error: " + c + "\n");
                        c.printStackTrace();
                    }
                    status.setText("Error.");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                canvas.setActive(null);
                lib.setLocked(false);
                runBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                worker = null;
            }
        };
        worker.execute();
    }

    // ================================================================ code generation

    private void generateCpp() {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        try {
            String code = CppGenerator.generate(lib);
            if (codeViewer == null) {
                codeViewer = new CodeViewer();
                codeViewer.setLocationRelativeTo(this);
            }
            String name = currentFile == null ? "program" : currentFile.getName().replaceFirst("\\.[^.]*$", "");
            codeViewer.show(code, name);
            status.setText("Generated " + code.lines().count() + " lines of C++20.");
        } catch (FlowException e) {
            console.setText("");
            reportError(e);
        }
    }

    /** Prints the error and marks the failing block; opens the definition if it is inside one. */
    private void reportError(FlowException e) {
        Block b = e.block;
        BlockDef where = b == null ? null : b.canvas().owner;
        console.append(">> error" + (where != null ? " in custom block '" + where.name + "'" : "")
                + ": " + e.getMessage() + "\n");
        if (b != null) {
            b.canvas().setError(b);
            if (where != null) openEditor(where);
        }
    }

    // ================================================================ files

    private void newProgram() {
        if (worker != null) return;
        closeEditors();
        canvas.clear();
        lib.clear();
        currentFile = null;
        console.setText("");
        setTitle(APP);
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(currentFile);
        fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma programs (*.evia)", "evia"));
        return fc;
    }

    private void open() {
        if (worker != null) return;
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        closeEditors();
        try {
            ProgramIO.load(lib, fc.getSelectedFile().toPath());
            currentFile = fc.getSelectedFile();
            setTitle(APP + " - " + currentFile.getName());
            console.setText("");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        JFileChooser fc = chooser();
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getPath() + ".evia");
        try {
            ProgramIO.save(lib, f.toPath());
            currentFile = f;
            setTitle(APP + " - " + f.getName());
            status.setText("Saved " + f.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
