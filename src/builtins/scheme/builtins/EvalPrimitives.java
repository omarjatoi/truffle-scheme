package scheme.builtins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.TailCall;
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

    /** This area's primitives, as {@code Builtins} registers them. */
    static List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> specs = new ArrayList<>();
        for (Primitive primitive : Primitive.values()) {
            specs.add(primitive.spec);
        }
        return specs;
    }

    /**
     * This area's primitives: the one list to add to. A constant has the primitive's name and
     * arity, and {@link #node} makes its node when it is first called: no lambda per primitive for
     * the JVM to link as a context starts (design.md D17, "The primitives' registry").
     */
    enum Primitive implements Supplier<PrimitiveNode> {
        // R7RS requires the environment; with none, chibi uses the interaction environment.
        EVAL("eval", 1, 2, Result.TRANSFERS_CONTROL),
        ENVIRONMENT("environment", 0, -1),
        INTERACTION_ENVIRONMENT("interaction-environment", 0, 0),
        // (scheme r5rs) imports these two from (r7rs %primitives).
        SCHEME_REPORT_ENVIRONMENT("scheme-report-environment", 1, 1),
        NULL_ENVIRONMENT("null-environment", 1, 1),
        // (scheme load) imports it from (r7rs %primitives).
        LOAD("load", 1, 2, Result.TRANSFERS_CONTROL);

        final PrimitiveSpec spec;

        Primitive(String name, int minArgs, int maxArgs) {
            this(name, minArgs, maxArgs, Result.VALUE);
        }

        Primitive(String name, int minArgs, int maxArgs, Result result) {
            this.spec = new PrimitiveSpec(name, minArgs, maxArgs, this, result);
        }

        @Override
        public PrimitiveNode get() {
            return (PrimitiveNode) node();
        }

        /**
         * The primitive's node: a {@code return} in each case, typed {@code Object}, so that the
         * JVM's verifier needn't load every node class to check what is returned (a switch
         * expression's cases would join as {@code PrimitiveNode}). Each is loaded when it is made.
         */
        private Object node() {
            switch (this) {
                case EVAL:
                    return new Eval();
                case ENVIRONMENT:
                    return new Environment();
                case INTERACTION_ENVIRONMENT:
                    return new InteractionEnvironment();
                case SCHEME_REPORT_ENVIRONMENT:
                    return new ReportEnvironment("scheme-report-environment", false);
                case NULL_ENVIRONMENT:
                    return new ReportEnvironment("null-environment", true);
                case LOAD:
                    return new Load();
            }
            throw new AssertionError(this);
        }
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
                throw ControlPrimitives.notA("eval", 2, "environment", args[2]);
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
                throw ControlPrimitives.notA("load", 1, "string", args[1]);
            }
            SchemeLanguage language = SchemeLanguage.get(this);
            SchemeContext context = SchemeContext.get(this);
            SEnvironment environment;
            if (args.length < 3) {
                environment = language.compiler().interactionEnvironment(context);
            } else if (args[2] instanceof SEnvironment e) {
                environment = e;
            } else {
                throw ControlPrimitives.notA("load", 2, "environment", args[2]);
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
            for (int i = 1; i < args.length; i++) {
                if (!(args[i] instanceof SPair)) {
                    throw ControlPrimitives.notA("environment", i, "import set", args[i]);
                }
            }
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
                throw ControlPrimitives.notA(name, 1, "version this implementation supports (only 5 is)", version);
            }
            SchemeContext context = SchemeContext.get(this);
            Object r5rs = new SPair(context.symbols.intern("scheme"), new SPair(context.symbols.intern("r5rs"), SConstant.EMPTY_LIST));
            return SchemeLanguage.get(this).compiler().environment(context, new Object[]{r5rs}, syntaxOnly);
        }
    }
}
