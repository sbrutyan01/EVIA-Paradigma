package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The outside tools EVIA Paradigma can fetch for you: arduino-cli for sketches, avr-gcc for bare-metal
 * AVR, the OSS CAD Suite for FPGAs. Each one is the project's own release on GitHub, unpacked into the
 * application's data folder; nothing is installed system-wide and no administrator rights are needed.
 *
 * <p>Microchip's XC32 is not here: its licence does not allow passing the compiler on, so EVIA Paradigma
 * opens Microchip's download page instead and then asks where it was installed.
 */
final class Toolchains {

    /** What a tool is for, where its releases are, and which file to take on each system. */
    enum Tool {
        ARDUINO_CLI("Arduino CLI", "arduino-cli", "arduino/arduino-cli", "arduino-cli",
                "arduino-cli_[^\"]*_Linux_64bit\\.tar\\.gz",
                "arduino-cli_[^\"]*_macOS_ARM64\\.tar\\.gz",
                "arduino-cli_[^\"]*_macOS_64bit\\.tar\\.gz",
                "arduino-cli_[^\"]*_Windows_64bit\\.zip",
                "https://arduino.github.io/arduino-cli/latest/installation/",
                "Builds and uploads Arduino and ESP32 sketches (about 30 MB; the board cores it needs are"
                        + " downloaded the first time you build)."),
        AVR_GCC("AVR GCC", "avr-gcc", "ZakKemble/avr-gcc-build", "avr-gcc",
                "avr-gcc-[^\"]*-x64-linux\\.tar\\.bz2",
                "avr-gcc-[^\"]*-x64-darwin\\.tar\\.bz2",
                "avr-gcc-[^\"]*-x64-darwin\\.tar\\.bz2",
                "avr-gcc-[^\"]*-x64-windows\\.zip",
                "https://blog.zakkemble.net/avr-gcc-builds/",
                "Compiles bare-metal AVR C for the ATmega boards (about 100 MB)."),
        ARM_GCC("Arm GNU Toolchain (xPack)", "arm-none-eabi-gcc", "xpack-dev-tools/arm-none-eabi-gcc-xpack", "arm-none-eabi-gcc",
                "xpack-arm-none-eabi-gcc-[^\"]*-linux-x64\\.tar\\.gz",
                "xpack-arm-none-eabi-gcc-[^\"]*-darwin-arm64\\.tar\\.gz",
                "xpack-arm-none-eabi-gcc-[^\"]*-darwin-x64\\.tar\\.gz",
                "xpack-arm-none-eabi-gcc-[^\"]*-win32-x64\\.zip",
                "https://xpack-dev-tools.github.io/arm-none-eabi-gcc-xpack/",
                "Compiles bare-metal STM32 C: arm-none-eabi-gcc with newlib (about 300 MB to download, 1 GB unpacked)."),
        OPENOCD("OpenOCD (xPack)", "openocd", "xpack-dev-tools/openocd-xpack", "openocd",
                "xpack-openocd-[^\"]*-linux-x64\\.tar\\.gz",
                "xpack-openocd-[^\"]*-darwin-arm64\\.tar\\.gz",
                "xpack-openocd-[^\"]*-darwin-x64\\.tar\\.gz",
                "xpack-openocd-[^\"]*-win32-x64\\.zip",
                "https://xpack-dev-tools.github.io/openocd-xpack/",
                "Uploads to STM32 boards through an ST-Link (the Nucleo and Discovery boards have one built in; a Blue"
                        + " or Black Pill needs a separate ST-Link V2). About 3 MB."),
        OSS_CAD("OSS CAD Suite", "oss-cad-suite", "YosysHQ/oss-cad-suite-build", "yosys",
                "oss-cad-suite-linux-x64-[0-9]+\\.tgz",
                "oss-cad-suite-darwin-arm64-[0-9]+\\.tgz",
                "oss-cad-suite-darwin-x64-[0-9]+\\.tgz",
                "oss-cad-suite-windows-x64-[0-9]+\\.exe",
                "https://github.com/YosysHQ/oss-cad-suite-build/releases",
                "The open-source FPGA tools: yosys, nextpnr, gmpack and openFPGALoader, for Artix-7 and"
                        + " GateMate (about 1 GB unpacked)."),
        PIC32PROG("pic32prog", "pic32prog", null, "pic32prog", null, null, null, null,
                "https://github.com/sergev/pic32prog",
                "Uploads PIC32 programs: PICkit2, PICkit3 in its PICkit2 mode, the chipKIT and Microchip bootloaders,"
                        + " FT2232 JTAG adapters. A package of the EVIA server (about 1 MB)."),
        JDK("JDK (Eclipse Temurin 25)", "jdk", "adoptium/temurin25-binaries", "javac",
                "OpenJDK25U-jdk_x64_linux_hotspot_[0-9._]+\\.tar\\.gz",
                "OpenJDK25U-jdk_aarch64_mac_hotspot_[0-9._]+\\.tar\\.gz",
                "OpenJDK25U-jdk_x64_mac_hotspot_[0-9._]+\\.tar\\.gz",
                "OpenJDK25U-jdk_x64_windows_hotspot_[0-9._]+\\.zip",
                "https://adoptium.net/temurin/releases/",
                "Compiles and runs generated Java and Kotlin, and builds Android apps: the Eclipse Temurin JDK 25"
                        + " (about 200 MB; any JDK 17 or newer works too)."),
        KOTLINC("Kotlin compiler", "kotlin", "JetBrains/kotlin", "kotlinc",
                "kotlin-compiler-[0-9.]+\\.zip", "kotlin-compiler-[0-9.]+\\.zip",
                "kotlin-compiler-[0-9.]+\\.zip", "kotlin-compiler-[0-9.]+\\.zip",
                "https://kotlinlang.org/docs/command-line.html",
                "Compiles generated Kotlin and Android apps: kotlinc from JetBrains (about 90 MB). It needs a JDK."),
        PYTHON("Python 3", null, null, "python3", null, null, null, null,
                "https://www.python.org/downloads/",
                "Runs generated Python programs: Python 3.8 or newer, nothing else to install. On Windows, the"
                        + " python.org installer also sets up the py launcher, which EVIA Paradigma finds by itself."),
        XC32("Microchip XC32", null, null, "xc32-gcc", null, null, null, null,
                "https://www.microchip.com/en-us/tools-resources/develop/mplab-xc-compilers",
                "Compiles PIC32 C. Microchip's licence does not allow passing the compiler on, so it is"
                        + " downloaded from Microchip and installed by their installer; the free edition is enough.");

