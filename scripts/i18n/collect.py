#!/usr/bin/env python3
"""Lists the English strings the interface asks Text.t() for, and what is still untranslated.

    scripts/i18n/collect.py                 # a report: how many of each language are done
    scripts/i18n/collect.py --missing ru    # the untranslated lines, ready to paste into ru.tsv
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "src/main/java/evia/paradigma"
I18N = ROOT / "src/main/resources/evia/paradigma/i18n"

wanted = []
# the block names, which come from the enum rather than from a literal at the call site
for m in re.finditer(r'^\s{4}[A-Z0-9_]+\("((?:[^"\\]|\\.)*)"', (SRC / "BlockType.java").read_text(encoding="utf-8"), re.M):
    if m.group(1) not in wanted:
        wanted.append(m.group(1))
for f in sorted(SRC.glob("*.java")):
    text = f.read_text(encoding="utf-8")
    # Text.t("..."), and the helpers that translate what they are given: menu(), item(), header(), note()
    for m in re.finditer(r'(?:Text\.t|[A-Za-z]*[Mm]enu|item|header|note|button|action)\(\s*"((?:[^"\\]|\\.)*)"', text):
        s = m.group(1)
        if s not in wanted:
            wanted.append(s)

def have(code):
    p = I18N / f"{code}.tsv"
    if not p.exists():
        return {}
    out = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#") and "\t" in line:
            en, tr = line.split("\t", 1)
            if tr.strip():
                out[en] = tr.strip()
    return out

if len(sys.argv) > 2 and sys.argv[1] == "--missing":
    done = have(sys.argv[2])
    for s in wanted:
        if s not in done:
            print(s + "\t")
    sys.exit(0)

print(f"{len(wanted)} strings in the interface")
for code in ("ru", "hy"):
    done = have(code)
    extra = [k for k in done if k not in wanted]
    print(f"  {code}: {len(set(wanted) & set(done))} translated, {len([s for s in wanted if s not in done])} missing"
          + (f", {len(extra)} no longer used" if extra else ""))
