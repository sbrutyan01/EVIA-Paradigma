package evia.paradigma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small runtimes embedded at the top of generated programs. Every runtime reproduces the
 * interpreter's number text exactly (Java's {@code Double.toString}, whole numbers without ".0").
 *
 * <ul>
 *   <li>C++17 / C++20, safe values: {@link CppRuntime#SOURCE} ({@code std::variant}, {@code std::to_chars}).</li>
 *   <li>C++98 / C++11 / C++14, safe values: a classic {@code Value} class (kind, number, text).</li>
 *   <li>C++, fast values: number formatting and number input only.</li>
 *   <li>C99 / C11 / C17: small pieces; a program only gets the ones it uses, so nothing is unused
 *       ({@code -Wall -Wextra -Werror} reject unused static functions).</li>
 * </ul>
 */
final class Runtimes {
    private Runtimes() {}

    // ================================================================ C++

    static String cpp(Target t) {
        if (t.lang().cpp() >= 17) return t.fast() ? modernFast(t) : CppRuntime.SOURCE.replace(
                "// ===================== EVIA Paradigma runtime =====================",
                "// ===================== EVIA Paradigma runtime (" + t.lang() + ") =====================");
        return classic(t);
    }

    /** Shared by the classic C++ runtimes: number text and parsing without C++17 features. */
    private static final String CLASSIC_NUMBERS = """
            /// Number text exactly like the interpreter: whole numbers without ".0", others like Java's Double.toString.
            inline std::string str(double d) {
                if (d != d) return "NaN";
                if (d == HUGE_VAL) return "Infinity";
                if (d == -HUGE_VAL) return "-Infinity";
                char buf[64];
                if (d == std::floor(d) && std::fabs(d) < 1e15) {
                    snprintf(buf, sizeof buf, "%.0f", d == 0 ? 0.0 : d);
                    return buf;
                }
                // the shortest digits that read back as the same number
                for (int p = 1; p <= 17; ++p) {
                    snprintf(buf, sizeof buf, "%.*e", p - 1, d);
                    if (std::strtod(buf, 0) == d) break;
                }
                const char* s = buf[0] == '-' ? buf + 1 : buf;
                std::string digits;
                for (; *s && *s != 'e'; ++s) if (*s != '.') digits += *s;
                int exp = std::atoi(s + 1);
                std::string out = d < 0 ? "-" : "";
                double a = std::fabs(d);
                if (a >= 1e-3 && a < 1e7) {
                    if (exp >= 0) {
                        std::string::size_type n = static_cast<std::string::size_type>(exp) + 1;
                        while (digits.size() < n) digits += '0';
                        out += digits.substr(0, n) + "." + (digits.size() > n ? digits.substr(n) : std::string("0"));
                    } else {
                        out += "0." + std::string(static_cast<std::string::size_type>(-exp - 1), '0') + digits;
                    }
                } else {
                    out += digits.substr(0, 1) + "." + (digits.size() > 1 ? digits.substr(1) : std::string("0"));
                    char e[16];
                    snprintf(e, sizeof e, "E%d", exp);
                    out += e;
                }
                return out;
            }

            /// Text that reads as a number: digits, sign, point and exponent; spaces around and a leading '+' allowed.
            inline bool parse_num(const std::string& s, double& out) {
                std::string::size_type a = s.find_first_not_of(" \\t\\r\\n");
                if (a == std::string::npos) return false;
                std::string t = s.substr(a, s.find_last_not_of(" \\t\\r\\n") - a + 1);
                if (t.size() > 1 && t[0] == '+') t.erase(0, 1);
                if (t[0] == '+' || t.find_first_not_of("0123456789.eE+-") != std::string::npos) return false;
                char* end = 0;
                double d = std::strtod(t.c_str(), &end);
                if (end != t.c_str() + t.size()) return false;
                out = d;
                return true;
            }

            /// One line from standard input, without the line break.
            inline bool read_line(std::string& line) {
                if (!std::getline(std::cin, line)) return false;
                if (!line.empty() && line[line.size() - 1] == '\\r') line.erase(line.size() - 1);
                return true;
            }
            """;

    /** C++98 / 11 / 14. */
    private static String classic(Target t) {
        int v = t.lang().cpp();
        boolean cpp11 = v >= 11;
        StringBuilder s = new StringBuilder();
        s.append("// ===================== EVIA Paradigma runtime (").append(t.lang()).append(t.fast() ? ", fast values" : "")
                .append(") =====================\n");
        s.append("""
                #include <cmath>
                #include <cstdlib>
                #include <iostream>
                """);
        if (cpp11) s.append("#include <memory>\n");
        s.append("""
                #include <stdexcept>
                #include <stdio.h>
                #include <string>

                namespace evia_rt {

                """);
        s.append(CLASSIC_NUMBERS).append('\n');
        if (t.fast()) {
            s.append("""
                    /// Input block (fast values): the line must be a number.
                    inline double input_num(const char* name) {
                        std::cout << "Enter a value for " << name << ": " << std::flush;
                        std::string line;
                        double d = 0;
                        if (!read_line(line)) throw std::runtime_error("input cancelled");
                        if (!parse_num(line, d)) throw std::runtime_error("'" + line + "' is not a number");
                        return d;
                    }

                    """);
        } else {
            s.append("""
                    /// A dynamically typed value: undefined, a number or a text.
                    struct Value {
                        enum Kind { UNDEFINED, NUMBER, TEXT };
                        Kind kind;
                        double n;
                        std::string s;
                        Value() : kind(UNDEFINED), n(0) {}
                        Value(double d) : kind(NUMBER), n(d) {}
                        Value(const std::string& t) : kind(TEXT), n(0), s(t) {}
                        bool isNum() const { return kind == NUMBER; }
                        bool isStr() const { return kind == TEXT; }
                        bool defined() const { return kind != UNDEFINED; }
                    };

                    inline Value N(double d) { return Value(d); }
                    inline Value S(const std::string& s) { return Value(s); }
                    inline Value B(bool b) { return Value(b ? 1.0 : 0.0); }

                    /// Read a program variable; reading one that was never assigned is an error.
                    inline const Value& rd(const Value& x, const char* name) {
                        if (!x.defined()) throw std::runtime_error(std::string("undefined variable '") + name + "'");
                        return x;
                    }

                    inline std::string str(const Value& x) { return x.isStr() ? x.s : str(x.n); }

                    inline double num(const Value& x) {
                        if (x.isNum()) return x.n;
                        double d = 0;
                        if (!parse_num(x.s, d)) throw std::runtime_error("'" + x.s + "' is not a number");
                        return d;
                    }

                    inline bool truthy(const Value& x) { return x.isNum() ? x.n != 0.0 : !x.s.empty(); }

                    inline double nonZero(double d) {
                        if (d == 0.0) throw std::runtime_error("division by zero");
                        return d;
                    }

                    inline Value operator+(const Value& a, const Value& b) {
                        if (a.isStr() || b.isStr()) return S(str(a) + str(b));
                        return N(a.n + b.n);
                    }
                    inline Value operator-(const Value& a, const Value& b) { double x = num(a), y = num(b); return N(x - y); }
                    inline Value operator*(const Value& a, const Value& b) { double x = num(a), y = num(b); return N(x * y); }
                    inline Value operator/(const Value& a, const Value& b) { double x = num(a), y = num(b); return N(x / nonZero(y)); }
                    inline Value operator%(const Value& a, const Value& b) { double x = num(a), y = num(b); return N(std::fmod(x, nonZero(y))); }
                    inline Value neg(const Value& a) { return N(-num(a)); }
                    inline Value lnot(const Value& a) { return B(!truthy(a)); }

                    inline bool equal(const Value& a, const Value& b) {
                        if (a.isNum() && b.isNum()) return a.n == b.n;
                        return str(a) == str(b);
                    }
                    inline int compare(const Value& a, const Value& b) {
                        if (a.isStr() && b.isStr()) {
                            int c = a.s.compare(b.s);
                            return (c > 0) - (c < 0);
                        }
                        double x = num(a), y = num(b);
                        return (x > y) - (x < y);
                    }
                    inline Value eq(const Value& a, const Value& b) { return B(equal(a, b)); }
                    inline Value ne(const Value& a, const Value& b) { return B(!equal(a, b)); }
                    inline Value lt(const Value& a, const Value& b) { return B(compare(a, b) < 0); }
                    inline Value le(const Value& a, const Value& b) { return B(compare(a, b) <= 0); }
                    inline Value gt(const Value& a, const Value& b) { return B(compare(a, b) > 0); }
                    inline Value ge(const Value& a, const Value& b) { return B(compare(a, b) >= 0); }

                    inline void print(const Value& x) { std::cout << str(x) << '\\n'; }

                    /// Input block: a line that reads as a number becomes a number, otherwise a text.
                    inline Value input(const char* name) {
                        std::cout << "Enter a value for " << name << ": " << std::flush;
                        std::string line;
                        if (!read_line(line)) throw std::runtime_error("input cancelled");
                        double d = 0;
                        if (parse_num(line, d)) return N(d);
                        return S(line);
                    }

                    /// Limits nesting of method calls, like the interpreter (endless recursion check).
                    inline int& depth() {
                        static int d = 0;
                        return d;
                    }
                    struct DepthGuard {
                        DepthGuard() {
                            if (++depth() > 400) {
                                --depth();
                                throw std::runtime_error("too many nested method calls (endless recursion?)");
                            }
                        }
                        ~DepthGuard() { --depth(); }
                    """);
            if (cpp11) {
                s.append("""
                            DepthGuard(const DepthGuard&) = delete;
                            DepthGuard& operator=(const DepthGuard&) = delete;
                        };

                        """);
            } else {
                s.append("""
                        private:
                            DepthGuard(const DepthGuard&);
                            DepthGuard& operator=(const DepthGuard&);
                        };

                        """);
            }
        }
        s.append("/// Objects are created on first use.\n");
        if (v >= 14) {
            s.append("""
                    template <class T>
                    T& obj(std::unique_ptr<T>& p) {
                        if (!p) p = std::make_unique<T>();
                        return *p;
                    }
                    """);
        } else if (cpp11) {
            s.append("""
                    template <class T>
                    T& obj(std::unique_ptr<T>& p) {
                        if (!p) p.reset(new T());
                        return *p;
                    }
                    """);
        } else {
            s.append("""
                    template <class T>
                    T& obj(T*& p) {
                        if (!p) p = new T();
                        return *p;
                    }
                    """);
        }
        s.append("""

                }  // namespace evia_rt
                // ===================================================================
                """);
        return s.toString();
    }

    /** C++17 / C++20 with fast values. */
    private static String modernFast(Target t) {
        return """
                // ===================== EVIA Paradigma runtime (%s, fast values) =====================
                #include <charconv>
                #include <cmath>
                #include <iostream>
                #include <memory>
                #include <stdexcept>
                #include <string>
                #include <system_error>

                namespace evia_rt {

                /// Number text exactly like the interpreter: whole numbers without ".0", others like Java's Double.toString.
                inline std::string str(double d) {
                    if (std::isnan(d)) return "NaN";
                    if (std::isinf(d)) return d > 0 ? "Infinity" : "-Infinity";
                    if (d == std::rint(d) && std::fabs(d) < 1e15) return std::to_string(static_cast<long long>(d));
                    char buf[64];
                    double a = std::fabs(d);
                    if (a >= 1e-3 && a < 1e7) {
                        auto r = std::to_chars(buf, buf + sizeof buf, d, std::chars_format::fixed);
                        return std::string(buf, r.ptr);
                    }
                    auto r = std::to_chars(buf, buf + sizeof buf, d, std::chars_format::scientific);
                    std::string s(buf, r.ptr);
                    auto e = s.find('e');
                    std::string mant = s.substr(0, e);
                    if (mant.find('.') == std::string::npos) mant += ".0";
                    return mant + "E" + std::to_string(std::stoi(s.substr(e + 1)));
                }

                /// Input block (fast values): the line must be a number.
                inline double input_num(const char* name) {
                    std::cout << "Enter a value for " << name << ": " << std::flush;
                    std::string line;
                    if (!std::getline(std::cin, line)) throw std::runtime_error("input cancelled");
                    if (!line.empty() && line.back() == '\\r') line.pop_back();
                    auto a = line.find_first_not_of(" \\t\\r\\n");
                    auto b = line.find_last_not_of(" \\t\\r\\n");
                    if (a != std::string::npos) {
                        const char* first = line.data() + a;
                        const char* last = line.data() + b + 1;
                        if (*first == '+' && last - first > 1) ++first;
                        double d = 0;
                        auto r = std::from_chars(first, last, d);
                        if (r.ec == std::errc() && r.ptr == last) return d;
                    }
                    throw std::runtime_error("'" + line + "' is not a number");
                }

                /// Objects are created on first use.
                template <class T>
                T& obj(std::unique_ptr<T>& p) {
                    if (!p) p = std::make_unique<T>();
                    return *p;
                }

                }  // namespace evia_rt
                // ===================================================================
                """.formatted(t.lang());
    }

    // ================================================================ C

    /** One piece of the C runtime: the names it defines, the names it needs, its code. */
    private record Piece(List<String> defines, List<String> needs, String code) {}

    private static final Map<String, Piece> C_PIECES = new LinkedHashMap<>();

    private static void piece(String defines, String needs, String code) {
        Piece p = new Piece(List.of(defines.split(" ")), needs.isEmpty() ? List.of() : List.of(needs.split(" ")), code);
        for (String d : p.defines()) C_PIECES.put(d, p);
    }

    static {
        piece("Value RT_UNDEFINED RT_NUMBER RT_TEXT", "", """
                /* A value: undefined, a number or a text. Text kept in variables and fields is owned
                   (malloc); text made while evaluating an expression lives in scratch memory (rt_tmp). */
                typedef struct {
                    int kind;
                    double n;
                    const char *s;
                } Value;
                enum { RT_UNDEFINED, RT_NUMBER, RT_TEXT };
                """);
        piece("rt_fail rt_context", "", """
                static const char *rt_context = "";   /* prefix while a field gets its initial value */

                NORETURN void rt_fail(const char *msg) {
                    fflush(stdout);
                    fprintf(stderr, ">> error: %s%s\\n", rt_context, msg);
                    exit(1);
                }
                """);
        piece("rt_fail3", "rt_fail", """
                NORETURN void rt_fail3(const char *a, const char *b, const char *c) {
                    fflush(stdout);
                    fprintf(stderr, ">> error: %s%s%s%s\\n", rt_context, a, b, c);
                    exit(1);
                }
                """);
        piece("rt_alloc", "rt_fail", """
                static void *rt_alloc(size_t n) {
                    void *p = calloc(1, n ? n : 1);
                    if (!p) rt_fail("out of memory");
                    return p;
                }
                """);
        piece("rt_tmp rt_chunk rt_arena", "rt_alloc", """
                /* Scratch memory for text made while evaluating an expression; rt_reset() releases it. */
                typedef struct rt_chunk {
                    struct rt_chunk *next;
                    size_t used, size;
                    char data[];
                } rt_chunk;
                static rt_chunk *rt_arena;

                static char *rt_tmp(size_t n) {
                    char *p;
                    if (!rt_arena || rt_arena->size - rt_arena->used < n) {
                        size_t size = n > 4096 ? n : 4096;
                        rt_chunk *c = (rt_chunk *) rt_alloc(sizeof(rt_chunk) + size);
                        c->next = rt_arena;
                        c->size = size;
                        rt_arena = c;
                    }
                    p = rt_arena->data + rt_arena->used;
                    rt_arena->used += n;
                    return p;
                }
                """);
        piece("rt_reset", "rt_tmp", """
                static void rt_reset(void) {
                    if (!rt_arena) return;
                    while (rt_arena->next) {   /* keep the newest chunk for the next expression */
                        rt_chunk *old = rt_arena->next;
                        rt_arena->next = old->next;
                        free(old);
                    }
                    rt_arena->used = 0;
                }
                """);
        piece("rt_N", "Value", """
                static Value rt_N(double d) {
                    Value v;
                    v.kind = RT_NUMBER;
                    v.n = d;
                    v.s = NULL;
                    return v;
                }
                """);
        piece("rt_S", "Value", """
                static Value rt_S(const char *s) {
                    Value v;
                    v.kind = RT_TEXT;
                    v.n = 0;
                    v.s = s;
                    return v;
                }
                """);
        piece("rt_B", "rt_N", """
                static Value rt_B(int b) { return rt_N(b ? 1.0 : 0.0); }
                """);
        piece("rt_rd", "Value rt_fail3", """
                /* Read a program variable; reading one that was never assigned is an error. */
                static Value rt_rd(const Value *x, const char *name) {
                    if (x->kind == RT_UNDEFINED) rt_fail3("undefined variable '", name, "'");
                    return *x;
                }
                """);
        piece("rt_set", "Value rt_alloc", """
                /* Store a value in a variable or field; text is copied so it outlives scratch memory. */
                static void rt_set(Value *dst, Value v) {
                    char *copy = NULL;
                    if (v.kind == RT_TEXT) {
                        size_t n = strlen(v.s) + 1;
                        copy = (char *) rt_alloc(n);
                        memcpy(copy, v.s, n);
                    }
                    if (dst->kind == RT_TEXT) free((void *) dst->s);
                    *dst = v;
                    dst->s = copy;
                }
                """);
        piece("rt_fmtbuf", "", """
                /* Number text exactly like the interpreter: whole numbers without ".0", others like Java's Double.toString. */
                static void rt_fmtbuf(double d, char out[48]) {
                    char buf[40], digits[24], *o = out;
                    const char *s;
                    int p, i, nd = 0, exp;
                    double a = fabs(d);
                    if (d != d) {
                        strcpy(out, "NaN");
                        return;
                    }
                    if (a == HUGE_VAL) {
                        strcpy(out, d > 0 ? "Infinity" : "-Infinity");
                        return;
                    }
                    if (d == floor(d) && a < 1e15) {
                        snprintf(out, 48, "%.0f", d == 0 ? 0.0 : d);
                        return;
                    }
                    for (p = 1; p <= 17; p++) {   /* the shortest digits that read back as the same number */
                        snprintf(buf, sizeof buf, "%.*e", p - 1, d);
                        if (strtod(buf, NULL) == d) break;
                    }
                    for (s = buf[0] == '-' ? buf + 1 : buf; *s && *s != 'e'; s++) {
                        if (*s != '.') digits[nd++] = *s;
                    }
                    exp = atoi(s + 1);
                    if (d < 0) *o++ = '-';
                    if (a >= 1e-3 && a < 1e7) {
                        if (exp >= 0) {
                            for (i = 0; i <= exp; i++) *o++ = i < nd ? digits[i] : '0';
                            *o++ = '.';
                            if (nd > exp + 1) {
                                for (i = exp + 1; i < nd; i++) *o++ = digits[i];
                            } else {
                                *o++ = '0';
                            }
                        } else {
                            *o++ = '0';
                            *o++ = '.';
                            for (i = 0; i < -exp - 1; i++) *o++ = '0';
                            for (i = 0; i < nd; i++) *o++ = digits[i];
                        }
                        *o = '\\0';
                    } else {
                        *o++ = digits[0];
                        *o++ = '.';
                        if (nd > 1) {
                            for (i = 1; i < nd; i++) *o++ = digits[i];
                        } else {
                            *o++ = '0';
                        }
                        snprintf(o, 12, "E%d", exp);
                    }
                }
                """);
        piece("rt_str", "Value rt_fmtbuf rt_tmp", """
                static const char *rt_str(Value x) {
                    char buf[48], *t;
                    if (x.kind == RT_TEXT) return x.s;
                    rt_fmtbuf(x.n, buf);
                    t = rt_tmp(strlen(buf) + 1);
                    strcpy(t, buf);
                    return t;
                }
                """);
        piece("rt_parse", "", """
                /* Text that reads as a number: digits, sign, point and exponent; spaces around and a leading '+' allowed. */
                static int rt_parse(const char *s, double *out) {
                    const char *a = s, *b = s + strlen(s), *p;
                    char *end;
                    double d;
                    while (*a == ' ' || *a == '\\t' || *a == '\\r' || *a == '\\n') a++;
                    while (b > a && (b[-1] == ' ' || b[-1] == '\\t' || b[-1] == '\\r' || b[-1] == '\\n')) b--;
                    if (b - a > 1 && *a == '+') a++;
                    if (a == b || *a == '+') return 0;
                    for (p = a; p < b; p++) {
                        if (!strchr("0123456789.eE+-", *p)) return 0;
                    }
                    d = strtod(a, &end);
                    if (end != b) return 0;
                    *out = d;
                    return 1;
                }
                """);
        piece("rt_num", "Value rt_parse rt_fail3", """
                static double rt_num(Value x) {
                    double d = 0;
                    if (x.kind == RT_NUMBER) return x.n;
                    if (!rt_parse(x.s, &d)) rt_fail3("'", x.s, "' is not a number");
                    return d;
                }
                """);
        piece("rt_truthy", "Value", """
                static int rt_truthy(Value x) { return x.kind == RT_NUMBER ? x.n != 0.0 : x.s[0] != '\\0'; }
                """);
        piece("rt_nonzero", "rt_fail", """
                static double rt_nonzero(double d) {
                    if (d == 0.0) rt_fail("division by zero");
                    return d;
                }
                """);
        piece("rt_add", "rt_str rt_N rt_S rt_tmp", """
                static Value rt_add(Value a, Value b) {
                    if (a.kind == RT_TEXT || b.kind == RT_TEXT) {
                        const char *x = rt_str(a), *y = rt_str(b);
                        size_t nx = strlen(x), ny = strlen(y);
                        char *t = rt_tmp(nx + ny + 1);
                        memcpy(t, x, nx);
                        memcpy(t + nx, y, ny + 1);
                        return rt_S(t);
                    }
                    return rt_N(a.n + b.n);
                }
                """);
        piece("rt_sub", "rt_num rt_N", """
                static Value rt_sub(Value a, Value b) { double x = rt_num(a), y = rt_num(b); return rt_N(x - y); }
                """);
        piece("rt_mul", "rt_num rt_N", """
                static Value rt_mul(Value a, Value b) { double x = rt_num(a), y = rt_num(b); return rt_N(x * y); }
                """);
        piece("rt_div", "rt_num rt_N rt_nonzero", """
                static Value rt_div(Value a, Value b) { double x = rt_num(a), y = rt_num(b); return rt_N(x / rt_nonzero(y)); }
                """);
        piece("rt_mod", "rt_num rt_N rt_nonzero", """
                static Value rt_mod(Value a, Value b) { double x = rt_num(a), y = rt_num(b); return rt_N(fmod(x, rt_nonzero(y))); }
                """);
        piece("rt_neg", "rt_num rt_N", """
                static Value rt_neg(Value a) { return rt_N(-rt_num(a)); }
                """);
        piece("rt_not", "rt_truthy rt_B", """
                static Value rt_not(Value a) { return rt_B(!rt_truthy(a)); }
                """);
        piece("rt_equal", "Value rt_fmtbuf", """
                static int rt_equal(Value a, Value b) {
                    char x[48], y[48];
                    const char *s = a.s, *t = b.s;
                    if (a.kind == RT_NUMBER && b.kind == RT_NUMBER) return a.n == b.n;
                    if (a.kind == RT_NUMBER) {
                        rt_fmtbuf(a.n, x);
                        s = x;
                    }
                    if (b.kind == RT_NUMBER) {
                        rt_fmtbuf(b.n, y);
                        t = y;
                    }
                    return strcmp(s, t) == 0;
                }
                """);
        piece("rt_compare", "rt_num", """
                static int rt_compare(Value a, Value b) {
                    double x, y;
                    if (a.kind == RT_TEXT && b.kind == RT_TEXT) {
                        int c = strcmp(a.s, b.s);
                        return (c > 0) - (c < 0);
                    }
                    x = rt_num(a);
                    y = rt_num(b);
                    return (x > y) - (x < y);
                }
                """);
        piece("rt_eq", "rt_equal rt_B", "static Value rt_eq(Value a, Value b) { return rt_B(rt_equal(a, b)); }\n");
        piece("rt_ne", "rt_equal rt_B", "static Value rt_ne(Value a, Value b) { return rt_B(!rt_equal(a, b)); }\n");
        piece("rt_lt", "rt_compare rt_B", "static Value rt_lt(Value a, Value b) { return rt_B(rt_compare(a, b) < 0); }\n");
        piece("rt_le", "rt_compare rt_B", "static Value rt_le(Value a, Value b) { return rt_B(rt_compare(a, b) <= 0); }\n");
        piece("rt_gt", "rt_compare rt_B", "static Value rt_gt(Value a, Value b) { return rt_B(rt_compare(a, b) > 0); }\n");
        piece("rt_ge", "rt_compare rt_B", "static Value rt_ge(Value a, Value b) { return rt_B(rt_compare(a, b) >= 0); }\n");
        piece("rt_print", "Value rt_fmtbuf", """
                static void rt_print(Value x) {
                    char buf[48];
                    if (x.kind == RT_TEXT) {
                        fputs(x.s, stdout);
                    } else {
                        rt_fmtbuf(x.n, buf);
                        fputs(buf, stdout);
                    }
                    putchar('\\n');
                }
                """);
        piece("rt_put_num", "rt_fmtbuf", """
                static void rt_put_num(double d) {
                    char buf[48];
                    rt_fmtbuf(d, buf);
                    fputs(buf, stdout);
                }
                """);
        piece("rt_readline", "rt_alloc rt_tmp rt_fail", """
                /* One line from standard input in scratch memory, without the line break; NULL at the end of input. */
                static char *rt_readline(void) {
                    size_t len = 0, cap = 128;
                    char *line = (char *) rt_alloc(cap), *t;
                    int ch;
                    while ((ch = getchar()) != EOF && ch != '\\n') {
                        if (len + 1 >= cap) {
                            char *bigger = (char *) realloc(line, cap *= 2);
                            if (!bigger) rt_fail("out of memory");
                            line = bigger;
                        }
                        line[len++] = (char) ch;
                    }
                    if (ch == EOF && len == 0) {
                        free(line);
                        return NULL;
                    }
                    if (len > 0 && line[len - 1] == '\\r') len--;
                    line[len] = '\\0';
                    t = rt_tmp(len + 1);
                    memcpy(t, line, len + 1);
                    free(line);
                    return t;
                }
                """);
        piece("rt_input", "rt_readline rt_parse rt_N rt_S rt_fail", """
                /* Input block: a line that reads as a number becomes a number, otherwise a text. */
                static Value rt_input(const char *name) {
                    char *line;
                    double d = 0;
                    printf("Enter a value for %s: ", name);
                    fflush(stdout);
                    line = rt_readline();
                    if (!line) rt_fail("input cancelled");
                    if (rt_parse(line, &d)) return rt_N(d);
                    return rt_S(line);
                }
                """);
        piece("rt_input_num", "rt_readline rt_parse rt_fail rt_fail3 rt_reset", """
                /* Input block (fast values): the line must be a number. */
                static double rt_input_num(const char *name) {
                    char *line;
                    double d = 0;
                    printf("Enter a value for %s: ", name);
                    fflush(stdout);
                    line = rt_readline();
                    if (!line) rt_fail("input cancelled");
                    if (!rt_parse(line, &d)) rt_fail3("'", line, "' is not a number");
                    rt_reset();
                    return d;
                }
                """);
        piece("rt_enter rt_depth", "rt_fail", """
                /* Limits nesting of method calls, like the interpreter (endless recursion check). */
                static int rt_depth;

                static void rt_enter(void) {
                    if (++rt_depth > 400) {
                        --rt_depth;
                        rt_fail("too many nested method calls (endless recursion?)");
                    }
                }
                """);
        piece("rt_leave", "rt_enter", """
                static void rt_leave(void) { --rt_depth; }
                """);
    }

    private static final Pattern RT_NAME = Pattern.compile("\\b(rt_[A-Za-z0-9_]+|RT_[A-Z_]+|Value)\\b");

    /** Arduino sketch or bare-metal AVR runtime (see {@link EmbeddedRuntime}). */
    static String embedded(Target t, String program) {
        return EmbeddedRuntime.source(t, program);
    }

    /** The C runtime pieces {@code program} uses (and what they need), in a valid order. */
    static String c(Target t, String program) {
        Set<Piece> needed = new LinkedHashSet<>();
        // look at code only: names inside comments and string literals don't count
        String code = program.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        Matcher m = RT_NAME.matcher(code);
        List<String> todo = new ArrayList<>();
        while (m.find()) todo.add(m.group(1));
        while (!todo.isEmpty()) {
            Piece p = C_PIECES.get(todo.remove(todo.size() - 1));
            if (p != null && needed.add(p)) todo.addAll(p.needs());
        }
        String noreturn = t.lang() == Target.Lang.C99 ? "static" : "_Noreturn static";
        StringBuilder s = new StringBuilder();
        s.append("/* ===================== EVIA Paradigma runtime (").append(t.lang()).append(t.fast() ? ", fast values" : "")
                .append(") ===================== */\n");
        s.append("""
                #include <math.h>
                #include <stdio.h>
                #include <stdlib.h>
                #include <string.h>
                """);
        for (Piece p : new LinkedHashSet<>(C_PIECES.values())) {
            if (needed.contains(p)) s.append('\n').append(p.code().replace("NORETURN", noreturn));
        }
        s.append("/* =================================================================== */\n");
        return s.toString();
    }
}
