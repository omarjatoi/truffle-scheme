package scheme.compiler;

import scheme.reader.Scope;
import scheme.runtime.GlobalEnvironment;

/**
 * The compiler's state in one context (design.md D7): the syntactic bindings of every unit, the
 * libraries, and the interaction environment that programs and REPL inputs run in. The interaction
 * environment imports (r7rs %primitives) and every standard library, and persists from one program
 * to the next: a program's {@code import} adds to it, and its definitions stay.
 */
final class CompilerState {

    final SyntacticEnvironment top = new SyntacticEnvironment();
    final Scope interactionScope = new Scope();
    final GlobalEnvironment interaction = new GlobalEnvironment();
    Libraries libraries;

    CompilerState() {
        top.registerNamespace(interactionScope, interaction);
    }
}
