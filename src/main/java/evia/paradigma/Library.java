package evia.paradigma;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * All custom block definitions of the open program, plus the main canvas.
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

    /** New empty definition (with its Enter and one Exit block). */
    BlockDef create(String name) {
        BlockDef d = create(nextId, name, PALETTE[(nextId - 1) % PALETTE.length], List.of());
        d.body.addBlock(BlockType.ENTER, "", null, 60, 30);
        d.body.addBlock(BlockType.EXIT, "next", null, 60, 260);
        changed();
        return d;
    }

    /** Low-level create, used by loading; the body starts empty. */
    BlockDef create(int id, String name, Color color, List<String> params) {
        BlockDef d = new BlockDef(this, id, name, color, params);
        defs.add(d);
        nextId = Math.max(nextId, id + 1);
        return d;
    }

    void remove(BlockDef d) {
        defs.remove(d);
        changed();
    }

    /** True if some block outside the definition's own body is an instance of it. */
    boolean isUsed(BlockDef d) {
        for (FlowCanvas c : canvases()) {
            if (c.owner == d) continue;
            for (Block b : c.getBlocks()) if (b.def == d) return true;
        }
        return false;
    }

    List<FlowCanvas> canvases() {
        List<FlowCanvas> out = new ArrayList<>();
        if (main != null) out.add(main);
        for (BlockDef d : defs) out.add(d.body);
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
}
