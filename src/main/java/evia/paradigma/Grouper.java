package evia.paradigma;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a selection of blocks into a new custom block definition and replaces
 * the selection with one instance of it, keeping every outside connection.
 *
 * <ul>
 *   <li>The block the selection is entered at becomes the target of the new Enter block.</li>
 *   <li>Every distinct outside block the selection jumps to becomes an Exit
 *       (and so an output port of the instance).</li>
 * </ul>
 * Plain variable names keep referring to program variables, so behaviour is unchanged.
 */
final class Grouper {
    private Grouper() {}

    static BlockDef group(FlowCanvas c, Collection<Block> selected, String name) {
        Set<Block> sel = new LinkedHashSet<>(selected);
        if (sel.isEmpty()) throw new FlowException("nothing selected");
        for (Block b : sel) {
            switch (b.type) {
                case START, END, ENTER, EXIT ->
                        throw new FlowException(b.type.title + " blocks can't be moved into a class");
                default -> { }
            }
        }

        // ---- analyse the connections crossing the selection border
        List<FlowCanvas.Link> incoming = new ArrayList<>(), outgoing = new ArrayList<>(), internal = new ArrayList<>();
        Set<Block> entries = new LinkedHashSet<>();
        for (FlowCanvas.Link l : c.links()) {
            boolean fromIn = sel.contains(l.from()), toIn = sel.contains(l.to());
            if (!fromIn && toIn) { incoming.add(l); entries.add(l.to()); }
            else if (fromIn && !toIn) outgoing.add(l);
            else if (fromIn) internal.add(l);
        }
        if (entries.size() > 1) {
            throw new FlowException("the selection is entered at " + entries.size()
                    + " different blocks; a method can only have one entry point");
        }
        Block entry = entries.isEmpty()
                ? sel.stream().min(Comparator.comparingInt(Block::modelY).thenComparingInt(Block::modelX)).orElseThrow()
                : entries.iterator().next();

        // distinct outside targets, left to right, each gets an exit name
        List<Block> targets = outgoing.stream().map(FlowCanvas.Link::to).distinct()
                .sorted(Comparator.comparingInt(Block::modelX)).toList();
        Map<Block, String> exitName = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (Block t : targets) {
            String base = targets.size() == 1 ? "next" : suggestName(outgoing, t);
            String n = base;
            for (int i = 2; used.contains(n); i++) n = base + i;
            used.add(n);
            exitName.put(t, n);
        }

        // ---- build the definition body
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxBottom = 0;
        for (Block b : sel) {
            minX = Math.min(minX, b.modelX());
            minY = Math.min(minY, b.modelY());
        }
        BlockDef def = c.lib.create(name);
        Method method = def.methods.get(0);
        FlowCanvas body = method.body;
        for (Block b : List.copyOf(body.getBlocks())) {
            if (b.type == BlockType.EXIT) body.removeBlock(b);   // drop the default exit
        }

        Map<Block, Block> copy = new HashMap<>();
        for (Block b : sel) {
            Block nb = body.addBlock(b.type, b.getCode(), b.def, b.modelX() - minX + 60, b.modelY() - minY + 120);
            if (b.def != null) nb.setCall(b.methodName(), b.objectName());
            copy.put(b, nb);
            maxBottom = Math.max(maxBottom, nb.modelY() + nb.modelBounds().height);
        }
        Block enter = method.enter();
        enter.place(copy.get(entry).modelX(), 20);
        body.connect(enter, 0, copy.get(entry));
        for (FlowCanvas.Link l : internal) body.connect(copy.get(l.from()), l.port(), copy.get(l.to()));

        Map<Block, Block> exitBlock = new HashMap<>();
        int x = 40;
        for (Block t : targets) {
            exitBlock.put(t, body.addBlock(BlockType.EXIT, exitName.get(t), null, x, maxBottom + 60));
            x += 230;
        }
        if (targets.isEmpty()) body.addBlock(BlockType.EXIT, "next", null, 40, maxBottom + 60);
        for (FlowCanvas.Link l : outgoing) body.connect(copy.get(l.from()), l.port(), exitBlock.get(l.to()));
        c.lib.sync();

        // ---- replace the selection with one instance
        Block inst = c.addBlock(BlockType.CUSTOM, "", def, minX, minY);
        inst.setCall(method.name, "");
        for (FlowCanvas.Link l : incoming) c.connect(l.from(), l.port(), inst);
        for (Block t : targets) c.connect(inst, inst.portIndex(exitName.get(t)), t);
        for (Block b : sel) c.removeBlock(b);
        c.select(inst);
        c.lib.changed();
        return def;
    }

    /** "yes"/"no" if the jump comes from an If's true/false port, otherwise "out". */
    private static String suggestName(List<FlowCanvas.Link> outgoing, Block target) {
        for (FlowCanvas.Link l : outgoing) {
            if (l.to() == target && l.from().type == BlockType.IF) return l.port() == 0 ? "yes" : "no";
        }
        return "out";
    }
}
