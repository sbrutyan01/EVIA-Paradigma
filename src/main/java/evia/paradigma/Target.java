package evia.paradigma;

import java.util.Locale;

/**
 * What code generation produces: the language standard, how control flow is written and how
 * values are represented.
 *
 * @param lang   C99 ... C++20
 * @param flow   STRUCTURED: if/else, for, while, do-while and switch where the wiring allows it
 *               (labels only where it doesn't); GOTO: every block is a label, wires are jumps
 * @param values SAFE: dynamic values (number or text) with the interpreter's exact checks;
 *               FAST: plain {@code double} numbers, text only in Print, no runtime checks
 */
record Target(Lang lang, Flow flow, Values values) {

    static final Target DEFAULT = new Target(Lang.CPP20, Flow.STRUCTURED, Values.SAFE);

    enum Lang {
        C99("C99", "c99", 99, "ISO C 1999"), C11("C11", "c11", 11, "ISO C 2011"), C17("C17", "c17", 17, "ISO C 2018"),
        CPP98("C++98", "c++98", 98, "ISO C++ 1998"), CPP11("C++11", "c++11", 11, "ISO C++ 2011"),
        CPP14("C++14", "c++14", 14, "ISO C++ 2014"), CPP17("C++17", "c++17", 17, "ISO C++ 2017"),
        CPP20("C++20", "c++20", 20, "ISO C++ 2020");

        final String display, std, note;
        final int year;

        Lang(String display, String std, int year, String note) {
            this.display = display;
            this.std = std;
            this.year = year;
            this.note = note;
        }

        boolean isC() {
            return !std.startsWith("c++");
        }

        /** 98, 11, 14, 17, 20 for C++ (0 for C); compares chronologically. */
        int cpp() {
            return isC() ? 0 : year == 98 ? 3 : year;
        }

        String ext() {
            return isC() ? ".c" : ".cpp";
        }

        @Override public String toString() {
            return display;
        }
    }

    enum Flow {
        STRUCTURED("Structured"), GOTO("Goto");
        final String display;
        Flow(String display) { this.display = display; }
        @Override public String toString() { return display; }
    }

    enum Values {
        SAFE("Safe"), FAST("Fast");
        final String display;
        Values(String display) { this.display = display; }
        @Override public String toString() { return display; }
    }

    boolean isC() {
        return lang.isC();
    }

    boolean fast() {
        return values == Values.FAST;
    }

    /** True for C++ before C++17, where the object of a call and its arguments are evaluated in unspecified order. */
    boolean cppBefore17() {
        return !isC() && lang.cpp() < 17;
    }

    String languageName() {
        return isC() ? "C" : "C++";
    }

    String ext() {
        return lang.ext();
    }

    /** e.g. "C11, structured, fast values". */
    String describe() {
        return lang.display + ", " + flow.display.toLowerCase(Locale.ROOT) + " flow, " + values.display.toLowerCase(Locale.ROOT) + " values";
    }

    /** Stored form, e.g. "c11/structured/fast". */
    String key() {
        return lang.std + "/" + flow.name().toLowerCase(Locale.ROOT) + "/" + values.name().toLowerCase(Locale.ROOT);
    }

    static Target parse(String key) {
        if (key == null || key.isBlank()) return DEFAULT;
        String[] p = key.trim().toLowerCase(Locale.ROOT).split("/");
        Lang lang = langOf(p[0]);
        Flow flow = p.length > 1 && p[1].equals("goto") ? Flow.GOTO : Flow.STRUCTURED;
        Values values = p.length > 2 && p[2].equals("fast") ? Values.FAST : Values.SAFE;
        return new Target(lang == null ? DEFAULT.lang : lang, flow, values);
    }

    /** Accepts "c11", "C11", "c++14", "cpp14", "cxx14". */
    static Lang langOf(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT).replace("cpp", "c++").replace("cxx", "c++");
        for (Lang l : Lang.values()) if (l.std.equals(k)) return l;
        return null;
    }

    Target withLang(Lang l) { return new Target(l, flow, values); }
    Target withFlow(Flow f) { return new Target(lang, f, values); }
    Target withValues(Values v) { return new Target(lang, flow, v); }
}
