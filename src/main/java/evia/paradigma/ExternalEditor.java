package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Opens text in the real Vim (gVim, or Vim in a terminal) and reads it back.
 * The command comes from {@link Settings#editorCommand()}; {file} is replaced with the file path.
 */
final class ExternalEditor {
    private static final String OS = System.getProperty("os.name").toLowerCase();
    static final boolean WINDOWS = OS.contains("win");
    static final boolean MAC = OS.contains("mac");

    private ExternalEditor() {}

    /** A sensible Vim command for this machine. */
    static String defaultCommand() {
        if (WINDOWS) {
            Path gvim = findOnPath("gvim.exe");
            if (gvim == null) gvim = findInstalledWindowsVim();
            if (gvim != null) return "\"" + gvim + "\" -f {file}";
            return "cmd /c start \"Vim\" /wait vim {file}";
        }
        if (MAC) {
            if (findOnPath("mvim") != null) return "mvim -f {file}";
            if (findOnPath("gvim") != null) return "gvim -f {file}";
            return "open -W -n -a MacVim {file}";
        }
        if (findOnPath("gvim") != null) return "gvim -f {file}";
        if (findOnPath("gnome-terminal") != null) return "gnome-terminal --wait -- vim {file}";
        if (findOnPath("konsole") != null) return "konsole -e vim {file}";
        if (findOnPath("x-terminal-emulator") != null) return "x-terminal-emulator -e vim {file}";
        return "xterm -e vim {file}";
    }

    /**
     * Edits {@code text} in Vim as file {@code fileName} (its extension picks Vim's syntax highlighting).
     * Blocks with a small modal dialog until Vim is closed or the user clicks Done.
     * Returns the new text, or null if cancelled or Vim could not be started.
     */
    static String edit(Component parent, String text, String fileName) {
        Path file;
        try {
            Path dir = Files.createTempDirectory("evia-vim");
            file = dir.resolve(fileName);
            Files.writeString(file, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Vim", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        List<String> cmd = new ArrayList<>();
        for (String tok : Settings.split(Settings.editorCommand())) cmd.add(tok.replace("{file}", file.toString()));
        if (cmd.stream().noneMatch(s -> s.contains(file.toString()))) cmd.add(file.toString());

        Process process;
        long modified0 = file.toFile().lastModified();
        long started = System.currentTimeMillis();
        try {
            process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Could not start Vim:\n" + e.getMessage()
                            + "\n\nInstall Vim (gVim) or set the editor command in Settings.\nCommand: " + String.join(" ", cmd),
                    "Vim", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null && parent instanceof Window w) owner = w;
        JDialog d = new JDialog(owner, "Editing in Vim", Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel msg = new JLabel("<html>Editing <b>" + fileName + "</b> in Vim.<br>"
                + "Save and quit Vim (<code>:wq</code>) to bring your changes back here.</html>");
        msg.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        JButton done = new JButton("Done");
        JButton cancel = new JButton("Cancel");
        boolean[] ok = {false};
        done.addActionListener(e -> { ok[0] = true; d.dispose(); });
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(done);
        buttons.add(cancel);
        d.getContentPane().add(msg, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(parent);

        Thread watcher = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            boolean quickExit = System.currentTimeMillis() - started < 1500;
            boolean changed = file.toFile().lastModified() != modified0;
            SwingUtilities.invokeLater(() -> {
                if (!d.isDisplayable()) return;
                if (quickExit && !changed) {
                    // the launcher returned at once (Vim runs in its own terminal window): wait for "Done"
                    msg.setText("<html>Vim opened <b>" + fileName + "</b> in its own window.<br>"
                            + "Save it (<code>:w</code>), then click <b>Done</b>.</html>");
                    d.pack();
                } else {
                    ok[0] = true;
                    d.dispose();
                }
            });
        }, "evia-vim-watch");
        watcher.setDaemon(true);
        watcher.start();
        d.setVisible(true);
        watcher.interrupt();

        if (!ok[0]) return null;
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            if (s.endsWith("\r\n")) s = s.substring(0, s.length() - 2);
            else if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            return s.replace("\r\n", "\n");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Vim", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir.replace("\"", ""), exe);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    /** The official Vim installer puts gvim.exe in "C:\Program Files\Vim\vimNN\". */
    private static Path findInstalledWindowsVim() {
        List<String> roots = new ArrayList<>();
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)"}) {
            String v = System.getenv(env);
            if (v != null) roots.add(v);
        }
        for (String root : roots) {
            File[] dirs = new File(root, "Vim").listFiles(f -> f.isDirectory() && f.getName().startsWith("vim"));
            if (dirs == null) continue;
            return Arrays.stream(dirs)
                    .sorted(Comparator.comparing(File::getName).reversed())
                    .map(f -> f.toPath().resolve("gvim.exe"))
                    .filter(Files::isExecutable)
                    .findFirst().orElse(null);
        }
        return null;
    }
}
