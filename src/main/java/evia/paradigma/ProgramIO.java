package evia.paradigma;

import java.awt.Color;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Programs (*.evia) and modules (*.eviam) as line-based text files, version 3. Text fields are URL-encoded.
 * <pre>
 * U|modules/geometry.eviam                     program uses a module (path relative to this file)
 * C|class|rrggbb                               a class
 * F|class|field|initial value                  a declared field
 * M|class|method|param,param                   a method, in order
 * B|scope|id|TYPE|x|y|code|class|method|object a block; class/method "-" if not a call
 * L|scope|fromId|port|toId                     a wire
 * </pre>
 * Scope "M" is the main program, "class:method" a method body. Version 2 files (0.1.x) still load.
 */
final class ProgramIO {
    private ProgramIO() {}

    static final String MODULE_EXT = ".eviam";

    // ================================================================ save

    /** Saves the program; classes that belong to a module are written back to their module file. */
    static void save(Library lib, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        List<String> lines = new ArrayList<>();
        lines.add("# EVIA Paradigma program v3");
        for (String module : lib.modules()) {
            lines.add("U|" + enc(relative(dir, module)));
            saveModule(lib, Path.of(module));
        }
        for (BlockDef d : lib.defs()) if (d.module == null) classLines(d, lines);
        canvasLines(lib.main, "M", lines);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Text form of the whole program (all classes, wherever stored) - used to detect unsaved changes. */
    static String snapshot(Library lib) {
        List<String> lines = new ArrayList<>();
        for (BlockDef d : lib.defs()) {
            lines.add("# module " + d.module);
            classLines(d, lines);
        }
        canvasLines(lib.main, "M", lines);
        return String.join("\n", lines);
    }

    /** Writes every class whose module is {@code moduleFile} into that file. */
    static void saveModule(Library lib, Path moduleFile) throws IOException {
        String key = moduleFile.toAbsolutePath().normalize().toString();
        List<String> lines = new ArrayList<>();
        lines.add("# EVIA Paradigma module v3");
        for (BlockDef d : lib.defs()) if (key.equals(d.module)) classLines(d, lines);
        Files.createDirectories(moduleFile.toAbsolutePath().getParent());
        Files.write(moduleFile, lines, StandardCharsets.UTF_8);
    }

    /** Moves the given classes into a module file (created or replaced) and writes it. */
    static void exportModule(Library lib, Collection<BlockDef> classes, Path moduleFile) throws IOException {
        String key = moduleFile.toAbsolutePath().normalize().toString();
        for (BlockDef d : classes) d.module = key;
        saveModule(lib, moduleFile);
        lib.changed();
    }

    private static void classLines(BlockDef d, List<String> lines) {
        lines.add(String.join("|", "C", enc(d.name), String.format("%06x", d.color.getRGB() & 0xFFFFFF)));
        for (BlockDef.Field f : d.fields) lines.add(String.join("|", "F", enc(d.name), enc(f.name()), enc(f.init())));
        for (Method m : d.methods) {
            lines.add(String.join("|", "M", enc(d.name), enc(m.name), enc(String.join(",", m.params))));
        }
        for (Method m : d.methods) canvasLines(m.body, enc(d.name) + ":" + enc(m.name), lines);
    }

    private static void canvasLines(FlowCanvas c, String scope, List<String> lines) {
        for (Block b : c.getBlocks()) {
            lines.add(String.join("|", "B", scope, "" + b.id, b.type.name(), "" + b.getX(), "" + b.getY(), enc(b.getCode()),
                    b.def == null ? "-" : enc(b.def.name),
                    b.def == null || b.methodName() == null ? "-" : enc(b.methodName()),
                    b.def == null ? "" : enc(b.objectName())));
        }
        for (FlowCanvas.Link l : c.links()) {
            lines.add(String.join("|", "L", scope, "" + l.from().id, "" + l.port(), "" + l.to().id));
        }
    }

    // ================================================================ load

    /** One parsed file: its lines and, for a module, its absolute path. */
    private record Source(List<String> lines, String module) {}

    static void load(Library lib, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lib.main.clear();
        lib.clear();
        try {
            if (!records(lines, "D").isEmpty() || (!lines.isEmpty() && lines.get(0).contains("program v2"))) {
                loadV2(lib, lines);
            } else {
                List<Source> sources = new ArrayList<>();
                collectModules(file.toAbsolutePath().getParent(), lines, sources, new HashSet<>());
                sources.add(new Source(lines, null));
                loadSources(lib, sources, true);
            }
            lib.changed();
        } catch (RuntimeException e) {
            lib.main.clear();
            lib.clear();
            throw new IOException("not a valid EVIA Paradigma file: " + e.getMessage(), e);
        }
    }

    /** Adds the classes of a module file to the open program, keeping them linked to that file. */
    static List<BlockDef> importModule(Library lib, Path moduleFile) throws IOException {
        Path abs = moduleFile.toAbsolutePath().normalize();
        List<String> lines = Files.readAllLines(abs, StandardCharsets.UTF_8);
        for (String[] f : records(lines, "C")) {
            if (lib.byName(dec(f[1])) != null) throw new IOException("a class named " + dec(f[1]) + " already exists");
        }
        int before = lib.defs().size();
        try {
            List<Source> sources = new ArrayList<>();
            collectModules(abs.getParent(), lines, sources, new HashSet<>());
            sources.add(new Source(lines, abs.toString()));
            loadSources(lib, sources, false);
        } catch (RuntimeException e) {
            throw new IOException("not a valid EVIA Paradigma module: " + e.getMessage(), e);
        }
        lib.changed();
        return List.copyOf(lib.defs().subList(before, lib.defs().size()));
    }

    private static void collectModules(Path dir, List<String> lines, List<Source> out, Set<String> seen) throws IOException {
        for (String[] f : records(lines, "U")) {
            Path p = dir.resolve(dec(f[1])).toAbsolutePath().normalize();
            if (!seen.add(p.toString())) continue;
            if (!Files.exists(p)) throw new IOException("module not found: " + p);
            List<String> ml = Files.readAllLines(p, StandardCharsets.UTF_8);
            collectModules(p.getParent(), ml, out, seen);
            out.add(new Source(ml, p.toString()));
        }
    }

    /** Classes first (all files), then blocks, then wires - so classes may use each other in any order. */
    private static void loadSources(Library lib, List<Source> sources, boolean withMain) {
        for (Source src : sources) {
            for (String[] f : records(src.lines(), "C")) {
                String name = dec(f[1]);
                if (lib.byName(name) != null) continue;      // the same module reached twice
                BlockDef d = lib.createEmpty(name, new Color(Integer.parseInt(f[2], 16)));
                d.module = src.module();
            }
            for (String[] f : records(src.lines(), "F")) {
                cls(lib, f[1]).fields.add(new BlockDef.Field(dec(f[2]), dec(f[3])));
            }
            for (String[] f : records(src.lines(), "M")) {
                BlockDef d = cls(lib, f[1]);
                if (d.method(dec(f[2])) == null) d.addMethod(dec(f[2]), BlockDef.parseParams(dec(f[3])));
            }
        }
        Map<String, Map<Integer, Block>> byScope = new HashMap<>();
        for (Source src : sources) {
            for (String[] f : records(src.lines(), "B")) {
                if (f[1].equals("M") && !withMain) continue;
                FlowCanvas c = canvas(lib, f[1]);
                BlockDef def = f[7].equals("-") ? null : cls(lib, f[7]);
                int id = Integer.parseInt(f[2]);
                Block b = c.addBlock(id, BlockType.valueOf(f[3]), dec(f[6]), def, Integer.parseInt(f[4]), Integer.parseInt(f[5]));
                if (def != null) b.setCall(f[8].equals("-") ? null : dec(f[8]), f.length > 9 ? dec(f[9]) : "");
                byScope.computeIfAbsent(scopeKey(src, f[1]), k -> new HashMap<>()).put(id, b);
            }
        }
        lib.sync();   // every exit exists now, so calling blocks have their final ports
        for (Source src : sources) {
            for (String[] f : records(src.lines(), "L")) {
                if (f[1].equals("M") && !withMain) continue;
                Map<Integer, Block> ids = byScope.getOrDefault(scopeKey(src, f[1]), Map.of());
                Block from = ids.get(Integer.parseInt(f[2])), to = ids.get(Integer.parseInt(f[4]));
                int port = Integer.parseInt(f[3]);
                if (from != null && to != null && port < from.next.length) canvas(lib, f[1]).connect(from, port, to);
            }
        }
    }

    private static String scopeKey(Source src, String scope) {
        return (src.module() == null ? "" : src.module()) + "|" + scope;
    }

    private static BlockDef cls(Library lib, String encName) {
        BlockDef d = lib.byName(dec(encName));
        if (d == null) throw new IllegalArgumentException("unknown class " + dec(encName));
        return d;
    }

    private static FlowCanvas canvas(Library lib, String scope) {
        if (scope.equals("M")) return lib.main;
        int i = scope.indexOf(':');
        if (i < 0) throw new IllegalArgumentException("bad scope " + scope);
        BlockDef d = cls(lib, scope.substring(0, i));
        Method m = d.method(dec(scope.substring(i + 1)));
        if (m == null) throw new IllegalArgumentException("unknown method " + dec(scope.substring(i + 1)) + " of " + d.name);
        return m.body;
    }

    /** Version 2 files (EVIA Paradigma 0.1.x): every custom block is a class with one method "run". */
    private static void loadV2(Library lib, List<String> lines) {
        Map<Integer, BlockDef> byId = new HashMap<>();
        for (String[] f : records(lines, "D")) {
            BlockDef d = lib.createEmpty(dec(f[2]), new Color(Integer.parseInt(f[3], 16)));
            d.addMethod("run", BlockDef.parseParams(dec(f[4])));
            byId.put(Integer.parseInt(f[1]), d);
        }
        Map<String, Map<Integer, Block>> byScope = new HashMap<>();
        for (String[] f : records(lines, "B")) {
            FlowCanvas c = f[1].equals("M") ? lib.main : byId.get(Integer.parseInt(f[1])).methods.get(0).body;
            int defId = Integer.parseInt(f[7]);
            BlockDef def = defId < 0 ? null : byId.get(defId);
            int id = Integer.parseInt(f[2]);
            Block b = c.addBlock(id, BlockType.valueOf(f[3]), dec(f[6]), def, Integer.parseInt(f[4]), Integer.parseInt(f[5]));
            if (def != null) b.setCall("run", "");
            byScope.computeIfAbsent(f[1], k -> new HashMap<>()).put(id, b);
        }
        lib.sync();
        for (String[] f : records(lines, "L")) {
            Map<Integer, Block> ids = byScope.getOrDefault(f[1], Map.of());
            Block from = ids.get(Integer.parseInt(f[2])), to = ids.get(Integer.parseInt(f[4]));
            int port = Integer.parseInt(f[3]);
            FlowCanvas c = f[1].equals("M") ? lib.main : byId.get(Integer.parseInt(f[1])).methods.get(0).body;
            if (from != null && to != null && port < from.next.length) c.connect(from, port, to);
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

    private static String relative(Path dir, String absolute) {
        Path p = Path.of(absolute);
        try {
            return dir.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();     // another drive on Windows
        }
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
        FlowCanvas b1 = isEven.methods.get(0).body;
        Block e1 = b1.addBlock(1, BlockType.ENTER, "", null, 160, 20);
        Block if1 = b1.addBlock(2, BlockType.IF, "this.v % 2 == 0", null, 160, 120);
        Block even = b1.addBlock(3, BlockType.EXIT, "even", null, 40, 250);
        Block odd = b1.addBlock(4, BlockType.EXIT, "odd", null, 280, 250);
        b1.connect(e1, 0, if1);
        b1.connect(if1, 0, even);
        b1.connect(if1, 1, odd);

        BlockDef avg = lib.create(2, "Average", new Color(255, 170, 120), List.of("x"));
        FlowCanvas b2 = avg.methods.get(0).body;
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
        Block loop = c.addBlock(3, BlockType.WHILE, "i <= 6", null, 400, 215);
        Block test = c.addBlock(4, BlockType.CUSTOM, "i", isEven, 330, 330);
        test.setCall("run", "");
        Block pEven = c.addBlock(5, BlockType.PRINT, "i + \" is even\"", null, 200, 450);
        Block mean = c.addBlock(6, BlockType.CUSTOM, "i", avg, 200, 555);
        mean.setCall("run", "");
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
