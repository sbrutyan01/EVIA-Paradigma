package evia.paradigma;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Custom Linux images for open boards (BeagleBone Black, Raspberry Pi 5) with Yocto, Buildroot or OpenWrt.
 *
 * <p>An image lives in a folder of its own, the workspace: {@code evia-linux.json} (the choices),
 * {@code build.sh} (every step, written from the choices, and runnable by hand), the root password in
 * {@code secrets/root-password} (readable only by the owner), and the sources and the output of the
 * build system. {@code build.sh fetch|configure|build|menuconfig|images|all}: each step can run again,
 * and {@code all} (what Build runs) does the three first ones.
 *
 * <p>The versions are pinned: a workspace builds the same image next year.
 */
final class LinuxImages {
    private LinuxImages() {}

    static final String BUILDROOT_VERSION = "2026.02.3";      // the long-term-support series of 2026
    static final String YOCTO_BRANCH = "scarthgap";            // Yocto 5.0, long-term support until April 2028
    static final String OPENWRT_VERSION = "25.12.6";

    /** The build systems. */
    enum Sys {
        BUILDROOT("buildroot", "Buildroot " + BUILDROOT_VERSION,
                "Small and simple: one make builds the toolchain, kernel, BusyBox and the root file system. About 1 hour, 15 GB."),
        YOCTO("yocto", "Yocto " + YOCTO_BRANCH + " (5.0 LTS)",
                "A full distribution with packages, the industry standard. The first build takes hours and needs about 90 GB."),
        OPENWRT("openwrt", "OpenWrt " + OPENWRT_VERSION + " (Image Builder)",
                "A router and network OS with a web interface (LuCI). Prebuilt packages, so an image takes minutes. x86-64 Linux."),
        OPENWRT_SOURCE("openwrt-source", "OpenWrt " + OPENWRT_VERSION + " (from source)",
                "OpenWrt compiled from source: any package and kernel option (menuconfig). About 2 hours, 30 GB.");

        final String key, display, note;

        Sys(String key, String display, String note) {
            this.key = key;
            this.display = display;
            this.note = note;
        }

        boolean openwrt() {
            return this == OPENWRT || this == OPENWRT_SOURCE;
        }

        /** Disk space a first build needs, in GB. */
        int diskGb() {
            return switch (this) {
                case BUILDROOT -> 15;
                case YOCTO -> 90;
                case OPENWRT -> 3;
                case OPENWRT_SOURCE -> 30;
            };
        }

        static Sys of(String s) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            for (Sys x : values()) if (x.key.equals(k) || x.name().equalsIgnoreCase(k)) return x;
            if (k.equals("openwrt-ib") || k.equals("imagebuilder")) return OPENWRT;
            throw new IllegalArgumentException("unknown build system " + s + " (buildroot, yocto, openwrt, openwrt-source)");
        }

