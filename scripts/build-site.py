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
# the download links point at a published release: SITE_VERSION (the latest release, set by the workflow),
# or else the version of the jar
version = os.environ.get("SITE_VERSION", "").lstrip("v") or re.search(r"evia-paradigma-([0-9.]+)\.jar", jar).group(1)

# Where the installers are fetched from. By default the GitHub release of that version; DOWNLOAD_BASE
# points the three download links at another server instead - a mirror, or a machine of your own. The
# file names do not change, so whatever is given has to serve them directly under that base:
#
#   DOWNLOAD_BASE=https://downloads.example.com/evia/v0.6.7 python3 scripts/build-site.py
#
# A private address (192.168.x.x, a .local name) only works for visitors on that network, so it does
# not belong on the published site; it is fine for a site served inside the same network.
github_downloads = f"https://github.com/sbrutyan01/EVIA-Paradigma/releases/download/v{version}"
download_base = os.environ.get("DOWNLOAD_BASE", "").rstrip("/")


def relink(page):
    """Sends the download links somewhere other than GitHub, when DOWNLOAD_BASE says so."""
    return page.replace(github_downloads, download_base) if download_base else page
out = "build/site"
shutil.rmtree(out, ignore_errors=True)
shutil.copytree("site", out)
os.makedirs(out + "/img", exist_ok=True)
os.makedirs(out + "/tutorial", exist_ok=True)
for f in glob.glob("docs/tutorial/*.pdf"):   # tutorial PDFs in all available languages
    shutil.copy(f, out + "/tutorial/")
for f in glob.glob("docs/screenshots/*.png") + ["docs/logo.png", "docs/logo-dark.png"]:
    shutil.copy(f, out + "/img/")

def excerpt(code, lang):
    """The main program's function: run_program() in C, Program::run() in C++, Program.run() in Python."""
    lines = code.split("\n")
    if lang == "python":
        i = next(k for k, l in enumerate(lines) if l == "    def run(self):")
        j = next(k for k in range(i + 1, len(lines)) if lines[k] and not lines[k].startswith("        "))
        while not lines[j - 1].strip():
            j -= 1
        return "\n".join(l[4:] for l in lines[i:j])
    start = "static void run_program(void) {" if not lang.startswith("c++") else "void Program::run() {"
    i = next(k for k, l in enumerate(lines) if l.startswith(start))
    j = next(k for k in range(i, len(lines)) if lines[k] == "}")
    return "\n".join(lines[i:j + 1])

samples = {}
for lang in ["c99", "c11", "c17", "c++98", "c++11", "c++14", "c++17", "c++20", "python"]:
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
    html = f.read().replace("{{VERSION}}", version)

# Browsers keep scripts and styles for a day (the server's Cache-Control), so a page from a new build
# could run last week's samples.js: a new language tab then does nothing. Each link carries a
# fingerprint of the file's content, so a changed file is a new address and is fetched at once.
import hashlib
for name in ("samples.js", "app.js", "style.css"):
    with open(f"{out}/{name}", "rb") as f:
        stamp = hashlib.sha256(f.read()).hexdigest()[:10]
    attr = "href" if name.endswith(".css") else "src"
    before = f'{attr}="{name}"'
    if before not in html:
        sys.exit(f"index.html no longer links {name} as {before}")
    html = html.replace(before, f'{attr}="{name}?v={stamp}"')


def current(page, lang):
    """Marks the page's own entry in the language switcher."""
    return page.replace(f'hreflang="{lang}" lang="{lang}"', f'hreflang="{lang}" lang="{lang}" aria-current="page"', 1)


with open(out + "/index.html", "w") as f:
    f.write(relink(current(html, "en")))

# Russian and Armenian files hold [English, translation] pairs. The newer locales hold translations
# in the same order as the source strings in site/i18n/ru.json. Every English string must still be
# on the page (whitespace may differ), so a changed page can't silently stay half-English.
source_pairs = json.load(open("site/i18n/ru.json", encoding="utf-8"))
for lang in ["ru", "hy", "de", "zh-CN", "zh-TW", "ja"]:
    page = html.replace('<html lang="en">', f'<html lang="{lang}">', 1)
    with open(f"site/i18n/{lang}.json", encoding="utf-8") as f:
        translations = json.load(f)
    if lang in ("ru", "hy"):
        pairs = translations
    else:
        if len(translations) != len(source_pairs):
            sys.exit(f"site/i18n/{lang}.json: expected {len(source_pairs)} translations, got {len(translations)}")
        pairs = [[en, tr] for (en, _), tr in zip(source_pairs, translations)]
    for en, tr in sorted(pairs, key=lambda p: -len(p[0])):   # longer texts first
        pattern = r"\s+".join(re.escape(w) for w in en.replace("{{VERSION}}", version).split())
        page, n = re.subn(pattern, lambda m: tr.replace("{{VERSION}}", version), page)
        if n == 0:
            sys.exit(f"site/i18n/{lang}.json: not on the page any more: {en[:80]}")
    font = {"ru": "NotoSans", "hy": "NotoSansArmenian"}.get(lang)
    if font:
        page = page.replace('href="fonts/FamiljenGrotesk.woff"', f'href="fonts/{font}.woff"', 1)
    # the page lives one folder down: relative links and files are one level up
    page = re.sub(r'(src|href)="(?!https?:|#|mailto:|/)', r'\1="../', page)
    os.makedirs(f"{out}/{lang}", exist_ok=True)
    with open(f"{out}/{lang}/index.html", "w", encoding="utf-8") as f:
        f.write(relink(current(page, lang)))
shutil.rmtree(out + "/i18n")
open(out + "/.nojekyll", "w").close()

# A service worker from an application served on this domain before keeps answering from its own
# cache, ahead of the network, so replacing the site does not replace what a browser shows. The
# worker script itself is always revalidated against the server, so serving a self-destructing one
# at the usual names removes it. This site registers no service worker, so these files do nothing
# else. Harmless to keep; drop them once nobody is asking for them.
killsw = out + "/killsw.js"
if os.path.exists(killsw):
    for name in ("sw.js", "service-worker.js", "serviceworker.js", "worker.js",
                 "ngsw-worker.js", "firebase-messaging-sw.js", "pwabuilder-sw.js"):
        shutil.copy(killsw, f"{out}/{name}")
print(f"site for {version}: {len(samples)} code samples, English, Russian, Armenian, German, Chinese (simplified and traditional) and Japanese -> {out}")
