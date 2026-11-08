
# ---- fast values: plain floats, text only in Print, no checks (dividing by zero gives Infinity or NaN, as in C)

def rt_div(a, b):
    try:
        return a / b
    except ZeroDivisionError:
        if a != a or a == 0:
            return math.nan
        return math.copysign(math.inf, a) * math.copysign(1.0, b)


def rt_mod(a, b):
    try:
        return math.fmod(a, b)
    except ValueError:
        return math.nan


def rt_input_num(name):
    """Input block (fast values): the line must be a number."""
    line = rt_read_line(name)
    d = rt_parse(line)
    if d is None:
        raise EviaError("'" + line + "' is not a number")
    return d
