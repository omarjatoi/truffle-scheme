package scheme.runtime.printer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.DynEnv;

import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SRecord;
import scheme.runtime.value.SRecordType;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * {@code write}, {@code display}, {@code write-shared} and {@code write-simple} (design.md D9).
 *
 * <ul>
 * <li>{@code write} and {@code display} label only what is needed to terminate on cycles;
 * {@code write-shared} labels all shared structure; {@code write-simple} uses no labels.</li>
 * <li>{@code display} follows R7RS: strings, characters and symbols are not escaped, also when nested.</li>
 * <li>Where R7RS leaves the format open, output matches chibi-scheme 0.12 (flonums, character names,
 * string escapes, when symbols need bars, {@code (quote x)} without abbreviation), so that
 * {@code mx r7rs-diff} can compare stdout.</li>
 * </ul>
 *
 * Both passes use explicit stacks: no Java recursion proportional to nesting depth (AGENTS.md rule 4).
 */
public final class Printer {

    public enum Mode {
        WRITE,
        DISPLAY,
        WRITE_SHARED,
        WRITE_SIMPLE
    }

    private static final Integer UNASSIGNED = -1;

    private final StringBuilder out;
    private final boolean display;
    /** Nodes that get a datum label: UNASSIGNED until printed, then their label number. */
    private final IdentityHashMap<Object, Integer> labels;
    private int nextLabel;

    private Printer(StringBuilder out, boolean display, IdentityHashMap<Object, Integer> labels) {
        this.out = out;
        this.display = display;
        this.labels = labels;
    }

    @TruffleBoundary
    public static String print(Object value, Mode mode) {
        StringBuilder sb = new StringBuilder();
        print(value, mode, sb);
        return sb.toString();
    }

    @TruffleBoundary
    public static void print(Object value, Mode mode, StringBuilder out) {
        IdentityHashMap<Object, Integer> labels = switch (mode) {
            case WRITE_SIMPLE -> new IdentityHashMap<>();
            case WRITE_SHARED -> findShared(value);
            case WRITE, DISPLAY -> findCycles(value);
        };
        new Printer(out, mode == Mode.DISPLAY, labels).run(value);
    }

    // ------------------------------------------------------------------------------------------
    // Label pass

    private static int childCount(Object node) {
        if (node instanceof SPair) {
            return 2;
        } else if (node instanceof SVector v) {
            return v.elements.length;
        } else if (node instanceof SErrorObject) {
            return 2;
        } else if (node instanceof MultipleValues mv) {
            return mv.values.length;
        }
        return 0;
    }

    private static Object child(Object node, int index) {
        if (node instanceof SPair p) {
            return index == 0 ? p.car : p.cdr;
        } else if (node instanceof SVector v) {
            return v.elements[index];
        } else if (node instanceof SErrorObject e) {
            return index == 0 ? e.message : e.irritants;
        }
        return ((MultipleValues) node).values[index];
    }

    private static boolean isCompound(Object node) {
        return node instanceof SPair || node instanceof SVector || node instanceof SErrorObject || node instanceof MultipleValues;
    }

