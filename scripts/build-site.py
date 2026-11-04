#!/usr/bin/env python3
"""Builds the project web site (GitHub Pages) into build/site.

The code samples on the front page are real output of the generator: the tutorial program
(examples/tutorial.evia) in every language x flow x values combination.

Usage: scripts/build-site.py [jar]
"""
import glob, json, os, re, shutil, subprocess, sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(root)
jar = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob("target/evia-paradigma-*.jar"))[-1]
version = re.search(r"evia-paradigma-([0-9.]+)\.jar", jar).group(1)
out = "build/site"
shutil.rmtree(out, ignore_errors=True)
shutil.copytree("site", out)
os.makedirs(out + "/img", exist_ok=True)
for f in glob.glob("docs/screenshots/*.png") + ["docs/logo.png", "docs/logo-dark.png"]:
    shutil.copy(f, out + "/img/")

def excerpt(code, lang):
    """The main program's function: run_program() in C, Program::run() in C++."""
    start = "static void run_program(void) {" if not lang.startswith("c++") else "void Program::run() {"
    lines = code.split("\n")
    i = next(k for k, l in enumerate(lines) if l.startswith(start))
    j = next(k for k in range(i, len(lines)) if lines[k] == "}")
    return "\n".join(lines[i:j + 1])

samples = {}
for lang in ["c99", "c11", "c17", "c++98", "c++11", "c++14", "c++17", "c++20"]:
    for flow in ["structured", "goto"]:
        for values in ["safe", "fast"]:
            code = subprocess.run(["java", "-jar", jar, "--gen", "examples/tutorial.evia", "--lang", lang,
                                   "--flow", flow, "--values", values], capture_output=True, text=True, check=True).stdout
            samples[f"{lang}/{flow}/{values}"] = {"code": excerpt(code, lang), "lines": code.count("\n")}
output = subprocess.run(["java", "-jar", jar, "--run", "examples/tutorial.evia"], capture_output=True, text=True).stdout

with open(out + "/samples.js", "w") as f:
    f.write("window.EVIA_SAMPLES = " + json.dumps(samples) + ";\n")
    f.write("window.EVIA_OUTPUT = " + json.dumps(output) + ";\n")
with open(out + "/index.html") as f:
    html = f.read()
with open(out + "/index.html", "w") as f:
    f.write(html.replace("{{VERSION}}", version))
open(out + "/.nojekyll", "w").close()
print(f"site for {version}: {len(samples)} code samples -> {out}")
