package scheme.builtins;

import java.io.IOException;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.TailCall;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SEnvironment;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;

/**
 * The eval and environments area (R7RS §6.12, and {@code load} from §6.13.1): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 *
 * <p>The compiler is a service of the language ({@link scheme.runtime.SchemeCompiler}): {@code eval}
 * has it compile the datum into a procedure of no arguments and returns a {@link TailCall} to it
 * (design.md D5, D6), so the evaluated code runs in {@code eval}'s tail position.
 */
final class EvalPrimitives {

    private EvalPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        // R7RS requires the environment; with none, chibi uses the interaction environment.
                        PrimitiveSpec.transfersControl("eval", 1, 2, Eval::new),
                        new PrimitiveSpec("environment", 0, -1, Environment::new),
                        new PrimitiveSpec("interaction-environment", 0, 0, InteractionEnvironment::new),
                        // (scheme r5rs) imports these two from (r7rs %primitives).
                        new PrimitiveSpec("scheme-report-environment", 1, 1, () -> new ReportEnvironment("scheme-report-environment", false)),
                        new PrimitiveSpec("null-environment", 1, 1, () -> new ReportEnvironment("null-environment", true)),
                        // (scheme load) imports it from (r7rs %primitives).
                        PrimitiveSpec.transfersControl("load", 1, 2, Load::new));
    }

    /** {@code (eval expr-or-def [environment])}: compiles, then tail-calls the result (design.md D6). */
    static final class Eval extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SClosure thunk = compile(args);
            return new TailCall(thunk, new Object[]{thunk});
        }

        @TruffleBoundary
        private SClosure compile(Object[] args) {
            SchemeLanguage language = SchemeLanguage.get(this);
            SchemeContext context = SchemeContext.get(this);
            SEnvironment environment;
            if (args.length < 3) {
                environment = language.compiler().interactionEnvironment(context);
            } else if (args[2] instanceof SEnvironment e) {
                environment = e;
            } else {
                throw ControlPrimitives.notA("eval", "environment", args[2]);
            }
            return language.compiler().compileEval(language, context, args[1], environment);
        }
    }

    /**
     * {@code (load filename [environment])} (R7RS §6.13.1): reads the file through {@link TruffleFile}
     * (design.md D10), compiles its forms in the environment (by default the interaction
     * environment, as in chibi), then tail-calls the result (D5, D6), like {@code eval}. A file that
     * can't be read is a file error.
     */
    static final class Load extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SClosure thunk = compile(args);
            return new TailCall(thunk, new Object[]{thunk});
        }

        @TruffleBoundary
        private SClosure compile(Object[] args) {
            if (!(args[1] instanceof SString name)) {
                throw ControlPrimitives.notA("load", "string", args[1]);
            }
            SchemeLanguage language = SchemeLanguage.get(this);
            SchemeContext context = SchemeContext.get(this);
            SEnvironment environment;
            if (args.length < 3) {
                environment = language.compiler().interactionEnvironment(context);
            } else if (args[2] instanceof SEnvironment e) {
                environment = e;
            } else {
                throw ControlPrimitives.notA("load", "environment", args[2]);
            }
            Source source;
            try {
                TruffleFile file = context.getEnv().getPublicTruffleFile(name.toString());
                source = Source.newBuilder(SchemeLanguage.ID, file).build();
            } catch (IOException | SecurityException | UnsupportedOperationException | IllegalArgumentException e) {
                throw IoPrimitives.fileError("load: cannot read file", name);
            }
            return language.compiler().compileLoad(language, context, source, environment);
        }
    }

    /** {@code (environment import-set ...)}: an immutable environment importing them (design.md D7). */
    static final class Environment extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return environment(args);
        }

        @TruffleBoundary
        private Object environment(Object[] args) {
            return SchemeLanguage.get(this).compiler().environment(SchemeContext.get(this), java.util.Arrays.copyOfRange(args, 1, args.length), false);
        }
    }

    static final class InteractionEnvironment extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return interaction();
        }

        @TruffleBoundary
        private Object interaction() {
            return SchemeLanguage.get(this).compiler().interactionEnvironment(SchemeContext.get(this));
        }
    }

    /**
     * {@code (scheme-report-environment 5)}: (scheme r5rs); {@code (null-environment 5)}: only its
     * syntactic keywords (R7RS §6.12). Version 5 is the only one supported.
     */
    static final class ReportEnvironment extends PrimitiveNode {
        private final String name;
        private final boolean syntaxOnly;

        ReportEnvironment(String name, boolean syntaxOnly) {
            this.name = name;
            this.syntaxOnly = syntaxOnly;
        }

        @Override
        public Object invoke(Object[] args) {
            return environment(args[1]);
        }

        @TruffleBoundary
        private Object environment(Object version) {
            if (!(version instanceof Long v && v == 5)) {
                throw new SchemeError(name + ": unsupported version (only 5 is): " + Printer.print(version, Printer.Mode.WRITE));
            }
            SchemeContext context = SchemeContext.get(this);
            Object r5rs = new SPair(context.symbols.intern("scheme"), new SPair(context.symbols.intern("r5rs"), SConstant.EMPTY_LIST));
            return SchemeLanguage.get(this).compiler().environment(context, new Object[]{r5rs}, syntaxOnly);
        }
    }
}
