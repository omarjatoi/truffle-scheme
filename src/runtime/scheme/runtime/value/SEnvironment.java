package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * An environment specifier (R7RS §6.12, design.md D7): what {@code environment},
 * {@code interaction-environment}, {@code scheme-report-environment} and {@code null-environment}
 * return, and {@code eval} takes. {@link #state} is the compiler's (it sits above the runtime):
 * the environment's top-level scope and namespace.
 */
public final class SEnvironment implements TruffleObject {

    public final Object state;

    public SEnvironment(Object state) {
        this.state = state;
    }
}
