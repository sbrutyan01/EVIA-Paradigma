package evia.paradigma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the serial ports a board can be on, without a serial library: the device files on Linux
 * and macOS, the registry (and the device names, when PowerShell can tell them) on Windows.
 */
final class SerialPorts {
    private SerialPorts() {}

    /** A port and what is on it, e.g. "/dev/ttyUSB0" and "Silicon Labs CP2102"; the description may be empty. */
    record Port(String name, String description) {
        @Override public String toString() {
            return description.isEmpty() ? name : name + "  -  " + description;
        }
    }

    /** The ports there are now, boards first. Never null; empty when nothing is plugged in. */
    static List<Port> scan() {
        try {
            if (ExternalEditor.WINDOWS) return windows();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("mac") ? mac() : linux();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- Linux

    private static final Pattern LINUX_PORT = Pattern.compile("tty(ACM|USB|AMA|XRUSB)\\d+|rfcomm\\d+");

    private static List<Port> linux() throws IOException {
        // /dev/serial/by-id names the USB adapters: usb-Silicon_Labs_CP2102_..._0001-if00-port0 -> ../../ttyUSB0
        Map<String, String> byId = new LinkedHashMap<>();
        Path ids = Path.of("/dev/serial/by-id");
        if (Files.isDirectory(ids)) {
            try (Stream<Path> s = Files.list(ids)) {
                for (Path link : s.toList()) {
                    try {
                        String dev = link.toRealPath().getFileName().toString();
                        byId.put(dev, tidyId(link.getFileName().toString()));
                    } catch (IOException ignored) {
                        // a link to a device that just went away
                    }
                }
            }
        }
        List<Port> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of("/dev"))) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> LINUX_PORT.matcher(n).matches())
                    .sorted(SerialPorts::natural)
                    .forEach(n -> out.add(new Port("/dev/" + n, byId.getOrDefault(n, linuxKind(n)))));
        }
        return out;
    }

    /** "usb-Silicon_Labs_CP2102_USB_to_UART_Bridge_Controller_0001-if00-port0" -> "Silicon Labs CP2102 USB to UART Bridge Controller". */
    static String tidyId(String id) {
        String s = id.replaceFirst("^usb-", "").replaceFirst("-if\\d+(-port\\d+)?$", "");
        int serial = s.lastIndexOf('_');
        if (serial > 0 && s.substring(serial + 1).matches("[0-9A-Fa-f]{4,}")) s = s.substring(0, serial);
        return s.replace('_', ' ').trim();
    }

    private static String linuxKind(String name) {
        if (name.startsWith("ttyACM")) return "USB (native, e.g. Uno, Leonardo, ESP32-S3)";
        if (name.startsWith("ttyUSB")) return "USB to serial adapter";
        if (name.startsWith("rfcomm")) return "Bluetooth";
        return "";
    }

    // ---------------------------------------------------------------- macOS

    private static List<Port> mac() throws IOException {
        List<Port> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of("/dev"))) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("cu.") && !n.equals("cu.Bluetooth-Incoming-Port") && !n.equals("cu.debug-console"))
                    .sorted(SerialPorts::natural)
                    .forEach(n -> out.add(new Port("/dev/" + n, macKind(n))));
        }
        // the USB ones first: those are the boards
        out.sort((a, b) -> Boolean.compare(!a.name().contains("usb"), !b.name().contains("usb")));
        return out;
    }

    private static String macKind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("slab")) return "Silicon Labs CP210x";
        if (n.contains("wchusbserial")) return "WCH CH340";
        if (n.contains("usbmodem")) return "USB (native)";
        if (n.contains("usbserial")) return "USB to serial adapter";
        return "";
    }

    // ---------------------------------------------------------------- Windows

    private static final Pattern COM = Pattern.compile("\\b(COM\\d+)\\b");

    private static List<Port> windows() {
        // the device names: "USB-SERIAL CH340 (COM5)"; PowerShell may be slow or missing, so it only adds names
        Map<String, String> names = new LinkedHashMap<>();
        String ps = run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_PnPEntity -Filter \"Name like '%(COM%'\" | ForEach-Object { $_.Name }"), 8);
        for (String line : ps.split("\\R")) {
            Matcher m = Pattern.compile("^(.*?)\\s*\\((COM\\d+)\\)\\s*$").matcher(line.trim());
            if (m.matches()) names.put(m.group(2), m.group(1));
        }
        // the ports themselves: the registry lists every one Windows has opened a driver for
        List<String> ports = new ArrayList<>();
        for (String line : run(List.of("reg", "query", "HKLM\\HARDWARE\\DEVICEMAP\\SERIALCOMM"), 5).split("\\R")) {
            Matcher m = COM.matcher(line);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null && !ports.contains(last)) ports.add(last);
        }
        for (String p : names.keySet()) if (!ports.contains(p)) ports.add(p);
        ports.sort(SerialPorts::natural);
        List<Port> out = new ArrayList<>();
        for (String p : ports) out.add(new Port(p, names.getOrDefault(p, "")));
        return out;
    }

    private static String run(List<String> cmd, int seconds) {
        Path out = null;
        try {
            // into a file, so a tool that hangs can be given up on after the time instead of blocking a read
            out = Files.createTempFile("evia-ports", ".txt");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            p.getOutputStream().close();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return Files.readString(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temporary file
                }
            }
        }
    }

    /** COM2 before COM10, ttyUSB2 before ttyUSB10. */
    static int natural(String a, String b) {
        Matcher ma = Pattern.compile("^(.*?)(\\d+)$").matcher(a), mb = Pattern.compile("^(.*?)(\\d+)$").matcher(b);
        if (ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1))) {
            return Long.compare(Long.parseLong(ma.group(2)), Long.parseLong(mb.group(2)));
        }
        return a.compareTo(b);
    }
}
