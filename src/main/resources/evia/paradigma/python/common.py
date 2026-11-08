# ===================== EVIA Paradigma runtime (Python 3) =====================
import decimal
import math
import re
import sys

for _stream in (sys.stdin, sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")   # text in any language, on any console
    except (AttributeError, ValueError):
        pass


class EviaError(Exception):
    """An error the interpreter reports too: the program stops with '>> error: ...' and exit code 1."""


# A number as Java's Double.parseDouble reads it: spaces around, a sign, digits, a point, an exponent.
_NUMBER = re.compile(r"[+-]?(?:(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?|Infinity|NaN)")
_SPACE = "".join(chr(c) for c in range(33))


def rt_parse(s):
    """The number in a text, or None when the text is not one."""
    t = s.strip(_SPACE)
    if not _NUMBER.fullmatch(t):
        return None
    if t[-1] in "fFdD" and t[-2:] != "aN":
        t = t[:-1]
    return float(t)


def rt_text(x):
    """Text like the interpreter: whole numbers without ".0", others like Java's Double.toString."""
    if isinstance(x, str):
        return x
    if x != x:
        return "NaN"
    if x in (math.inf, -math.inf):
        return "Infinity" if x > 0 else "-Infinity"
    if x == math.floor(x) and abs(x) < 1e15:
        return str(int(x))
    # the shortest digits that read back as the same number, then Java's layout
    sign, digits, exponent = decimal.Decimal(repr(abs(x))).as_tuple()
    ds = "".join(map(str, digits))
    stripped = ds.rstrip("0")
    exponent += len(ds) - len(stripped)
    ds = stripped.lstrip("0") or "0"
    first = len(ds) - 1 + exponent           # the power of ten of the first digit
    if len(ds) == 1:
        # Java writes at least two digits, and takes the closer second digit when it still reads back
        # as the same number: 4.9E-324 rather than 5.0E-324
        two = format(decimal.Decimal(abs(x)), ".1e")
        if float(two) == abs(x):
            mantissa, _, power = two.partition("e")
            ds = mantissa.replace(".", "").rstrip("0") or "0"
            first = int(power)
    out = "-" if x < 0 else ""
    if 1e-3 <= abs(x) < 1e7:
        if first >= 0:
            whole = ds[:first + 1].ljust(first + 1, "0")
            return out + whole + "." + (ds[first + 1:] or "0")
        return out + "0." + "0" * (-first - 1) + ds
    return out + ds[0] + "." + (ds[1:] or "0") + "E" + str(first)


def rt_print_line(text):
    sys.stdout.write(text + "\n")


def rt_read_line(name):
    sys.stdout.write("Enter a value for " + name + ": ")
    sys.stdout.flush()
    line = sys.stdin.readline()
    if not line:
        raise EviaError("input cancelled")
    if line.endswith("\n"):
        line = line[:-1]
    if line.endswith("\r"):
        line = line[:-1]
    return line


def rt_obj(owner, name, cls):
    """Objects inside an object are created on first use."""
    obj = getattr(owner, name)
    if obj is None:
        obj = cls()
        setattr(owner, name, obj)
    return obj
