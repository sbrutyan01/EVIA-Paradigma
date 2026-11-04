#!/usr/bin/env python3
"""Cuts the parts that do not touch a chip out of a generated program: the number helpers and the MQTT client.

Usage: extract.py generated.c > core.c
"""
import re, sys

src = open(sys.argv[1], encoding="utf-8").read().split("\n")


def span(name):
    """The first and last line of a function, as indexes into src."""
    start = next(i for i, l in enumerate(src) if re.match(r"static .*\b" + name + r"\(", l))
    depth = 0
    for i in range(start, len(src)):
        depth += src[i].count("{") - src[i].count("}")
        if depth == 0 and "{" in "".join(src[start:i + 1]):
            return start, i
    raise SystemExit("unterminated " + name)


def function(name):
    start, end = span(name)
    return src[start:end + 1]


def region(first, last_function):
    start = next(i for i, l in enumerate(src) if l.startswith(first))
    return src[start:span(last_function)[1] + 1]


parts = ["#include <stdio.h>", "#include <stdlib.h>", "#include <string.h>", "#include <math.h>", ""]
parts += function("rt_digits") + [""] + function("rt_fmt") + [""] + function("rt_parse_num") + [""]
parts += region("#define RT_MQTT_TOPICS", "mqttRead")
print("\n".join(parts))
