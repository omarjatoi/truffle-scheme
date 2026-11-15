package r7rs.runtime;

import com.oracle.truffle.api.TruffleLanguage;

/** Per-context state. All I/O goes through {@link #env} (design.md D10). */
public final class R7RSContext {

    private final TruffleLanguage.Env env;

    R7RSContext(TruffleLanguage.Env env) {
        this.env = env;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }
}
