"""Test programs for the FPGA generator, written with a tiny builder.

Each test is a finite flowchart.  `kind` says how it is checked:
  sim       the interpreter's output (pin changes, printed text) must match what the generated HDL does
  expected  the printed text must equal `expect` (for things the double-based simulator can't show, e.g. 32-bit wrap)
"""
from urllib.parse import quote_plus


class P:
    def __init__(self):
        self.blocks = []
        self.edges = []

    def add(self, t, code=""):
        self.blocks.append((t, code))
        return len(self.blocks)

    def link(self, a, b, port=0):
        self.edges.append((a, port, b))

    def seq(self, *items):
        """Adds blocks (type, code) one after the other and returns (first, last) ids."""
        ids = [self.add(*it) for it in items]
        for a, b in zip(ids, ids[1:]):
            self.link(a, b)
        return ids[0], ids[-1]

    def text(self):
        out = ["# EVIA Paradigma program v3"]
        for i, (t, c) in enumerate(self.blocks, 1):
            out.append(f"B|M|{i}|{t}|300|{20 + (i - 1) * 90}|{quote_plus(c)}|-|-|")
        for a, p, b in self.edges:
            out.append(f"L|M|{a}|{p}|{b}")
        return "\n".join(out) + "\n"


def straight(*items):
    p = P()
    first, last = p.seq(("START",), *items, ("END",))
    return p


def arith():
    return straight(
        ("SET", "a = 12"), ("SET", "b = 5"),
        ("PRINT", '"sum " + (a + b) + " diff " + (a - b) + " prod " + a * b'),
        ("PRINT", '"neg " + -a + " mod " + a % b + " negmod " + -a % b + " modneg " + a % -b'),
        ("SET", "x = 84"), ("SET", "y = -7"),
        ("PRINT", '"exact " + x / y + " " + -x / y + " " + x / 7 + " " + -x / -7'),
        ("PRINT", '"rem " + x % y + " " + (x + 5) % y + " " + (x + 5) % -y + " " + -(x + 5) % y'),
        ("SET", "big = 46340"), ("PRINT", '"square " + big * big'),
        ("SET", "m = 2147483647"), ("PRINT", '"max " + m + " " + (0 - m) + " " + (0 - m - 1)'),
        ("PRINT", '"zero " + 0 + " ten " + 10 + " hundred " + 100 + " " + 1000000000'),
        ("PRINT", '(a + b) * 2 - 3 * (b - a) / 3'),
    )


def logic():
    items = [("SET", "a = 3"), ("SET", "b = 7"), ("SET", "z = 0")]
    for e in ["a < b", "a <= b", "a > b", "a >= b", "a == b", "a != b", "a == 3", "!a", "!z", "a && b", "a && z", "z || b", "z || z",
              "a < b && b < 10", "a > b || b > 5", "!(a < b)", "-a < z", "a + b == 10"]:
        items.append(("PRINT", f'"{e.replace(chr(34), "")} -> " + ({e})'))
    return straight(*items)


def loops():
    p = P()
    s = p.add("START")
    init = p.add("SET", "sum = 0")
    p.link(s, init)
    f = p.add("FOR", "i = 1; i <= 10; i = i + 1")
    p.link(init, f)
    body = p.add("SET", "sum = sum + i")
    p.link(f, body, 0)
    p.link(body, f)
    pr = p.add("PRINT", '"for sum " + sum')
    p.link(f, pr, 1)
    c = p.add("SET", "n = 5")
    p.link(pr, c)
    w = p.add("WHILE", "n > 0")
    p.link(c, w)
    wb = p.add("SET", "n = n - 2")
    p.link(w, wb, 0)
    p.link(wb, w)
    pw = p.add("PRINT", '"while n " + n')
    p.link(w, pw, 1)
    d0 = p.add("SET", "k = 10")
    p.link(pw, d0)
    dw = p.add("DO_WHILE", "k < 5")
    p.link(d0, dw)
    db = p.add("PRINT", '"do-while body k " + k')
    p.link(dw, db, 0)
    dk = p.add("SET", "k = k + 1")
    p.link(db, dk)
    p.link(dk, dw)
    pd = p.add("PRINT", '"do-while done k " + k')
    p.link(dw, pd, 1)
    # nested: for i, for j, count i*j when (i + j) % 3 == 0
    z = p.add("SET", "t = 0")
    p.link(pd, z)
    fi = p.add("FOR", "i = 0; i < 6; i = i + 1")
    p.link(z, fi)
    fj = p.add("FOR", "j = 0; j < 6; j = j + 1")
    p.link(fi, fj, 0)
    iff = p.add("IF", "(i + j) % 3 == 0")
    p.link(fj, iff, 0)
    add = p.add("SET", "t = t + i * j")
    p.link(iff, add, 0)
    p.link(add, fj)
    p.link(iff, fj, 1)
    p.link(fj, fi, 1)
    pt = p.add("PRINT", '"nested " + t')
    p.link(fi, pt, 1)
    e = p.add("END")
    p.link(pt, e)
    return p


def prints():
    return straight(
        ("PRINT", '"plain text"'),
        ("PRINT", '""'),
        ("SET", "a = 7"),
        ("PRINT", 'a'),
        ("PRINT", '1 + 2 + "a"'),
        ("PRINT", '"a" + 1 + 2'),
        ("PRINT", '"x=" + a + ", y=" + -a + "!"'),
        ("SET", "z = 0"), ("PRINT", '"zero:" + z + ":"'),
        ("SET", "n = -2147483647"), ("PRINT", '"min+1 " + n + " min " + (n - 1)'),
        ("PRINT", '"digits " + 1234567890 + " " + 100000 + " " + 99'),
    )


