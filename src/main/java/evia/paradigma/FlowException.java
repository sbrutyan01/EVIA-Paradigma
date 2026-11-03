package evia.paradigma;

/** Any compile or runtime error in a block program. Remembers which block failed. */
public final class FlowException extends RuntimeException {
    Block block;

    FlowException(String message) {
        super(message);
    }

    FlowException(Block block, String message) {
        super(message);
        this.block = block;
    }
}
