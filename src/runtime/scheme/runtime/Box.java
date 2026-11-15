package scheme.runtime;

/**
 * The storage of a variable that is the target of {@code set!}, or a {@code letrec*} variable a
 * closure captures (design.md D2). Frames and closures hold the box, never the value, so frame
 * copies on continuation resume share it.
 */
public final class Box {

    public Object value;

    public Box(Object value) {
        this.value = value;
    }
}
