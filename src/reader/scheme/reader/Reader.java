package scheme.reader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.SchemeError;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;
import scheme.runtime.value.SymbolTable;

/**
 * Reads R7RS data (section 7.1.2) from a {@link CharSource}, one datum per {@link #read()}.
 *
 * <p>Parsing never recurses in Java: nested data, abbreviations, datum labels, block comments and
 * datum comments are all handled with explicit stacks (AGENTS.md rule 4). Read errors are
 * {@link SchemeError}s of kind {@code READ} (design.md D8). The {@code #!fold-case} state belongs
 * to the reader, so it lasts across reads from the same source, as R7RS requires for ports.
 *
 * <p>{@link #readSyntax()} reads program text as {@link Syntax} objects (design.md D3): every
 * datum, compound or not, is wrapped with its source section; datum labels may make the result
 * cyclic or shared, as with {@link #read()}.
 */
public final class Reader {

    private final CharSource in;
    private final SymbolTable symbols;
    /** Program text: strings, vectors and bytevectors are immutable literals. */
    private final boolean literals;
    private boolean foldCase;

    /**
     * Whether reading ran into the end of the input: after an error, the datum was cut off by the
     * end (a REPL should ask for more), rather than malformed.
     */
    public boolean ranIntoEnd() {
        return sawEnd;
    }

    /** The line of the next character (1-based). */
    public int line() {
        return line;
    }

    /** Starts folding case, as if the text began with {@code #!fold-case} ({@code include-ci}). */
    public void setFoldCase(boolean fold) {
        this.foldCase = fold;
    }

    private int line = 1;
    /** Whether a read has seen the end of the input. */
    private boolean sawEnd;
    /** The UTF-16 index of the next character (what {@link SourceSection}s count). */
    private int position;
    private final int[] pushback = new int[2];
    private int pushed;
    /** For {@link #readSyntax()}; null when the text has no {@link Source}. */
    private final Source source;
    /** Set while {@link #readSyntax()} runs. */
    private boolean syntax;

    public Reader(CharSource in, SymbolTable symbols, boolean literals) {
        this(in, symbols, literals, null);
    }

    private Reader(CharSource in, SymbolTable symbols, boolean literals, Source source) {
        this.in = in;
        this.symbols = symbols;
        this.literals = literals;
        this.source = source;
    }

    public static Reader forString(String text, SymbolTable symbols, boolean literals) {
        return new Reader(new StringCharSource(text), symbols, literals);
    }

    /** A reader over a source's text whose {@link #readSyntax()} records sections of that source. */
    public static Reader forSource(Source source, SymbolTable symbols, boolean literals) {
        return new Reader(new StringCharSource(source.getCharacters().toString()), symbols, literals, source);
    }

    // ------------------------------------------------------------------------------------------
    // Characters

    private int peek() {
        int c = pushed > 0 ? pushback[pushed - 1] : in.peek();
        sawEnd |= c < 0;
        return c;
    }

    private int next() {
        int c = pushed > 0 ? pushback[--pushed] : in.next();
        sawEnd |= c < 0;
        if (c == '\n') {
            line++;
        }
        if (c != -1) {
            position += Character.charCount(c);
        }
        return c;
    }

    private void unread(int c) {
        if (c == '\n') {
            line--;
        }
        if (c != -1) {
            position -= Character.charCount(c);
        }
        pushback[pushed++] = c;
    }

    private static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    private static boolean isDelimiter(int c) {
        return c == -1 || isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';' || c == '|' || c == '\'' || c == '`' || c == ',' ||
                        c == '[' || c == ']' || c == '{' || c == '}';
    }

    @TruffleBoundary
    private SchemeError error(String message) {
        return new SchemeError(new SErrorObject(SString.of(message + " (line " + line + ")"), SConstant.EMPTY_LIST, SErrorObject.Kind.READ));
    }

    // ------------------------------------------------------------------------------------------
    // Tokens

