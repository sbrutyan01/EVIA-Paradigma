package evia.paradigma;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The words of the interface, in English, Russian or Armenian.
 *
 * <p>The English text is the key: {@code Text.t("Save As...")} gives back the Russian or Armenian when the
 * interface is in that language, and the English itself when it is not, or when a line has not been
 * translated yet. Nothing breaks when a translation is missing, which is what keeps the files easy to add to.
 *
 * <p>The translations live beside the class, one tab-separated file per language
 * ({@code i18n/ru.tsv}, {@code i18n/hy.tsv}): the English on the left, the translation on the right.
 * {@code scripts/i18n/collect.py} lists the strings the interface asks for and shows which are still missing.
 */
final class Text {

    /** The languages the interface itself is in. */
    enum Lang {
        EN("English", "en"), RU("Русский", "ru"), HY("Հայերեն", "hy");

        final String display, code;

        Lang(String display, String code) {
            this.display = display;
            this.code = code;
        }

        @Override public String toString() {
            return display;
        }
    }

    private Text() {}

    private static Lang lang;
    private static Map<String, String> words = Map.of();

    /** The language of the interface, from Settings; English until something else is chosen. */
    static synchronized Lang lang() {
        if (lang == null) use(Settings.uiLanguage());
        return lang;
    }

    /** Switches the interface language and loads its words. */
    static synchronized void use(Lang l) {
        lang = l == null ? Lang.EN : l;
        words = lang == Lang.EN ? Map.of() : load(lang.code);
    }

    /** The translation of {@code english}, or the English itself when there is none. */
    static String t(String english) {
        if (english == null || english.isEmpty()) return english;
        lang();
        String s = words.get(english);
        return s == null || s.isBlank() ? english : s;
    }

    /** A translated line with values in it: {@code t("Step delay: %d ms", 40)}. */
    static String t(String english, Object... args) {
        return String.format(t(english), args);
    }

    /** The language of a {@code Locale}-style code, or English for anything else. */
    static Lang of(String code) {
        if (code == null) return Lang.EN;
        for (Lang l : Lang.values()) if (l.code.equalsIgnoreCase(code.trim())) return l;
        return Lang.EN;
    }

    private static Map<String, String> load(String code) {
        Map<String, String> m = new HashMap<>();
        try (InputStream in = Text.class.getResourceAsStream("i18n/" + code + ".tsv")) {
            if (in == null) return m;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String en = unescape(line.substring(0, tab)), tr = unescape(line.substring(tab + 1)).trim();
                if (!tr.isEmpty()) m.put(en, tr);
            }
        } catch (Exception e) {
            return m;      // an unreadable file leaves the interface in English
        }
        return m;
    }

    /** Line breaks and tabs inside a line are written {@code \n} and {@code \t}. */
    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    /** True when the interface language writes right to left (none of them do yet). */
    static boolean rightToLeft() {
        return false;
    }

    /** The locale that matches the interface language, for dates and number formats. */
    static Locale locale() {
        return switch (lang()) {
            case RU -> Locale.forLanguageTag("ru");
            case HY -> Locale.forLanguageTag("hy");
            case EN -> Locale.ENGLISH;
        };
    }
}
