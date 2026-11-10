
// ---- safe values: a value is a Double or a String; a program variable is null until it is assigned

fun rt_rd(x: Any?, name: String): Any = x ?: throw EviaError("undefined variable '$name'")

fun rt_num(x: Any): Double {
    if (x is Double) return x
    return rt_parse(x as String) ?: throw EviaError("'$x' is not a number")
}

fun rt_text(x: Any): String = if (x is Double) rt_text(x) else x as String   // x is a Double here: the number version

fun rt_truthy(x: Any): Boolean = if (x is Double) x != 0.0 else (x as String).isNotEmpty()

fun rt_B(b: Boolean): Any = if (b) 1.0 else 0.0

fun rt_non_zero(d: Double): Double {
    if (d == 0.0) throw EviaError("division by zero")
    return d
}

fun rt_add(a: Any, b: Any): Any {
    if (a is String || b is String) return rt_text(a) + rt_text(b)
    return (a as Double) + (b as Double)
}

fun rt_sub(a: Any, b: Any): Any = rt_num(a) - rt_num(b)
fun rt_mul(a: Any, b: Any): Any = rt_num(a) * rt_num(b)

fun rt_div(a: Any, b: Any): Any {
    val x = rt_num(a)
    return x / rt_non_zero(rt_num(b))
}

fun rt_mod(a: Any, b: Any): Any {
    val x = rt_num(a)
    return x % rt_non_zero(rt_num(b))
}

fun rt_neg(a: Any): Any = -rt_num(a)
fun rt_not(a: Any): Any = rt_B(!rt_truthy(a))

/** Both statically Double, so == compares as numbers (NaN is not equal to itself, -0.0 equals 0.0). */
fun rt_same(x: Double, y: Double): Boolean = x == y

fun rt_equal(a: Any, b: Any): Boolean {
    if (a is Double && b is Double) return rt_same(a, b)
    return rt_text(a) == rt_text(b)
}

fun rt_compare(a: Any, b: Any): Int {
    if (a is String && b is String) return a.compareTo(b)
    return java.lang.Double.compare(rt_num(a), rt_num(b))
}

fun rt_eq(a: Any, b: Any): Any = rt_B(rt_equal(a, b))
fun rt_ne(a: Any, b: Any): Any = rt_B(!rt_equal(a, b))
fun rt_lt(a: Any, b: Any): Any = rt_B(rt_compare(a, b) < 0)
fun rt_le(a: Any, b: Any): Any = rt_B(rt_compare(a, b) <= 0)
fun rt_gt(a: Any, b: Any): Any = rt_B(rt_compare(a, b) > 0)
fun rt_ge(a: Any, b: Any): Any = rt_B(rt_compare(a, b) >= 0)

fun rt_print(x: Any) {
    rt_print_line(rt_text(x))
}

/** Input block: a line that reads as a number becomes a number, otherwise text. */
fun rt_input(name: String): Any {
    val line = rt_read_line(name)
    return rt_parse(line) ?: line
}

/** Nested method calls, limited like the interpreter (an endless recursion stops with an error). */
var rt_depth = 0

fun rt_enter() {
    if (rt_depth >= 400) throw EviaError("too many nested method calls (endless recursion?)")
    rt_depth++
}
