package evia.paradigma;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The Android app of a program: a project folder, the APK, and putting it on a phone.
 *
 * <p>The folder is an ordinary Android Studio project (Gradle, Kotlin, one activity), so it can be opened
 * there. EVIA Paradigma does not need Gradle to build it, though: it runs the SDK's own tools one after the
 * other, which takes seconds and downloads nothing more:
 * <ol>
 *   <li>aapt2 links the manifest against the platform's android.jar into the start of an APK;</li>
 *   <li>kotlinc compiles MainActivity.kt;</li>
 *   <li>R8 turns the classes, and the part of the Kotlin standard library they use, into Android's dex
 *       format (D8 with the whole library if R8 fails);</li>
 *   <li>the dex files go into the APK, zipalign aligns it, apksigner signs it with the debug key
 *       (~/.android/debug.keystore, the one Android Studio uses, made with the JDK's keytool if missing).</li>
 * </ol>
 * Upload is adb: install, then start the activity.
 *
 * <p>The SDK is Google's and may not be passed on, so {@link #installSdk} downloads its three pieces
 * (platform, build-tools, platform-tools) from Google, after the person has accepted Google's licence,
 * whose text comes from the same index. Acceptance is recorded the way sdkmanager records it, so Gradle
 * and Android Studio accept the SDK too.
 */
final class AndroidBuild {
    private AndroidBuild() {}

    /** Android 17: the platform the app is compiled against. */
    static final String PLATFORM = "android-37.0";
    static final String BUILD_TOOLS = "37.0.0";
    /** Android 7.0 and newer; the app targets Android 16. */
    static final int MIN_SDK = 24, TARGET_SDK = 36;
    /** For the Android Studio project: the Android Gradle plugin and Gradle it was written for. */
    static final String AGP = "9.5.1", GRADLE = "9.9.1";
    static final String REPOSITORY = "https://dl.google.com/android/repository/";

    interface Log {
        void line(String s);
    }

    // ================================================================ names

    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Set<String> KEYWORDS = Set.of("abstract", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when");

    /** An application id Android and both languages accept: two or more lower-case parts, no keywords. */
    static boolean validPackage(String p) {
        if (p == null || !PACKAGE.matcher(p).matches()) return false;
        for (String part : p.split("\\.")) if (KEYWORDS.contains(part)) return false;
        return true;
    }

    /** An application id made from a program's name: "My Counter" -> am.evia.mycounter. */
    static String packageFor(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) sb.append(c);
        }
        String last = sb.isEmpty() || !Character.isLetter(sb.charAt(0)) ? "program" + sb : sb.toString();
        String p = "am.evia." + last;
        return validPackage(p) ? p : "am.evia.program";
    }

    // ================================================================ the project folder

    /**
     * Writes the Android Studio project of a program into a folder: Gradle files, the manifest and
     * MainActivity.kt (the program). Files of an earlier write are replaced; nothing else is touched.
     * Returns MainActivity.kt.
     */
    static Path writeProject(Library lib, Target t, Path dir, String pkg, String name) throws IOException {
        if (!validPackage(pkg)) throw new IOException("'" + pkg + "' is not a valid application id: lower-case words"
                + " with dots, at least two, e.g. am.evia.counter");
        String code = JvmGenerator.generate(lib, t.withLang(Target.Lang.ANDROID), JvmGenerator.Dialect.ANDROID, pkg, name).code();
        return writeProject(code, dir, pkg, name);
    }

    /** The same, from the code of MainActivity.kt as it is (the code window may have changed it). */
    static Path writeProject(String code, Path dir, String pkg, String name) throws IOException {
        if (!validPackage(pkg)) throw new IOException("'" + pkg + "' is not a valid application id");
        Path main = dir.resolve("app/src/main");
        Path src = main.resolve("kotlin").resolve(pkg.replace('.', '/')).resolve("MainActivity.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, code, StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AndroidManifest.xml"), manifest(null, name), StandardCharsets.UTF_8);
        String project = name.replaceAll("[^A-Za-z0-9 _-]", "").strip();
        write(dir.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "%s"
                include(":app")
                """.formatted(project.isEmpty() ? "evia-app" : project));
        write(dir.resolve("build.gradle.kts"), """
                // Android Gradle plugin %s (Kotlin built in). EVIA Paradigma builds the app without Gradle;
                // these files are for Android Studio, or: gradle assembleDebug (Gradle %s).
                plugins {
                    id("com.android.application") version "%s" apply false
                }
                """.formatted(AGP, GRADLE, AGP));
        write(dir.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=false\n");
        write(dir.resolve("app/build.gradle.kts"), """
                plugins {
                    id("com.android.application")
                }

                android {
                    namespace = "%s"
                    compileSdk = %d
                    defaultConfig {
                        applicationId = "%s"
                        minSdk = %d
                        targetSdk = %d
                        versionCode = 1
                        versionName = "1.0"
                    }
                }
                """.formatted(pkg, TARGET_SDK, pkg, MIN_SDK, TARGET_SDK));
        write(dir.resolve(".gitignore"), "build/\n.gradle/\nlocal.properties\n*.apk\n");
        return src;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    /** The manifest: one activity that keeps running when the phone is turned. Gradle adds the package itself. */
    static String manifest(String pkg, String name) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""
                + (pkg != null ? "\n    package=\"" + pkg + "\" android:versionCode=\"1\" android:versionName=\"1.0\"" : "") + ">\n"
                + (pkg != null ? "    <uses-sdk android:minSdkVersion=\"" + MIN_SDK + "\" android:targetSdkVersion=\"" + TARGET_SDK + "\" />\n" : "")
                + "    <application android:label=\"" + xml(name) + "\" android:allowBackup=\"true\"\n"
                + "        android:theme=\"@android:style/Theme.Material.Light.DarkActionBar\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\"\n"
                + "            android:windowSoftInputMode=\"adjustResize\"\n"
                + "            android:configChanges=\"orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density\">\n"
                + "            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                + "            </intent-filter>\n"
                + "        </activity>\n"
                + "    </application>\n"
                + "</manifest>\n";
    }

    private static String xml(String s) {
        String r = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        // aapt reads @ and ? at the start as references and \ ' as escapes
        r = r.replace("\\", "\\\\").replace("'", "\\'");
        return r.startsWith("@") || r.startsWith("?") ? "\\" + r : r;
    }

    // ================================================================ the SDK's tools

    /** The platform's android.jar: the pinned one, else the newest installed. Null when there is none. */
    static Path platformJar(Path sdk) {
        Path pinned = sdk.resolve("platforms").resolve(PLATFORM).resolve("android.jar");
        if (Files.isRegularFile(pinned)) return pinned;
        return newest(sdk.resolve("platforms"), d -> Files.isRegularFile(d.resolve("android.jar")), "android.jar");
    }

    /** The build-tools folder (aapt2, zipalign, lib/d8.jar, lib/apksigner.jar): pinned, else the newest. */
    static Path buildTools(Path sdk) {
        Path pinned = sdk.resolve("build-tools").resolve(BUILD_TOOLS);
        if (Files.isRegularFile(pinned.resolve("lib/d8.jar"))) return pinned;
        return newest(sdk.resolve("build-tools"), d -> Files.isRegularFile(d.resolve("lib/d8.jar")), null);
    }

    private static Path newest(Path dir, Predicate<Path> ok, String child) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            Path best = s.filter(ok).max(Comparator.comparing(p -> versionKey(p.getFileName().toString()))).orElse(null);
            return best == null ? null : child == null ? best : best.resolve(child);
        } catch (IOException e) {
            return null;
        }
    }

    /** The package line of MainActivity.kt, or null. */
    static String packageOf(String code) {
        java.util.regex.Matcher m = Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+)").matcher(code);
        return m.find() ? m.group(1) : null;
    }

    /** "android-36.1" or "36.1.0" as a comparable string of zero-padded numbers. */
    private static String versionKey(String name) {
        StringBuilder sb = new StringBuilder();
        for (String n : name.replaceAll("[^0-9.]", "").split("\\.")) {
            if (!n.isEmpty()) sb.append(String.format("%06d", Long.parseLong(n.length() > 6 ? n.substring(0, 6) : n)));
        }
        return sb.toString();
    }

    static Path adb(Path sdk) {
        Path p = sdk.resolve("platform-tools").resolve(ExternalEditor.WINDOWS ? "adb.exe" : "adb");
        if (Files.isRegularFile(p)) return p;
        Path onPath = Settings.onPath(ExternalEditor.WINDOWS ? "adb.exe" : "adb");
        return onPath;
    }

    private static Path exe(Path dir, String name) {
        return dir.resolve(ExternalEditor.WINDOWS ? name + ".exe" : name);
    }

    /** What is missing to build an app, one line each; empty when everything is there. */
    static List<String> missing() {
        List<String> r = new ArrayList<>();
        Path sdk = Settings.androidSdk();
        if (platformJar(sdk) == null || buildTools(sdk) == null) {
            r.add("the Android SDK (platform and build-tools) in " + sdk + ": Settings > Android > Get Android SDK...");
        }
        Path jdk = Settings.jdkHome();
        if (jdk == null || !Files.isRegularFile(Settings.jdkProgram(jdk, "java"))) {
            r.add("a JDK (Java " + Settings.MIN_JDK + " or newer): Settings > Java and Kotlin");
        }
        if (Settings.kotlinStdlib() == null) r.add("kotlinc, the Kotlin compiler: Settings > Java and Kotlin > Get");
        return r;
    }

    // ================================================================ building

    /**
     * Builds the debug APK of a project folder written by {@link #writeProject} and returns it
     * (build/&lt;name&gt;-debug.apk inside the folder).
     */
    static Path build(Path dir, String pkg, String name, Log log) throws IOException, InterruptedException {
        List<String> need = missing();
        if (!need.isEmpty()) throw new IOException("To build an Android app, EVIA Paradigma needs " + String.join("; and ", need));
        Path sdk = Settings.androidSdk();
        Path jar = platformJar(sdk), bt = buildTools(sdk);
        Path jdk = Settings.jdkHome();
        String java = Settings.jdkProgram(jdk, "java").toString();
        Path stdlib = Settings.kotlinStdlib();
        Path src = dir.resolve("app/src/main/kotlin").resolve(pkg.replace('.', '/')).resolve("MainActivity.kt");
        if (!Files.isRegularFile(src)) throw new IOException("no " + src + ": write the project first");

        Path out = dir.resolve("build");
        Path work = out.resolve("evia");
        Packages.deleteTree(work);
        Files.createDirectories(work);
        log.line("Android SDK: " + sdk + " (platform " + jar.getParent().getFileName() + ", build-tools " + bt.getFileName() + ")");

        // 1. the manifest, linked against the platform
        Path manifest = work.resolve("AndroidManifest.xml");
        Files.writeString(manifest, manifest(pkg, name), StandardCharsets.UTF_8);
        Path base = work.resolve("base.apk");
        log.line("aapt2: linking the manifest");
        run(log, null, exe(bt, "aapt2").toString(), "link", "-o", base.toString(), "-I", jar.toString(),
                "--manifest", manifest.toString(), "--min-sdk-version", String.valueOf(MIN_SDK),
                "--target-sdk-version", String.valueOf(TARGET_SDK), "--version-code", "1", "--version-name", "1.0");

        // 2. Kotlin
        Path classes = work.resolve("classes.jar");
        log.line("kotlinc: compiling MainActivity.kt");
        run(log, jdk, Settings.kotlinc(), src.toString(), "-classpath", jar.toString(), "-no-jdk", "-no-reflect",
                "-jvm-target", "1.8", "-nowarn", "-d", classes.toString());

        // 3. dex: R8 keeps only what the app uses of the Kotlin library (an APK of tens of KB, not megabytes);
        //    plain D8 if R8 fails for any reason
        Path dex = work.resolve("dex");
        Files.createDirectories(dex);
        Path rules = work.resolve("rules.pro");
        // the Kotlin library mentions a few JDK classes Android lacks (java.lang.ClassValue ...): never used here
        Files.writeString(rules, "-keep class " + pkg + ".MainActivity { <init>(); }\n-dontobfuscate\n"
                + "-dontwarn org.jetbrains.annotations.**\n-ignorewarnings\n", StandardCharsets.UTF_8);
        String d8jar = bt.resolve("lib/d8.jar").toString();
        try {
            log.line("r8: converting to Android's dex format, leaving out the unused parts of the Kotlin library");
            run(log, null, java, "-cp", d8jar, "com.android.tools.r8.R8", "--release", "--min-api", String.valueOf(MIN_SDK),
                    "--lib", jar.toString(), "--pg-conf", rules.toString(), "--output", dex.toString(),
                    classes.toString(), stdlib.toString());
        } catch (IOException e) {
            log.line("r8 did not work (" + e.getMessage() + "): d8 instead, with the whole Kotlin library");
            Packages.deleteTree(dex);
            Files.createDirectories(dex);
            run(log, null, java, "-cp", d8jar, "com.android.tools.r8.D8", "--release",
                    "--min-api", String.valueOf(MIN_SDK), "--lib", jar.toString(), "--output", dex.toString(),
                    classes.toString(), stdlib.toString());
        }

        // 4. the APK: the linked manifest and the dex files, aligned and signed
        Path unsigned = work.resolve("unsigned.apk");
        addDex(base, dex, unsigned);
        Path aligned = work.resolve("aligned.apk");
        log.line("zipalign, apksigner: aligning and signing with the debug key");
        run(log, null, exe(bt, "zipalign").toString(), "-f", "-p", "4", unsigned.toString(), aligned.toString());
        Path ks = debugKeystore(jdk, log);
        Path apk = out.resolve(pkg.substring(pkg.lastIndexOf('.') + 1) + "-debug.apk");
        run(log, null, java, "-jar", bt.resolve("lib/apksigner.jar").toString(), "sign", "--ks", ks.toString(),
                "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
                "--out", apk.toString(), aligned.toString());
        Files.deleteIfExists(apk.resolveSibling(apk.getFileName() + ".idsig"));
        log.line("Built " + apk + " (" + Packages.mb(Files.size(apk)) + ")");
        return apk;
    }

    /** Copies the APK that aapt2 made and adds classes.dex (and classes2.dex ...). */
    private static void addDex(Path base, Path dexDir, Path out) throws IOException {
        try (ZipFile in = new ZipFile(base.toFile()); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (ZipEntry e : Collections.list(in.entries())) {
                ZipEntry n = new ZipEntry(e.getName());
                n.setMethod(e.getMethod());
                if (e.getMethod() == ZipEntry.STORED) {
                    n.setSize(e.getSize());
                    n.setCompressedSize(e.getSize());
                    n.setCrc(e.getCrc());
                }
                zip.putNextEntry(n);
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(zip);
                }
                zip.closeEntry();
            }
            try (Stream<Path> s = Files.list(dexDir)) {
                for (Path d : s.filter(p -> p.getFileName().toString().endsWith(".dex")).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(d.getFileName().toString()));
                    Files.copy(d, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    /** ~/.android/debug.keystore, as Android Studio makes it; made with keytool when it is not there. */
    private static Path debugKeystore(Path jdk, Log log) throws IOException, InterruptedException {
        Path ks = Path.of(System.getProperty("user.home"), ".android", "debug.keystore");
        if (Files.isRegularFile(ks)) return ks;
        Files.createDirectories(ks.getParent());
        log.line("keytool: making the debug key " + ks);
        run(log, null, Settings.jdkProgram(jdk, "keytool").toString(), "-genkeypair", "-keystore", ks.toString(),
                "-storetype", "PKCS12", "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US");
        return ks;
    }

    /** Runs a tool, passing its output to the log; an IOException when it fails. */
    static String run(Log log, Path javaHome, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.toString());
            pb.environment().put("PATH", javaHome.resolve("bin") + java.io.File.pathSeparator + pb.environment().getOrDefault("PATH", ""));
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException(cmd[0] + " could not be started: " + e.getMessage());
        }
        p.getOutputStream().close();
        StringBuilder all = new StringBuilder();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("warning: unable to find kotlin-stdlib")) continue;
                all.append(line).append('\n');
                log.line("  " + line);
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            String tool = Path.of(cmd[0]).getFileName().toString();
            throw new IOException(tool + " failed (exit code " + rc + ")");
        }
        return all.toString();
    }

    // ================================================================ the phone

    /** Serial numbers of the phones and emulators adb sees, with their model when adb says it. */
    static List<String[]> devices() {
        Path adb = adb(Settings.androidSdk());
        List<String[]> r = new ArrayList<>();
        if (adb == null) return r;
        try {
            String out = run(s -> { }, null, adb.toString(), "devices", "-l");
            for (String l : out.split("\n")) {
                String[] f = l.trim().split("\\s+");
                if (f.length < 2 || l.startsWith("List of")) continue;
                String model = "";
                for (String x : f) if (x.startsWith("model:")) model = x.substring(6).replace('_', ' ');
                r.add(new String[]{f[0], f[1], model});   // serial, state (device, unauthorized, offline), model
            }
        } catch (Exception ignored) {
            // no adb or no server: no phones
        }
        return r;
    }

    /** Installs the APK on the phone (the only one, or serial) and starts it. */
    static void install(Path apk, String pkg, String serial, Log log) throws IOException, InterruptedException {
        Path adb = adb(Settings.androidSdk());
        if (adb == null) throw new IOException("adb was not found: Settings > Android > Get Android SDK... installs it");
        List<String> base = new ArrayList<>(List.of(adb.toString()));
        if (serial != null && !serial.isBlank()) base.addAll(List.of("-s", serial));
        else {
            List<String[]> d = devices();
            long ready = d.stream().filter(x -> x[1].equals("device")).count();
            if (ready == 0) {
                boolean unauthorized = d.stream().anyMatch(x -> x[1].equals("unauthorized"));
                throw new IOException(unauthorized
                        ? "the phone has not allowed USB debugging from this computer yet: unlock it and tap Allow"
                        : "no phone found: connect one by USB with USB debugging on (Settings > About phone: tap Build"
                        + " number seven times, then Developer options > USB debugging), or start an emulator");
            }
            if (ready > 1) throw new IOException("more than one phone is connected: choose one in Settings > Android");
        }
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of("install", "-r", apk.toString()));
        log.line("adb: installing " + apk.getFileName());
        run(log, null, cmd.toArray(String[]::new));
        cmd = new ArrayList<>(base);
        cmd.addAll(List.of("shell", "am", "start", "-n", pkg + "/.MainActivity"));
        log.line("adb: starting the app");
        run(log, null, cmd.toArray(String[]::new));
    }

    // ================================================================ getting the SDK from Google

    /** A piece of the SDK to download: its path in the SDK, the file, its SHA-1 and size. */
    record Piece(String path, String folder, String url, String sha1, long size, String license) {}

    /** What installSdk will fetch for this computer, and the licence text to accept. */
    record Plan(List<Piece> pieces, Map<String, String> licenses) {
        long bytes() {
            return pieces.stream().mapToLong(Piece::size).sum();
        }
    }

    /** Reads Google's index and picks the pinned platform and build-tools and the current platform-tools. */
    static Plan plan() throws IOException {
        byte[] index = fetch(REPOSITORY + "repository2-3.xml");
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(index));
        } catch (Exception e) {
            throw new IOException("Google's SDK index could not be read: " + e.getMessage());
        }
        String os = ExternalEditor.WINDOWS ? "windows"
                : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "macosx" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch64") ? "aarch64" : "x64";
        Map<String, String> want = new LinkedHashMap<>();
        want.put("platforms;" + PLATFORM, "platforms/" + PLATFORM);
        want.put("build-tools;" + BUILD_TOOLS, "build-tools/" + BUILD_TOOLS);
        want.put("platform-tools", "platform-tools");
        List<Piece> pieces = new ArrayList<>();
        Set<String> licenseIds = new LinkedHashSet<>();
        NodeList pkgs = doc.getElementsByTagName("remotePackage");
        for (Map.Entry<String, String> w : want.entrySet()) {
            Piece found = null;
            for (int i = 0; i < pkgs.getLength() && found == null; i++) {
                Element p = (Element) pkgs.item(i);
                if (!p.getAttribute("path").equals(w.getKey())) continue;
                Element ch = first(p, "channelRef");
                if (ch != null && !ch.getAttribute("ref").equals("channel-0")) continue;   // stable only
                Element lic = first(p, "uses-license");
                NodeList archives = p.getElementsByTagName("archive");
                for (int a = 0; a < archives.getLength() && found == null; a++) {
                    Element ar = (Element) archives.item(a);
                    String hostOs = text(ar, "host-os"), hostArch = text(ar, "host-arch");
                    if (hostOs != null && !hostOs.equals(os)) continue;
                    if (hostArch != null && !hostArch.equals(arch)) continue;
                    Element c = first(ar, "complete");
                    String url = text(c, "url");
                    found = new Piece(w.getKey(), w.getValue(), url.startsWith("http") ? url : REPOSITORY + url,
                            text(c, "checksum"), Long.parseLong(text(c, "size")), lic == null ? null : lic.getAttribute("ref"));
                }
            }
            if (found == null) throw new IOException("Google's SDK index has no " + w.getKey() + " for this computer");
            pieces.add(found);
            if (found.license() != null) licenseIds.add(found.license());
        }
        Map<String, String> licenses = new LinkedHashMap<>();
        NodeList all = doc.getElementsByTagName("license");
        for (int i = 0; i < all.getLength(); i++) {
            Element l = (Element) all.item(i);
            if (licenseIds.contains(l.getAttribute("id"))) licenses.put(l.getAttribute("id"), l.getTextContent());
        }
        return new Plan(pieces, licenses);
    }

    /**
     * Downloads and unpacks the pieces of a plan into the SDK folder, checking each against its SHA-1, and
     * records that the licences were accepted. Call it only after the person accepted plan.licenses().
     */
    static void installSdk(Plan plan, Path sdk, Toolchains.Bytes progress, Toolchains.Stop stop, Log log)
            throws IOException, InterruptedException {
        Files.createDirectories(sdk);
        long total = plan.bytes(), before = 0;
        for (Piece p : plan.pieces()) {
            Path target = sdk.resolve(p.folder());
            if (Files.isDirectory(target) && Files.exists(target.resolve("source.properties"))) {
                log.line(p.path() + ": already there");
                before += p.size();
                continue;
            }
            log.line("Downloading " + p.path() + " (" + Packages.mb(p.size()) + ")");
            long done = before;
            Path file = Toolchains.download(p.url(), (d, t) -> progress.report(done + d, total), stop);
            try {
                String sha = hex(digest("SHA-1", file));
                if (!sha.equalsIgnoreCase(p.sha1())) throw new IOException(p.path() + ": the download is damaged (SHA-1 " + sha + ")");
                log.line("Unpacking into " + target);
                Packages.deleteTree(target);
                unzipStripped(file, target);
            } finally {
                Files.deleteIfExists(file);
            }
            before += p.size();
        }
        Path licenses = sdk.resolve("licenses");
        Files.createDirectories(licenses);
        for (Map.Entry<String, String> l : plan.licenses().entrySet()) {
            // sdkmanager keeps the SHA-1 of each accepted licence text; both spellings, trimmed or not
            Set<String> hashes = new LinkedHashSet<>();
            Path f = licenses.resolve(l.getKey());
            if (Files.isRegularFile(f)) for (String h : Files.readAllLines(f)) if (!h.isBlank()) hashes.add(h.strip());
            hashes.add(hex(digest("SHA-1", l.getValue().getBytes(StandardCharsets.UTF_8))));
            hashes.add(hex(digest("SHA-1", l.getValue().strip().getBytes(StandardCharsets.UTF_8))));
            Files.writeString(f, "\n" + String.join("\n", hashes), StandardCharsets.UTF_8);
        }
        log.line("The Android SDK is ready in " + sdk);
    }

    /** Unzips an SDK piece without its single top folder (android-17/, platform-tools/ ...), keeping programs runnable. */
    private static void unzipStripped(Path zip, Path into) throws IOException {
        Files.createDirectories(into);
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0 || slash == name.length() - 1) continue;
                Path to = into.resolve(name.substring(slash + 1)).normalize();
                if (!to.startsWith(into)) throw new IOException("the file contains a path outside the folder: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(to);
                    continue;
                }
                Files.createDirectories(to.getParent());
                Files.copy(z, to, StandardCopyOption.REPLACE_EXISTING);
                if (!ExternalEditor.WINDOWS && runnable(to, into)) {
                    try {
                        Set<PosixFilePermission> perm = new HashSet<>(Files.getPosixFilePermissions(to));
                        perm.addAll(Set.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                        Files.setPosixFilePermissions(to, perm);
                    } catch (UnsupportedOperationException ignored) {
                        // not a POSIX file system
                    }
                }
            }
        }
    }

    /** Java's zip reader does not see the file modes: the SDK's programs are the files without an extension at its top, and .so libraries. */
    private static boolean runnable(Path file, Path root) {
        String n = file.getFileName().toString();
        boolean top = file.getParent().equals(root) || file.getParent().getFileName().toString().equals("bin");
        return top && (!n.contains(".") || n.endsWith(".sh")) || n.endsWith(".so") || n.endsWith(".dylib");
    }

    private static Element first(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static String text(Element e, String tag) {
        Element c = first(e, tag);
        return c == null ? null : c.getTextContent().strip();
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() / 100 != 2) throw new IOException("the server answered " + c.getResponseCode() + " for " + url);
            return in.readAllBytes();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] digest(String alg, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(alg);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] digest(String alg, byte[] data) {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
