package evia.paradigma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the package index: objects become {@link Map}s (in file order), arrays
 * {@link List}s, numbers {@link Double}s, and true, false and null themselves. Strict: anything that is not
 * JSON is an error, with the place where it went wrong.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object v = j.value();
        j.space();
        if (j.i != j.s.length()) throw j.error("unexpected text after the value");
        return v;
    }

    /** A field of an object as text, or {@code fallback} when it is missing or not text. */
    static String str(Map<?, ?> o, String key, String fallback) {
        Object v = o.get(key);
        return v instanceof String t ? t : fallback;
    }

    /** A field of an object as a whole number, or {@code fallback}. */
    static long num(Map<?, ?> o, String key, long fallback) {
        Object v = o.get(key);
        return v instanceof Double d ? d.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at character " + i);
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw error("the text ends where a value should be");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw error("unexpected '" + c + "'");
            }
        };
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected a name in quotes");
            String key = string();
            space();
            if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
            i++;
            space();
            m.put(key, value());
            space();
            if (i >= s.length()) throw error("the object is not closed");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            space();
            l.add(value());
            space();
            if (i >= s.length()) throw error("the array is not closed");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("a \\u escape is cut short");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("unknown escape \\" + e);
            }
        }
        throw error("the text in quotes is not closed");
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("not a number: " + s.substring(start, i));
        }
    }
}