        @Override public String toString() {
            return display;
        }
    }

    /** The boards, with what each build system calls them. */
    enum Brd {
        BEAGLEBONE_BLACK("bbb", "BeagleBone Black (AM335x, Cortex-A8)",
                "beaglebone_defconfig", "beaglebone-yocto", null, "omap", "generic", "ti_am335x-bone-black",
                "Hold the S2 (boot) button while powering on to boot from the SD card instead of the eMMC."),
        RASPBERRY_PI_5("rpi5", "Raspberry Pi 5 (BCM2712, Cortex-A76)",
                "raspberrypi5_defconfig", "raspberrypi5", "meta-raspberrypi", "bcm27xx", "bcm2712", "rpi-5",
                "The serial console is the 3-pin UART connector between the HDMI ports (115200 baud, 3.3 V).");

        final String key, display, buildrootDefconfig, yoctoMachine, yoctoLayer, owrtTarget, owrtSubtarget, owrtProfile, bootNote;

        Brd(String key, String display, String buildrootDefconfig, String yoctoMachine, String yoctoLayer,
            String owrtTarget, String owrtSubtarget, String owrtProfile, String bootNote) {
            this.key = key;
            this.display = display;
            this.buildrootDefconfig = buildrootDefconfig;
            this.yoctoMachine = yoctoMachine;
            this.yoctoLayer = yoctoLayer;
            this.owrtTarget = owrtTarget;
            this.owrtSubtarget = owrtSubtarget;
            this.owrtProfile = owrtProfile;
            this.bootNote = bootNote;
        }

        static Brd of(String s) {
            String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Brd b : values()) if (b.key.equals(k) || b.name().equalsIgnoreCase(s.trim())) return b;
            if (k.contains("beagle")) return BEAGLEBONE_BLACK;
            if (k.equals("rpi-5") || k.equals("raspberrypi5") || k.equals("pi5")) return RASPBERRY_PI_5;
            throw new IllegalArgumentException("unknown board " + s + " (bbb, rpi5)");
        }

        @Override public String toString() {
            return display;
        }
    }

    /** The image to build: everything a workspace remembers, except the root password. */
    record Config(Sys sys, Brd board, String hostname, boolean ssh, boolean python, boolean web,
                  boolean fullImage, String packages) {

        static Config defaults(Sys s, Brd b) {
            return new Config(s, b, "evia-" + b.key, true, false, s.openwrt(), true, "");
        }

        /** The extra packages, one name each. */
        List<String> packageList() {
            List<String> l = new ArrayList<>();
            for (String p : packages.trim().split("[\\s,]+")) if (!p.isEmpty()) l.add(p);
            return l;
        }
    }

    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._-]*");

    /** What is wrong with the choices, or null when they can be built. */
    static String problem(Config c, Path workspace) {
        if (!HOSTNAME.matcher(c.hostname()).matches()) return "The host name may have letters, digits and '-' (up to 63).";
        for (String p : c.packageList()) if (!PACKAGE.matcher(p).matches()) return "Not a package name: " + p;
        if (workspace == null) return "Choose a folder for the image.";
        String w = workspace.toAbsolutePath().toString();
        // Buildroot, Yocto and OpenWrt all refuse (or break in) paths with spaces and other shell characters
        if (!w.matches("[A-Za-z0-9/._+-]+")) return "The folder path may have only letters, digits and / . _ + - (no spaces): " + w;
        return null;
    }

    // ================================================================ the workspace

    static final String CONFIG_FILE = "evia-linux.json";

    static boolean isWorkspace(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(CONFIG_FILE));
    }

    /** Writes evia-linux.json, build.sh and (when {@code password} is not null) the root password. */
    static void write(Path ws, Config c, char[] password) throws IOException {
        String p = problem(c, ws);
        if (p != null) throw new IOException(p);
        Files.createDirectories(ws);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "EVIA Paradigma " + App.VERSION);
        m.put("system", c.sys().key);
        m.put("board", c.board().key);
        m.put("hostname", c.hostname());
        m.put("ssh", c.ssh());
        m.put("python", c.python());
        m.put("web", c.web());
        m.put("fullImage", c.fullImage());
        m.put("packages", c.packages().trim());
        Files.writeString(ws.resolve(CONFIG_FILE), json(m), StandardCharsets.UTF_8);
        Path script = ws.resolve("build.sh");
        Files.writeString(script, script(c), StandardCharsets.UTF_8);
        script.toFile().setExecutable(true, false);
        if (password != null) {
            Path secrets = ws.resolve("secrets");
            Files.createDirectories(secrets);
            try {
                Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                // Windows
            }
            Path f = secrets.resolve("root-password");
            Files.deleteIfExists(f);
            Files.createFile(f);
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Windows: the user's own folder
            }
            Files.writeString(f, new String(password), StandardCharsets.UTF_8);
        }
        Path ignore = ws.resolve(".gitignore");
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "# the sources and builds are downloaded and made again by build.sh\n"
                    + "secrets/\nbuildroot/\noutput/\npoky/\nmeta-raspberrypi/\nbuild/\nopenwrt/\nimagebuilder/\ndl/\n",
                    StandardCharsets.UTF_8);
        }
    }

    /** True when the workspace has a root password saved. */
    static boolean hasPassword(Path ws) {
        try {
            return Files.size(ws.resolve("secrets/root-password")) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    static Config read(Path ws) throws IOException {
        Map<String, Object> m = Json.obj(Json.parse(Files.readString(ws.resolve(CONFIG_FILE), StandardCharsets.UTF_8)));
        Sys s = Sys.of(Json.str(m, "system", "buildroot"));
        Brd b = Brd.of(Json.str(m, "board", "rpi5"));
        return new Config(s, b, Json.str(m, "hostname", "evia-" + b.key), bool(m, "ssh", true), bool(m, "python", false),
                bool(m, "web", s.openwrt()), bool(m, "fullImage", true), Json.str(m, "packages", ""));
    }

    private static boolean bool(Map<String, Object> m, String k, boolean fallback) {
        return m.get(k) instanceof Boolean b ? b : fallback;
    }

    private static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int n = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("  ").append(quote(e.getKey())).append(": ");
            sb.append(e.getValue() instanceof String s ? quote(s) : String.valueOf(e.getValue()));
            sb.append(++n < m.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ================================================================ build.sh

    /** The build script of a workspace. */
    static String script(Config c) {
        StringBuilder s = new StringBuilder();
        s.append("#!/usr/bin/env bash\n")
                .append("# ").append(c.sys().display).append(" for the ").append(c.board().display).append(".\n")
                .append("# Written by EVIA Paradigma ").append(App.VERSION)
                .append(" from evia-linux.json (Linux > Linux Images); saving there writes it again.\n")
                .append("#\n#   ./build.sh fetch       download the sources (once)\n")
                .append("#   ./build.sh configure   apply the choices (host name, packages, root password)\n")
                .append("#   ./build.sh build       build the image\n")
                .append("#   ./build.sh all         the three above (what Build runs)\n")
                .append("#   ./build.sh menuconfig  change any option in a text menu (needs a terminal)\n")
                .append("#   ./build.sh images      list the finished images\n")
                .append("set -euo pipefail\ncd \"$(dirname \"$0\")\"\nWS=\"$PWD\"\n")
                .append("JOBS=\"${JOBS:-$(nproc 2>/dev/null || echo 4)}\"\n")
                .append("HOSTNAME_=").append(c.hostname()).append('\n')
                .append("say() { printf '\\n>> %s\\n' \"$*\"; }\n")
                .append("die() { printf '\\n!! %s\\n' \"$*\" >&2; exit 1; }\n")
                // the password is kept beside, never in this script; the image gets only its SHA-512 crypt hash
                .append("root_hash() {\n")
                .append("  [ -s \"$WS/secrets/root-password\" ] || return 1\n")
                .append("  command -v openssl > /dev/null || die \"openssl is needed to hash the root password\"\n")
                .append("  openssl passwd -6 -stdin < \"$WS/secrets/root-password\"\n}\n");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            s.append("# (written on Windows: run it in WSL 2 or on a Linux computer)\n");
        }
        switch (c.sys()) {
            case BUILDROOT -> buildroot(s, c);
            case YOCTO -> yocto(s, c);
            case OPENWRT -> openwrtImageBuilder(s, c);
            case OPENWRT_SOURCE -> openwrtSource(s, c);
        }
        s.append("\ndo_images() {\n  say \"Images in $WS:\"\n  find ").append(imageDirs(c))
                .append(" -maxdepth 1 \\( -type f -o -type l \\) \\( ").append(imagePattern(c)).append(" \\) 2> /dev/null | sort || true\n}\n");
        s.append("""

                case "${1:-all}" in
                  fetch) do_fetch ;;
                  configure) do_configure ;;
                  build) do_build ;;
                  all) do_fetch; do_configure; do_build; do_images ;;
                  menuconfig) do_menuconfig ;;
                  images) do_images ;;
                  *) die "usage: $0 fetch|configure|build|all|menuconfig|images" ;;
                esac
                """);
        return s.toString();
    }

    private static void buildroot(StringBuilder s, Config c) {
        List<String> frag = new ArrayList<>();
        frag.add("BR2_TARGET_GENERIC_HOSTNAME=\\\"$HOSTNAME_\\\"");
        frag.add("BR2_TARGET_GENERIC_ISSUE=\\\"Welcome to $HOSTNAME_ (EVIA Paradigma, Buildroot)\\\"");
        if (c.ssh()) frag.add("BR2_PACKAGE_DROPBEAR=y");
        if (c.python()) frag.add("BR2_PACKAGE_PYTHON3=y");
        for (String p : c.packageList()) frag.add("BR2_PACKAGE_" + p.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "=y");
        // room for the packages: the defconfigs make a root file system just big enough for BusyBox
        frag.add("BR2_TARGET_ROOTFS_EXT2_SIZE=\\\"" + (c.python() || !c.packageList().isEmpty() ? "1G" : "256M") + "\\\"");
        s.append("BR=\"$WS/buildroot\"; OUT=\"$WS/output\"\n");
        s.append("do_fetch() {\n")
                .append("  [ -d \"$BR/.git\" ] && { say \"Buildroot is already here\"; return; }\n")
                .append("  say \"Downloading Buildroot ").append(BUILDROOT_VERSION).append("\"\n")
                .append("  git clone --depth 1 -b ").append(BUILDROOT_VERSION)
                .append(" https://gitlab.com/buildroot.org/buildroot.git \"$BR\"\n}\n");
        s.append("do_configure() {\n")
                .append("  say \"Configuring ").append(c.board().buildrootDefconfig).append("\"\n")
                .append("  make -C \"$BR\" O=\"$OUT\" ").append(c.board().buildrootDefconfig).append('\n')
                .append("  {\n    echo \"# EVIA Paradigma\"\n");
        for (String f : frag) s.append("    echo \"").append(f).append("\"\n");
        s.append("    if h=$(root_hash); then echo \"BR2_TARGET_GENERIC_ROOT_PASSWD=\\\"${h//\\$/\\$\\$}\\\"\"; fi\n")
                .append("    if [ -d \"$WS/overlay\" ]; then echo \"BR2_ROOTFS_OVERLAY=\\\"$WS/overlay\\\"\"; fi\n")
                .append("  } >> \"$OUT/.config\"\n")
                .append("  make -C \"$BR\" O=\"$OUT\" olddefconfig\n");
        for (String p : c.packageList()) {
            String sym = "BR2_PACKAGE_" + p.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            s.append("  grep -q '^").append(sym).append("=y' \"$OUT/.config\" || echo \"warning: Buildroot has no package ")
                    .append(p).append(" (or it needs another option first; see ./build.sh menuconfig)\"\n");
        }
        s.append("}\n");
        s.append("do_build() {\n  say \"Building (the first time takes about an hour)\"\n  make -C \"$BR\" O=\"$OUT\" -j\"$JOBS\"\n}\n");
        s.append("do_menuconfig() {\n  make -C \"$BR\" O=\"$OUT\" menuconfig\n")
                .append("  echo \"Saved in output/.config. (./build.sh configure starts again from the board's defconfig.)\"\n}\n");
    }

    private static void yocto(StringBuilder s, Config c) {
        Brd b = c.board();
        String image = c.fullImage() ? "core-image-base" : "core-image-minimal";
        s.append("IMAGE=").append(image).append("\nMACHINE_=").append(b.yoctoMachine).append('\n');
        s.append("""
                env_() {   # the BitBake environment, in build/
                  set +u
                  # shellcheck disable=SC1091
                  source "$WS/poky/oe-init-build-env" "$WS/build" > /dev/null
                  set -u
                }
                """);
        s.append("do_fetch() {\n")
                .append("  [ -d poky/.git ] || { say \"Downloading Poky (Yocto ").append(YOCTO_BRANCH).append(")\"; ")
                .append("git clone --depth 1 -b ").append(YOCTO_BRANCH).append(" https://git.yoctoproject.org/poky poky; }\n");
        if (b.yoctoLayer != null) {
            s.append("  [ -d ").append(b.yoctoLayer).append("/.git ] || { say \"Downloading ").append(b.yoctoLayer).append("\"; ")
                    .append("git clone --depth 1 -b ").append(YOCTO_BRANCH).append(" https://git.yoctoproject.org/")
                    .append(b.yoctoLayer).append(' ').append(b.yoctoLayer).append("; }\n");
        }
        s.append("  say \"The sources are here\"\n}\n");
        s.append("do_configure() {\n  env_\n");
        if (b.yoctoLayer != null) {
            s.append("  grep -q '").append(b.yoctoLayer).append("' conf/bblayers.conf || bitbake-layers add-layer \"$WS/")
                    .append(b.yoctoLayer).append("\"\n");
        }
        s.append("  sed -i '/^# >>> EVIA Paradigma/,/^# <<< EVIA Paradigma/d' conf/local.conf\n")
                .append("  {\n    echo '# >>> EVIA Paradigma (written by build.sh configure)'\n")
                .append("    echo \"MACHINE = \\\"$MACHINE_\\\"\"\n")
                .append("    echo \"hostname:pn-base-files = \\\"$HOSTNAME_\\\"\"\n");
        if (b == Brd.RASPBERRY_PI_5) {
            s.append("    echo 'ENABLE_UART = \"1\"'\n")
                    // the Wi-Fi firmware of core-image-base carries this licence flag
                    .append("    echo 'LICENSE_FLAGS_ACCEPTED += \"synaptics-killswitch\"'\n");
        }
        if (c.ssh()) s.append("    echo 'EXTRA_IMAGE_FEATURES += \"ssh-server-dropbear\"'\n");
        List<String> pk = new ArrayList<>();
        if (c.python()) pk.add("python3");
        pk.addAll(c.packageList());
        if (!pk.isEmpty()) s.append("    echo 'IMAGE_INSTALL:append = \" ").append(String.join(" ", pk)).append("\"'\n");
        s.append("    if h=$(root_hash); then\n")
                .append("      echo 'IMAGE_CLASSES += \"extrausers\"'\n")
                .append("      echo \"EXTRA_USERS_PARAMS = \\\"usermod -p '${h//\\$/\\\\\\$}' root;\\\"\"\n")
                .append("    else\n")
                .append("      echo '# no root password was given: root logs in without one'\n")
                .append("      echo 'EXTRA_IMAGE_FEATURES += \"allow-empty-password empty-root-password allow-root-login\"'\n")
                .append("    fi\n")
                .append("    if [ -d \"$WS/overlay\" ]; then echo '# (overlay/ is used by Buildroot and OpenWrt; in Yocto write a recipe)'; fi\n")
                .append("    echo '# <<< EVIA Paradigma'\n  } >> conf/local.conf\n")
                .append("  say \"conf/local.conf: MACHINE $MACHINE_, image $IMAGE\"\n}\n");
        s.append("""
                do_build() {
                  env_
                  say "Building $IMAGE for $MACHINE_ (the first build takes hours; later ones minutes)"
                  bitbake "$IMAGE"
                }
                do_menuconfig() {
                  env_
                  bitbake -c menuconfig virtual/kernel
                  echo "This changes the kernel of this build only; a lasting change belongs in a .bbappend."
                }
                """);
    }

    private static String owrtFirstBoot(Config c) {
        // runs once on the first boot of the board (uci-defaults), then OpenWrt deletes it
        return """
                write_files() {
                  mkdir -p "$WS/files/etc/uci-defaults"
                  if [ -d "$WS/overlay" ]; then cp -a "$WS/overlay/." "$WS/files/"; fi
                  {
                    echo '#!/bin/sh'
                    echo "uci set system.@system[0].hostname='$HOSTNAME_'"
                    echo 'uci commit system'
                    if h=$(root_hash); then echo "sed -i 's|^root:[^:]*:|root:$h:|' /etc/shadow"; fi
                    echo 'exit 0'
                  } > "$WS/files/etc/uci-defaults/90-evia"
                  chmod 600 "$WS/files/etc/uci-defaults/90-evia"
                }
                """;
    }

    private static List<String> owrtPackages(Config c) {
        List<String> l = new ArrayList<>();
        if (c.web()) l.add("luci");
        if (c.python()) l.add("python3");
        if (!c.ssh()) l.add("-dropbear");
        l.addAll(c.packageList());
        return l;
    }

    private static void openwrtImageBuilder(StringBuilder s, Config c) {
        Brd b = c.board();
        String dir = "https://downloads.openwrt.org/releases/" + OPENWRT_VERSION + "/targets/" + b.owrtTarget + "/" + b.owrtSubtarget;
        String file = "openwrt-imagebuilder-" + OPENWRT_VERSION + "-" + b.owrtTarget + "-" + b.owrtSubtarget + ".Linux-x86_64.tar.zst";
        s.append("IB=\"$WS/imagebuilder\"\nURL=").append(dir).append("\nFILE=").append(file).append('\n')
                .append("PROFILE=").append(b.owrtProfile).append('\n')
                .append("PACKAGES=\"").append(String.join(" ", owrtPackages(c))).append("\"\n");
        s.append(owrtFirstBoot(c));
        s.append("""
                do_fetch() {
                  [ -f "$IB/Makefile" ] && { say "The Image Builder is already here"; return; }
                  [ "$(uname -m)" = x86_64 ] || die "the OpenWrt Image Builder runs on x86-64 Linux only; choose OpenWrt (from source)"
                  mkdir -p dl
                  say "Downloading the OpenWrt Image Builder ($FILE)"
                  wget -q -O dl/sha256sums "$URL/sha256sums"
                  wget -c -O "dl/$FILE" "$URL/$FILE"
                  (cd dl && grep " \\*$FILE\\$" sha256sums | sha256sum -c -) || die "the download is damaged; delete dl/ and try again"
                  mkdir -p "$IB"
                  tar --zstd -xf "dl/$FILE" -C "$IB" --strip-components=1
                }
                do_configure() {
                  write_files
                  say "Profile $PROFILE, packages: ${PACKAGES:-(the default ones)}"
                }
                do_build() {
                  write_files
                  say "Building the image"
                  make -C "$IB" image PROFILE="$PROFILE" PACKAGES="$PACKAGES" FILES="$WS/files" BIN_DIR="$WS/output"
                }
                do_menuconfig() {
                  die "the Image Builder has no menuconfig: add packages in the window, or choose OpenWrt (from source)"
                }
                """);
    }

    private static void openwrtSource(StringBuilder s, Config c) {
        Brd b = c.board();
        s.append("OW=\"$WS/openwrt\"\n");
        s.append(owrtFirstBoot(c));
        s.append("do_fetch() {\n")
                .append("  if [ ! -d \"$OW/.git\" ]; then\n    say \"Downloading OpenWrt ").append(OPENWRT_VERSION).append("\"\n")
                .append("    git clone --depth 1 -b v").append(OPENWRT_VERSION).append(" https://git.openwrt.org/openwrt/openwrt.git \"$OW\"\n")
                .append("  fi\n  say \"Updating the package feeds\"\n")
                .append("  (cd \"$OW\" && ./scripts/feeds update -a && ./scripts/feeds install -a)\n}\n");
        s.append("do_configure() {\n  write_files\n  ln -sfn ../files \"$OW/files\"\n  {\n")
                .append("    echo 'CONFIG_TARGET_").append(b.owrtTarget).append("=y'\n")
                .append("    echo 'CONFIG_TARGET_").append(b.owrtTarget).append('_').append(b.owrtSubtarget).append("=y'\n")
                .append("    echo 'CONFIG_TARGET_").append(b.owrtTarget).append('_').append(b.owrtSubtarget)
                .append("_DEVICE_").append(b.owrtProfile).append("=y'\n");
        for (String p : owrtPackages(c)) {
            if (p.startsWith("-")) s.append("    echo '# CONFIG_PACKAGE_").append(p.substring(1)).append(" is not set'\n");
            else s.append("    echo 'CONFIG_PACKAGE_").append(p).append("=y'\n");
        }
        s.append("  } > \"$OW/.config\"\n  make -C \"$OW\" defconfig\n")
                .append("  say \"Configured the ").append(b.owrtProfile).append(" profile\"\n}\n");
        s.append("""
                do_build() {
                  write_files
                  say "Downloading the package sources"
                  make -C "$OW" -j"$JOBS" download
                  say "Building (the first time takes about two hours)"
                  make -C "$OW" -j"$JOBS" || { say "failed: again with one job, to show the error"; make -C "$OW" -j1 V=s; }
                }
                do_menuconfig() {
                  make -C "$OW" menuconfig
                  echo "Saved in openwrt/.config. (./build.sh configure starts again from the choices of the window.)"
                }
                """);
    }

    // ================================================================ the finished images

    private static String imageDirs(Config c) {
        Brd b = c.board();
        return switch (c.sys()) {
            case BUILDROOT -> "\"$WS/output/images\"";
            case YOCTO -> "\"$WS/build/tmp/deploy/images/" + b.yoctoMachine + "\"";
            case OPENWRT -> "\"$WS/output\"";
            case OPENWRT_SOURCE -> "\"$WS/openwrt/bin/targets/" + b.owrtTarget + "/" + b.owrtSubtarget + "\"";
        };
    }

    private static String imagePattern(Config c) {
        return switch (c.sys()) {
            case BUILDROOT -> "-name 'sdcard.img'";
            case YOCTO -> "-name '*.wic' -o -name '*.wic.bz2' -o -name '*.wic.xz' -o -name '*.wic.gz'";
            case OPENWRT, OPENWRT_SOURCE -> "-name '*" + c.board().owrtProfile + "*.img.gz'";
        };
    }

    /** The SD-card images a build made, the most useful first. */
    static List<Path> images(Path ws, Config c) {
        Path dir = switch (c.sys()) {
            case BUILDROOT -> ws.resolve("output/images");
            case YOCTO -> ws.resolve("build/tmp/deploy/images/" + c.board().yoctoMachine);
            case OPENWRT -> ws.resolve("output");
            case OPENWRT_SOURCE -> ws.resolve("openwrt/bin/targets/" + c.board().owrtTarget + "/" + c.board().owrtSubtarget);
        };
        if (!Files.isDirectory(dir)) return List.of();
        String yoctoImage = c.fullImage() ? "core-image-base" : "core-image-minimal";
        try (Stream<Path> st = Files.list(dir)) {
            return st.filter(p -> {
                String n = p.getFileName().toString();
                return switch (c.sys()) {
                    case BUILDROOT -> n.equals("sdcard.img");
                    // the short names are links to the newest build
                    case YOCTO -> n.startsWith(yoctoImage + "-" + c.board().yoctoMachine) && n.matches(".*\\.wic(\\.(bz2|xz|gz))?")
                            && Files.isSymbolicLink(p);
                    case OPENWRT, OPENWRT_SOURCE -> n.contains(c.board().owrtProfile) && n.endsWith(".img.gz");
                };
            }).sorted(Comparator.comparingInt((Path p) -> rank(p.getFileName().toString())).thenComparing(Path::toString)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // OpenWrt: the SD-card image first ("factory" on the Raspberry Pi, "sdcard" on the BeagleBone), squashfs before ext4
    private static int rank(String n) {
        int r = 0;
        if (n.contains("sysupgrade") || n.contains("rootfs")) r += 10;
        if (n.contains("ext4")) r += 1;
        return r;
    }

    // ================================================================ the build computer

    /** One line of the check of this computer. */
    record Check(boolean ok, String text) {}

    /** What this computer has and lacks for building with {@code s} in {@code ws}. */
    static List<Check> checkHost(Sys s, Path ws) {
        List<Check> out = new ArrayList<>();
        String os = System.getProperty("os.name", "");
        boolean linux = os.toLowerCase(Locale.ROOT).contains("linux");
        out.add(new Check(linux, linux ? "Linux (" + System.getProperty("os.arch") + ")"
                : os + ": the build systems run on Linux. Use WSL 2 (Windows) or a Linux virtual machine (macOS), "
                + "and run build.sh there."));
        if (!linux) return out;
        if (s == Sys.OPENWRT) {
            String arch = System.getProperty("os.arch", "");
            boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
            out.add(new Check(x64, x64 ? "x86-64, for the Image Builder" : "The Image Builder needs x86-64; this is " + arch
                    + ". Choose OpenWrt (from source)."));
        }
        List<String> missing = new ArrayList<>();
        for (String[] t : tools(s)) if (!onPath(t[0])) missing.add(t[0] + " (" + t[1] + ")");
        if (missing.isEmpty()) out.add(new Check(true, "The build tools are installed"));
        else out.add(new Check(false, "Missing: " + String.join(", ", missing)));
        if (!missing.isEmpty() && onPath("apt-get")) out.add(new Check(false, "Install them with:  sudo apt install " + aptPackages(s)));
        else if (!missing.isEmpty() && onPath("dnf")) out.add(new Check(false, "Install them with:  sudo dnf install " + dnfPackages(s)));
        if (s == Sys.YOCTO) {
            String userns = readSys("/proc/sys/kernel/apparmor_restrict_unprivileged_userns");
            if ("1".equals(userns)) {
                out.add(new Check(false, "Ubuntu's AppArmor blocks BitBake's user namespaces. Allow them with:  "
                        + "sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0"));
            }
            String locale = System.getenv("LANG");
            if (locale == null || !locale.toLowerCase(Locale.ROOT).contains("utf")) {
                out.add(new Check(false, "BitBake needs a UTF-8 locale (LANG=en_US.UTF-8); LANG is " + locale));
            }
        }
        if ("root".equals(System.getProperty("user.name"))) {
            out.add(new Check(false, "Do not build as root: Buildroot, BitBake and OpenWrt refuse to."));
        }
        if (ws != null) {
            Path p = ws.toAbsolutePath();
            while (p != null && !Files.exists(p)) p = p.getParent();
            if (p != null) {
                try {
                    long gb = Files.getFileStore(p).getUsableSpace() >> 30;
                    out.add(new Check(gb >= s.diskGb(), gb + " GB free in " + p + " (a first build needs about " + s.diskGb() + " GB)"));
                } catch (IOException e) {
                    // not known
                }
            }
        }
        long cpus = Runtime.getRuntime().availableProcessors();
        out.add(new Check(true, cpus + " processor threads (more is faster)"));
        return out;
    }

    /** Commands each build system calls, with the Debian package that has it. */
    private static List<String[]> tools(Sys s) {
        List<String[]> l = new ArrayList<>(List.of(new String[]{"git", "git"}, new String[]{"make", "make"},
                new String[]{"gcc", "build-essential"}, new String[]{"g++", "build-essential"}, new String[]{"python3", "python3"},
                new String[]{"wget", "wget"}, new String[]{"file", "file"}, new String[]{"openssl", "openssl"}));
        switch (s) {
            case BUILDROOT -> l.addAll(List.of(new String[]{"patch", "patch"}, new String[]{"perl", "perl"},
                    new String[]{"cpio", "cpio"}, new String[]{"unzip", "unzip"}, new String[]{"rsync", "rsync"},
                    new String[]{"bc", "bc"}, new String[]{"bzip2", "bzip2"}));
            case YOCTO -> l.addAll(List.of(new String[]{"gawk", "gawk"}, new String[]{"diffstat", "diffstat"},
                    new String[]{"chrpath", "chrpath"}, new String[]{"socat", "socat"}, new String[]{"cpio", "cpio"},
                    new String[]{"makeinfo", "texinfo"}, new String[]{"unzip", "unzip"}, new String[]{"xz", "xz-utils"},
                    new String[]{"zstd", "zstd"}, new String[]{"lz4", "lz4"}));
            case OPENWRT -> l.addAll(List.of(new String[]{"gawk", "gawk"}, new String[]{"perl", "perl"},
                    new String[]{"rsync", "rsync"}, new String[]{"unzip", "unzip"}, new String[]{"zstd", "zstd"},
                    new String[]{"xsltproc", "xsltproc"}));
            case OPENWRT_SOURCE -> l.addAll(List.of(new String[]{"gawk", "gawk"}, new String[]{"perl", "perl"},
                    new String[]{"rsync", "rsync"}, new String[]{"unzip", "unzip"}, new String[]{"flex", "flex"},
                    new String[]{"bison", "bison"}, new String[]{"swig", "swig"}, new String[]{"zstd", "zstd"}));
        }
        return l;
    }

    static String aptPackages(Sys s) {
        return switch (s) {
            case BUILDROOT -> "build-essential git wget file openssl patch perl cpio unzip rsync bc bzip2 libncurses-dev";
            case YOCTO -> "build-essential chrpath cpio debianutils diffstat file gawk gcc git iputils-ping libacl1 liblz4-tool "
                    + "locales python3 python3-git python3-jinja2 python3-pexpect python3-pip python3-subunit socat texinfo "
                    + "unzip wget xz-utils zstd openssl";
            case OPENWRT -> "build-essential libncurses-dev zlib1g-dev gawk git gettext libssl-dev xsltproc rsync wget unzip "
                    + "python3 file zstd openssl";
            case OPENWRT_SOURCE -> "build-essential clang flex bison g++ gawk gcc-multilib g++-multilib gettext git "
                    + "libncurses-dev libssl-dev python3-setuptools rsync swig unzip zlib1g-dev file wget zstd openssl";
        };
    }

    static String dnfPackages(Sys s) {
        return switch (s) {
            case BUILDROOT -> "gcc gcc-c++ make git wget file openssl patch perl cpio unzip rsync bc bzip2 ncurses-devel";
            case YOCTO -> "gawk make wget tar bzip2 gzip python3 unzip perl patch diffutils diffstat git cpp gcc gcc-c++ "
                    + "glibc-devel texinfo chrpath socat perl-Data-Dumper perl-Text-ParseWords perl-Thread-Queue python3-pip "
                    + "python3-GitPython python3-jinja2 python3-pexpect xz which SDL-devel rpcgen zstd lz4 cpio glibc-langpack-en "
                    + "openssl";
            case OPENWRT, OPENWRT_SOURCE -> "bash-completion bzip2 file gcc gcc-c++ git gawk gettext make ncurses-devel "
                    + "openssl-devel patch perl-FindBin perl-IPC-Cmd python3 rsync swig unzip wget which zlib-devel zstd "
                    + "libxslt flex bison openssl";
        };
    }

    static boolean onPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, cmd))) return true;
        }
        return false;
    }

    private static String readSys(String file) {
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ================================================================ SD cards

    /** A disk an image can be written to: removable or on USB/MMC, and nothing of this computer mounted from it. */
    record Disk(String device, long size, String model, String bus) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%s   %.1f GB   %s(%s)", device, size / 1e9, model.isBlank() ? "" : model + "   ", bus);
        }
    }

    /** The SD cards and USB drives plugged in (Linux; lsblk). */
    static List<Disk> removableDisks() throws IOException {
        Process p = new ProcessBuilder("lsblk", "-J", "-b", "-o", "NAME,PATH,SIZE,MODEL,RM,HOTPLUG,TRAN,TYPE,MOUNTPOINT,RO")
                .redirectErrorStream(true).start();
        String text;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            text = String.join("\n", r.lines().toList());
        }
        try {
            if (p.waitFor() != 0) throw new IOException("lsblk: " + text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        return disks(text);
    }

    /** The disks of lsblk's JSON that are safe to offer. */
    static List<Disk> disks(String lsblkJson) {
        List<Disk> out = new ArrayList<>();
        for (Object o : Json.list(Json.obj(Json.parse(lsblkJson)).get("blockdevices"))) {
            Map<String, Object> d = Json.obj(o);
            if (!"disk".equals(Json.str(d, "type", ""))) continue;
            String tran = Json.str(d, "tran", "");
            String name = Json.str(d, "name", "");
            boolean removable = flag(d.get("rm")) || flag(d.get("hotplug")) || "usb".equals(tran) || "mmc".equals(tran)
                    || name.startsWith("mmcblk");
            if (!removable || flag(d.get("ro")) || name.startsWith("zram") || name.startsWith("loop")) continue;
            if (systemMount(d)) continue;
            long size = d.get("size") instanceof Double n ? n.longValue() : parseLong(Json.str(d, "size", "0"));
            if (size <= 0) continue;
            String path = Json.str(d, "path", "/dev/" + name);
            out.add(new Disk(path, size, Json.str(d, "model", "").trim(), tran.isEmpty() ? (name.startsWith("mmcblk") ? "mmc" : "removable") : tran));
        }
        return out;
    }

    // a disk with /, /boot, /home, /usr, /var or swap on it belongs to this computer
    private static boolean systemMount(Map<String, Object> d) {
        String mp = Json.str(d, "mountpoint", "");
        if (mp.equals("/") || mp.equals("[SWAP]") || mp.matches("/(boot|home|usr|var|opt|srv)(/.*)?")) return true;
        for (Object c : Json.list(d.get("children"))) if (systemMount(Json.obj(c))) return true;
        return false;
    }

    private static boolean flag(Object v) {
        return v instanceof Boolean b ? b : "1".equals(v) || "true".equals(v);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The command that writes {@code image} (plain, .gz, .bz2, .xz or .zst) to {@code disk} as root (pkexec asks
     * for the password): unmounts the card's partitions, writes, flushes.
     */
    static List<String> writeCommand(Path image, String disk) {
        String n = image.getFileName().toString();
        String cat = n.endsWith(".gz") ? "{ gzip -dc \"$1\" || [ $? -eq 2 ]; }"   // OpenWrt's images have padding after the gzip data: exit 2
                : n.endsWith(".bz2") ? "bzip2 -dc \"$1\""
                : n.endsWith(".xz") ? "xz -dc \"$1\""
                : n.endsWith(".zst") ? "zstd -dc \"$1\""
                : "cat \"$1\"";
        String sh = "set -e; for p in \"$2\"?*; do umount \"$p\" 2> /dev/null || true; done; "
                + cat + " | dd of=\"$2\" bs=4M conv=fsync status=progress; sync; echo; echo 'Written. The card can be taken out.'";
        return List.of("pkexec", "sh", "-c", sh, "sh", image.toAbsolutePath().toString(), disk);
    }
}
