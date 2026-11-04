package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sets up a self-contained compiler for Windows, downloaded from GitHub into the user's application
 * data folder (no admin rights, no PATH changes, no Visual Studio):
 * <ul>
 *   <li>Clang: the latest llvm-mingw release (Clang + libc++ + Windows headers);</li>
 *   <li>GCC: the latest WinLibs release (GNU GCC + MinGW-w64, UCRT).</li>
 * </ul>
 * Programs are linked with -static so they run on any Windows PC.
 */
final class ToolchainInstaller {

    /** The two toolchains EVIA Paradigma can install. */
    enum Kind {
        CLANG("Clang", "llvm-mingw", "clang++.exe",
                "https://api.github.com/repos/mstorsjo/llvm-mingw/releases/latest",
                "llvm-mingw-[^\"]*-ucrt-%ARCH%\\.zip", "https://github.com/mstorsjo/llvm-mingw/releases",
                "Self-contained Clang with its own C++ library."),
        GCC("GCC", "winlibs-gcc", "g++.exe",
                "https://api.github.com/repos/brechtsanders/winlibs_mingw/releases/latest",
                "winlibs-x86_64-posix-seh-gcc-[0-9.]+-mingw-w64ucrt-[0-9.]+-r[0-9]+\\.zip", "https://winlibs.com",
                "GNU GCC with MinGW-w64 (WinLibs build, UCRT).");

        final String display, folder, exe, releases, asset, page, about;

        Kind(String display, String folder, String exe, String releases, String asset, String page, String about) {
            this.display = display;
            this.folder = folder;
            this.exe = exe;
            this.releases = System.getProperty("evia.toolchain.releases." + folder, releases);   // property: for tests
            this.asset = asset;
            this.page = page;
            this.about = about;
        }
    }

    private ToolchainInstaller() {}

    /** Progress callback for download and unpacking. */
    private interface Progress {
        void report(String text, long done, long total);
        boolean cancelled();
    }

    static boolean supported() {
        return ExternalEditor.WINDOWS;
    }

    /** Where EVIA Paradigma keeps its own Clang. */
    static Path home() {
        return home(Kind.CLANG);
    }

