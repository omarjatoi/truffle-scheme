package scheme.builtins;

import java.util.List;
import java.util.Locale;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;

/**
 * The characters, strings and symbols area (R7RS §6.5–6.7): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 *
 * <p>
 * Unicode comes from the JDK's tables (design.md D9): character procedures use simple case mapping, string procedures
 * full case mapping, and everything uses {@link Locale#ROOT}. {@code string-map} and {@code string-for-each} call
 * procedures, so they are in {@code chars-strings.scm} (D5).
 */
final class CharStringPrimitives {

    private CharStringPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        // §6.5 Symbols
                        new PrimitiveSpec("symbol?", 1, 1, () -> new TypePredicate(x -> x instanceof SSymbol)),
                        new PrimitiveSpec("symbol=?", 2, -1, SymbolEquals::new),
                        new PrimitiveSpec("symbol->string", 1, 1, SymbolToString::new),
                        new PrimitiveSpec("string->symbol", 1, 1, StringToSymbol::new),
                        // §6.6 Characters
                        new PrimitiveSpec("char?", 1, 1, () -> new TypePredicate(x -> x instanceof SChar)),
                        new PrimitiveSpec("char=?", 2, -1, () -> new CharCompare("char=?", EQ, false)),
                        new PrimitiveSpec("char<?", 2, -1, () -> new CharCompare("char<?", LT, false)),
                        new PrimitiveSpec("char>?", 2, -1, () -> new CharCompare("char>?", GT, false)),
                        new PrimitiveSpec("char<=?", 2, -1, () -> new CharCompare("char<=?", LE, false)),
                        new PrimitiveSpec("char>=?", 2, -1, () -> new CharCompare("char>=?", GE, false)),
                        new PrimitiveSpec("char-ci=?", 2, -1, () -> new CharCompare("char-ci=?", EQ, true)),
                        new PrimitiveSpec("char-ci<?", 2, -1, () -> new CharCompare("char-ci<?", LT, true)),
                        new PrimitiveSpec("char-ci>?", 2, -1, () -> new CharCompare("char-ci>?", GT, true)),
                        new PrimitiveSpec("char-ci<=?", 2, -1, () -> new CharCompare("char-ci<=?", LE, true)),
                        new PrimitiveSpec("char-ci>=?", 2, -1, () -> new CharCompare("char-ci>=?", GE, true)),
                        new PrimitiveSpec("char-alphabetic?", 1, 1, () -> new CharUnary("char-alphabetic?", CharUnary.ALPHABETIC)),
                        new PrimitiveSpec("char-numeric?", 1, 1, () -> new CharUnary("char-numeric?", CharUnary.NUMERIC)),
                        new PrimitiveSpec("char-whitespace?", 1, 1, () -> new CharUnary("char-whitespace?", CharUnary.WHITESPACE)),
                        new PrimitiveSpec("char-upper-case?", 1, 1, () -> new CharUnary("char-upper-case?", CharUnary.UPPER_CASE)),
                        new PrimitiveSpec("char-lower-case?", 1, 1, () -> new CharUnary("char-lower-case?", CharUnary.LOWER_CASE)),
                        new PrimitiveSpec("digit-value", 1, 1, () -> new CharUnary("digit-value", CharUnary.DIGIT_VALUE)),
                        new PrimitiveSpec("char->integer", 1, 1, () -> new CharUnary("char->integer", CharUnary.TO_INTEGER)),
                        new PrimitiveSpec("char-upcase", 1, 1, () -> new CharUnary("char-upcase", CharUnary.UPCASE)),
                        new PrimitiveSpec("char-downcase", 1, 1, () -> new CharUnary("char-downcase", CharUnary.DOWNCASE)),
                        new PrimitiveSpec("char-foldcase", 1, 1, () -> new CharUnary("char-foldcase", CharUnary.FOLDCASE)),
                        new PrimitiveSpec("integer->char", 1, 1, IntegerToChar::new),
                        // §6.7 Strings
                        new PrimitiveSpec("string?", 1, 1, () -> new TypePredicate(x -> x instanceof SString)),
                        new PrimitiveSpec("make-string", 1, 2, MakeString::new),
                        new PrimitiveSpec("string", 0, -1, StringOf::new),
                        new PrimitiveSpec("string-length", 1, 1, StringLength::new),
                        new PrimitiveSpec("string-ref", 2, 2, StringRef::new),
                        new PrimitiveSpec("string-set!", 3, 3, StringSet::new),
                        new PrimitiveSpec("string=?", 2, -1, () -> new StringCompare("string=?", EQ, false)),
                        new PrimitiveSpec("string<?", 2, -1, () -> new StringCompare("string<?", LT, false)),
                        new PrimitiveSpec("string>?", 2, -1, () -> new StringCompare("string>?", GT, false)),
                        new PrimitiveSpec("string<=?", 2, -1, () -> new StringCompare("string<=?", LE, false)),
                        new PrimitiveSpec("string>=?", 2, -1, () -> new StringCompare("string>=?", GE, false)),
                        new PrimitiveSpec("string-ci=?", 2, -1, () -> new StringCompare("string-ci=?", EQ, true)),
                        new PrimitiveSpec("string-ci<?", 2, -1, () -> new StringCompare("string-ci<?", LT, true)),
                        new PrimitiveSpec("string-ci>?", 2, -1, () -> new StringCompare("string-ci>?", GT, true)),
                        new PrimitiveSpec("string-ci<=?", 2, -1, () -> new StringCompare("string-ci<=?", LE, true)),
                        new PrimitiveSpec("string-ci>=?", 2, -1, () -> new StringCompare("string-ci>=?", GE, true)),
                        new PrimitiveSpec("string-upcase", 1, 1, () -> new StringCase("string-upcase", StringCase.UPCASE)),
                        new PrimitiveSpec("string-downcase", 1, 1, () -> new StringCase("string-downcase", StringCase.DOWNCASE)),
                        new PrimitiveSpec("string-foldcase", 1, 1, () -> new StringCase("string-foldcase", StringCase.FOLDCASE)),
                        new PrimitiveSpec("substring", 3, 3, () -> new StringCopy("substring")),
                        new PrimitiveSpec("string-copy", 1, 3, () -> new StringCopy("string-copy")),
                        new PrimitiveSpec("string-append", 0, -1, StringAppend::new),
                        new PrimitiveSpec("string->list", 1, 3, StringToList::new),
                        new PrimitiveSpec("list->string", 1, 1, ListToString::new),
                        new PrimitiveSpec("string-copy!", 3, 5, StringCopyInto::new),
                        new PrimitiveSpec("string-fill!", 2, 4, StringFill::new));
    }

    // Comparison operators, shared by the character and string comparisons.
    static final int EQ = 0;
    static final int LT = 1;
    static final int GT = 2;
    static final int LE = 3;
    static final int GE = 4;

    /** Whether {@code cmp} (negative, zero or positive, as from {@code compare}) satisfies {@code op}. */
    static boolean holds(int op, int cmp) {
        switch (op) {
            case EQ:
                return cmp == 0;
            case LT:
                return cmp < 0;
            case GT:
                return cmp > 0;
            case LE:
                return cmp <= 0;
            default:
                return cmp >= 0;
        }
    }

    // ---- Arguments --------------------------------------------------------------------------

    static SChar charArg(String who, Object value) {
        if (value instanceof SChar c) {
            return c;
        }
        throw ControlPrimitives.notA(who, "character", value);
    }

    static SString stringArg(String who, Object value) {
        if (value instanceof SString s) {
            return s;
        }
        throw ControlPrimitives.notA(who, "string", value);
    }

    /** An index argument {@code value}, which must be an exact integer in {@code [min, max]}. */
    static int indexArg(String who, Object value, int min, int max) {
        if (value instanceof Long k && k >= min && k <= max) {
            return (int) (long) k;
        }
        throw badIndex(who, value, min, max);
    }

    @TruffleBoundary
    private static SchemeError badIndex(String who, Object value, int min, int max) {
        return ControlPrimitives.notA(who, "valid index (" + min + " to " + max + ")", value);
    }

    // ---- Unicode (design.md D9) ---------------------------------------------------------------

    /** Cherokee: the one script whose case folding maps to upper case (Unicode CaseFolding.txt). */
    private static boolean isCherokeeLower(int cp) {
        return (cp >= 0x13f8 && cp <= 0x13fd) || (cp >= 0xab70 && cp <= 0xabbf);
    }

    /** Simple case folding (Unicode CaseFolding.txt, statuses C and S), for {@code char-foldcase}. */
    static int foldChar(int cp) {
        if (cp < 0x80) {
            return cp >= 'A' && cp <= 'Z' ? cp + ('a' - 'A') : cp;
        }
        return foldCharSlow(cp);
    }

    @TruffleBoundary
    private static int foldCharSlow(int cp) {
        if (cp == 0x130 || cp == 0x131) {
            // Dotted capital I and dotless small i have no simple folding outside Turkic locales.
            return cp;
        }
        if (isCherokeeLower(cp)) {
            return Character.toUpperCase(cp);
        }
        return Character.toLowerCase(Character.toUpperCase(cp));
    }

    /** Appends the full case folding (statuses C and F) of {@code cp}, for {@code string-foldcase} and {@code string-ci}. */
    @TruffleBoundary
    private static void appendFolded(StringBuilder out, int cp) {
        if (cp < 0x80) {
            out.append((char) foldChar(cp));
        } else if (cp == 0x130) {
            out.append("i̇");
        } else if (cp == 0x131 || isCherokeeLower(cp)) {
            out.appendCodePoint(foldCharSlow(cp));
        } else {
            // One character at a time, so no context (final sigma) applies. Lower case first, so that
            // capital sharp s folds to "ss" as small sharp s does.
            out.append(Character.toString(cp).toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
        }
    }

    @TruffleBoundary
    static String foldString(SString s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            appendFolded(out, s.codePointAt(i));
        }
        return out.toString();
    }

    // ---- Symbols (§6.5) ---------------------------------------------------------------------

    /** {@code (symbol=? symbol1 symbol2 symbol3 ...)}. */
    static final class SymbolEquals extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            boolean result = true;
            for (Object a : args) {
                if (!(a instanceof SSymbol)) {
                    throw ControlPrimitives.notA("symbol=?", "symbol", a);
                }
                result &= a == args[0];
            }
            return result;
        }
    }

    /** {@code (symbol->string symbol)}: an immutable string, since R7RS makes mutating it an error. */
    static final class SymbolToString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (args[0] instanceof SSymbol s) {
                return name(s);
            }
            throw ControlPrimitives.notA("symbol->string", "symbol", args[0]);
        }

        @TruffleBoundary
        private static SString name(SSymbol s) {
            return SString.literal(s.toString());
        }
    }

    /** {@code (string->symbol string)}: interned in this context's symbol table (design.md D1). */
    static final class StringToSymbol extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return intern(SchemeContext.get(this), stringArg("string->symbol", args[0]));
        }

        @TruffleBoundary
        private static SSymbol intern(SchemeContext context, SString name) {
            return context.symbols.intern(name.toString());
        }
    }

    // ---- Characters (§6.6) ------------------------------------------------------------------

    /** The character comparisons, n-ary; the {@code -ci} ones compare simple case foldings. */
    static final class CharCompare extends PrimitiveNode {
        private final String name;
        private final int op;
        private final boolean ci;

        CharCompare(String name, int op, boolean ci) {
            this.name = name;
            this.op = op;
            this.ci = ci;
        }

        @Override
        public Object execute(Object[] args) {
            boolean result = true;
            int previous = key(charArg(name, args[0]));
            for (int i = 1; i < args.length; i++) {
                int next = key(charArg(name, args[i]));
                result &= holds(op, Integer.compare(previous, next));
                previous = next;
            }
            return result;
        }

        private int key(SChar c) {
            return ci ? foldChar(c.codePoint) : c.codePoint;
        }
    }

    /** One-character procedures: the classifications, {@code digit-value}, {@code char->integer} and the case maps. */
    static final class CharUnary extends PrimitiveNode {
        static final int ALPHABETIC = 0;
        static final int NUMERIC = 1;
        static final int WHITESPACE = 2;
        static final int UPPER_CASE = 3;
        static final int LOWER_CASE = 4;
        static final int DIGIT_VALUE = 5;
        static final int TO_INTEGER = 6;
        static final int UPCASE = 7;
        static final int DOWNCASE = 8;
        static final int FOLDCASE = 9;

        private final String name;
        private final int op;

        CharUnary(String name, int op) {
            this.name = name;
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            int cp = charArg(name, args[0]).codePoint;
            switch (op) {
                case TO_INTEGER:
                    return (long) cp;
                case FOLDCASE:
                    return SChar.of(foldChar(cp));
                default:
                    return apply(op, cp);
            }
        }

        @TruffleBoundary
        private static Object apply(int op, int cp) {
            switch (op) {
                case ALPHABETIC:
                    return Character.isAlphabetic(cp);
                case NUMERIC:
                    return Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER;
                case WHITESPACE:
                    // The White_Space property: Java's isWhitespace leaves out no-break spaces and adds
                    // the information separators.
                    return Character.isSpaceChar(cp) || (cp >= 0x9 && cp <= 0xd) || cp == 0x85;
                case UPPER_CASE:
                    return Character.isUpperCase(cp);
                case LOWER_CASE:
                    return Character.isLowerCase(cp);
                case DIGIT_VALUE:
                    if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                        return (long) Character.digit(cp, 10);
                    }
                    return false;
                case UPCASE:
                    return SChar.of(Character.toUpperCase(cp));
                default:
                    return SChar.of(Character.toLowerCase(cp));
            }
        }
    }

    /** {@code (integer->char n)}: {@code n} must be a Unicode scalar value. */
    static final class IntegerToChar extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (args[0] instanceof Long n && n >= 0 && n <= Character.MAX_CODE_POINT && !(n >= Character.MIN_SURROGATE && n <= Character.MAX_SURROGATE)) {
                return SChar.of((int) (long) n);
            }
            throw ControlPrimitives.notA("integer->char", "Unicode scalar value", args[0]);
        }
    }

    // ---- Strings (§6.7) ---------------------------------------------------------------------

    /** The longest string we allocate: Java's array limit. */
    private static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

    /** {@code (make-string k [char])}: a new mutable string, filled with spaces by default. */
    static final class MakeString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            int k = indexArg("make-string", args[0], 0, MAX_LENGTH);
            int fill = args.length > 1 ? charArg("make-string", args[1]).codePoint : ' ';
            return SString.make(k, fill);
        }
    }

    /** {@code (string char ...)}. */
    static final class StringOf extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return fromChars("string", args);
        }
    }

    /** A new mutable string of the characters {@code chars}. */
    @TruffleBoundary
    static SString fromChars(String who, Object[] chars) {
        SString s = SString.make(chars.length, ' ');
        for (int i = 0; i < chars.length; i++) {
            s.set(i, charArg(who, chars[i]).codePoint);
        }
        return s;
    }

    static final class StringLength extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return (long) stringArg("string-length", args[0]).length();
        }
    }

    static final class StringRef extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SString s = stringArg("string-ref", args[0]);
            return SChar.of(s.codePointAt(indexArg("string-ref", args[1], 0, s.length() - 1)));
        }
    }

    static final class StringSet extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SString s = stringArg("string-set!", args[0]);
            int k = indexArg("string-set!", args[1], 0, s.length() - 1);
            s.set(k, charArg("string-set!", args[2]).codePoint);
            return SConstant.UNSPECIFIED;
        }
    }

    /** The string comparisons, n-ary and lexicographic by code point; the {@code -ci} ones compare full case foldings. */
    static final class StringCompare extends PrimitiveNode {
        private final String name;
        private final int op;
        private final boolean ci;

        StringCompare(String name, int op, boolean ci) {
            this.name = name;
            this.op = op;
            this.ci = ci;
        }

        @Override
        public Object execute(Object[] args) {
            for (Object a : args) {
                stringArg(name, a);
            }
            boolean result = true;
            for (int i = 1; i < args.length; i++) {
                SString a = (SString) args[i - 1];
                SString b = (SString) args[i];
                result &= holds(op, ci ? compareFolded(a, b) : compare(a, b));
            }
            return result;
        }

        static int compare(SString a, SString b) {
            int n = Math.min(a.length(), b.length());
            for (int i = 0; i < n; i++) {
                int x = a.codePointAt(i);
                int y = b.codePointAt(i);
                if (x != y) {
                    return x < y ? -1 : 1;
                }
            }
            return Integer.compare(a.length(), b.length());
        }

        @TruffleBoundary
        static int compareFolded(SString a, SString b) {
            String x = foldString(a);
            String y = foldString(b);
            // Code point order: compare by code points, not UTF-16 units.
            int i = 0;
            int j = 0;
            while (i < x.length() && j < y.length()) {
                int cx = x.codePointAt(i);
                int cy = y.codePointAt(j);
                if (cx != cy) {
                    return cx < cy ? -1 : 1;
                }
                i += Character.charCount(cx);
                j += Character.charCount(cy);
            }
            return Boolean.compare(i < x.length(), j < y.length());
        }
    }

    /** {@code string-upcase}, {@code string-downcase} and {@code string-foldcase}: full case mapping (design.md D9). */
    static final class StringCase extends PrimitiveNode {
        static final int UPCASE = 0;
        static final int DOWNCASE = 1;
        static final int FOLDCASE = 2;

        private final String name;
        private final int op;

        StringCase(String name, int op) {
            this.name = name;
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            return map(op, stringArg(name, args[0]));
        }

        @TruffleBoundary
        private static SString map(int op, SString s) {
            switch (op) {
                case UPCASE:
                    return SString.of(s.toString().toUpperCase(Locale.ROOT));
                case DOWNCASE:
                    return SString.of(s.toString().toLowerCase(Locale.ROOT));
                default:
                    return SString.of(foldString(s));
            }
        }
    }

    /** {@code (substring string start end)} and {@code (string-copy string [start [end]])}: a new mutable string. */
    static final class StringCopy extends PrimitiveNode {
        private final String name;

        StringCopy(String name) {
            this.name = name;
        }

        @Override
        public Object execute(Object[] args) {
            SString s = stringArg(name, args[0]);
            int end = args.length > 2 ? indexArg(name, args[2], 0, s.length()) : s.length();
            int start = args.length > 1 ? indexArg(name, args[1], 0, end) : 0;
            return copy(s, start, end);
        }

        @TruffleBoundary
        static SString copy(SString s, int start, int end) {
            SString result = SString.make(end - start, ' ');
            for (int i = start; i < end; i++) {
                result.set(i - start, s.codePointAt(i));
            }
            return result;
        }
    }

    /** {@code (string-append string ...)}: a new mutable string. */
    static final class StringAppend extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SString append(Object[] args) {
            long length = 0;
            for (Object a : args) {
                length += stringArg("string-append", a).length();
            }
            if (length > MAX_LENGTH) {
                throw new SchemeError("string-append: result too long");
            }
            SString result = SString.make((int) length, ' ');
            int at = 0;
            for (Object a : args) {
                SString s = (SString) a;
                for (int i = 0; i < s.length(); i++) {
                    result.set(at++, s.codePointAt(i));
                }
            }
            return result;
        }
    }

    /** {@code (string->list string [start [end]])}. */
    static final class StringToList extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SString s = stringArg("string->list", args[0]);
            int end = args.length > 2 ? indexArg("string->list", args[2], 0, s.length()) : s.length();
            int start = args.length > 1 ? indexArg("string->list", args[1], 0, end) : 0;
            return toList(s, start, end);
        }

        @TruffleBoundary
        private static Object toList(SString s, int start, int end) {
            Object list = SConstant.EMPTY_LIST;
            for (int i = end - 1; i >= start; i--) {
                list = new SPair(SChar.of(s.codePointAt(i)), list);
            }
            return list;
        }
    }

    /** {@code (list->string list)}. */
    static final class ListToString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return fromChars("list->string", Lists.toArray("list->string", args[0]));
        }
    }

    /** {@code (string-copy! to at from [start [end]])}: overlapping ranges of one string copy as if through a temporary. */
    static final class StringCopyInto extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SString to = stringArg("string-copy!", args[0]);
            int at = indexArg("string-copy!", args[1], 0, to.length());
            SString from = stringArg("string-copy!", args[2]);
            int end = args.length > 4 ? indexArg("string-copy!", args[4], 0, from.length()) : from.length();
            int start = args.length > 3 ? indexArg("string-copy!", args[3], 0, end) : 0;
            if (end - start > to.length() - at) {
                throw tooShort(to, at, end - start);
            }
            copy(to, at, from, start, end);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void copy(SString to, int at, SString from, int start, int end) {
            if (to == from && at > start) {
                for (int i = end - 1; i >= start; i--) {
                    to.set(at + i - start, from.codePointAt(i));
                }
            } else {
                for (int i = start; i < end; i++) {
                    to.set(at + i - start, from.codePointAt(i));
                }
            }
        }

        @TruffleBoundary
        private static SchemeError tooShort(SString to, int at, int count) {
            return new SchemeError("string-copy!: " + count + " characters don't fit at index " + at + " of a string of length " + to.length());
        }
    }

    /** {@code (string-fill! string char [start [end]])}. */
    static final class StringFill extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SString s = stringArg("string-fill!", args[0]);
            int fill = charArg("string-fill!", args[1]).codePoint;
            int end = args.length > 3 ? indexArg("string-fill!", args[3], 0, s.length()) : s.length();
            int start = args.length > 2 ? indexArg("string-fill!", args[2], 0, end) : 0;
            fill(s, fill, start, end);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void fill(SString s, int fill, int start, int end) {
            for (int i = start; i < end; i++) {
                s.set(i, fill);
            }
        }
    }
}
