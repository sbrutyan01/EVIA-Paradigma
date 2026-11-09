package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Microchip's MPLAB IPE and its command-line programmer, ipecmd: it programs PIC32 chips through the tools
 * pic32prog cannot drive, such as the on-board programmer of the starter kits (PIC32MX Ethernet Starter Kit II:
 * "PKOB"), the PICkit 4 and 5, the ICD 4 and 5 and the Snap.
 *
 * <p>IPE is Microchip's software and comes with MPLAB X; its licence does not let EVIA Paradigma pass it on, and
 * Microchip's site does not let a program download the installer (it answers 403). So the user downloads MPLAB X
 * from microchip.com in the browser, and this runs that installer with administrator rights to install IPE alone,
 * with the 32-bit device support only. {@link #find()} then finds ipecmd where Microchip puts it.
 */
final class IpeInstaller {
    private IpeInstaller() {}

    /** The MPLAB X release IPE is installed from: v6.20, the one EVIA Paradigma is checked with. */
    static final String VERSION = "6.20";
    /** Microchip's earlier releases, v6.20 among them (in the browser only: scripted downloads get 403). */
    static final String DOWNLOAD_PAGE = "https://www.microchip.com/en-us/tools-resources/archives/mplab-ecosystem";
    /** The v6.20 Linux installer as Microchip published it: checked by size and SHA-256 before it runs. */
    static final Xc32Installer.Package LINUX = new Xc32Installer.Package("MPLABX-v6.20-linux-installer.tar", 936519680L,
            "cecefb0acb8a154086630522bf86592e6f0764bb24c66df33fc1e818c5077560");
    static final String LICENSE_PAGE = "https://www.microchip.com/en-us/tools-resources/develop/mplab-x-ide";

    /** The upload commands for the programmers ipecmd drives: label and command ({chip} and {hex} are replaced). */
    static final String[][] PRESETS = {
            {"Starter kit, on-board programmer (PKOB): PIC32MX Ethernet Starter Kit II", "ipecmd -TPPKOB -P{chip} -M -F{hex} -OL"},
            {"PICkit 3", "ipecmd -TPPK3 -P{chip} -M -F{hex} -OL"},
            {"PICkit 4", "ipecmd -TPPK4 -P{chip} -M -F{hex} -OL"},
            {"PICkit 5", "ipecmd -TPPK5 -P{chip} -M -F{hex} -OL"},
            {"MPLAB ICD 4", "ipecmd -TPICD4 -P{chip} -M -F{hex} -OL"},
            {"MPLAB ICD 5", "ipecmd -TPICD5 -P{chip} -M -F{hex} -OL"},
            {"MPLAB Snap", "ipecmd -TPSNAP -P{chip} -M -F{hex} -OL"},
    };

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** Where Microchip installs MPLAB X: one folder per version under these. */
    static List<Path> installRoots() {
        List<Path> r = new ArrayList<>();
        String override = System.getProperty("evia.mplabx.root");     // for scripts/check-ipe.sh
        if (override != null && !override.isBlank()) {
            r.add(Path.of(override));
        } else if (ExternalEditor.WINDOWS) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)"}) {
                String base = System.getenv(env);
                if (base != null) r.add(Path.of(base, "Microchip", "MPLABX"));
            }
        } else if (mac()) {
            r.add(Path.of("/Applications/microchip/mplabx"));
        } else {
            r.add(Path.of("/opt/microchip/mplabx"));
        }
        return r;
    }

    /** ipecmd of the newest MPLAB X / IPE installed in Microchip's usual place, or on the PATH; null when there is none. */
    static Path find() {
        String[] names = ExternalEditor.WINDOWS ? new String[]{"ipecmd.exe", "ipecmd.bat"} : new String[]{"ipecmd.sh", "ipecmd"};
        Path best = null;
        for (Path root : installRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> versions = Files.list(root)) {
                for (Path v : versions.sorted(Comparator.comparing(p -> p.getFileName().toString(), SerialPorts::natural)).toList()) {
                    for (String n : names) {
                        Path exe = v.resolve("mplab_platform").resolve("mplab_ipe").resolve(n);
                        if (Files.isRegularFile(exe)) {
                            best = exe;     // sorted, so the last one found is the newest
                            break;
                        }
                    }
                }
            } catch (IOException ignored) {
                // an unreadable folder holds no ipecmd for us
            }
        }
        if (best != null) return best;
        for (String n : names) {
            if (LinuxImages.onPath(n)) return Path.of(n);
        }
        return null;
    }

    /** Microchip's options: IPE only (no IDE), 32-bit devices only, nothing sent to Microchip, no questions. */
    static List<String> installerOptions(boolean showProgress) {
        return List.of("--mode", "unattended", "--unattendedmodeui", showProgress ? "minimal" : "none",
                "--ide", "0", "--ipe", "1", "--8bitmcu", "0", "--16bitmcu", "0", "--32bitmcu", "1", "--othermcu", "0",
                "--collectInfo", "0", "--collectMyMicrochipInfo", "0");
    }

    /** True for the name of one of Microchip's MPLAB X installers for this computer. */
    static boolean isInstaller(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.startsWith("mplabx-v" + VERSION + "-")) return false;
        if (ExternalEditor.WINDOWS) return n.endsWith(".exe");
        if (mac()) return n.endsWith(".dmg");
        return n.endsWith("-linux-installer.tar") || n.endsWith("-linux-installer.sh");
    }

    /**
     * Installs MPLAB IPE from an MPLAB X installer the user downloaded, with the user's agreement. Returns ipecmd
     * when it is there afterwards, otherwise null (cancelled, or failed with a message shown).
     */
    static Path install(Component parent) {
        String title = "MPLAB IPE (ipecmd)";
        Path have = find();
        if (have != null) {
            int a = JOptionPane.showConfirmDialog(parent, "<html>" + Text.t("ipecmd is already installed:") + "<br><code>" + have
                    + "</code><br><br>" + Text.t("Install MPLAB IPE again from an installer?") + "</html>", title, JOptionPane.YES_NO_OPTION);
            if (a != JOptionPane.YES_OPTION) return have;
        }
        String text = "<html><div style='width:500px'><b>" + Text.t("MPLAB IPE is Microchip's software, not part of EVIA Paradigma.")
                + "</b><br><br>" + Text.t("It comes with MPLAB X. Microchip lets it be downloaded only from its site, in the browser:")
                + "<br>&nbsp;&nbsp;1. " + Text.t("Open Microchip's page and download MPLAB X v6.20 for this computer (the .tar for Linux, .exe for Windows, .dmg for macOS).")
                + "<br>&nbsp;&nbsp;2. " + Text.t("Choose the downloaded file here. EVIA Paradigma runs Microchip's installer for IPE only, with the 32-bit devices; your system asks for an administrator password.")
                + "<br><br>" + Text.t("IPE is used under Microchip's licence agreement, which its installer shows. By choosing the file you agree to it.")
                + "<br><a href='" + DOWNLOAD_PAGE + "'>" + DOWNLOAD_PAGE + "</a></div></html>";
        Object[] options = {Text.t("Choose the installer..."), Text.t("Open Microchip's page"), Text.t("Cancel")};
        Path file = null;
        while (file == null) {
            int a = JOptionPane.showOptionDialog(parent, text, title, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[1]);
            if (a == 1) {
                Toolchains.browse(DOWNLOAD_PAGE);
                continue;
            }
            if (a != 0) return null;
            JFileChooser fc = new JFileChooser(Path.of(System.getProperty("user.home"), "Downloads").toFile());
            fc.setDialogTitle(Text.t("The MPLAB X installer you downloaded"));
            if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) continue;
            Path f = fc.getSelectedFile().toPath();
            if (!isInstaller(f.getFileName().toString())) {
                JOptionPane.showMessageDialog(parent, Text.t("%s is not the MPLAB X v6.20 installer for this computer (MPLABX-v6.20-linux-installer.tar, .exe or .dmg).",
                        f.getFileName()), title, JOptionPane.WARNING_MESSAGE);
                continue;
            }
            file = f;
        }
        Path chosen = file;

        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        p.add(new JLabel("<html><b>" + chosen.getFileName() + "</b><br>" + Text.t("Microchip's installer is running (IPE only); your system asks for an administrator password. This takes a few minutes.") + "</html>"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.setContentPane(p);
        d.setSize(600, 160);
        d.setLocationRelativeTo(parent);
        Path[] result = {null};
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception {
                // the Linux installer is checked against Microchip's published v6.20 file before it runs as root
                if (chosen.getFileName().toString().equals(LINUX.file())) Xc32Installer.check(chosen, LINUX);
                runInstaller(chosen);
                Path exe = find();
                if (exe == null) throw new IOException("the installer finished, but ipecmd is not in " + installRoots());
                return exe;
            }

            @Override protected void done() {
                d.dispose();
                try {
                    result[0] = get();
                    JOptionPane.showMessageDialog(parent, "<html>" + Text.t("MPLAB IPE is installed:") + "<br><code>" + result[0]
                            + "</code></html>", title, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(parent, "<html><div style='width:460px'>" + Text.t("Could not install MPLAB IPE:") + "<br>"
                            + String.valueOf(c.getMessage()).replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
                            + "</div></html>", title, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        d.setVisible(true);
        return result[0];
    }

    /** Runs Microchip's installer for IPE only, with administrator rights, and waits for it. */
    static void runInstaller(Path installer) throws IOException, InterruptedException {
        if (ExternalEditor.WINDOWS) {
            Xc32Installer.runWindows(installer, installerOptions(true));
            return;
        }
        if (mac()) {
            Xc32Installer.runMac(installer, installerOptions(true));
            return;
        }
        // Linux: the .tar holds a makeself .sh; what follows "--" goes to Microchip's installer inside it
        Path work = Files.createTempDirectory("evia-mplabx");
        try {
            Path sh = installer;
            if (installer.getFileName().toString().endsWith(".tar")) {
                Xc32Installer.run(List.of("tar", "-xf", installer.toString(), "-C", work.toString()), "tar");
                try (Stream<Path> s = Files.list(work)) {
                    sh = s.filter(f -> f.getFileName().toString().endsWith(".sh")).findFirst()
                            .orElseThrow(() -> new IOException("no .sh installer in " + installer.getFileName()));
                }
            }
            List<String> cmd = new ArrayList<>(List.of("pkexec", "sh", sh.toString(), "--"));
            cmd.addAll(installerOptions(false));
            try {
                Xc32Installer.run(cmd, "pkexec");
            } catch (IOException e) {
                throw new IOException(e.getMessage() + "\n\nWithout a graphical password prompt (pkexec), run Microchip's installer"
                        + " in a terminal instead:\n  sudo sh " + Xc32Installer.shellQuote(sh.toString()) + " -- "
                        + String.join(" ", installerOptions(false)));
            }
        } finally {
            Packages.deleteTree(work);
        }
    }

    /** The PIC32 upload command with ipecmd at its real place when it starts with plain "ipecmd". */
    static String resolve(String cmd) {
        if (!(cmd.equals("ipecmd") || cmd.startsWith("ipecmd "))) return cmd;
        Path exe = find();
        if (exe == null) return cmd;
        String e = exe.toString();
        return (e.contains(" ") ? "\"" + e + "\"" : e) + cmd.substring("ipecmd".length());
    }
}
