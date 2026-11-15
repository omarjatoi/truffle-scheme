package scheme.runtime;

import java.util.List;

/**
 * Primitive procedures, provided by the scheme.builtins project through
 * {@link java.util.ServiceLoader}. Primitives are leaf operations that never call Scheme (AGENTS.md
 * rule 1); the runtime wraps each in a {@link PrimitiveRootNode}.
 */
public interface BuiltinLibrary {

    List<PrimitiveSpec> primitives();
}
