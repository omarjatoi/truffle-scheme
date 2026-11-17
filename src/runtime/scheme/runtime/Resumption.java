package scheme.runtime;

/**
 * The value a suspended frame is resumed with ({@code PromptDriver}), or the error to unwind it
 * with, which its {@code Resumed} operation takes. A holder, so that the value isn't kept by what
 * passes it on: the continuation root's arguments and the driver's Java frames stay on the stack
 * while the resumed frame runs, which may be for ever (R7RS §3.5, design.md "Tail calls keep
 * nothing alive").
 */
final class Resumption {

    private Object value;

    Resumption(Object value) {
        this.value = value;
    }

    /** The value; once only. */
    Object take() {
        Object v = value;
        value = null;
        return v;
    }
}
