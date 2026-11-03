package evia.paradigma;

/**
 * The small C++20 runtime embedded at the top of every generated program.
 * It reproduces the interpreter's value semantics exactly (see {@link Expr}):
 * values are numbers or strings, booleans are 1/0, "+" concatenates when either
 * side is a string, program variables must be assigned before use, fields start at 0.
 */
final class CppRuntime {
    private CppRuntime() {}

    static final String SOURCE = """
            // ===================== EVIA Paradigma runtime =====================
            #include <cerrno>
            #include <charconv>
            #include <cmath>
            #include <iostream>
            #include <memory>
            #include <stdexcept>
            #include <string>
            #include <system_error>
            #include <utility>
            #include <variant>

            namespace evia_rt {

            /// A dynamically typed value: undefined, a number or a string.
            struct Value {
                std::variant<std::monostate, double, std::string> v;
                Value() = default;
                Value(double d) : v(d) {}
                Value(std::string s) : v(std::move(s)) {}
                [[nodiscard]] bool isNum() const { return std::holds_alternative<double>(v); }
                [[nodiscard]] bool isStr() const { return std::holds_alternative<std::string>(v); }
                [[nodiscard]] bool defined() const { return !std::holds_alternative<std::monostate>(v); }
            };

            inline Value N(double d) { return Value(d); }
            inline Value S(std::string s) { return Value(std::move(s)); }
            inline Value B(bool b) { return Value(b ? 1.0 : 0.0); }

            /// Read a program variable; reading one that was never assigned is an error.
            inline const Value& rd(const Value& x, const char* name) {
                if (!x.defined()) throw std::runtime_error(std::string("undefined variable '") + name + "'");
                return x;
            }

            /// Same text as the interpreter: whole numbers without ".0", others like Java's Double.toString.
            inline std::string str(const Value& x) {
                if (x.isStr()) return std::get<std::string>(x.v);
                double d = std::get<double>(x.v);
                if (std::isnan(d)) return "NaN";
                if (std::isinf(d)) return d > 0 ? "Infinity" : "-Infinity";
                if (d == std::rint(d) && std::fabs(d) < 1e15) return std::to_string(static_cast<long long>(d));
                char buf[64];
                double a = std::fabs(d);
                if (a >= 1e-3 && a < 1e7) {
                    auto r = std::to_chars(buf, buf + sizeof buf, d, std::chars_format::fixed);
                    return std::string(buf, r.ptr);
                }
                // scientific: 1.2345e+25 -> 1.2345E25,  1e-05 -> 1.0E-5
                auto r = std::to_chars(buf, buf + sizeof buf, d, std::chars_format::scientific);
                std::string s(buf, r.ptr);
                auto e = s.find('e');
                std::string mant = s.substr(0, e);
                if (mant.find('.') == std::string::npos) mant += ".0";
                return mant + "E" + std::to_string(std::stoi(s.substr(e + 1)));
            }

            inline double num(const Value& x) {
                if (x.isNum()) return std::get<double>(x.v);
                const std::string& s = std::get<std::string>(x.v);
                auto a = s.find_first_not_of(" \\t\\r\\n");
                auto b = s.find_last_not_of(" \\t\\r\\n");
                if (a != std::string::npos) {
                    const char* first = s.data() + a;
                    const char* last = s.data() + b + 1;
                    if (*first == '+' && last - first > 1) ++first;
                    double d = 0;
                    auto r = std::from_chars(first, last, d);
                    if (r.ec == std::errc() && r.ptr == last) return d;
                }
                throw std::runtime_error("'" + s + "' is not a number");
            }

            inline bool truthy(const Value& x) {
                if (x.isNum()) return std::get<double>(x.v) != 0.0;
                return !std::get<std::string>(x.v).empty();
            }

            inline double nonZero(double d) {
                if (d == 0.0) throw std::runtime_error("division by zero");
                return d;
            }

            inline Value operator+(const Value& a, const Value& b) {
                if (a.isStr() || b.isStr()) return S(str(a) + str(b));
                return N(num(a) + num(b));
            }
            inline Value operator-(const Value& a, const Value& b) { return N(num(a) - num(b)); }
            inline Value operator*(const Value& a, const Value& b) { return N(num(a) * num(b)); }
            inline Value operator/(const Value& a, const Value& b) { return N(num(a) / nonZero(num(b))); }
            inline Value operator%(const Value& a, const Value& b) { return N(std::fmod(num(a), nonZero(num(b)))); }
            inline Value neg(const Value& a) { return N(-num(a)); }
            inline Value lnot(const Value& a) { return B(!truthy(a)); }

            inline bool equal(const Value& a, const Value& b) {
                if (a.isNum() && b.isNum()) return std::get<double>(a.v) == std::get<double>(b.v);
                return str(a) == str(b);
            }
            inline int compare(const Value& a, const Value& b) {
                if (a.isStr() && b.isStr()) {
                    int c = std::get<std::string>(a.v).compare(std::get<std::string>(b.v));
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

            /// Input block: a line that parses as a number becomes a number, otherwise a string.
            inline Value input(const char* name) {
                std::cout << "Enter a value for " << name << ": " << std::flush;
                std::string line;
                if (!std::getline(std::cin, line)) throw std::runtime_error("input cancelled");
                if (!line.empty() && line.back() == '\\r') line.pop_back();
                try {
                    return N(num(S(line)));
                } catch (const std::runtime_error&) {
                    return S(line);
                }
            }

            /// Limits nesting of custom block calls, like the interpreter (endless recursion check).
            inline int depth = 0;
            struct DepthGuard {
                DepthGuard() {
                    if (++depth > 400) {
                        --depth;
                        throw std::runtime_error("too many nested calls of custom blocks (endless recursion?)");
                    }
                }
                ~DepthGuard() { --depth; }
                DepthGuard(const DepthGuard&) = delete;
                DepthGuard& operator=(const DepthGuard&) = delete;
            };

            /// Objects inside an object are created on first use.
            template <class T>
            T& obj(std::unique_ptr<T>& p) {
                if (!p) p = std::make_unique<T>();
                return *p;
            }

            }  // namespace evia_rt
            // ===================================================================
            """;
}
