package scheme.compiler;

import java.util.HashMap;
import java.util.Map;

import scheme.reader.Scope;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SEnvironment;

/**
 * The compiler's state in one context (design.md D7): the syntactic bindings of every unit, the
 * libraries, and the interaction environment that programs and REPL inputs run in. The interaction
 * environment imports (r7rs %primitives) and every standard library, and persists from one program
 * to the next: a program's {@code import} adds to it, and its definitions stay.
 */
final class CompilerState {

    /**
     * What an {@link SEnvironment} holds (R7RS §6.12): a top-level scope and its namespace. Only the
     * interaction environment is mutable: definitions and imports in the others are errors.
     */
    record Environment(Scope scope, GlobalEnvironment namespace, boolean mutable) {
    }

    final SyntacticEnvironment top = new SyntacticEnvironment();
    final Scope interactionScope = new Scope();
    final GlobalEnvironment interaction = new GlobalEnvironment();
    final SEnvironment interactionEnvironment = new SEnvironment(new Environment(interactionScope, interaction, true));
    /**
     * The immutable environments made so far, by their import sets as written: they are the same
     * each time, so each is made once (and its bindings added to {@link #top} once).
     */
    final Map<String, SEnvironment> environments = new HashMap<>();
    Libraries libraries;

    CompilerState() {
        top.registerNamespace(interactionScope, interaction);
    }
}
