package scheme.reader;

/** A {@link CharSource} over a Java string. */
public final class StringCharSource implements CharSource {

    private final String text;
    private int index;

    public StringCharSource(String text) {
        this.text = text;
    }

    @Override
    public int peek() {
        return index < text.length() ? text.codePointAt(index) : -1;
    }

    @Override
    public int next() {
        if (index >= text.length()) {
            return -1;
        }
        int cp = text.codePointAt(index);
        index += Character.charCount(cp);
        return cp;
    }
}
