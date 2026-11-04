#!/usr/bin/env python3
"""Builds the tutorial PDFs (English, Russian, Armenian) from docs/tutorial/TUTORIAL*.md.

Markdown -> HTML with pandoc, HTML -> PDF with Chromium (Playwright). Uses the fonts bundled with the
application (EVIA UI = Inter + Cyrillic + Armenian, JetBrains Mono). Screenshots are shrunk to JPEG
so a PDF stays around 1 MB.

Usage: scripts/build-tutorial-pdf.py        -> docs/tutorial/EVIA-Paradigma-Tutorial.{en,ru,hy}.pdf
"""
import io, os, re, subprocess, sys, tempfile
from PIL import Image
from playwright.sync_api import sync_playwright

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(root)
fonts = os.path.abspath("src/main/resources/evia/paradigma/fonts")
LANGS = {
    "en": ("docs/tutorial/TUTORIAL.md", "EVIA Paradigma: tutorial", "Page", "of"),
    "ru": ("docs/tutorial/TUTORIAL.ru.md", "EVIA Paradigma: учебник", "Страница", "из"),
    "hy": ("docs/tutorial/TUTORIAL.hy.md", "EVIA Paradigma. ուսուցում", "Էջ", "/"),
}
CSS = """
@font-face { font-family: EVIAUI; src: url('file://%(f)s/EVIAUI-Regular.ttf'); font-weight: 400; }
@font-face { font-family: EVIAUI; src: url('file://%(f)s/EVIAUI-Bold.ttf'); font-weight: 700; }
@font-face { font-family: JBM; src: url('file://%(f)s/JetBrainsMono-Regular.ttf'); font-weight: 400; }
@font-face { font-family: JBM; src: url('file://%(f)s/JetBrainsMono-Bold.ttf'); font-weight: 700; }
html { font: 10.5pt/1.5 EVIAUI, sans-serif; color: #1f2328; }
body { margin: 0; }
h1 { font-size: 22pt; margin: 0 0 .4em; }
h2 { font-size: 15pt; margin: 1.6em 0 .5em; padding-bottom: .2em; border-bottom: 1px solid #c9ced6; break-after: avoid; }
p, li { orphans: 3; widows: 3; }
code { font: 9pt JBM, monospace; background: #eef0f3; padding: .05em .3em; border-radius: 3px; }
pre { font: 8.5pt/1.4 JBM, monospace; background: #f3f4f6; padding: .7em; border: 1px solid #d5d9e0; border-radius: 4px; white-space: pre-wrap; break-inside: avoid; }
pre code { background: none; padding: 0; }
a { color: #2557b8; text-decoration: none; }
table { border-collapse: collapse; margin: .8em 0; width: 100%%; break-inside: avoid; font-size: 9.5pt; }
th, td { border: 1px solid #c9ced6; padding: .3em .55em; text-align: left; vertical-align: top; }
th { background: #eef0f3; }
p[align=center] { text-align: center; margin: .8em 0; break-inside: avoid; }
img { max-width: 100%%; height: auto; border: 1px solid #c9ced6; border-radius: 3px; }
"""

def shrink(html, tmp):
    """Replaces each screenshot with a smaller JPEG (and keeps its display width)."""
    def one(m):
        src = m.group(1)
        path = os.path.normpath(os.path.join("docs/tutorial", src))
        if not os.path.exists(path):
            return m.group(0)
        im = Image.open(path).convert("RGB")
        if im.width > 1500:
            im = im.resize((1500, round(im.height * 1500 / im.width)), Image.LANCZOS)
        out = os.path.join(tmp, os.path.basename(path).rsplit(".", 1)[0] + ".jpg")
        im.save(out, "JPEG", quality=84, optimize=True)
        return 'src="file://%s"' % out
    return re.sub(r'src="([^"]+\.png)"', one, html)

with tempfile.TemporaryDirectory() as tmp, sync_playwright() as pw:
    browser = pw.chromium.launch()
    for lang, (md, title, page_word, of_word) in LANGS.items():
        body = subprocess.run(["pandoc", "-f", "gfm+raw_html", "-t", "html", md], capture_output=True, text=True, check=True).stdout
        # links to other files in the repository become plain text in a PDF
        body = re.sub(r'<p><a\s+href="TUTORIAL[^"]*".*?</p>\n?', "", body, count=1, flags=re.S)   # the language links
        body = re.sub(r'<a\s+href="(?!https?:|#|mailto:)[^"]*">(.*?)</a>', r"\1", body, flags=re.S)
        body = shrink(body, tmp)
        html = f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><title>{title}</title><style>{CSS % {"f": fonts}}</style></head><body>{body}</body></html>'
        src = os.path.join(tmp, f"tutorial.{lang}.html")
        open(src, "w", encoding="utf-8").write(html)
        page = browser.new_page()
        page.goto("file://" + src)
        page.wait_for_load_state("networkidle")
        page.evaluate("document.fonts.ready")
        out = f"docs/tutorial/EVIA-Paradigma-Tutorial.{lang}.pdf"
        footer = ('<div style="font: 8pt sans-serif; color: #6b7280; width: 100%; padding: 0 18mm; display: flex; justify-content: space-between;">'
                  f'<span>{title}</span><span>{page_word} <span class="pageNumber"></span> {of_word} <span class="totalPages"></span></span></div>')
        page.pdf(path=out, format="A4", print_background=True, display_header_footer=True,
                 header_template="<span></span>", footer_template=footer,
                 margin={"top": "16mm", "bottom": "18mm", "left": "18mm", "right": "18mm"})
        page.close()
        print(f"{out}: {os.path.getsize(out) // 1024} KB")
    browser.close()