    /** write-shared: every compound node reached more than once. */
    private static IdentityHashMap<Object, Integer> findShared(Object root) {
        IdentityHashMap<Object, Integer> labels = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (!isCompound(node)) {
                continue;
            }
            if (seen.put(node, Boolean.TRUE) != null) {
                labels.put(node, UNASSIGNED);
                continue;
            }
            for (int i = childCount(node) - 1; i >= 0; i--) {
                stack.push(child(node, i));
            }
        }
        return labels;
    }

    private static final class Frame {
        final Object node;
        int next;

        Frame(Object node) {
            this.node = node;
        }
    }

    /** write and display: the targets of back edges, i.e. nodes reached again while on the DFS path. */
    private static IdentityHashMap<Object, Integer> findCycles(Object root) {
        IdentityHashMap<Object, Integer> labels = new IdentityHashMap<>();
        if (!isCompound(root)) {
            return labels;
        }
        IdentityHashMap<Object, Boolean> onPath = new IdentityHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        onPath.put(root, Boolean.TRUE);
        stack.push(new Frame(root));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.next == childCount(frame.node)) {
                onPath.put(frame.node, Boolean.FALSE);
                stack.pop();
                continue;
            }
            Object c = child(frame.node, frame.next++);
            if (!isCompound(c)) {
                continue;
            }
            Boolean state = onPath.get(c);
            if (state == null) {
                onPath.put(c, Boolean.TRUE);
                stack.push(new Frame(c));
            } else if (state) {
                labels.put(c, UNASSIGNED);
            }
        }
        return labels;
    }

    // ------------------------------------------------------------------------------------------
    // Print pass. The task stack holds literal text (String), list and vector continuations, and
    // values. A Scheme value is never a Java String.

    private record ListRest(Object rest) {
    }

    private record ElementsRest(Object[] elements, int index, String close) {
    }

    private void run(Object root) {
        Deque<Object> tasks = new ArrayDeque<>();
        tasks.push(root);
        while (!tasks.isEmpty()) {
            Object task = tasks.pop();
            if (task instanceof String text) {
                out.append(text);
            } else if (task instanceof ListRest lr) {
                listRest(lr.rest(), tasks);
            } else if (task instanceof ElementsRest er) {
                elementsRest(er, tasks);
            } else {
                value(task, tasks);
            }
        }
    }

    private void value(Object v, Deque<Object> tasks) {
        Integer label = labels.get(v);
        if (label != null) {
            if (label >= 0) {
                out.append('#').append(label).append('#');
                return;
            }
            int n = nextLabel++;
            labels.put(v, n);
            out.append('#').append(n).append('=');
        }
        if (v instanceof SPair p) {
            out.append('(');
            tasks.push(new ListRest(p.cdr));
            tasks.push(p.car);
        } else if (v instanceof SVector vector) {
            out.append("#(");
            tasks.push(new ElementsRest(vector.elements, 0, ")"));
        } else if (v instanceof SErrorObject e) {
            out.append("#<error ");
            tasks.push(">");
            tasks.push(e.irritants);
            tasks.push(" ");
            tasks.push(e.message);
        } else if (v instanceof MultipleValues mv) {
            out.append("#<values");
            tasks.push(new ElementsRest(mv.values, 0, ">"));
            if (mv.values.length > 0) {
                out.append(' ');
            }
        } else {
            atom(v);
        }
    }

    private void listRest(Object rest, Deque<Object> tasks) {
        if (rest == SConstant.EMPTY_LIST) {
            out.append(')');
        } else if (rest instanceof SPair p && !labels.containsKey(p)) {
            out.append(' ');
            tasks.push(new ListRest(p.cdr));
            tasks.push(p.car);
        } else {
            out.append(" . ");
            tasks.push(")");
            tasks.push(rest);
        }
    }

    private static void elementsRest(ElementsRest er, Deque<Object> tasks) {
        if (er.index() == er.elements().length) {
            tasks.push(er.close());
            return;
        }
        tasks.push(new ElementsRest(er.elements(), er.index() + 1, er.close()));
        tasks.push(er.elements()[er.index()]);
        if (er.index() > 0) {
            tasks.push(" ");
        }
    }

    private void atom(Object v) {
        if (v instanceof Boolean b) {
            out.append(b ? "#t" : "#f");
        } else if (v instanceof Long || v instanceof Double || v instanceof SBigInteger || v instanceof SRational) {
            out.append(formatReal(v));
        } else if (v instanceof SComplex c) {
            out.append(formatComplex(c));
        } else if (v instanceof SChar c) {
            if (display) {
                out.appendCodePoint(c.codePoint);
            } else {
                writeChar(c.codePoint);
            }
        } else if (v instanceof SString s) {
            if (display) {
                out.append(s);
            } else {
                writeString(s);
            }
        } else if (v instanceof SSymbol s) {
            if (display) {
                out.append(s);
            } else {
                writeSymbol(s.toString());
            }
        } else if (v instanceof SBytevector bv) {
            out.append("#u8(");
            for (int i = 0; i < bv.bytes.length; i++) {
                if (i > 0) {
                    out.append(' ');
                }
                out.append(bv.bytes[i] & 0xff);
            }
            out.append(')');
        } else if (v instanceof SConstant) {
            out.append(v);
        } else if (v instanceof SClosure c) {
            String name = c.getName();
            out.append(name == null ? "#<procedure>" : "#<procedure " + name + ">");
        } else if (v instanceof SRecord r) {
            out.append("#<record ").append(r.type.name).append('>');
        } else if (v instanceof DynEnv) {
            out.append("#<dynamic-environment>");
        } else if (v instanceof SRecordType t) {
            out.append("#<record-type ").append(t.name).append('>');
        } else {
            // Internal error, never a Scheme error (design.md D8).
            throw new IllegalArgumentException("not a Scheme value: " + v.getClass().getName());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Characters, strings, symbols

    private static String charName(int cp) {
        return switch (cp) {
            case 0 -> "null";
            case 7 -> "alarm";
            case 8 -> "backspace";
            case 9 -> "tab";
            case 10 -> "newline";
            case 13 -> "return";
            case 27 -> "escape";
            case 32 -> "space";
            case 127 -> "delete";
            default -> null;
        };
    }

    private void writeChar(int cp) {
        out.append("#\\");
        String name = charName(cp);
        if (name != null) {
            out.append(name);
        } else if (cp < 0x20) {
            out.append('x').append(Integer.toHexString(cp));
        } else {
            out.appendCodePoint(cp);
        }
    }

    private void writeString(SString s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            switch (cp) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case 7 -> out.append("\\a");
                case '\b' -> out.append("\\b");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (cp < 0x20) {
                        out.append("\\x").append(Character.forDigit(cp >> 4, 16)).append(Character.forDigit(cp & 0xf, 16)).append(';');
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean isSeparator(char c) {
        return (c >= 0x09 && c <= 0x0d) || c == ' ' || c == '"' || c == '\'' || c == '(' || c == ')' || c == ',' || c == ';' || c == '[' || c == ']' ||
                        c == '{' || c == '}';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** chibi-scheme's rule for when a written symbol needs |bars| (any non-ASCII character does). */
    private static boolean needsBars(String name) {
        int len = name.length();
        if (len == 0 || name.equals(".") || isAsciiDigit(name.charAt(0))) {
            return true;
        }
        char c0 = name.charAt(0);
        if (len > 1 && (c0 == '+' || c0 == '-')) {
            char c1 = name.charAt(1);
            if (isAsciiDigit(c1) || c1 == '.' || c1 == 'i') {
                return true;
            }
            if (len > 3 && name.substring(1, 4).toLowerCase(Locale.ROOT).equals("nan")) {
                return true;
            }
        }
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c <= ' ' || c >= 0x80 || c == '\\' || c == '|' || c == '#' || isSeparator(c)) {
                return true;
            }
        }
        return false;
    }

    private void writeSymbol(String name) {
        if (!needsBars(name)) {
            out.append(name);
            return;
        }
        out.append('|');
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '|') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('|');
    }

    // ------------------------------------------------------------------------------------------
    // Numbers

    private static String formatReal(Object v) {
        if (v instanceof Long l) {
            return Long.toString(l);
        } else if (v instanceof Double d) {
            return formatDouble(d);
        } else if (v instanceof SBigInteger b) {
            return b.value.toString();
        }
        SRational r = (SRational) v;
        return r.numerator + "/" + r.denominator;
    }

    private static String formatComplex(SComplex c) {
        String imag;
        if (c.imag instanceof Long l && l == -1L) {
            imag = "-";
        } else if (c.imag instanceof Long l && l == 1L) {
            imag = "";
        } else {
            imag = formatReal(c.imag);
        }
        String sign = imag.startsWith("-") || imag.startsWith("+") ? "" : "+";
        return formatReal(c.real) + sign + imag + "i";
    }

    /**
     * chibi-scheme's flonum format: the first of C's {@code %.15g}, {@code %.16g}, {@code %.17g}
     * that reads back as the same double, with {@code .0} appended if it has no '.' or exponent.
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "+nan.0";
        } else if (Double.isInfinite(d)) {
            return d > 0 ? "+inf.0" : "-inf.0";
        }
        String s = formatG(d, 15);
        if (Double.parseDouble(s) != d) {
            s = formatG(d, 16);
            if (Double.parseDouble(s) != d) {
                s = formatG(d, 17);
            }
        }
        if (s.indexOf('.') < 0 && s.indexOf('e') < 0) {
            s = s + ".0";
        }
        return s;
    }

    /** C's {@code printf("%.<precision>g", d)} for finite d. */
    static String formatG(double d, int precision) {
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        String sign = d < 0 ? "-" : "";
        BigDecimal rounded = new BigDecimal(Math.abs(d)).round(new MathContext(precision, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent < -4 || exponent >= precision) {
            String digits = stripTrailingZeros(rounded.unscaledValue().toString());
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int abs = Math.abs(exponent);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + (abs < 10 ? "0" : "") + abs;
        }
        String plain = rounded.setScale(Math.max(precision - 1 - exponent, 0), RoundingMode.HALF_EVEN).toPlainString();
        if (plain.indexOf('.') >= 0) {
            plain = stripTrailingZeros(plain);
            if (plain.endsWith(".")) {
                plain = plain.substring(0, plain.length() - 1);
            }
        }
        return sign + plain;
    }

    private static String stripTrailingZeros(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        return s.substring(0, end);
    }
}