        final String display, folder, repo, exe, linux, macArm, macIntel, windows, page, about;

        Tool(String display, String folder, String repo, String exe, String linux, String macArm, String macIntel,
             String windows, String page, String about) {
            this.display = display;
            this.folder = folder;
            this.repo = repo;
            this.exe = exe;
            this.linux = linux;
            this.macArm = macArm;
            this.macIntel = macIntel;
            this.windows = windows;
            this.page = page;
            this.about = about;
        }

        /** True when EVIA Paradigma can fetch and unpack this tool itself on this computer. */
        boolean downloadable() {
            return repo != null && asset() != null;
        }

        /** The release file for this computer, or null when the project publishes none for it. */
        String asset() {
            if (ExternalEditor.WINDOWS) return windows;
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("mac")) return linux;
            return System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch64") ? macArm : macIntel;
        }

        /** The name of the program to look for once the archive is unpacked. */
        String program() {
            if (this == KOTLINC) return ExternalEditor.WINDOWS ? exe + ".bat" : exe;   // a script, not a program
            return ExternalEditor.WINDOWS ? exe + ".exe" : exe;
        }
    }

    private Toolchains() {}

    /** Where the tools are kept: the same application data folder as the Windows compilers. */
    static Path home(Tool t) {
        String base = ExternalEditor.WINDOWS ? System.getenv("LOCALAPPDATA") : null;
        if (base == null) base = System.getProperty("user.home");
        return Path.of(base, ExternalEditor.WINDOWS ? "EVIA Paradigma" : ".evia-paradigma", t.folder);
    }

    /** The program of a tool EVIA Paradigma installed, or null when it is not there. */
    static Path installed(Tool t) {
        Path dir = home(t);
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> all = Files.walk(dir, 5)) {
            return all.filter(p -> p.getFileName().toString().equals(t.program()))
                    .filter(Files::isRegularFile)
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Fetches the tool and writes its path into Settings. Shows what it is doing and what went wrong.
     * Returns the program's path when it is ready, otherwise null.
     */
    static Path install(Component parent, Tool t) {
        if (!t.downloadable()) {
            int answer = JOptionPane.showConfirmDialog(parent,
                    "<html><b>" + t.display + "</b><br>" + wrap(t.about) + "<br><br>"
                            + "Open the download page in the browser?</html>",
                    t.display, JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) browse(t.page);
            return null;
        }
        Path have = installed(t);
        if (have != null) {
            int answer = JOptionPane.showConfirmDialog(parent,
                    "<html>" + t.display + " is already here:<br><code>" + have + "</code><br><br>Download it again?</html>",
                    t.display, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return have;
        }
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Get " + t.display, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel status = new JLabel("Looking up the latest release...");
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        bar.setStringPainted(true);
        JButton cancel = new JButton("Cancel");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        p.add(new JLabel("<html><b>" + t.display + "</b><br>" + wrap(t.about) + "<br>Into: " + home(t) + "</html>"),
                BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.add(status, BorderLayout.NORTH);
        mid.add(bar, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(600, 220);
        d.setLocationRelativeTo(parent);

        Path[] result = {null};
        SwingWorker<Path, Object[]> worker = new SwingWorker<>() {
            @Override protected Path doInBackground() throws Exception {
                String url = latest(t);
                publish(new Object[]{"Downloading " + url.substring(url.lastIndexOf('/') + 1) + "...", -1});
                Path archive = download(url, (done, total) ->
                        publish(new Object[]{"Downloading... " + mb(done) + (total > 0 ? " of " + mb(total) : ""),
                                total > 0 ? (int) (done * 1000 / total) : -1}), this::isCancelled);
                publish(new Object[]{"Unpacking...", -1});
                Path dir = home(t);
                deleteTree(dir);
                Files.createDirectories(dir);
                unpack(archive, dir);
                Files.deleteIfExists(archive);
                Path exe = installed(t);
                if (exe == null) throw new IOException(t.program() + " is not in the downloaded file");
                if (!ExternalEditor.WINDOWS) exe.toFile().setExecutable(true);
                return exe;
            }

            @Override protected void process(java.util.List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                status.setText((String) last[0]);
                int v = (Integer) last[1];
                bar.setIndeterminate(v < 0);
                if (v >= 0) {
                    bar.setValue(v);
                    bar.setString(v / 10 + " %");
                }
            }

            @Override protected void done() {
                d.dispose();
                if (isCancelled()) return;
                try {
                    result[0] = get();
                    JOptionPane.showMessageDialog(parent,
                            "<html>" + t.display + " is ready:<br><code>" + result[0] + "</code></html>",
                            t.display, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    int answer = JOptionPane.showConfirmDialog(parent,
                            "<html>Could not get " + t.display + ":<br>" + wrap(String.valueOf(c.getMessage()))
                                    + "<br><br>Open the download page in the browser instead?</html>",
                            t.display, JOptionPane.OK_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE);
                    if (answer == JOptionPane.OK_OPTION) browse(t.page);
                }
            }
        };
        cancel.addActionListener(e -> worker.cancel(true));
        worker.execute();
        d.setVisible(true);
        return result[0];
    }

    // ---------------------------------------------------------------- the ESP32 and STM32 cores

    /** Espressif's board package index, which arduino-cli needs to find the ESP32 core. */
    static final String ESP32_INDEX = "https://espressif.github.io/arduino-esp32/package_esp32_index.json";

    /** STMicroelectronics' board package index, for the STM32 core (STMicroelectronics:stm32). */
    static final String STM32_INDEX = "https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json";

    /**
     * Installs (or updates) the ESP32 core with arduino-cli: its compilers, esptool and libraries, a few
     * hundred MB. The output is shown as it comes. Returns true when arduino-cli finished without an error.
     */
    static boolean installEsp32Core(Component parent, String cli, String indexUrl) {
        return installCore(parent, cli, indexUrl == null || indexUrl.isBlank() ? ESP32_INDEX : indexUrl.trim(),
                "esp32:esp32", "ESP32", "The compilers, esptool and libraries for the ESP32 boards");
    }

    /** The same for the STM32 core: its compiler, CMSIS and HAL, a few hundred MB. */
    static boolean installStm32Core(Component parent, String cli, String indexUrl) {
        return installCore(parent, cli, indexUrl == null || indexUrl.isBlank() ? STM32_INDEX : indexUrl.trim(),
                "STMicroelectronics:stm32", "STM32", "The compiler, CMSIS and HAL for the STM32 boards");
    }

    /** Installs an Arduino core from a board package index with arduino-cli, showing its output. */
    static boolean installCore(Component parent, String cli, String url, String core, String name, String what) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        String title = Text.t("Install the %s core", name);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JTextArea log = new JTextArea(16, 80);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JButton close = new JButton(Text.t("Cancel"));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        p.add(new JLabel("<html><b>" + name + " (" + core + ")</b><br>" + wrap(what + ", a few hundred MB, downloaded by"
                + " arduino-cli from " + url) + "</html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(10, 0));
        south.add(bar, BorderLayout.CENTER);
        south.add(close, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(parent);

        boolean[] ok = {false};
        Process[] running = {null};
        SwingWorker<Integer, String> worker = new SwingWorker<>() {
            @Override protected Integer doInBackground() throws Exception {
                List<List<String>> steps = List.of(
                        List.of(cli, "core", "update-index", "--additional-urls", url),
                        List.of(cli, "core", "install", core, "--additional-urls", url));
                for (List<String> cmd : steps) {
                    publish("$ " + String.join(" ", cmd) + "\n");
                    Process proc;
                    try {
                        proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    } catch (IOException e) {
                        publish(">> arduino-cli could not be started (" + e.getMessage() + ").\n"
                                + "   Click Get next to arduino-cli in Settings, or set its path, and try again.\n");
                        return -1;
                    }
                    running[0] = proc;
                    proc.getOutputStream().close();
                    try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) publish(line + "\n");
                    }
                    int rc = proc.waitFor();
                    if (isCancelled()) return -1;
                    if (rc != 0) {
                        publish(">> failed (exit code " + rc + ")\n");
                        return rc;
                    }
                }
                return 0;
            }

            @Override protected void process(List<String> chunks) {
                for (String s : chunks) log.append(s);
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override protected void done() {
                bar.setIndeterminate(false);
                close.setText(Text.t("Close"));
                if (isCancelled()) {
                    d.dispose();
                    return;
                }
                try {
                    ok[0] = get() == 0;
                } catch (Exception e) {
                    log.append(">> " + e.getMessage() + "\n");
                }
                bar.setValue(ok[0] ? bar.getMaximum() : 0);
                log.append(ok[0] ? ">> the " + name + " core is installed: the " + name + " boards build and upload now.\n" : "");
            }
        };
        close.addActionListener(e -> {
            if (!worker.isDone()) {
                worker.cancel(true);
                if (running[0] != null) running[0].destroyForcibly();
            }
            d.dispose();
        });
        worker.execute();
        d.setVisible(true);
        return ok[0];
    }

    // ---------------------------------------------------------------- the work

    private static final Pattern ASSET_URL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");

    /** The address of the newest file that matches the tool's pattern. */
    static String latest(Tool t) throws IOException {
        String api = System.getProperty("evia.toolchain.releases." + t.folder,
                "https://api.github.com/repos/" + t.repo + "/releases/latest");
        String json = text(api);
        Pattern want = Pattern.compile(t.asset());
        Matcher m = ASSET_URL.matcher(json);
        while (m.find()) {
            String url = m.group(1);
            String name = url.substring(url.lastIndexOf('/') + 1);
            if (want.matcher(name).matches()) return url;
        }
        throw new IOException("the latest release of " + t.repo + " has no file for this computer"
                + " (looking for " + t.asset() + ")");
    }

    interface Bytes {
        void report(long done, long total);
    }

    interface Stop {
        boolean cancelled();
    }

    private static String text(String url) throws IOException {
        if (!url.startsWith("http")) {                       /* a file, for the checks */
            try (InputStream in = URI.create(url).toURL().openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION);
        c.setRequestProperty("Accept", "application/vnd.github+json, */*");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException("the server answered " + code + " for " + url);
        return c;
    }

    static Path download(String url, Bytes progress, Stop stop) throws IOException {
        String name = url.substring(url.lastIndexOf('/') + 1);
        Path file = Files.createTempFile("evia-", "-" + name);
        if (!url.startsWith("http")) {                       /* a file, for the checks */
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            progress.report(Files.size(file), Files.size(file));
            return file;
        }
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(file)) {
            long total = c.getContentLengthLong(), done = 0;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (stop.cancelled()) throw new IOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                progress.report(done, total);
            }
        } finally {
            c.disconnect();
        }
        return file;
    }

    /** Unpacks a .zip here, and hands anything else (.tar.gz, .tgz, .tar.bz2) to the system's tar. */
    static void unpack(Path archive, Path into) throws IOException, InterruptedException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            unzip(archive, into);
            return;
        }
        if (name.endsWith(".exe")) throw new IOException("this release is an installer: run it yourself and then say where it is");
        Process tar = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", into.toString())
                .redirectErrorStream(true).start();
        String out = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (tar.waitFor() != 0) throw new IOException("tar could not unpack the file: " + out.trim());
    }

    private static void unzip(Path archive, Path into) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path to = into.resolve(e.getName()).normalize();
                if (!to.startsWith(into)) throw new IOException("the file contains a path outside the folder: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(to);
                    continue;
                }
                Files.createDirectories(to.getParent());
                Files.copy(zip, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String mb(long bytes) {
        return bytes < 1 << 20 ? (bytes + 1023) / 1024 + " KB" : String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String wrap(String s) {
        return "<div style='width:430px'>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</div>";
    }

    static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // no browser here: the address is in the dialog
        }
    }
}
