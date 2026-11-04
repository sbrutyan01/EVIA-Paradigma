package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;

/**
 * The fonts of the app, the same pair IntelliJ IDEA uses: Inter for the interface (with Armenian letters added) and JetBrains Mono for code.
 * Both are bundled (SIL Open Font License), so the look is the same on every system. When a font cannot be
 * loaded, or does not have a character (Armenian, for example), Java's own fonts take over.
 */
final class Fonts {
    private Fonts() {}

    static final String UI = "EVIA UI";   // Inter plus Armenian letters, see scripts/fonts/make-ui-font.py
    static final String MONO = "JetBrains Mono";

    private static boolean loaded;
    private static boolean uiOk, monoOk;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String f : new String[]{"EVIAUI-Regular", "EVIAUI-Bold", "JetBrainsMono-Regular", "JetBrainsMono-Bold",
                "JetBrainsMono-Italic", "JetBrainsMono-BoldItalic"}) {
            try (InputStream in = Fonts.class.getResourceAsStream("fonts/" + f + ".ttf")) {
                if (in == null) continue;
                ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
                if (f.startsWith("EVIAUI")) uiOk = true; else monoOk = true;
            } catch (Exception | Error e) {
                // keep going with the built-in fonts
            }
        }
    }

    /** Interface font. */
    static Font ui(int style, float size) {
        load();
        return new Font(uiOk ? UI : Font.SANS_SERIF, style, 1).deriveFont(size);
    }

    /** Code font. */
    static Font mono(int style, float size) {
        load();
        return new Font(monoOk ? MONO : Font.MONOSPACED, style, 1).deriveFont(size);
    }

    /** Sets the default font of every Swing component (call before the look and feel is installed). */
    static void install() {
        load();
        UIManager.put("defaultFont", ui(Font.PLAIN, 13));
        UIManager.put("monospaced.font", mono(Font.PLAIN, 13));
    }

    /** CSS font stack for the HTML guide. */
    static String uiCss() {
        load();
        return (uiOk ? "'" + UI + "', " : "") + "sans-serif";
    }

    static String monoCss() {
        load();
        return (monoOk ? "'" + MONO + "', " : "") + "monospace";
    }
}