def pins():
    p = P()
    first, last = p.seq(
        ("START",), ("PIN_MODE", "LED0, OUTPUT"), ("PIN_MODE", "LED1, OUTPUT"), ("PIN_MODE", "LED7, OUTPUT"),
        ("PIN_MODE", "SW0, INPUT"), ("PIN_MODE", "SW5, INPUT"), ("PIN_MODE", "BTN0, INPUT"),
        ("DIGITAL_WRITE", "LED0, HIGH"), ("DELAY", "3"), ("DIGITAL_WRITE", "LED1, 5"), ("DELAY", "10"),
        ("DIGITAL_WRITE", "LED0, LOW"), ("DELAY", "1"), ("DIGITAL_WRITE", "LED7, 2 > 1"), ("DELAY", "20"),
        ("DIGITAL_WRITE", "LED1, 0"), ("DELAY", "100"),
        ("DELAY", "400"),
        ("DIGITAL_READ", "early = SW0"), ("DELAY", "1100"),
        ("DIGITAL_READ", "late = SW0"), ("DIGITAL_READ", "other = SW5"), ("DIGITAL_READ", "button = BTN0"),
        ("PRINT", '"early " + early + " late " + late + " sw5 " + other + " btn " + button'),
        ("DIGITAL_WRITE", "LED0, late"), ("DELAY", "5"), ("DIGITAL_WRITE", "LED0, !late"), ("DELAY", "5"),
        ("END",))
    return p


def millis():
    return straight(
        ("MILLIS", "t0"), ("DELAY", "10"), ("MILLIS", "t1"), ("DELAY", "250"), ("MILLIS", "t2"),
        ("PRINT", '"waited " + (t1 - t0) + " then " + (t2 - t1)'),
        ("DELAY", "0"), ("DELAY", "-5"), ("PRINT", '"zero and negative delays return"'),
    )


def uart():
    return straight(
        ("UART_OPEN", "115200"),
        ("UART_WRITE", "72"), ("DELAY", "1"), ("UART_READ", "a"),
        ("UART_WRITE", "105"), ("DELAY", "1"), ("UART_READ", "b"),
        ("UART_WRITE", "300 - 290"), ("DELAY", "1"), ("UART_READ", "c"), ("UART_READ", "d"),
        ("PRINT", '"read " + a + " " + b + " " + c + " " + d'),
    )


def pwm():
    items = [("PIN_MODE", "LED0, OUTPUT")]
    for v in [0, 64, 128, 255, 300, -5, 191]:
        items += [("ANALOG_WRITE", f"LED0, {v}"), ("DELAY", "131")]
    items += [("DIGITAL_WRITE", "LED0, HIGH"), ("DELAY", "131"), ("DIGITAL_WRITE", "LED0, LOW"), ("DELAY", "131")]
    return straight(*items)


def divide_expected():
    """Divider check: a hash over i / j and i % j for many operands, computed here with 32-bit wrap-around."""
    p = P()
    s = p.add("START")
    z = p.add("SET", "acc = 7")
    p.link(s, z)
    fi = p.add("FOR", "i = -60; i <= 60; i = i + 7")
    p.link(z, fi)
    fj = p.add("FOR", "j = -9; j <= 9; j = j + 1")
    p.link(fi, fj, 0)
    nz = p.add("IF", "j != 0")
    p.link(fj, nz, 0)
    body = p.add("SET", "acc = (acc * 31 + i / j * 3 + i % j) % 1000003")
    p.link(nz, body, 0)
    p.link(body, fj)
    p.link(nz, fj, 1)
    p.link(fj, fi, 1)
    pr = p.add("PRINT", '"hash " + acc')
    p.link(fi, pr, 1)
    z2 = p.add("SET", "q = 0")
    p.link(pr, z2)
    p2 = p.add("PRINT", '"by zero " + 5 / q + " " + 5 % q + " " + -5 / q')
    p.link(z2, p2)
    e = p.add("END")
    p.link(p2, e)

    def trunc_div(a, b):
        q = abs(a) // abs(b)
        return q if (a < 0) == (b < 0) else -q

    def trunc_mod(a, b):
        return a - trunc_div(a, b) * b

    acc = 7
    for i in range(-60, 61, 7):
        for j in range(-9, 10):
            if j != 0:
                acc = trunc_mod(acc * 31 + trunc_div(i, j) * 3 + trunc_mod(i, j), 1000003)
    return p, [f"hash {acc}", "by zero 0 0 0"]


# name -> (builder, kind, run_ms, extras)
TESTS = {
    "arith": (arith, "sim", 50, {}),
    "logic": (logic, "sim", 50, {}),
    "loops": (loops, "sim", 80, {}),
    "prints": (prints, "sim", 50, {}),
    "pins": (pins, "sim", 2000, {}),
    "millis": (millis, "sim", 400, {"tolerant": True}),
    "uart": (uart, "sim", 40, {"loopback": True}),
    "pwm": (pwm, "pwm", 1250, {}),
    "divide": (lambda: divide_expected()[0], "expected", 400, {"expect": lambda: divide_expected()[1]}),
}
