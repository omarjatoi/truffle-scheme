package scheme.runtime;

import java.util.Locale;
import java.util.logging.Level;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLogger;

/**
 * The language's loggers (design.md D13, "Logging"; docs/usage.md): {@code --log.scheme.level=FINE}
 * turns on all of them, {@code --log.scheme.<component>.level=FINE} one. Each logs rare events only,
 * at {@code FINE}, so a guard costs nothing when its logger is off: callers test
 * {@code isLoggable(Level.FINE)}, which partial evaluation folds to a constant (a static final logger,
 * whose level is compilation final under an assumption), and log in a {@link TruffleBoundary} method.
 */
public final class SchemeLogs {

    /** Spills of deep recursion to the heap, and the stack measurements that set the spill limit. */
    public static final TruffleLogger SPILL = TruffleLogger.getLogger(SchemeLanguage.ID, "spill");
    /** Full and composable continuations captured, re-entered and reinstated by prompt drivers. */
    public static final TruffleLogger CONTINUATIONS = TruffleLogger.getLogger(SchemeLanguage.ID, "continuations");
    /** How a context gets its standard libraries: restored from the prelude image, or expanded. */
    public static final TruffleLogger PRELUDE = TruffleLogger.getLogger(SchemeLanguage.ID, "prelude");
    /** Libraries loaded from files, and the ones not found. */
    public static final TruffleLogger LIBRARY = TruffleLogger.getLogger(SchemeLanguage.ID, "library");

    private SchemeLogs() {
    }

    /** Logs {@code message} at {@code FINE}; callers have checked that the logger is on. */
    @TruffleBoundary
    public static void fine(TruffleLogger logger, String message) {
        logger.fine(message);
    }

    /** Milliseconds since {@code startNanos}, with a tenth, for a log message. */
    @TruffleBoundary
    public static String millisSince(long startNanos) {
        return String.format(Locale.ROOT, "%.1f ms", (System.nanoTime() - startNanos) / 1e6);
    }

    /** {@code 1 frame}, {@code 2 frames}. */
    public static String frames(int count) {
        return count == 1 ? "1 frame" : count + " frames";
    }

    /** Whether {@code logger} logs at {@code FINE}: a constant in compiled code, so free when off. */
    public static boolean on(TruffleLogger logger) {
        return logger.isLoggable(Level.FINE);
    }
}
