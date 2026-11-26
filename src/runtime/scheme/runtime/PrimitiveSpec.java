package scheme.runtime;

import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * A primitive procedure: its name, arity ({@code maxArgs} -1 means any number), and a factory for
 * the node that implements it. An area's primitives are the constants of its enum, each its own
 * factory, whose {@code switch} makes the node (scheme.builtins.Builtins): no lambda per primitive,
 * which the JVM would link, a hidden class each, as every context starts (design.md D17). A
 * primitive's node is made when it is first called ({@link PrimitiveRootNode}) or run in place.
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

    /** A primitive whose calls give {@code result}. */
    public PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory, Result result) {
        this(name, minArgs, maxArgs, factory, result != Result.TRANSFERS_CONTROL,
                        result == Result.NEW_OBJECT ? Allocations.OBJECT : result == Result.NEW_LIST ? Allocations.LIST : Allocations.NONE);
    }

    /** What a primitive's call gives: its {@code returnsValue} and {@code allocates}. */
    public enum Result {
        /** A value: nothing to report ({@link Allocations#NONE}). */
        VALUE,
        /** A value that, when it is a Scheme heap object, is a new one ({@link Allocations#OBJECT}). */
        NEW_OBJECT,
        /** A value that, when it is a list, has new pairs ({@link Allocations#LIST}). */
        NEW_LIST,
        /** A {@link TailCall}, a {@link Capture} or a throw to a prompt: it is always called. */
        TRANSFERS_CONTROL
    }

    /** This primitive, whose result, when it is a Scheme heap object, is a new one. */
    public PrimitiveSpec allocating() {
        return new PrimitiveSpec(name, minArgs, maxArgs, factory, returnsValue, Allocations.OBJECT);
    }

    /**
     * A new node for the primitive: the factory's, which reports its results to allocation
     * listeners if the primitive allocates.
     */
    public PrimitiveNode createNode() {
        PrimitiveNode node = factory.get();
        return allocates == Allocations.NONE ? node : new Allocations.Reporting(node, allocates);
    }

    /** Throws the arity error unless the primitive takes {@code count} arguments. */
    public void checkArity(int count) {
        if (count < minArgs || (maxArgs >= 0 && count > maxArgs)) {
            throw arityError(count);
        }
    }

    /**
     * Named as the program calls it (design.md D8, "Messages"): a %-primitive's public procedure is
     * its name without the %, as the polyglot library renames them and io.scm's procedures are.
     */
    @TruffleBoundary
    private SchemeError arityError(int count) {
        return SchemeError.arity(name.startsWith("%") ? name.substring(1) : name, minArgs, maxArgs, count);
    }
}
