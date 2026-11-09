package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux > Linux Images: builds a custom Linux for a BeagleBone Black or a Raspberry Pi 5 with Buildroot, Yocto or
 * OpenWrt, and writes it to an SD card. The window edits a workspace folder (see {@link LinuxImages}); the steps run
 * its build.sh, with the output below.
 */
final class LinuxWindow extends JFrame {

    private static LinuxWindow instance;

    /** What the window does once it is open. */
    enum Then { NOTHING, NEW, BUILD, WRITE, CHECK }

    /** Opens the window on the last workspace (or brings it to the front), then does {@code then}. */
    static void open(Component parent, Then then) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new LinuxWindow();
            instance.setLocationRelativeTo(parent);
        }
        instance.setVisible(true);
        instance.toFront();
        switch (then) {
            case NEW -> instance.newImage();
            case BUILD -> instance.run("all");
            case WRITE -> instance.writeCard();
            case CHECK -> instance.check();
            case NOTHING -> { }
        }
    }

    /** Opens the window on a workspace folder chosen by the user. */
    static void openFolder(Component parent) {
        JFileChooser fc = new JFileChooser(Settings.linuxWorkspace().isEmpty() ? null : Path.of(Settings.linuxWorkspace()).toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(Text.t("Open a Linux image folder (with evia-linux.json)"));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        if (!LinuxImages.isWorkspace(dir)) {
            JOptionPane.showMessageDialog(parent, Text.t("%s has no evia-linux.json: it is not a Linux image folder.", dir),
                    Text.t("Linux Images"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Settings.setLinuxWorkspace(dir.toString());
        open(parent, Then.NOTHING);
        instance.load(dir);
    }

    private final JComboBox<LinuxImages.Sys> sys = new JComboBox<>(LinuxImages.Sys.values());
    private final JComboBox<LinuxImages.Brd> board = new JComboBox<>(LinuxImages.Brd.values());
    private final JTextField folder = new JTextField(34);
    private final JTextField hostname = new JTextField(14);
    private final JPasswordField password = new JPasswordField(12);
    private final JCheckBox ssh = new JCheckBox(Text.t("SSH server"));
    private final JCheckBox python = new JCheckBox("Python 3");
    private final JCheckBox web = new JCheckBox(Text.t("Web interface (LuCI)"));
    private final JComboBox<String> image = new JComboBox<>(new String[]{"core-image-base", "core-image-minimal"});
    private final JTextField packages = new JTextField(30);
    private final JLabel note = new JLabel(" ");
    private final JTextArea out = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final List<JComponent> busyOff = new ArrayList<>();
    private final JButton stop = new JButton(Text.t("Stop"));
    private volatile Process process;
    private boolean loading;

    private LinuxWindow() {
        super(Text.t("Linux Images") + " - EVIA Paradigma");
        AppIcon.apply(this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        JButton browse = new JButton("...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                Path d = fc.getSelectedFile().toPath();
                folder.setText(d.toString());
                if (LinuxImages.isWorkspace(d)) load(d);
            }
        });
        password.setToolTipText(Text.t("Empty: keep the saved one (or none: root logs in without a password)"));
        packages.setToolTipText(Text.t("More packages, by the build system's names, e.g. htop nano i2c-tools"));
        folder.setToolTipText(Text.t("The workspace: sources, build and images. No spaces in the path."));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.anchor = GridBagConstraints.WEST;
        int y = 0;
        y = field(form, g, y, "Build system:", sys);
        y = field(form, g, y, "Board:", board);
        y = field(form, g, y, "Folder:", row(folder, browse));
        y = field(form, g, y, "Host name:", row(hostname, new JLabel(Text.t("Root password:")), password));
        y = field(form, g, y, "Include:", row(ssh, python, web, new JLabel(Text.t("Yocto image:")), image));
        y = field(form, g, y, "More packages:", packages);
        g.gridx = 1;
        g.gridy = y;
        g.fill = GridBagConstraints.HORIZONTAL;
        form.add(note, g);

        JButton save = new JButton(Text.t("Save"));
        save.setToolTipText(Text.t("Write evia-linux.json and build.sh in the folder"));
        save.addActionListener(e -> save());
        JButton checkBtn = new JButton(Text.t("Check This Computer"));
        checkBtn.addActionListener(e -> check());
        JButton build = new JButton(Text.t("Build Image"));
        build.setToolTipText(Text.t("Download the sources (the first time), apply the choices and build"));
        build.addActionListener(e -> run("all"));
        JButton menuconfig = new JButton(Text.t("Menuconfig..."));
        menuconfig.setToolTipText(Text.t("Change any option of the build system in a text menu (opens a terminal)"));
        menuconfig.addActionListener(e -> menuconfig());
        JButton write = new JButton(Text.t("Write to SD Card..."));
        write.addActionListener(e -> writeCard());
        JButton openDir = new JButton(Text.t("Open Folder"));
        openDir.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(workspace().toFile());
            } catch (IOException | RuntimeException ex) {
                status.setText(String.valueOf(ex.getMessage()));
            }
        });
        JButton term = new JButton(Text.t("Terminal (SSH)"));
        term.setToolTipText(Text.t("Log in to the board once it has booted: UART or SSH"));
        term.addActionListener(e -> TerminalWindow.open(this));
        stop.setEnabled(false);
        stop.addActionListener(e -> stopProcess());
        busyOff.addAll(List.of(save, checkBtn, build, menuconfig, write, sys, board));
        JPanel buttons = row(save, checkBtn, new JSeparator(SwingConstants.VERTICAL), build, stop, menuconfig,
                new JSeparator(SwingConstants.VERTICAL), write, openDir, term);

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);

        out.setEditable(false);
        out.setLineWrap(true);
        out.setFont(Fonts.mono(Font.PLAIN, 13));
        out.setBackground(Theme.consoleBg());
        out.setForeground(Theme.consoleFg());
        out.setText(Text.t("Choose the build system and the board, then Build Image. The steps run build.sh in the folder, which can also be run by hand in a terminal.") + "\n");

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(out), BorderLayout.CENTER);
        p.add(status, BorderLayout.SOUTH);
        setContentPane(p);
        setSize(1100, 760);

        sys.addActionListener(e -> choicesChanged(true));
        board.addActionListener(e -> choicesChanged(true));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (process != null && process.isAlive()) {
                    int r = JOptionPane.showConfirmDialog(LinuxWindow.this, Text.t("A build is running. Stop it and close?"),
                            Text.t("Linux Images"), JOptionPane.YES_NO_OPTION);
                    if (r != JOptionPane.YES_OPTION) return;
                    stopProcess();
                }
                dispose();
            }
        });

        String last = Settings.linuxWorkspace();
        if (!last.isEmpty() && LinuxImages.isWorkspace(Path.of(last))) load(Path.of(last));
        else newImage();
    }

    private static int field(JPanel form, GridBagConstraints g, int y, String label, Component c) {
        g.gridx = 0;
        g.gridy = y;
        g.fill = GridBagConstraints.NONE;
        form.add(new JLabel(Text.t(label)), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        form.add(c, g);
        g.weightx = 0;
        return y + 1;
    }

    private static JPanel row(Component... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : cs) r.add(c);
        return r;
    }

    // ================================================================ the choices

    /** Fresh choices in a new folder. */
    private void newImage() {
        if (busy()) return;
        loading = true;
        try {
            LinuxImages.Config c = LinuxImages.Config.defaults((LinuxImages.Sys) sys.getSelectedItem(), (LinuxImages.Brd) board.getSelectedItem());
            show(c);
            folder.setText(defaultFolder(c).toString());
            password.setText("");
        } finally {
            loading = false;
        }
        choicesChanged(false);
        status.setText(Text.t("A new image: choose, then Save or Build Image."));
    }

    private static Path defaultFolder(LinuxImages.Config c) {
        Path base = Path.of(System.getProperty("user.home"), "evia-linux");
        Path p = base.resolve(c.sys().key + "-" + c.board().key);
        for (int i = 2; LinuxImages.isWorkspace(p); i++) p = base.resolve(c.sys().key + "-" + c.board().key + "-" + i);
        return p;
    }

    private void load(Path ws) {
        try {
            LinuxImages.Config c = LinuxImages.read(ws);
            loading = true;
            try {
                show(c);
                folder.setText(ws.toString());
                password.setText("");
            } finally {
                loading = false;
            }
            choicesChanged(false);
            Settings.setLinuxWorkspace(ws.toString());
            status.setText(Text.t("Opened %s", ws));
        } catch (IOException | RuntimeException e) {
            status.setText(Text.t("Could not read %s: %s", ws.resolve(LinuxImages.CONFIG_FILE), String.valueOf(e.getMessage())));
        }
    }

    private void show(LinuxImages.Config c) {
        sys.setSelectedItem(c.sys());
        board.setSelectedItem(c.board());
        hostname.setText(c.hostname());
        ssh.setSelected(c.ssh());
        python.setSelected(c.python());
        web.setSelected(c.web());
        image.setSelectedIndex(c.fullImage() ? 0 : 1);
        packages.setText(c.packages());
    }

    private LinuxImages.Config config() {
        return new LinuxImages.Config((LinuxImages.Sys) sys.getSelectedItem(), (LinuxImages.Brd) board.getSelectedItem(),
                hostname.getText().trim(), ssh.isSelected(), python.isSelected(), web.isSelected(),
                image.getSelectedIndex() == 0, packages.getText().trim());
    }

    private Path workspace() {
        String f = folder.getText().trim();
        return f.isEmpty() ? null : Path.of(f.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();
    }

    private void choicesChanged(boolean byUser) {
        LinuxImages.Sys s = (LinuxImages.Sys) sys.getSelectedItem();
        LinuxImages.Brd b = (LinuxImages.Brd) board.getSelectedItem();
        web.setEnabled(s.openwrt());
        image.setEnabled(s == LinuxImages.Sys.YOCTO);
        if (byUser && !loading) {
            web.setSelected(s.openwrt());
            Path ws = workspace();
            // a folder of another system or board would mix two builds: offer a folder of its own
            if (ws == null || !LinuxImages.isWorkspace(ws) || !sameKind(ws, s, b)) {
                folder.setText(defaultFolder(LinuxImages.Config.defaults(s, b)).toString());
            }
            if (hostname.getText().matches("evia-(bbb|rpi5)")) hostname.setText("evia-" + b.key);
        }
        note.setText("<html><div style='width:640px'><small>" + Text.t(s.note) + "<br>" + Text.t(b.bootNote) + "</small></div></html>");
    }

    private static boolean sameKind(Path ws, LinuxImages.Sys s, LinuxImages.Brd b) {
        try {
            LinuxImages.Config c = LinuxImages.read(ws);
            return c.sys() == s && c.board() == b;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Writes the workspace; false (with the reason shown) when it could not. */
    private boolean save() {
        LinuxImages.Config c = config();
        Path ws = workspace();
        String problem = LinuxImages.problem(c, ws);
        if (problem != null) {
            status.setText(Text.t(problem));
            JOptionPane.showMessageDialog(this, Text.t(problem), Text.t("Linux Images"), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        char[] pw = password.getPassword();
        try {
            LinuxImages.write(ws, c, pw.length > 0 ? pw : null);
            Settings.setLinuxWorkspace(ws.toString());
            status.setText(Text.t("Saved %s and build.sh%s.", ws.resolve(LinuxImages.CONFIG_FILE),
                    LinuxImages.hasPassword(ws) ? Text.t(" (root password in secrets/, owner-only)") : Text.t(" (no root password)")));
            return true;
        } catch (IOException e) {
            status.setText(Text.t("Could not save: %s", String.valueOf(e.getMessage())));
            return false;
        } finally {
            java.util.Arrays.fill(pw, '\0');
            password.setText("");
        }
    }

    // ================================================================ running build.sh

    private boolean busy() {
        if (process != null && process.isAlive()) {
            status.setText(Text.t("A step is still running (Stop ends it)."));
            return true;
        }
        return false;
    }

    private void check() {
        LinuxImages.Sys s = (LinuxImages.Sys) sys.getSelectedItem();
        append("\n>> " + Text.t("This computer, for %s:", s.display) + "\n");
        boolean allOk = true;
        for (LinuxImages.Check c : LinuxImages.checkHost(s, workspace())) {
            append((c.ok() ? "  ok   " : "  !!   ") + c.text() + "\n");
            allOk &= c.ok();
        }
        status.setText(allOk ? Text.t("This computer is ready to build.") : Text.t("Some things are missing: see the lines with !!"));
    }

    /** Runs {@code build.sh step} in the background, with its output in the window. */
    private void run(String step) {
        if (busy()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            JOptionPane.showMessageDialog(this, Text.t("Buildroot, Yocto and OpenWrt build on Linux. The folder has build.sh: run it in WSL 2 (Windows) or a Linux virtual machine (macOS), then come back to write the card."),
                    Text.t("Linux Images"), JOptionPane.INFORMATION_MESSAGE);
            save();
            return;
        }
        if (!save()) return;
        Path ws = workspace();
        List<String> missing = new ArrayList<>();
        for (LinuxImages.Check c : LinuxImages.checkHost(config().sys(), ws)) if (!c.ok()) missing.add(c.text());
        if (!missing.isEmpty()) {
            int r = JOptionPane.showConfirmDialog(this, Text.t("This computer may not be ready:") + "\n\n  " + String.join("\n  ", missing)
                    + "\n\n" + Text.t("Build anyway?"), Text.t("Linux Images"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        start(List.of("bash", ws.resolve("build.sh").toString(), step), ws, "build.sh " + step, () -> {
            List<Path> imgs = LinuxImages.images(ws, config());
            if (!imgs.isEmpty() && step.equals("all")) {
                status.setText(Text.t("Done: %s. Write to SD Card puts it on a card.", imgs.get(0).getFileName()));
            }
        });
    }

    private void start(List<String> cmd, Path dir, String what, Runnable onSuccess) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putIfAbsent("LANG", "C.UTF-8");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            status.setText(Text.t("Could not start %s: %s", what, String.valueOf(e.getMessage())));
            return;
        }
        process = p;
        setBusy(true);
        long t0 = System.currentTimeMillis();
        append("\n>> " + String.join(" ", cmd) + "\n");
        status.setText(Text.t("Running %s...", what));
        Thread t = new Thread(() -> {
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                for (int n; (n = r.read(buf)) > 0; ) {
                    String s = new String(buf, 0, n);
                    SwingUtilities.invokeLater(() -> append(s));
                }
            } catch (IOException e) {
                // the process ended
            }
            int rc;
            try {
                rc = p.waitFor();
            } catch (InterruptedException e) {
                rc = -1;
            }
            int code = rc;
            long min = (System.currentTimeMillis() - t0) / 60000;
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                process = null;
                if (code == 0) {
                    append("\n>> " + Text.t("%s finished (%d min).", what, min) + "\n");
                    status.setText(Text.t("%s finished (%d min).", what, min));
                    onSuccess.run();
                } else {
                    append("\n>> " + Text.t("%s stopped with exit code %d.", what, code) + "\n");
                    status.setText(Text.t("%s stopped with exit code %d: the reason is in the last lines above.", what, code));
                }
            });
        }, "linux-build");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean on) {
        for (JComponent c : busyOff) c.setEnabled(!on);
        stop.setEnabled(on);
    }

    private void stopProcess() {
        Process p = process;
        if (p == null) return;
        // BitBake and make leave children: end them too
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        append("\n>> " + Text.t("Stopping...") + "\n");
    }

    // the output: a carriage return (progress lines of wget, dd, git) writes over the line instead of adding one
    private int lineStart;
    private boolean overwrite;
    private static final int MAX_OUTPUT = 400_000;

    private void append(String s) {
        javax.swing.text.Document d = out.getDocument();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') continue;
                flush(plain);
                overwrite = true;
            } else if (c == '\u001b') {
                // colour codes of make and git: skip to the letter that ends them
                int j = i + 1;
                if (j < s.length() && s.charAt(j) == '[') {
                    j++;
                    while (j < s.length() && !Character.isLetter(s.charAt(j))) j++;
                }
                i = j;
            } else {
                if (overwrite) {
                    flush(plain);
                    try {
                        d.remove(lineStart, d.getLength() - lineStart);
                    } catch (javax.swing.text.BadLocationException e) {
                        // keep it
                    }
                    overwrite = false;
                }
                plain.append(c);
                if (c == '\n') {
                    flush(plain);
                    lineStart = d.getLength();
                }
            }
        }
        flush(plain);
        if (d.getLength() > MAX_OUTPUT) {
            int cut = d.getLength() - MAX_OUTPUT * 3 / 4;
            try {
                d.remove(0, cut);
                lineStart = Math.max(0, lineStart - cut);
            } catch (javax.swing.text.BadLocationException e) {
                // keep it
            }
        }
        out.setCaretPosition(d.getLength());
    }

    private void flush(StringBuilder plain) {
        if (plain.length() == 0) return;
        out.append(plain.toString());
        plain.setLength(0);
    }

    // ================================================================ menuconfig, the SD card

    /** Menuconfig needs a real terminal: it opens the desktop's terminal on build.sh menuconfig. */
    private void menuconfig() {
        if (busy() || !save()) return;
        Path ws = workspace();
        String sh = "cd '" + ws + "' && ./build.sh menuconfig; echo; read -r -p 'Press Enter to close this window. ' _";
        List<String[]> terms = List.of(new String[]{"x-terminal-emulator", "-e", "bash", "-c", sh},
                new String[]{"gnome-terminal", "--", "bash", "-c", sh}, new String[]{"konsole", "-e", "bash", "-c", sh},
                new String[]{"xfce4-terminal", "-x", "bash", "-c", sh}, new String[]{"mate-terminal", "-x", "bash", "-c", sh},
                new String[]{"kitty", "bash", "-c", sh}, new String[]{"alacritty", "-e", "bash", "-c", sh},
                new String[]{"xterm", "-e", "bash", "-c", sh});
        for (String[] t : terms) {
            if (!LinuxImages.onPath(t[0])) continue;
            try {
                new ProcessBuilder(t).directory(ws.toFile()).start();
                status.setText(Text.t("Menuconfig opened in %s. Save there, then Build Image.", t[0]));
                return;
            } catch (IOException e) {
                // the next one
            }
        }
        JTextField cmd = new JTextField("cd " + ws + " && ./build.sh menuconfig");
        cmd.setEditable(false);
        JOptionPane.showMessageDialog(this, new Object[]{Text.t("No terminal program was found. Run this in a terminal:"), cmd},
                Text.t("Menuconfig"), JOptionPane.INFORMATION_MESSAGE);
    }

    /** Writes the newest image to an SD card the user chooses among the removable disks. */
    private void writeCard() {
        if (busy()) return;
        Path ws = workspace();
        List<Path> imgs = ws == null ? List.of() : LinuxImages.images(ws, config());
        if (imgs.isEmpty()) {
            JOptionPane.showMessageDialog(this, Text.t("There is no image in this folder yet: Build Image first."),
                    Text.t("Write to SD Card"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux") || !LinuxImages.onPath("pkexec")) {
            JOptionPane.showMessageDialog(this, Text.t("Write %s with Raspberry Pi Imager or balenaEtcher (Use custom image).",
                    imgs.get(0)), Text.t("Write to SD Card"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<LinuxImages.Disk> disks;
        try {
            disks = LinuxImages.removableDisks();
        } catch (IOException e) {
            status.setText(Text.t("Could not list the disks: %s", String.valueOf(e.getMessage())));
            return;
        }
        if (disks.isEmpty()) {
            JOptionPane.showMessageDialog(this, Text.t("No SD card or USB drive was found. Put the card in and try again."),
                    Text.t("Write to SD Card"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<Path> imgBox = new JComboBox<>(imgs.toArray(new Path[0]));
        imgBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof Path p ? p.getFileName().toString() : v, i, s, f);
            }
        });
        JComboBox<LinuxImages.Disk> diskBox = new JComboBox<>(disks.toArray(new LinuxImages.Disk[0]));
        JLabel warn = new JLabel("<html><b>" + Text.t("Everything on the card is erased.") + "</b></html>");
        int r = JOptionPane.showConfirmDialog(this, new Object[]{Text.t("Image:"), imgBox, Text.t("Card:"), diskBox, warn},
                Text.t("Write to SD Card"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        Path img = (Path) imgBox.getSelectedItem();
        LinuxImages.Disk disk = (LinuxImages.Disk) diskBox.getSelectedItem();
        int sure = JOptionPane.showConfirmDialog(this, Text.t("Erase %s and write %s to it?", disk.toString(), img.getFileName()),
                Text.t("Write to SD Card"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (sure != JOptionPane.YES_OPTION) return;
        start(LinuxImages.writeCommand(img, disk.device()), ws, Text.t("writing the card"), () -> status.setText(
                Text.t("The card is ready. Put it in the board and power it on. %s", Text.t(config().board().bootNote))));
    }
}
