package evia.paradigma;

import java.util.Comparator;
import java.util.List;

/**
 * A method of a class: parameters and a body graph that starts at its Enter block and leaves
 * through Exit blocks. Each distinct Exit name is an output port of the blocks that call it.
 * A method named {@link BlockDef#CONSTRUCTOR} is the constructor.
 */
final class Method {
    final BlockDef cls;
    String name;
    List<String> params;
    final FlowCanvas body;

    Method(Library lib, BlockDef cls, String name, List<String> params) {
        this.cls = cls;
        this.name = name;
        this.params = List.copyOf(params);
        this.body = new FlowCanvas(lib, this);
    }

    boolean isConstructor() {
        return name.equals(BlockDef.CONSTRUCTOR);
    }

    /** The Enter block of the body, or null if it is missing. */
    Block enter() {
        for (Block b : body.getBlocks()) if (b.type == BlockType.ENTER) return b;
        return null;
    }

    /** Output port names: distinct Exit names, ordered left to right as they sit in the body. */
    List<String> exitNames() {
        return body.getBlocks().stream()
                .filter(b -> b.type == BlockType.EXIT)
                .sorted(Comparator.comparingInt(Block::modelX).thenComparingInt(b -> b.id))
                .map(b -> b.getCode().trim())
                .distinct()
                .toList();
    }

    /** "add(n)" */
    String signature() {
        return name + "(" + String.join(", ", params) + ")";
    }

    /** "Counter.add" - how errors and generated code refer to it. */
    String qualified() {
        return cls.name + "." + name;
    }
}
