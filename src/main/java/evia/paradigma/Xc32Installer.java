package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Microchip's free MPLAB XC32 compiler, fetched from Microchip and installed by Microchip's own
 * installer. Its licence does not allow passing the compiler on, so it is never part of EVIA
 * Paradigma: this downloads the official installer from microchip.com, checks it, has the user
 * accept Microchip's licence, and runs it in Free mode. The installer needs administrator rights,
 * which the system asks for in its usual way (pkexec on Linux, UAC on Windows, a password dialog
 * on macOS). XC32 then sits where Microchip puts it, and {@link #find()} finds it there.
 */
final class Xc32Installer {
    private Xc32Installer() {}

    static final String VERSION = "4.35";
    static final String LICENSE_PAGE = "https://www.microchip.com/en-us/tools-resources/develop/mplab-xc-compilers/licenses";
    private static final String BASE =
            "https://ww1.microchip.com/downloads/aemDocuments/documents/DEV/ProductDocuments/SoftwareTools/";

    /** One of Microchip's installer files, with what it must measure once downloaded. */
    record Package(String file, long size, String sha256) {
        String url() {
            return BASE + file;
        }
    }

    // the checksums are of Microchip's files as published for v4.35
    private static final Package LINUX = new Package("xc32-v4.35-full-install-linux-x64-installer.tar", 448358400L,
            "56004fd681edd6513f8cbc5e88e2d453b9ba1a68dd063262649e9a8b540d2709");
    private static final Package WINDOWS = new Package("xc32-v4.35-full-install-windows-x64-installer.exe", 444831416L,
            "56b028e3bec2ea16ea34c6907618c38febc25988d90919a894bd4eec1bcbfac2");
    private static final Package MAC = new Package("xc32-v4.35-full-install-osx-installer.dmg", 468289208L,
            "f4664a269b873ae72572924fb5525979fa60a399c1d6ecc8fc196833013b79c2");

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** The installer for this computer, or null where Microchip publishes none (e.g. Linux on ARM). */
    static Package forThisComputer() {
        if (ExternalEditor.WINDOWS) return WINDOWS;
        if (mac()) return MAC;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("amd64") || arch.equals("x86_64") ? LINUX : null;
    }

    /** Where Microchip's installer puts XC32: one folder per version under these. */
    static List<Path> installRoots() {
        List<Path> r = new ArrayList<>();
        if (ExternalEditor.WINDOWS) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)"}) {
                String base = System.getenv(env);
                if (base != null) r.add(Path.of(base, "Microchip", "xc32"));
            }
        } else if (mac()) {
            r.add(Path.of("/Applications/microchip/xc32"));
        } else {
            r.add(Path.of("/opt/microchip/xc32"));
        }
        return r;
    }

    /** xc32-gcc of the newest XC32 installed in Microchip's usual place, or null. */
    static Path find() {
        String exe = ExternalEditor.WINDOWS ? "xc32-gcc.exe" : "xc32-gcc";
        Path best = null;
        for (Path root : installRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> versions = Files.list(root)) {
                for (Path v : versions.sorted(Comparator.comparing(p -> p.getFileName().toString(), SerialPorts::natural)).toList()) {
                    Path gcc = v.resolve("bin").resolve(exe);
                    if (Files.isRegularFile(gcc)) best = gcc;   // sorted, so the last one found is the newest
                }
            } catch (IOException ignored) {
                // an unreadable folder holds no compiler for us
            }
        }
        return best;
    }

    /**
     * Downloads, checks and installs XC32 with the user's agreement. Returns xc32-gcc when it is
     * there afterwards, otherwise null (cancelled, refused, or failed with a message shown).
     */
    static Path install(Component parent) {
        Package pkg = forThisComputer();
        String title = "MPLAB XC32 " + VERSION;
        if (pkg == null) {
            JOptionPane.showMessageDialog(parent, "Microchip publishes XC32 for x86-64 Windows, macOS and Linux only.\n"
                    + "Install it from " + LICENSE_PAGE.replace("/licenses", "/xc32") + " and set xc32-gcc in Settings.",
                    title, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        Path have = find();
        if (have != null) {
            int a = JOptionPane.showConfirmDialog(parent, "<html>XC32 is already installed:<br><code>" + have
                            + "</code><br><br>Install XC32 " + VERSION + " anyway?</html>", title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (a != JOptionPane.YES_OPTION) return have;
        }
        if (!accepted(parent, title, pkg)) return null;

        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel status = new JLabel("Downloading from microchip.com...");
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        JButton cancel = new JButton(Text.t("Cancel"));
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        p.add(new JLabel("<html><b>" + title + " (Free)</b><br>From " + pkg.url().replaceFirst("/[^/]*$", "/")
                + "<br>" + pkg.file() + ", " + (pkg.size() >> 20) + " MB</html>"), BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.add(status, BorderLayout.NORTH);
        mid.add(bar, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(620, 230);
        d.setLocationRelativeTo(parent);

        Path[] result = {null};
        SwingWorker<Path, Object[]> worker = new SwingWorker<>() {
            @Override protected Path doInBackground() throws Exception {
                Path file = Toolchains.download(pkg.url(), (done, total) -> publish(new Object[]{
                        "Downloading... " + (done >> 20) + " of " + (pkg.size() >> 20) + " MB", (int) (done * 1000 / pkg.size())}),
                        this::isCancelled);
                try {
                    publish(new Object[]{"Checking the download...", -1});
                    check(file, pkg);
                    publish(new Object[]{"Microchip's installer is running; your system asks for an administrator password...", -1});
                    runInstaller(file);
                } finally {
                    Files.deleteIfExists(file);
                }
                Path gcc = find();
                if (gcc == null) throw new IOException("the installer finished, but xc32-gcc is not in " + installRoots());
                return gcc;
            }

            @Override protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                status.setText((String) last[0]);
                int v = (Integer) last[1];
                bar.setIndeterminate(v < 0);
                if (v >= 0) {
                    bar.setValue(v);
                    bar.setString(v / 10 + " %");
                }
                cancel.setEnabled(v >= 0);   // the download can stop; Microchip's installer runs to its end
            }

            @Override protected void done() {
                d.dispose();
                if (isCancelled()) return;
                try {
                    result[0] = get();
                    JOptionPane.showMessageDialog(parent, "<html>XC32 " + VERSION + " is installed:<br><code>" + result[0]
                            + "</code><br>PIC32 C now builds with it.</html>", title, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(parent, "<html><div style='width:460px'>Could not install XC32:<br>"
                                    + String.valueOf(c.getMessage()).replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
                                    + "</div></html>", title, JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        cancel.addActionListener(e -> worker.cancel(true));
        worker.execute();
        d.setVisible(true);
        return result[0];
    }

    /** Microchip's licence: the user reads it (in the browser) and accepts it, or nothing happens. */
    private static boolean accepted(Component parent, String title, Package pkg) {
        String text = "<html><div style='width:480px'><b>MPLAB XC32 is Microchip's software, not part of EVIA Paradigma.</b><br><br>"
                + "EVIA Paradigma downloads Microchip's own installer for XC32 " + VERSION + " (" + (pkg.size() >> 20)
                + " MB) from microchip.com, checks that it is the published file, and runs it in Free mode. "
                + "The installer needs administrator rights: your system asks for a password.<br><br>"
                + "XC32 is used under Microchip's licence agreement. By choosing <b>I accept</b> you agree to it.<br>"
                + "<a href='" + LICENSE_PAGE + "'>" + LICENSE_PAGE + "</a></div></html>";
        Object[] options = {"I accept, install", "Read the licence", Text.t("Cancel")};
        while (true) {
            int a = JOptionPane.showOptionDialog(parent, text, title, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[1]);
            if (a == 0) return true;
            if (a != 1) return false;
            Toolchains.browse(LICENSE_PAGE);
        }
    }

    /** The download must be exactly the file Microchip published. */
    static void check(Path file, Package pkg) throws IOException {
        long size = Files.size(file);
        if (size != pkg.size()) throw new IOException("the download is " + size + " bytes, Microchip's file is " + pkg.size());
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            String got = HexFormat.of().formatHex(md.digest());
            if (!got.equalsIgnoreCase(pkg.sha256())) {
                throw new IOException("the download's SHA-256 is " + got + ", not that of Microchip's file: it was not installed");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Microchip's options for a Free-mode install with no questions, into its usual folder. */
    static List<String> installerOptions(boolean showProgress) {
        return List.of("--mode", "unattended", "--unattendedmodeui", showProgress ? "minimal" : "none",
                "--LicenseType", "FreeMode", "--netservername", "localhost", "--ModifyAll", "1");
    }

    /** Runs Microchip's installer with administrator rights, and waits for it. */
    private static void runInstaller(Path download) throws IOException, InterruptedException {
        Path dir = download.getParent();
        if (ExternalEditor.WINDOWS) {
            // the installer asks for elevation itself (UAC); Start-Process -Verb RunAs shows that prompt
            String args = String.join(" ", installerOptions(true));
            run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$p = Start-Process -FilePath '" + download.toString().replace("'", "''") + "' -ArgumentList '" + args
                            + "' -Verb RunAs -Wait -PassThru; exit $p.ExitCode"), "Windows");
        } else if (mac()) {
            Path mount = Files.createTempDirectory(dir, "xc32-dmg");
            run(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount.toString(), download.toString()), "hdiutil");
            try {
                Path app;
                try (Stream<Path> s = Files.list(mount)) {
                    app = s.filter(p -> p.getFileName().toString().endsWith(".app")).findFirst()
                            .orElseThrow(() -> new IOException("no installer application in Microchip's disk image"));
                }
                Path exe = app.resolve("Contents/MacOS/installbuilder.sh");
                if (!Files.exists(exe)) {
                    try (Stream<Path> s = Files.list(app.resolve("Contents/MacOS"))) {
                        exe = s.findFirst().orElseThrow(() -> new IOException("no installer program in " + app));
                    }
                }
                StringBuilder cmd = new StringBuilder(shellQuote(exe.toString()));
                for (String o : installerOptions(true)) cmd.append(' ').append(shellQuote(o));
                run(List.of("osascript", "-e", "do shell script \"" + cmd.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\" with administrator privileges"), "the macOS administrator prompt");
            } finally {
                run(List.of("hdiutil", "detach", mount.toString(), "-force"), null);
            }
        } else {
            // the .tar holds Microchip's .run installer, which must run as root
            run(List.of("tar", "-xf", download.toString(), "-C", dir.toString()), "tar");
            Path runFile;
            try (Stream<Path> s = Files.list(dir)) {
                runFile = s.filter(p -> p.getFileName().toString().endsWith(".run")).findFirst()
                        .orElseThrow(() -> new IOException("no .run installer in Microchip's archive"));
            }
            runFile.toFile().setExecutable(true);
            List<String> cmd = new ArrayList<>(List.of("pkexec", runFile.toString()));
            cmd.addAll(installerOptions(false));
            try {
                run(cmd, "pkexec");
            } catch (IOException e) {
                throw new IOException(e.getMessage() + "\n\nWithout a graphical password prompt (pkexec), run Microchip's installer"
                        + " in a terminal instead:\n  sudo " + shellQuote(runFile.toString()) + " " + String.join(" ", installerOptions(false))
                        + "\n(or download it again from microchip.com)");
            } finally {
                Files.deleteIfExists(runFile);
            }
        }
    }

    /** Runs a command to its end; a failure names the step (what=null: failures do not matter). */
    private static void run(List<String> cmd, String what) throws IOException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            if (what == null) return;
            throw new IOException(cmd.get(0) + " could not be started (" + e.getMessage() + ")");
        }
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException(what + " did not finish in an hour");
        }
        if (p.exitValue() != 0 && what != null) {
            String text = new String(out, StandardCharsets.UTF_8).strip();
            if (text.length() > 600) text = text.substring(text.length() - 600);
            throw new IOException(what + " ended with exit code " + p.exitValue()
                    + (p.exitValue() == 126 || p.exitValue() == 127 ? " (the password prompt was cancelled or refused)" : "")
                    + (text.isEmpty() ? "" : ":\n" + text));
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
