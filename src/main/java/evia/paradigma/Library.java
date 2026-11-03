package evia.paradigma;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * All classes of the open program (including those from modules), plus the main canvas.
 * Keeps instances in sync when a definition changes (name, params, color, exits).
 */
final class Library {
    private static final Color[] PALETTE = {
            new Color(255, 170, 120), new Color(150, 210, 240), new Color(200, 225, 120),
            new Color(240, 160, 200), new Color(180, 180, 240), new Color(250, 215, 140),
            new Color(140, 220, 180), new Color(220, 190, 160)
    };

    private final List<BlockDef> defs = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private int nextId = 1;
    private boolean syncing;

    FlowCanvas main;
    Consumer<BlockDef> editorOpener = d -> {};

    List<BlockDef> defs() {
        return Collections.unmodifiableList(defs);
    }

    BlockDef byId(int id) {
        for (BlockDef d : defs) if (d.id == id) return d;
        return null;
    }

    BlockDef byName(String name) {
        for (BlockDef d : defs) if (d.name.equals(name)) return d;
        return null;
    }

    /** New class with one method "run" (its Enter and one Exit block). */
    BlockDef create(String name) {
        BlockDef d = createEmpty(name, null);
        newMethod(d, "run", List.of());
        changed();
        return d;
    }

    /** New method with an Enter and an Exit block. */
    Method newMethod(BlockDef d, String name, List<String> params) {
        Method m = d.addMethod(name, params);
        m.body.addBlock(BlockType.ENTER, "", null, 60, 30);
        m.body.addBlock(BlockType.EXIT, "next", null, 60, 260);
        return m;
    }

    /** A class with no fields or methods yet (used by loading and modules). */
    BlockDef createEmpty(String name, Color color) {
        BlockDef d = new BlockDef(this, nextId, name, color != null ? color : PALETTE[(nextId - 1) % PALETTE.length]);
        nextId++;
        defs.add(d);
        return d;
    }

    /** Compatibility helper: a class with one method "run" with these parameters and an empty body. */
    BlockDef create(int id, String name, Color color, List<String> params) {
        BlockDef d = new BlockDef(this, id, name, color);
        d.addMethod("run", params);
        defs.add(d);
        nextId = Math.max(nextId, id + 1);
        return d;
    }

    void remove(BlockDef d) {
        defs.remove(d);
        changed();
    }

    /** True if some block outside the class's own methods uses it. */
    boolean isUsed(BlockDef d) {
        for (FlowCanvas c : canvases()) {
            if (c.owner != null && c.owner.cls == d) continue;
            for (Block b : c.getBlocks()) if (b.def == d) return true;
        }
        return false;
    }

    /** True if a block anywhere calls this method. */
    boolean isUsed(Method m) {
        for (FlowCanvas c : canvases()) {
            for (Block b : c.getBlocks()) if (b.def == m.cls && b.method() == m) return true;
        }
        return false;
    }

    List<FlowCanvas> canvases() {
        List<FlowCanvas> out = new ArrayList<>();
        if (main != null) out.add(main);
        for (BlockDef d : defs) for (Method m : d.methods) out.add(m.body);
        return out;
    }

    /** Module files referenced by classes, in first-use order. */
    List<String> modules() {
        List<String> out = new ArrayList<>();
        for (BlockDef d : defs) if (d.module != null && !out.contains(d.module)) out.add(d.module);
        return out;
    }

    boolean nameTaken(String name, BlockDef except) {
        for (BlockDef d : defs) if (d != except && d.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    String uniqueName(String base) {
        String n = base;
        for (int i = 2; nameTaken(n, null); i++) n = base + i;
        return n;
    }

    /** Re-sync every instance with its definition (ports, size, compiled args). */
    void sync() {
        if (syncing) return;
        syncing = true;
        try {
            for (FlowCanvas c : canvases()) {
                for (Block b : c.getBlocks()) if (b.def != null) b.refresh();
                c.repaint();
            }
        } finally {
            syncing = false;
        }
    }

    /** A definition was added/removed/renamed: sync instances and rebuild palettes. */
    void changed() {
        sync();
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    void clear() {
        defs.clear();
        nextId = 1;
        changed();
    }

    void setLocked(boolean v) {
        for (FlowCanvas c : canvases()) c.setLocked(v);
    }

    void openEditor(BlockDef d) {
        editorOpener.accept(d);
    }

    /** Shows the generated C or C++ code for a block, a method or a class. */
    Consumer<Object> sourceViewer = o -> {};

    void viewSource(Object blockMethodOrClass) {
        sourceViewer.accept(blockMethodOrClass);
    }
}
