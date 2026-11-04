#!/usr/bin/env python3
"""Builds the interface font of the app: Inter (Latin, Cyrillic, Greek...) plus the Armenian letters of
Noto Sans Armenian, in one font, because Java does not fall back to another font letter by letter.
Both fonts are under the SIL Open Font License; the result is renamed "EVIA UI" (the license asks for that).

  python3 scripts/fonts/make-ui-font.py Inter-Regular.ttf Inter-Bold.ttf   (from https://github.com/rsms/inter/releases, extras/ttf) NotoSansArmenian.woff src/main/resources/evia/paradigma/fonts

NotoSansArmenian.woff is the variable font from site/fonts. Needs: pip install fonttools brotli
"""
import sys, io
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem
from fontTools.varLib import instancer
from fontTools import subset, merge

regular, bold, noto, out = sys.argv[1:5]
ARMENIAN = list(range(0x0531, 0x0557)) + list(range(0x0559, 0x058B)) + [0x058D, 0x058E, 0x058F] + list(range(0xFB13, 0xFB18))

def armenian(weight):
    f = TTFont(noto)
    f = instancer.instantiateVariableFont(f, {"wght": weight})
    scale_upem(f, 2048)
    opt = subset.Options(); opt.layout_features = []; opt.notdef_outline = False; opt.name_IDs = ["*"]
    opt.drop_tables += ["GDEF", "GPOS", "GSUB", "STAT", "gasp"]
    s = subset.Subsetter(opt); s.populate(unicodes=ARMENIAN); s.subset(f)
    buf = io.BytesIO(); f.save(buf); buf.seek(0)
    return TTFont(buf)

for path, weight, style in ((regular, 400, "Regular"), (bold, 700, "Bold")):
    m = merge.Merger(options=merge.Options(drop_tables=["GDEF", "GPOS", "GSUB", "kern", "gasp", "STAT", "fpgm", "prep", "cvt "]))
    buf = io.BytesIO(); armenian(weight).save(buf)
    open("/tmp/_arm.ttf", "wb").write(buf.getvalue())
    font = m.merge([path, "/tmp/_arm.ttf"])
    for rec in font["name"].names:
        if rec.nameID in (1, 16): rec.string = "EVIA UI"
        elif rec.nameID in (2, 17): rec.string = style
        elif rec.nameID == 4: rec.string = "EVIA UI " + style
        elif rec.nameID == 6: rec.string = "EVIAUI-" + style
        elif rec.nameID in (3,): rec.string = "EVIAUI-" + style + ";merged"
    font.save(f"{out}/EVIAUI-{style}.ttf")
    print("wrote", f"{out}/EVIAUI-{style}.ttf", len(font.getBestCmap()), "characters")
