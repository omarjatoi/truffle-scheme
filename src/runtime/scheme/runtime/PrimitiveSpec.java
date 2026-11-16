package scheme.runtime;

import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * A primitive procedure: its name, arity ({@code maxArgs} -1 means any number), and a factory for
 * the node that implements it.
 *
 * <p>{@code returnsValue}: whether the primitive only ever returns a value or throws a
 * {@link SchemeError}. Every primitive is a leaf that never calls Scheme (AGENTS.md rule 1), but a
 * few transfer control: they return a {@link TailCall} ({@code apply}, {@code eval}) or a
 * {@link Capture} ({@code call/cc}), or throw to a prompt. Only primitives that return values run
 * in place at their call sites (plan.md M5-09); the others are always called.
 *
 * <p>{@code allocates}: what the primitive's result is when it is a Scheme heap object, for
 * allocation listeners such as {@code --memtracer} (plan.md M7-07, {@link Allocations}):
 * {@link Allocations#NONE} (not new, or nothing to report), {@link Allocations#OBJECT} (a new
 * object), or {@link Allocations#LIST} (a list whose pairs are new).
 */
public record PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory, boolean returnsValue, int allocates) {

    /** A primitive that only returns values. */
    public PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory) {
        this(name, minArgs, maxArgs, factory, true, Allocations.NONE);
    }

    public PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory, boolean returnsValue) {
        this(name, minArgs, maxArgs, factory, returnsValue, Allocations.NONE);
    }

    /** This primitive, whose result, when it is a Scheme heap object, is a new one. */
    public PrimitiveSpec allocating() {
        return new PrimitiveSpec(name, minArgs, maxArgs, factory, returnsValue, Allocations.OBJECT);
    }

    /** This primitive, whose result, when it is a list, has new pairs. */
    public PrimitiveSpec allocatingList() {
        return new PrimitiveSpec(name, minArgs, maxArgs, factory, returnsValue, Allocations.LIST);
    }

    /**
     * A new node for the primitive: the factory's, which reports its results to allocation
     * listeners if the primitive allocates.
     */
    public PrimitiveNode createNode() {
        PrimitiveNode node = factory.get();
        return allocates == Allocations.NONE ? node : new Allocations.Reporting(node, allocates);
    }

    /** A primitive that transfers control: it is always called, never run in place. */
    public static PrimitiveSpec transfersControl(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory) {
        return new PrimitiveSpec(name, minArgs, maxArgs, factory, false);
    }

    /** Throws the arity error unless the primitive takes {@code count} arguments. */
    public void checkArity(int count) {
        if (count < minArgs || (maxArgs >= 0 && count > maxArgs)) {
            throw arityError(count);
        }
    }

    @TruffleBoundary
    private SchemeError arityError(int count) {
        String expected = maxArgs < 0 ? "at least " + minArgs : minArgs == maxArgs ? Integer.toString(minArgs) : minArgs + " to " + maxArgs;
        return new SchemeError(name + ": expected " + expected + " arguments, got " + count);
    }
}
