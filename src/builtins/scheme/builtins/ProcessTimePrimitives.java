package scheme.builtins;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.Features;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;

/**
 * The process context and time area (R7RS §6.14): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else. Everything
 * about the process (arguments, environment variables, files, exiting) comes from the language Env
 * (design.md D10); {@code exit}, which runs the after-thunks first, is in process-time.scm.
 */
final class ProcessTimePrimitives {

    /** Jiffies are nanoseconds of {@link System#nanoTime()}. */
    private static final long JIFFIES_PER_SECOND = 1_000_000_000L;

    private ProcessTimePrimitives() {
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
        EMERGENCY_EXIT("emergency-exit", 0, 1),
        COMMAND_LINE("command-line", 0, 0),
        GET_ENVIRONMENT_VARIABLE("get-environment-variable", 1, 1),
        GET_ENVIRONMENT_VARIABLES("get-environment-variables", 0, 0),
        CURRENT_SECOND("current-second", 0, 0),
        CURRENT_JIFFY("current-jiffy", 0, 0),
        JIFFIES_PER_SECOND("jiffies-per-second", 0, 0),
        FEATURES("features", 0, 0);

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
                case EMERGENCY_EXIT:
                    return new EmergencyExit();
                case COMMAND_LINE:
                    return new CommandLine();
                case GET_ENVIRONMENT_VARIABLE:
                    return new GetEnvironmentVariable();
                case GET_ENVIRONMENT_VARIABLES:
                    return new GetEnvironmentVariables();
                case CURRENT_SECOND:
                    return new CurrentSecond();
                case CURRENT_JIFFY:
                    return new CurrentJiffy();
                case JIFFIES_PER_SECOND:
                    return new JiffiesPerSecond();
                case FEATURES:
                    return new FeatureList();
            }
            throw new AssertionError(this);
        }
    }

    /**
     * {@code (emergency-exit [obj])}: exits the context through Truffle at once, without running the
     * after-thunks (design.md D9). Embedders see an exit ({@code PolyglotException.isExit()}, D13)
     * with status 0 for no argument or #t, the integer itself for an exact integer from 0 to 255,
     * and 1 for #f and anything else: an exact integer a process status can't hold is a failure,
     * rather than wrapping (256 would otherwise exit with 0). {@code exit} (process-time.scm)
     * unwinds, then calls this.
     */
    static final class EmergencyExit extends PrimitiveNode {
        /** The largest status a process can exit with (POSIX keeps a status's low 8 bits). */
        private static final long MAX_STATUS = 255;

        @Override
        public Object invoke(Object[] args) {
            throw exit(this, args.length == 1 ? Boolean.TRUE : args[1]);
        }

        @TruffleBoundary
        private static RuntimeException exit(Node location, Object obj) {
            int status;
            if (obj instanceof Boolean b) {
                status = b ? 0 : 1;
            } else if (obj instanceof Long l && l >= 0 && l <= MAX_STATUS) {
                status = l.intValue();
            } else {
                status = 1;
            }
            SchemeContext context = SchemeContext.get(location);
            context.out().flush();
            context.getEnv().getContext().closeExited(new ExitLocation(SchemeError.locate(location)), status);
            throw new IllegalStateException("closeExited returned");
        }
    }

    /**
     * Where an exit is: Truffle locates it at the node {@code closeExited} is given, by its source
     * section. The primitive's own node is in the frame that exits, the prelude's {@code exit}'s
     * when {@code exit} was called, and is located at the whole of that procedure, as a primitive
     * run in place has no source section of its own. This one is located as an error raised there
     * would be: at the innermost call of code that isn't internal (design.md D8), the program's.
     * Made only as the context exits, so no call site has one.
     */
    private static final class ExitLocation extends Node {
        private final SourceSection section;

        ExitLocation(SourceSection section) {
            this.section = section;
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
        }
    }

    /** {@code (command-line)}: the application arguments the embedder gave the context. */
    static final class CommandLine extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return strings(SchemeContext.get(this).getEnv());
        }

        @TruffleBoundary
        private static Object strings(TruffleLanguage.Env env) {
            String[] arguments = env.getApplicationArguments();
            Object list = SConstant.EMPTY_LIST;
            for (int i = arguments.length - 1; i >= 0; i--) {
                list = new SPair(SString.of(arguments[i]), list);
            }
            return list;
        }
    }

    /** {@code (get-environment-variable name)}: its value, or #f; through the Env (design.md D10). */
    static final class GetEnvironmentVariable extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SString name)) {
                throw ControlPrimitives.notA("get-environment-variable", "string", args[1]);
            }
            return lookup(SchemeContext.get(this).getEnv(), name);
        }

        @TruffleBoundary
        private static Object lookup(TruffleLanguage.Env env, SString name) {
            String value = env.getEnvironment().get(name.toString());
            return value == null ? Boolean.FALSE : SString.of(value);
        }
    }

    /** {@code (get-environment-variables)}: an alist of fresh strings, (name . value). */
    static final class GetEnvironmentVariables extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return alist(SchemeContext.get(this).getEnv());
        }

        @TruffleBoundary
        private static Object alist(TruffleLanguage.Env env) {
            Object list = SConstant.EMPTY_LIST;
            for (Map.Entry<String, String> entry : env.getEnvironment().entrySet()) {
                list = new SPair(new SPair(SString.of(entry.getKey()), SString.of(entry.getValue())), list);
            }
            return list;
        }
    }

    /** {@code (current-second)}: seconds since the epoch (UTC, standing in for TAI), inexact. */
    static final class CurrentSecond extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return now();
        }

        @TruffleBoundary
        private static double now() {
            Instant now = Instant.now();
            return now.getEpochSecond() + now.getNano() / 1e9;
        }
    }

    /** {@code (current-jiffy)}: an exact count of nanoseconds since an arbitrary, fixed epoch. */
    static final class CurrentJiffy extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return System.nanoTime();
        }
    }

    static final class JiffiesPerSecond extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return JIFFIES_PER_SECOND;
        }
    }

    /** {@code (features)}: a fresh list of the feature identifiers cond-expand recognizes. */
    static final class FeatureList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return symbols(SchemeContext.get(this));
        }

        @TruffleBoundary
        private static Object symbols(SchemeContext context) {
            Object list = SConstant.EMPTY_LIST;
            for (int i = Features.NAMES.size() - 1; i >= 0; i--) {
                list = new SPair(context.symbols.intern(Features.NAMES.get(i)), list);
            }
            return list;
        }
    }
}
