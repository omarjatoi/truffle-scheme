package scheme.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import scheme.reader.Scope;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SEnvironment;

/**
 * The compiler's state in one context (design.md D7, D15): the syntactic bindings of every unit, the
 * libraries, and the interaction environment that REPL inputs and other text without a leading
 * {@code import} run in. The interaction environment imports (r7rs %primitives) and every standard
 * library, and persists from one input to the next: an {@code import} adds to it, and definitions
 * stay. A program (text whose first form is {@code import}) gets a top level of its own instead.
 */
final class CompilerState {

    /**
     * What an {@link SEnvironment} holds (R7RS §6.12): a top-level scope and its namespace. Only the
     * interaction environment is mutable: definitions and imports in the others are errors.
     */
    record Environment(Scope scope, GlobalEnvironment namespace, boolean mutable) {
    }

    /**
     * The top level of an isolated program or a library, which a root compiled there records
     * ({@code SchemeRootNode.topLevel}): code a debugger evaluates in its frames resolves global
     * names there (plan.md M6-05, M7-07). A {@code closed} one's variables are assigned only by its
     * own code (an isolated program's, which its compilation assumes), so that code may not assign
     * them; a library's may be assigned from another unit by a macro it exports, so it may.
     */
    record TopLevel(Scope scope, GlobalEnvironment namespace, boolean closed) {
    }

    final SyntacticEnvironment top = new SyntacticEnvironment();
    final Scope interactionScope;
    final GlobalEnvironment interaction;
    final SEnvironment interactionEnvironment;
    /**
     * The immutable environments made so far, by their import sets as written: they are the same
     * each time, so each is made once (and its bindings added to {@link #top} once).
     */
    final Map<String, SEnvironment> environments = new HashMap<>();
    Libraries libraries;
    /**
     * While the context is being initialized for {@link PreludeImage}: the library bodies run so
     * far, in order. Otherwise null.
     */
    List<Ast.Lambda> recordedBodies;

    CompilerState() {
        this(new Scope(), new GlobalEnvironment());
    }

    CompilerState(Scope interactionScope, GlobalEnvironment interaction) {
        this.interactionScope = interactionScope;
        this.interaction = interaction;
        this.interactionEnvironment = new SEnvironment(new Environment(interactionScope, interaction, true));
        top.registerInteraction(interactionScope, interaction);
    }
}
