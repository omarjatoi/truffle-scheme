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
 */
public record PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory, boolean returnsValue) {

    /** A primitive that only returns values. */
    public PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory) {
        this(name, minArgs, maxArgs, factory, true);
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