    private enum Kind {
        EOF,
        OPEN,
        CLOSE,
        VECTOR,
        BYTEVECTOR,
        ABBREVIATION,
        DATUM_COMMENT,
        LABEL_DEF,
        LABEL_REF,
        DOT,
        ATOM
    }

    /** The value of the last ATOM or ABBREVIATION token, or the number of a LABEL_DEF/REF. */
    private Object tokenValue;
    private int tokenLabel;
    /** Where the last token starts. */
    private int tokenStart;

    /** Skips whitespace, line comments, nested block comments and directives. */
    private void skipAtmosphere() {
        while (true) {
            int c = peek();
            if (isWhitespace(c)) {
                next();
            } else if (c == ';') {
                // A line comment ends at a line ending: \n, \r or \r\n (R7RS §7.1.1).
                while (c != -1 && c != '\n' && c != '\r') {
                    c = next();
                }
            } else if (c == '#') {
                next();
                int c2 = peek();
                if (c2 == '|') {
                    next();
                    skipBlockComment();
                } else if (c2 == '!') {
                    next();
                    directive();
                } else {
                    unread('#');
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void skipBlockComment() {
        int depth = 1;
        while (depth > 0) {
            int c = next();
            if (c == -1) {
                throw error("unterminated block comment");
            } else if (c == '|' && peek() == '#') {
                next();
                depth--;
            } else if (c == '#' && peek() == '|') {
                next();
                depth++;
            }
        }
    }

    private void directive() {
        String name = readTokenText(new StringBuilder());
        switch (name.toLowerCase(Locale.ROOT)) {
            case "fold-case" -> foldCase = true;
            case "no-fold-case" -> foldCase = false;
            default -> throw error("unknown directive #!" + name);
        }
    }

    private String readTokenText(StringBuilder sb) {
        while (!isDelimiter(peek())) {
            sb.appendCodePoint(next());
        }
        return sb.toString();
    }

    private Kind nextToken() {
        skipAtmosphere();
        tokenStart = position;
        int c = next();
        switch (c) {
            case -1:
                return Kind.EOF;
            case '(':
                return Kind.OPEN;
            case ')':
                return Kind.CLOSE;
            case '[', ']', '{', '}':
                throw error("'" + (char) c + "' is reserved");
            case '\'':
                tokenValue = symbols.intern("quote");
                return Kind.ABBREVIATION;
            case '`':
                tokenValue = symbols.intern("quasiquote");
                return Kind.ABBREVIATION;
            case ',':
                if (peek() == '@') {
                    next();
                    tokenValue = symbols.intern("unquote-splicing");
                } else {
                    tokenValue = symbols.intern("unquote");
                }
                return Kind.ABBREVIATION;
            case '"':
                String s = readEscaped('"');
                tokenValue = literals ? SString.literal(s) : SString.of(s);
                return Kind.ATOM;
            case '|':
                tokenValue = symbols.intern(readEscaped('|'));
                return Kind.ATOM;
            case '#':
                return hash();
            default:
                String text = readTokenText(new StringBuilder().appendCodePoint(c));
                if (text.equals(".")) {
                    return Kind.DOT;
                }
                tokenValue = atom(text);
                return Kind.ATOM;
        }
    }

    private Object atom(String text) {
        Object number = NumberParser.parse(text, 10);
        if (number != null) {
            return number;
        }
        if (looksNumeric(text)) {
            throw error("invalid number: " + text);
        }
        return symbols.intern(foldCase ? fold(text) : text);
    }

    /** Text that can only be a number (R7RS identifiers can't start like this). */
    private static boolean looksNumeric(String t) {
        char c0 = t.charAt(0);
        if (c0 >= '0' && c0 <= '9') {
            return true;
        }
        if (t.length() > 1 && (c0 == '+' || c0 == '-' || c0 == '.')) {
            char c1 = t.charAt(1);
            return (c1 >= '0' && c1 <= '9') || (c0 != '.' && c1 == '.' && t.length() > 2 && t.charAt(2) >= '0' && t.charAt(2) <= '9');
        }
        return false;
    }

    /** string-foldcase, approximated with full case mapping (folds ß to ss). */
    private static String fold(String s) {
        return s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private Kind hash() {
        int c = peek();
        switch (c) {
            case '(':
                next();
                return Kind.VECTOR;
            case ';':
                next();
                return Kind.DATUM_COMMENT;
            case '\\':
                next();
                tokenValue = character();
                return Kind.ATOM;
            case 'u', 'U': {
                String text = readTokenText(new StringBuilder());
                if (text.equalsIgnoreCase("u8") && peek() == '(') {
                    next();
                    return Kind.BYTEVECTOR;
                }
                throw error("invalid syntax #" + text);
            }
            default:
                break;
        }
        if (c >= '0' && c <= '9') {
            int n = 0;
            while (peek() >= '0' && peek() <= '9') {
                n = Math.addExact(Math.multiplyExact(n, 10), next() - '0');
            }
            int c2 = next();
            tokenLabel = n;
            if (c2 == '=') {
                return Kind.LABEL_DEF;
            } else if (c2 == '#') {
                return Kind.LABEL_REF;
            }
            throw error("invalid datum label #" + n + (c2 == -1 ? "" : new String(Character.toChars(c2))));
        }
        String text = readTokenText(new StringBuilder());
        switch (text.toLowerCase(Locale.ROOT)) {
            case "t", "true":
                tokenValue = true;
                return Kind.ATOM;
            case "f", "false":
                tokenValue = false;
                return Kind.ATOM;
            default:
                break;
        }
        Object number = NumberParser.parse("#" + text, 10);
        if (number == null) {
            throw error("invalid syntax #" + text);
        }
        tokenValue = number;
        return Kind.ATOM;
    }

    private static int charByName(String name) {
        return switch (name) {
            case "alarm" -> 7;
            case "backspace" -> 8;
            case "delete" -> 0x7f;
            case "escape" -> 0x1b;
            case "newline" -> '\n';
            case "null" -> 0;
            case "return" -> '\r';
            case "space" -> ' ';
            case "tab" -> '\t';
            default -> -1;
        };
    }

    private SChar character() {
        int first = next();
        if (first == -1) {
            throw error("end of input after #\\");
        }
        String rest = readTokenText(new StringBuilder());
        if (rest.isEmpty()) {
            return SChar.of(first);
        }
        String name = new StringBuilder().appendCodePoint(first).append(rest).toString();
        if ((first == 'x' || first == 'X') && isHex(rest)) {
            return scalar(rest);
        }
        int cp = charByName(foldCase ? fold(name) : name);
        if (cp < 0) {
            throw error("unknown character name #\\" + name);
        }
        return SChar.of(cp);
    }

    private static boolean isHex(String s) {
        if (s.isEmpty() || s.length() > 8) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || s.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    private SChar scalar(String hex) {
        try {
            return SChar.of(Integer.parseInt(hex, 16));
        } catch (SchemeError | NumberFormatException e) {
            throw error("not a Unicode scalar value: #x" + hex);
        }
    }

    private static boolean isIntralineWhitespace(int c) {
        return c == ' ' || c == '\t';
    }

    /** The body of a string ("...") or a |symbol|, after the opening delimiter. */
    private String readEscaped(int close) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = next();
            if (c == -1) {
                throw error(close == '"' ? "unterminated string" : "unterminated |symbol|");
            } else if (c == close) {
                return sb.toString();
            } else if (c != '\\') {
                sb.appendCodePoint(c);
                continue;
            }
            int e = next();
            switch (e) {
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case '"', '\\', '|' -> sb.append((char) e);
                case 'x', 'X' -> {
                    StringBuilder hex = new StringBuilder();
                    int h;
                    while ((h = next()) != ';') {
                        if (h == -1) {
                            throw error("unterminated \\x escape");
                        }
                        hex.appendCodePoint(h);
                    }
                    if (!isHex(hex.toString())) {
                        throw error("invalid \\x escape: \\x" + hex + ";");
                    }
                    sb.appendCodePoint(scalar(hex.toString()).codePoint);
                }
                default -> {
                    // Line continuation: \ <intraline whitespace>* <line ending> <intraline whitespace>*
                    int w = e;
                    while (isIntralineWhitespace(w)) {
                        w = next();
                    }
                    if (close == '"' && (w == '\n' || w == '\r')) {
                        if (w == '\r' && peek() == '\n') {
                            next();
                        }
                        while (isIntralineWhitespace(peek())) {
                            next();
                        }
                    } else {
                        throw error("unknown escape \\" + (e == -1 ? "" : new String(Character.toChars(e))));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Data

    private abstract static class Frame {
        /** Where the datum this frame reads starts (for syntax objects). */
        int start;
    }

    private static final class ListFrame extends Frame {
        final List<Object> items = new ArrayList<>();
        /** 0: reading items; 1: after '.', expecting the tail; 2: tail read, expecting ')'. */
        int state;
        Object tail = SConstant.EMPTY_LIST;
    }

    private static final class VectorFrame extends Frame {
        final List<Object> items = new ArrayList<>();
    }

    private static final class BytevectorFrame extends Frame {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    }

    private static final class AbbreviationFrame extends Frame {
        final SSymbol symbol;

        AbbreviationFrame(SSymbol symbol) {
            this.symbol = symbol;
        }
    }

    private static final class DatumCommentFrame extends Frame {
    }

    private static final class LabelFrame extends Frame {
        final int label;
        final Placeholder placeholder;

        LabelFrame(int label, Placeholder placeholder) {
            this.label = label;
            this.placeholder = placeholder;
        }
    }

    /** Stands in for a labeled datum that is still being read; patched when it completes. */
    private static final class Placeholder {
        boolean referenced;
    }

    /** The next datum, or {@link SConstant#EOF} at the end of the input. */
    @TruffleBoundary
    public Object read() {
        syntax = false;
        return readDatum();
    }

    /**
     * The next datum as a {@link Syntax} object with empty scopes, or {@link SConstant#EOF} at the
     * end of the input. Lists are chains of {@link SPair}s whose elements are syntax objects; vector
     * elements are syntax objects; bytevectors are plain.
     */
    @TruffleBoundary
    public Object readSyntax() {
        syntax = true;
        try {
            return readDatum();
        } finally {
            syntax = false;
        }
    }

    private Object wrap(Object datum, int start) {
        if (!syntax) {
            return datum;
        }
        SourceSection section = source == null ? null : source.createSection(start, position - start);
        return new Syntax(datum, ScopeSet.EMPTY, section);
    }

    private Object readDatum() {
        Deque<Frame> stack = new ArrayDeque<>();
        Map<Integer, Object> labels = null;
        while (true) {
            Kind kind = nextToken();
            Object value;
            switch (kind) {
                case EOF:
                    if (stack.isEmpty()) {
                        return SConstant.EOF;
                    }
                    throw error("unexpected end of input");
                case OPEN:
                    push(stack, new ListFrame());
                    continue;
                case VECTOR:
                    push(stack, new VectorFrame());
                    continue;
                case BYTEVECTOR:
                    push(stack, new BytevectorFrame());
                    continue;
                case ABBREVIATION:
                    push(stack, new AbbreviationFrame((SSymbol) tokenValue));
                    continue;
                case DATUM_COMMENT:
                    stack.push(new DatumCommentFrame());
                    continue;
                case LABEL_DEF: {
                    if (labels == null) {
                        labels = new HashMap<>();
                    }
                    if (labels.containsKey(tokenLabel)) {
                        throw error("datum label #" + tokenLabel + "= defined twice");
                    }
                    Placeholder placeholder = new Placeholder();
                    labels.put(tokenLabel, placeholder);
                    stack.push(new LabelFrame(tokenLabel, placeholder));
                    continue;
                }
                case LABEL_REF: {
                    value = labels == null ? null : labels.get(tokenLabel);
                    if (value == null) {
                        throw error("undefined datum label #" + tokenLabel + "#");
                    }
                    if (value instanceof Placeholder p) {
                        p.referenced = true;
                    }
                    break;
                }
                case DOT: {
                    if (!(stack.peek() instanceof ListFrame list) || list.state != 0 || list.items.isEmpty()) {
                        throw error("unexpected '.'");
                    }
                    list.state = 1;
                    continue;
                }
                case CLOSE: {
                    int start = stack.isEmpty() ? tokenStart : stack.peek().start;
                    value = wrap(close(stack), start);
                    break;
                }
                case ATOM:
                    value = wrap(tokenValue, tokenStart);
                    break;
                default:
                    throw new IllegalStateException(kind.toString());
            }
            // Deliver the completed datum to the frames waiting for it.
            while (true) {
                Frame top = stack.peek();
                if (top == null) {
                    return value;
                } else if (top instanceof DatumCommentFrame) {
                    stack.pop();
                    break;
                } else if (top instanceof AbbreviationFrame a) {
                    stack.pop();
                    value = wrap(new SPair(wrap(a.symbol, a.start), new SPair(value, SConstant.EMPTY_LIST)), a.start);
                } else if (top instanceof LabelFrame l) {
                    stack.pop();
                    if (value == l.placeholder) {
                        throw error("datum label #" + l.label + "= refers only to itself");
                    }
                    labels.put(l.label, value);
                    if (l.placeholder.referenced) {
                        patch(value, l.placeholder, value);
                    }
                } else if (top instanceof ListFrame list) {
                    if (list.state == 0) {
                        list.items.add(value);
                    } else if (list.state == 1) {
                        list.tail = value;
                        list.state = 2;
                    } else {
                        throw error("more than one datum after '.'");
                    }
                    break;
                } else if (top instanceof VectorFrame vector) {
                    vector.items.add(value);
                    break;
                } else {
                    Object element = value instanceof Syntax s ? s.datum : value;
                    if (!(element instanceof Long b) || b < 0 || b > 255) {
                        throw error("a bytevector element must be an exact integer 0-255");
                    }
                    ((BytevectorFrame) top).bytes.write(b.intValue());
                    break;
                }
            }
        }
    }

    private void push(Deque<Frame> stack, Frame frame) {
        frame.start = tokenStart;
        stack.push(frame);
    }

    private Object close(Deque<Frame> stack) {
        Frame top = stack.peek();
        if (top instanceof ListFrame list) {
            if (list.state == 1) {
                throw error("datum expected after '.'");
            }
            stack.pop();
            Object result = list.tail;
            for (int i = list.items.size() - 1; i >= 0; i--) {
                result = new SPair(list.items.get(i), result);
            }
            return result;
        } else if (top instanceof VectorFrame vector) {
            stack.pop();
            return SVector.of(vector.items.toArray(), literals);
        } else if (top instanceof BytevectorFrame bv) {
            stack.pop();
            return new SBytevector(bv.bytes.toByteArray(), literals);
        } else if (top == null) {
            throw error("unexpected ')'");
        }
        throw error("datum expected before ')'");
    }

    /** Replaces every reference to {@code placeholder} inside {@code root} with {@code value}. */
    private static void patch(Object root, Placeholder placeholder, Object value) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (seen.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (node instanceof Syntax s) {
                stack.push(s.datum);
            } else if (node instanceof SPair p) {
                if (p.car == placeholder) {
                    p.car = value;
                } else {
                    stack.push(p.car);
                }
                if (p.cdr == placeholder) {
                    p.cdr = value;
                } else {
                    stack.push(p.cdr);
                }
            } else if (node instanceof SVector v) {
                for (int i = 0; i < v.length; i++) {
                    Object element = v.get(i);
                    if (element == placeholder) {
                        v.set(i, value);
                    } else {
                        stack.push(element);
                    }
                }
            }
        }
    }
}
