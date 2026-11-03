package evia.paradigma;

import java.awt.Color;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Save/load programs as a line-based text file (*.evia). Scope "M" = main program,
 * a number = body of the custom block definition with that id.
 * <pre>
 * D|defId|name|rrggbb|params
 * B|scope|id|TYPE|x|y|code|defId(-1 if none)
 * L|scope|fromId|port|toId
 * </pre>
 * Text fields are URL-encoded.
 */
final class ProgramIO {
    private ProgramIO() {}

    static void save(Library lib, Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# EVIA Paradigma program v2");
        for (BlockDef d : lib.defs()) {
            lines.add(String.join("|", "D", "" + d.id, enc(d.name),
                    String.format("%06x", d.color.getRGB() & 0xFFFFFF), enc(String.join(",", d.params))));
        }
        for (FlowCanvas c : lib.canvases()) {
            String scope = c.owner == null ? "M" : "" + c.owner.id;
            for (Block b : c.getBlocks()) {
                lines.add(String.join("|", "B", scope, "" + b.id, b.type.name(), "" + b.getX(), "" + b.getY(),
                        enc(b.getCode()), "" + (b.def == null ? -1 : b.def.id)));
            }
            for (FlowCanvas.Link l : c.links()) {
                lines.add(String.join("|", "L", scope, "" + l.from().id, "" + l.port(), "" + l.to().id));
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    static void load(Library lib, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lib.main.clear();
        lib.clear();
        try {
            for (String[] f : records(lines, "D")) {
                lib.create(Integer.parseInt(f[1]), dec(f[2]), new Color(Integer.parseInt(f[3], 16)),
                        BlockDef.parseParams(dec(f[4])));
            }
            Map<String, Map<Integer, Block>> byScope = new HashMap<>();
            for (String[] f : records(lines, "B")) {
                FlowCanvas c = canvas(lib, f[1]);
                int defId = Integer.parseInt(f[7]);
                BlockDef def = defId < 0 ? null : lib.byId(defId);
                if (defId >= 0 && def == null) throw new IllegalArgumentException("unknown definition " + defId);
                int id = Integer.parseInt(f[2]);
                Block b = c.addBlock(id, BlockType.valueOf(f[3]), dec(f[6]), def,
                        Integer.parseInt(f[4]), Integer.parseInt(f[5]));
                byScope.computeIfAbsent(f[1], k -> new HashMap<>()).put(id, b);
            }
            lib.sync();   // all exits exist now, so instances have their final ports
            for (String[] f : records(lines, "L")) {
                Map<Integer, Block> ids = byScope.getOrDefault(f[1], Map.of());
                Block from = ids.get(Integer.parseInt(f[2])), to = ids.get(Integer.parseInt(f[4]));
                int port = Integer.parseInt(f[3]);
                if (from != null && to != null && port < from.next.length) canvas(lib, f[1]).connect(from, port, to);
            }
            lib.changed();
        } catch (RuntimeException e) {
            lib.main.clear();
            lib.clear();
            throw new IOException("not a valid EVIA Paradigma file: " + e.getMessage(), e);
        }
    }

    private static List<String[]> records(List<String> lines, String kind) {
        List<String[]> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\\|", -1);
            if (f[0].equals(kind)) out.add(f);
        }
        return out;
    }

    private static FlowCanvas canvas(Library lib, String scope) {
        if (scope.equals("M")) return lib.main;
        BlockDef d = lib.byId(Integer.parseInt(scope));
        if (d == null) throw new IllegalArgumentException("unknown scope " + scope);
        return d.body;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    // ================================================================ example

    /**
     * Demo with two custom blocks:
     * <ul>
     *   <li>IsEven(v) — two exits, "even" and "odd", so the object has two output ports.</li>
     *   <li>Average(x) — keeps this.sum and this.n in its object between calls
     *       and writes the running mean into the program variable avg.</li>
     * </ul>
     */
    static void loadExample(Library lib) {
        lib.main.clear();
        lib.clear();

        BlockDef isEven = lib.create(1, "IsEven", new Color(150, 210, 240), List.of("v"));
        FlowCanvas b1 = isEven.body;
        Block e1 = b1.addBlock(1, BlockType.ENTER, "", null, 160, 20);
        Block if1 = b1.addBlock(2, BlockType.IF, "this.v % 2 == 0", null, 160, 120);
        Block even = b1.addBlock(3, BlockType.EXIT, "even", null, 40, 250);
        Block odd = b1.addBlock(4, BlockType.EXIT, "odd", null, 280, 250);
        b1.connect(e1, 0, if1);
        b1.connect(if1, 0, even);
        b1.connect(if1, 1, odd);

        BlockDef avg = lib.create(2, "Average", new Color(255, 170, 120), List.of("x"));
        FlowCanvas b2 = avg.body;
        Block e2 = b2.addBlock(1, BlockType.ENTER, "", null, 60, 20);
        Block s1 = b2.addBlock(2, BlockType.SET, "this.sum = this.sum + this.x", null, 60, 115);
        Block s2 = b2.addBlock(3, BlockType.SET, "this.n = this.n + 1", null, 60, 210);
        Block s3 = b2.addBlock(4, BlockType.SET, "avg = this.sum / this.n", null, 60, 305);
        Block x2 = b2.addBlock(5, BlockType.EXIT, "next", null, 60, 400);
        b2.connect(e2, 0, s1);
        b2.connect(s1, 0, s2);
        b2.connect(s2, 0, s3);
        b2.connect(s3, 0, x2);
        lib.sync();

        FlowCanvas c = lib.main;
        Block start = c.addBlock(1, BlockType.START, "", null, 400, 20);
        Block setI = c.addBlock(2, BlockType.SET, "i = 1", null, 400, 115);
        Block loop = c.addBlock(3, BlockType.IF, "i <= 6", null, 400, 215);
        Block test = c.addBlock(4, BlockType.CUSTOM, "i", isEven, 330, 330);
        Block pEven = c.addBlock(5, BlockType.PRINT, "i + \" is even\"", null, 200, 450);
        Block mean = c.addBlock(6, BlockType.CUSTOM, "i", avg, 200, 555);
        Block pAvg = c.addBlock(7, BlockType.PRINT, "\"   average of evens so far: \" + avg", null, 200, 660);
        Block pOdd = c.addBlock(8, BlockType.PRINT, "i + \" is odd\"", null, 520, 450);
        Block inc = c.addBlock(9, BlockType.SET, "i = i + 1", null, 400, 780);
        Block end = c.addBlock(10, BlockType.END, "", null, 680, 330);

        c.connect(start, 0, setI);
        c.connect(setI, 0, loop);
        c.connect(loop, 0, test);
        c.connect(loop, 1, end);
        c.connect(test, test.portIndex("even"), pEven);
        c.connect(test, test.portIndex("odd"), pOdd);
        c.connect(pEven, 0, mean);
        c.connect(mean, 0, pAvg);
        c.connect(pAvg, 0, inc);
        c.connect(pOdd, 0, inc);
        c.connect(inc, 0, loop);
        lib.changed();
    }
}
