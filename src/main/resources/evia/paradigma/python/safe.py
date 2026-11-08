
# ---- safe values: a value is a float or a str; a program variable is None until it is assigned

def rt_rd(x, name):
    """Reading a program variable that was never assigned is an error."""
    if x is None:
        raise EviaError("undefined variable '" + name + "'")
    return x


def rt_num(x):
    if isinstance(x, float):
        return x
    d = rt_parse(x)
    if d is None:
        raise EviaError("'" + x + "' is not a number")
    return d


def rt_truthy(x):
    return x != 0.0 if isinstance(x, float) else x != ""


def rt_B(b):
    return 1.0 if b else 0.0


def _non_zero(d):
    if d == 0.0:
        raise EviaError("division by zero")
    return d


def rt_add(a, b):
    if isinstance(a, str) or isinstance(b, str):
        return rt_text(a) + rt_text(b)
    return a + b


def rt_sub(a, b):
    return rt_num(a) - rt_num(b)


def rt_mul(a, b):
    return rt_num(a) * rt_num(b)


def rt_div(a, b):
    x = rt_num(a)
    y = _non_zero(rt_num(b))
    return x / y


def rt_mod(a, b):
    x = rt_num(a)
    y = _non_zero(rt_num(b))
    try:
        return math.fmod(x, y)               # the sign of the left side, as in Java and C
    except ValueError:
        return math.nan


def rt_neg(a):
    return -rt_num(a)


def rt_not(a):
    return rt_B(not rt_truthy(a))


def _equal(a, b):
    if isinstance(a, float) and isinstance(b, float):
        return a == b
    return rt_text(a) == rt_text(b)


def _compare(a, b):
    if isinstance(a, str) and isinstance(b, str):
        return (a > b) - (a < b)
    x, y = rt_num(a), rt_num(b)
    if x < y:
        return -1
    if x > y:
        return 1
    # equal, or NaN: Java's Double.compare puts NaN above everything and -0.0 below 0.0
    xn, yn = x != x, y != y
    if xn or yn:
        return xn - yn
    sx, sy = math.copysign(1.0, x), math.copysign(1.0, y)
    return (sx > sy) - (sx < sy)


def rt_eq(a, b):
    return rt_B(_equal(a, b))


def rt_ne(a, b):
    return rt_B(not _equal(a, b))


def rt_lt(a, b):
    return rt_B(_compare(a, b) < 0)


def rt_le(a, b):
    return rt_B(_compare(a, b) <= 0)


def rt_gt(a, b):
    return rt_B(_compare(a, b) > 0)


def rt_ge(a, b):
    return rt_B(_compare(a, b) >= 0)


def rt_print(x):
    rt_print_line(rt_text(x))


def rt_input(name):
    """Input block: a line that reads as a number becomes a number, otherwise text."""
    line = rt_read_line(name)
    d = rt_parse(line)
    return line if d is None else d


_depth = 0
sys.setrecursionlimit(max(sys.getrecursionlimit(), 5000))


def rt_method(method):
    """Limits nesting of method calls like the interpreter (an endless recursion stops with an error)."""
    def call(*args):
        global _depth
        if _depth >= 400:
            raise EviaError("too many nested method calls (endless recursion?)")
        _depth += 1
        try:
            return method(*args)
        finally:
            _depth -= 1
    call.__name__ = method.__name__
    call.__doc__ = method.__doc__
    return call