    static Path home(Kind k) {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null) base = System.getProperty("user.home");
        return Path.of(base, "EVIA Paradigma", k.folder);
    }

    static Path managedClang() {
        return managed(Kind.CLANG);
    }

    static Path managed(Kind k) {
        Path p = home(k).resolve("bin").resolve(k.exe);
        return Files.isRegularFile(p) ? p : null;
    }

    /** Compile command for the managed Clang, or null if it isn't installed. */
    static String managedCommand() {
        return managedCommand(Kind.CLANG);
    }

    static String managedCommand(Kind k) {
        Path exe = managed(k);
        if (exe == null) return null;
        return "\"" + exe + "\" -std=c++20 -O2 -Wall -Wextra -static -o {exe} {src}";
    }

    /** True if compiler output means the C++ standard library or the compiler itself is missing. */
    static boolean looksLikeMissingToolchain(String compilerOutput) {
        return compilerOutput.matches("(?s).*fatal error: '[a-z_]+' file not found.*")
                || compilerOutput.contains("Cannot run program");
    }

    /**
     * Downloads and unpacks llvm-mingw with a progress dialog, then makes it the compiler.
     * Returns true when the toolchain is installed and a test compile succeeded.
     */
    static boolean install(Component parent) {
        return install(parent, Kind.CLANG);
    }

    static boolean install(Component parent, Kind kind) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        String title = "Install " + kind.display + " toolchain";
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel status = new JLabel("Looking up the latest " + kind.folder + " release...");
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        JButton cancel = new JButton("Cancel");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        p.add(new JLabel("<html><b>" + kind.display + " for Windows (" + kind.folder + ")</b><br>"
                + kind.about + " No Visual Studio needed.<br>"
                + "Installed to: " + home(kind) + "</html>"), BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.add(status, BorderLayout.NORTH);
        mid.add(bar, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(560, 210);
        d.setLocationRelativeTo(parent);

        boolean[] ok = {false};
        SwingWorker<String, Object[]> worker = new SwingWorker<>() {
            final Progress progress = new Progress() {
                @Override public void report(String text, long done, long total) {
                    publish(new Object[]{text, total > 0 ? (int) (done * 1000 / total) : -1});
                }
                @Override public boolean cancelled() {
                    return isCancelled();
                }
            };

            @Override
            protected String doInBackground() throws Exception {
                // 1. find the release asset for this machine
                String json = readString(kind.releases);
                Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                String arch = System.getProperty("os.arch").toLowerCase().contains("aarch64") && kind == Kind.CLANG ? "aarch64" : "x86_64";
                Matcher url = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]*/" + kind.asset.replace("%ARCH%", arch) + ")\"")
                        .matcher(json);
                if (!url.find()) throw new IOException("no Windows " + arch + " download found in the latest " + kind.folder + " release");
                String release = tag.find() ? tag.group(1) : "latest";

                // 2. download with progress
                Path tmpDir = Files.createTempDirectory("evia-" + kind.folder);
                Path zip = tmpDir.resolve(kind.folder + ".zip");
                publish(new Object[]{"Downloading " + kind.folder + " " + release + "...", -1});
                download(url.group(1), zip, progress);

                // 3. unpack next to the final folder, then swap it in
                Path target = home(kind);
                Path staging = target.resolveSibling(kind.folder + ".new");
                deleteTree(staging);
                publish(new Object[]{"Unpacking...", -1});
                unzip(zip, staging, progress);
                Path root;
                try (Stream<Path> s = Files.list(staging)) {
                    root = s.filter(Files::isDirectory).findFirst().orElse(staging);   // zip has one top folder
                }
                deleteTree(target);
                Files.createDirectories(target.getParent());
                Files.move(root, target, StandardCopyOption.ATOMIC_MOVE);
                deleteTree(staging);
                deleteTree(tmpDir);
                if (!ExternalEditor.WINDOWS) {           // zip doesn't keep the Unix execute bit
                    try (Stream<Path> s = Files.list(target.resolve("bin"))) {
                        s.forEach(f -> f.toFile().setExecutable(true));
                    }
                }

                // 4. check that it really compiles the generated C++
                publish(new Object[]{"Testing the compiler...", -1});
                String problem = Settings.testCompile(managedCommand(kind));
                if (problem != null) {
                    deleteTree(target);                  // never leave a broken toolchain in use
                    throw new IOException("test compile failed:\n" + problem);
                }
                return release;
            }

            @Override
            protected void process(java.util.List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                status.setText((String) last[0]);
                int permille = (Integer) last[1];
                bar.setIndeterminate(permille < 0);
                if (permille >= 0) bar.setValue(permille);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    d.dispose();
                    return;
                }
                try {
                    String release = get();
                    Settings.setCompilerCommand(managedCommand(kind));
                    ok[0] = true;
                    d.dispose();
                    Settings.CompilerInfo ci = Settings.detectCompiler(managedCommand(kind));
                    JOptionPane.showMessageDialog(parent, kind.display + " is ready: " + kind.folder + " " + release
                                    + (ci != null ? " (" + ci.display() + ")" : "")
                                    + "\nEVIA Paradigma now uses it to compile generated C and C++.",
                            title, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    d.dispose();
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(parent, "Could not install the toolchain:\n" + c.getMessage()
                                    + "\n\nYou can also install it by hand: download the Windows x86_64 zip from\n"
                                    + kind.page + " and unpack it to\n" + home(kind),
                            title, JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        cancel.addActionListener(e -> worker.cancel(true));
        worker.execute();
        d.setVisible(true);
        return ok[0];
    }

    // ================================================================ download / unzip

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + url);
        return c;
    }

    private static String readString(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void download(String url, Path to, Progress w) throws IOException {
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                if (w.cancelled()) throw new InterruptedIOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > (1 << 20)) {
                    lastReport = done;
                    w.report(String.format("Downloading... %d of %d MB", done >> 20, Math.max(total, 0) >> 20), done, total);
                }
            }
        }
    }

    private static void unzip(Path zip, Path dir, Progress w) throws IOException {
        long total = Files.size(zip);
        Path base = dir.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zip);
             CountingInputStream counted = new CountingInputStream(raw);
             ZipInputStream z = new ZipInputStream(new BufferedInputStream(counted))) {
            ZipEntry e;
            int files = 0;
            while ((e = z.getNextEntry()) != null) {
                if (w.cancelled()) throw new InterruptedIOException("cancelled");
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) throw new IOException("bad zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                    if (++files % 200 == 0) w.report("Unpacking... " + files + " files", counted.count, total);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
