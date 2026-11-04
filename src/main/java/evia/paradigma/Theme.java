package evia.paradigma;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The look of the app: the IntelliJ IDEA light theme and Darcula (FlatLaf), chosen in View > Theme.
 * Colors that components paint themselves come from here, so they follow the theme too.
 */
final class Theme {
    private Theme() {}

    enum Mode { SYSTEM, LIGHT, DARK }

    private static boolean dark;
    private static final List<Runnable> listeners = new ArrayList<>();

    static boolean dark() {
        return dark;
    }

    /** Installs the look and feel for the saved mode. Call once at startup, before any window exists. */
    static void init() {
        Fonts.install();
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("SplitPane.dividerSize", 6);
        System.setProperty("flatlaf.menuBarEmbedded", "false");
        dark = resolve(Settings.themeMode());
        setLaf(dark);
    }

    /** Chooses a mode, saves it and repaints every open window. */
    static void apply(Mode m) {
        Settings.setThemeMode(m);
        boolean d = resolve(m);
        if (d != dark || !(UIManager.getLookAndFeel() instanceof FlatLaf)) {
            dark = d;
            setLaf(d);
            FlatLaf.updateUI();
        }
        for (Runnable r : new ArrayList<>(listeners)) r.run();
        for (Window w : Window.getWindows()) w.repaint();
    }

    /** Runs {@code r} after the theme changed (for components that set colors themselves). */
    static void onChange(Runnable r) {
        listeners.add(r);
    }

    private static void setLaf(boolean d) {
        try {
            UIManager.setLookAndFeel(d ? new FlatDarculaLaf() : new FlatIntelliJLaf());
        } catch (Exception | Error e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default is fine
            }
        }
        Fonts.install();
        UIManager.put("defaultFont", Fonts.ui(Font.PLAIN, 13));
    }

    static boolean resolve(Mode m) {
        return m == Mode.DARK || (m == Mode.SYSTEM && systemDark());
    }

    /** Asks the operating system whether it is set to a dark theme (Windows, macOS, GNOME/KDE). */
    static boolean systemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win"))
                return run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\s*");
            if (os.contains("mac")) return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("Dark");
            String s = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (s.contains("dark")) return true;
            if (s.contains("light") || s.contains("default")) return false;
            return run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT).contains("dark");
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        return sb.toString();
    }

    // ================================================================ colors painted by the app itself

    static Color canvas(boolean classCanvas) {
        return dark ? (classCanvas ? new Color(40, 50, 50) : new Color(43, 45, 48))
                : (classCanvas ? new Color(244, 251, 250) : new Color(248, 249, 252));
    }

    static Color grid(boolean classCanvas) {
        return dark ? (classCanvas ? new Color(58, 72, 72) : new Color(60, 63, 66))
                : (classCanvas ? new Color(200, 225, 222) : new Color(214, 218, 228));
    }

    static Color wire() { return dark ? new Color(170, 176, 192) : new Color(70, 75, 90); }
    static Color wireTrue() { return dark ? new Color(95, 190, 105) : new Color(50, 150, 60); }
    static Color wireFalse() { return dark ? new Color(235, 100, 90) : new Color(200, 60, 50); }
    static Color select() { return dark ? new Color(90, 150, 255) : new Color(30, 90, 210); }
    static Color selectFill() { return new Color(select().getRed(), select().getGreen(), select().getBlue(), 30); }
    static Color selectLine() { return new Color(select().getRed(), select().getGreen(), select().getBlue(), 160); }

    static Color consoleBg() { return dark ? new Color(30, 31, 34) : new Color(30, 32, 38); }
    static Color consoleFg() { return dark ? new Color(204, 208, 214) : new Color(220, 225, 230); }

    static Color codeBg() { return dark ? new Color(43, 43, 43) : new Color(255, 255, 255); }
    static Color modeLineBg() { return dark ? new Color(60, 63, 65) : new Color(235, 236, 240); }
    static Color mark() { return dark ? new Color(80, 74, 38) : new Color(255, 236, 140); }

    static Color plain() { return dark ? new Color(169, 183, 198) : new Color(25, 25, 30); }
    static Color comment() { return dark ? new Color(128, 128, 128) : new Color(110, 130, 110); }
    static Color string() { return dark ? new Color(106, 135, 89) : new Color(160, 80, 30); }
    static Color keyword() { return dark ? new Color(204, 120, 50) : new Color(30, 70, 170); }
    static Color preproc() { return dark ? new Color(187, 181, 41) : new Color(140, 60, 150); }
    static Color label() { return dark ? new Color(255, 198, 109) : new Color(190, 30, 60); }

    static Color error() { return dark ? new Color(255, 107, 104) : new Color(190, 30, 30); }
    static Color ok() { return dark ? new Color(98, 190, 110) : new Color(30, 130, 50); }
    static Color warn() { return dark ? new Color(230, 160, 60) : new Color(200, 110, 0); }
    static Color muted() { return dark ? new Color(150, 154, 160) : Color.GRAY; }
    static Color accentText() { return dark ? new Color(110, 165, 255) : new Color(30, 90, 170); }

    /** Hex like "#aabbcc" for the HTML guide. */
    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
