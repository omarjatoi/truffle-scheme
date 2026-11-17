package scheme.builtins;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.Features;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
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

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("emergency-exit", 0, 1, EmergencyExit::new),
                        new PrimitiveSpec("command-line", 0, 0, CommandLine::new),
                        new PrimitiveSpec("get-environment-variable", 1, 1, GetEnvironmentVariable::new),
                        new PrimitiveSpec("get-environment-variables", 0, 0, GetEnvironmentVariables::new),
                        new PrimitiveSpec("current-second", 0, 0, CurrentSecond::new),
                        new PrimitiveSpec("current-jiffy", 0, 0, CurrentJiffy::new),
                        new PrimitiveSpec("jiffies-per-second", 0, 0, JiffiesPerSecond::new),
                        new PrimitiveSpec("features", 0, 0, FeatureList::new));
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
            context.getEnv().getContext().closeExited(location, status);
            throw new IllegalStateException("closeExited returned");
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
