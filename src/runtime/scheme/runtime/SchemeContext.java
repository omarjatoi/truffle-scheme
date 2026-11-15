package scheme.runtime;

import com.oracle.truffle.api.TruffleLanguage;

/** Per-context state. All I/O goes through {@link #env} (design.md D10). */
public final class SchemeContext {

    private final TruffleLanguage.Env env;

    SchemeContext(TruffleLanguage.Env env) {
        this.env = env;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }
}
