package scheme.reader;

/** Code points for the {@link Reader}. Ports (M4) will implement this too. */
public interface CharSource {

    /** The next code point without consuming it, or -1 at the end. */
    int peek();

    /** The next code point, or -1 at the end. */
    int next();
}
