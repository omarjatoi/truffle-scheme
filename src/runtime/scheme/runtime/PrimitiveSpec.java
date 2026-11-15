package scheme.runtime;

import java.util.function.Supplier;

/**
 * A primitive procedure: its name, arity ({@code maxArgs} -1 means any number), and a factory for
 * the node that implements it.
 */
public record PrimitiveSpec(String name, int minArgs, int maxArgs, Supplier<PrimitiveNode> factory) {
}
