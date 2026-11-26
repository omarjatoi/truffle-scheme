package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.Locale;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
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

    /** This area's primitives, as {@code Builtins} registers them. */
    static List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> specs = new ArrayList<>();
        for (Primitive primitive : Primitive.values()) {
            specs.add(primitive.spec);
        }
        return specs;
    }

    /**
     * This area's primitives: the one list to add to. A constant has the primitive's name and
     * arity, and {@link #node} makes its node when it is first called: no lambda per primitive for
     * the JVM to link as a context starts (design.md D17, "The primitives' registry").
     */
    enum Primitive implements Supplier<PrimitiveNode> {
        // §6.5 Symbols
        IS_SYMBOL("symbol?", 1, 1),
        SYMBOL_EQ("symbol=?", 2, -1),
        SYMBOL_TO_STRING("symbol->string", 1, 1),
        STRING_TO_SYMBOL("string->symbol", 1, 1),
        // §6.6 Characters
        IS_CHAR("char?", 1, 1),
        CHAR_EQ("char=?", 2, -1),
        CHAR_LT("char<?", 2, -1),
        CHAR_GT("char>?", 2, -1),
        CHAR_LE("char<=?", 2, -1),
        CHAR_GE("char>=?", 2, -1),
        CHAR_CI_EQ("char-ci=?", 2, -1),
        CHAR_CI_LT("char-ci<?", 2, -1),
        CHAR_CI_GT("char-ci>?", 2, -1),
        CHAR_CI_LE("char-ci<=?", 2, -1),
        CHAR_CI_GE("char-ci>=?", 2, -1),
        IS_CHAR_ALPHABETIC("char-alphabetic?", 1, 1),
        IS_CHAR_NUMERIC("char-numeric?", 1, 1),
        IS_CHAR_WHITESPACE("char-whitespace?", 1, 1),
        IS_CHAR_UPPER_CASE("char-upper-case?", 1, 1),
        IS_CHAR_LOWER_CASE("char-lower-case?", 1, 1),
        DIGIT_VALUE("digit-value", 1, 1),
        CHAR_TO_INTEGER("char->integer", 1, 1),
        CHAR_UPCASE("char-upcase", 1, 1),
        CHAR_DOWNCASE("char-downcase", 1, 1),
        CHAR_FOLDCASE("char-foldcase", 1, 1),
        INTEGER_TO_CHAR("integer->char", 1, 1),
        // §6.7 Strings
        IS_STRING("string?", 1, 1),
        MAKE_STRING("make-string", 1, 2, Result.NEW_OBJECT),
        STRING("string", 0, -1, Result.NEW_OBJECT),
        STRING_LENGTH("string-length", 1, 1),
        STRING_REF("string-ref", 2, 2),
        STRING_SET("string-set!", 3, 3),
        STRING_EQ("string=?", 2, -1),
        STRING_LT("string<?", 2, -1),
        STRING_GT("string>?", 2, -1),
        STRING_LE("string<=?", 2, -1),
        STRING_GE("string>=?", 2, -1),
        STRING_CI_EQ("string-ci=?", 2, -1),
        STRING_CI_LT("string-ci<?", 2, -1),
        STRING_CI_GT("string-ci>?", 2, -1),
        STRING_CI_LE("string-ci<=?", 2, -1),
        STRING_CI_GE("string-ci>=?", 2, -1),
        STRING_UPCASE("string-upcase", 1, 1, Result.NEW_OBJECT),
        STRING_DOWNCASE("string-downcase", 1, 1, Result.NEW_OBJECT),
        STRING_FOLDCASE("string-foldcase", 1, 1, Result.NEW_OBJECT),
        SUBSTRING("substring", 3, 3, Result.NEW_OBJECT),
        STRING_COPY("string-copy", 1, 3, Result.NEW_OBJECT),
        STRING_APPEND("string-append", 0, -1, Result.NEW_OBJECT),
        STRING_TO_LIST("string->list", 1, 3, Result.NEW_LIST),
        LIST_TO_STRING("list->string", 1, 1, Result.NEW_OBJECT),
        STRING_COPY_INTO("string-copy!", 3, 5),
        STRING_FILL("string-fill!", 2, 4);

        final PrimitiveSpec spec;

        Primitive(String name, int minArgs, int maxArgs) {
            this(name, minArgs, maxArgs, Result.VALUE);
        }

        Primitive(String name, int minArgs, int maxArgs, Result result) {
            this.spec = new PrimitiveSpec(name, minArgs, maxArgs, this, result);
        }

        @Override
        public PrimitiveNode get() {
            return (PrimitiveNode) node();
        }

        /**
         * The primitive's node: a {@code return} in each case, typed {@code Object}, so that the
         * JVM's verifier needn't load every node class to check what is returned (a switch
         * expression's cases would join as {@code PrimitiveNode}). Each is loaded when it is made.
         */
        private Object node() {
            switch (this) {
                case IS_SYMBOL:
                    return new TypePredicate(x -> x instanceof SSymbol);
                case SYMBOL_EQ:
                    return new SymbolEquals();
                case SYMBOL_TO_STRING:
                    return new SymbolToString();
                case STRING_TO_SYMBOL:
                    return new StringToSymbol();
                case IS_CHAR:
                    return new TypePredicate(x -> x instanceof SChar);
                case CHAR_EQ:
                    return new CharCompare("char=?", EQ, false);
                case CHAR_LT:
                    return new CharCompare("char<?", LT, false);
                case CHAR_GT:
                    return new CharCompare("char>?", GT, false);
                case CHAR_LE:
                    return new CharCompare("char<=?", LE, false);
                case CHAR_GE:
                    return new CharCompare("char>=?", GE, false);
                case CHAR_CI_EQ:
                    return new CharCompare("char-ci=?", EQ, true);
                case CHAR_CI_LT:
                    return new CharCompare("char-ci<?", LT, true);
                case CHAR_CI_GT:
                    return new CharCompare("char-ci>?", GT, true);
                case CHAR_CI_LE:
                    return new CharCompare("char-ci<=?", LE, true);
                case CHAR_CI_GE:
                    return new CharCompare("char-ci>=?", GE, true);
                case IS_CHAR_ALPHABETIC:
                    return new CharUnary("char-alphabetic?", CharUnary.ALPHABETIC);
                case IS_CHAR_NUMERIC:
                    return new CharUnary("char-numeric?", CharUnary.NUMERIC);
                case IS_CHAR_WHITESPACE:
                    return new CharUnary("char-whitespace?", CharUnary.WHITESPACE);
                case IS_CHAR_UPPER_CASE:
                    return new CharUnary("char-upper-case?", CharUnary.UPPER_CASE);
                case IS_CHAR_LOWER_CASE:
                    return new CharUnary("char-lower-case?", CharUnary.LOWER_CASE);
                case DIGIT_VALUE:
                    return new CharUnary("digit-value", CharUnary.DIGIT_VALUE);
                case CHAR_TO_INTEGER:
                    return new CharUnary("char->integer", CharUnary.TO_INTEGER);
                case CHAR_UPCASE:
                    return new CharUnary("char-upcase", CharUnary.UPCASE);
                case CHAR_DOWNCASE:
                    return new CharUnary("char-downcase", CharUnary.DOWNCASE);
                case CHAR_FOLDCASE:
                    return new CharUnary("char-foldcase", CharUnary.FOLDCASE);
                case INTEGER_TO_CHAR:
                    return new IntegerToChar();
                case IS_STRING:
                    return new TypePredicate(x -> x instanceof SString);
                case MAKE_STRING:
                    return new MakeString();
                case STRING:
                    return new StringOf();
                case STRING_LENGTH:
                    return new StringLength();
                case STRING_REF:
                    return new StringRef();
                case STRING_SET:
                    return new StringSet();
                case STRING_EQ:
                    return new StringCompare("string=?", EQ, false);
                case STRING_LT:
                    return new StringCompare("string<?", LT, false);
                case STRING_GT:
                    return new StringCompare("string>?", GT, false);
                case STRING_LE:
                    return new StringCompare("string<=?", LE, false);
                case STRING_GE:
                    return new StringCompare("string>=?", GE, false);
                case STRING_CI_EQ:
                    return new StringCompare("string-ci=?", EQ, true);
                case STRING_CI_LT:
                    return new StringCompare("string-ci<?", LT, true);
                case STRING_CI_GT:
                    return new StringCompare("string-ci>?", GT, true);
                case STRING_CI_LE:
                    return new StringCompare("string-ci<=?", LE, true);
                case STRING_CI_GE:
                    return new StringCompare("string-ci>=?", GE, true);
                case STRING_UPCASE:
                    return new StringCase("string-upcase", StringCase.UPCASE);
                case STRING_DOWNCASE:
                    return new StringCase("string-downcase", StringCase.DOWNCASE);
                case STRING_FOLDCASE:
                    return new StringCase("string-foldcase", StringCase.FOLDCASE);
                case SUBSTRING:
                    return new StringCopy("substring");
                case STRING_COPY:
                    return new StringCopy("string-copy");
                case STRING_APPEND:
                    return new StringAppend();
                case STRING_TO_LIST:
                    return new StringToList();
                case LIST_TO_STRING:
                    return new ListToString();
                case STRING_COPY_INTO:
                    return new StringCopyInto();
                case STRING_FILL:
                    return new StringFill();
            }
            throw new AssertionError(this);
        }
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

    // Each takes the procedure's name and the argument's position in its call (design.md D8, "Messages").

    static SChar charArg(String who, int position, Object value) {
        if (value instanceof SChar c) {
            return c;
        }
        throw ControlPrimitives.notA(who, position, "character", value);
    }

    static SString stringArg(String who, int position, Object value) {
        if (value instanceof SString s) {
            return s;
        }
        throw ControlPrimitives.notA(who, position, "string", value);
    }

    /** A string argument that may be changed: not a literal's (R7RS §3.4, design.md D1). */
    static SString mutableStringArg(String who, int position, Object value) {
        SString s = stringArg(who, position, value);
        if (s.isImmutable()) {
            throw immutable(who, position, s);
        }
        return s;
    }

    /** A literal string changed (R7RS §3.4, design.md D1); changing none of it is no error. */
    @TruffleBoundary
    static SchemeError immutable(String who, int position, SString s) {
        return ControlPrimitives.notA(who, position, "mutable string (it is a literal)", s);
    }

    /** An index argument {@code value}, which must be an exact integer in {@code [min, max]}. */
    static int indexArg(String who, int position, Object value, int min, int max) {
        if (value instanceof Long k && k >= min && k <= max) {
            return (int) (long) k;
        }
        throw badIndex(who, position, value, min, max);
    }

    @TruffleBoundary
    private static SchemeError badIndex(String who, int position, Object value, int min, int max) {
        return SchemeError.argument(who, position, SchemeError.index(min, max), value);
    }

    // ---- Unicode (design.md D9) ---------------------------------------------------------------

    /**
     * Cherokee: the one script whose case folding maps to upper case (Unicode CaseFolding.txt,
     * since Unicode 8.0; R7RS erratum 25). Its upper case letters fold to themselves, its lower
     * case ones to their upper case.
     */
    private static boolean isCherokee(int cp) {
        return (cp >= 0x13a0 && cp <= 0x13fd) || (cp >= 0xab70 && cp <= 0xabbf);
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
        switch (cp) {
            case 0x130, 0x131:
                // Dotted capital I and dotless small i have no simple folding outside Turkic locales.
                return cp;
            case 0x1fd3:
                // Status S foldings between characters that are canonically equivalent, so that
                // neither one's upper case maps back to the other.
                return 0x390;
            case 0x1fe3:
                return 0x3b0;
            case 0xfb05:
                return 0xfb06;
            default:
                if (isCherokee(cp)) {
                    return Character.toUpperCase(cp);
                }
                return Character.toLowerCase(Character.toUpperCase(cp));
        }
    }

    /** Appends the full case folding (statuses C and F) of {@code cp}, for {@code string-foldcase} and {@code string-ci}. */
    @TruffleBoundary
    private static void appendFolded(StringBuilder out, int cp) {
        if (cp < 0x80) {
            out.append((char) foldChar(cp));
        } else if (cp == 0x130) {
            out.append("i̇");
        } else if (cp == 0x131 || isCherokee(cp)) {
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
        public Object invoke(Object[] args) {
            boolean result = true;
            for (int i = 1; i < args.length; i++) {
                Object a = args[i];
                if (!(a instanceof SSymbol)) {
                    throw ControlPrimitives.notA("symbol=?", i, "symbol", a);
                }
                result &= a == args[1];
            }
            return result;
        }
    }

    /** {@code (symbol->string symbol)}: an immutable string, since R7RS makes mutating it an error. */
    static final class SymbolToString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (args[1] instanceof SSymbol s) {
                return name(s);
            }
            throw ControlPrimitives.notA("symbol->string", 1, "symbol", args[1]);
        }

        @TruffleBoundary
        private static SString name(SSymbol s) {
            return SString.literal(s.toString());
        }
    }

    /** {@code (string->symbol string)}: interned in this context's symbol table (design.md D1). */
    static final class StringToSymbol extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return intern(SchemeContext.get(this), stringArg("string->symbol", 1, args[1]));
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
        public Object invoke(Object[] args) {
            boolean result = true;
            int previous = key(charArg(name, 1, args[1]));
            for (int i = 2; i < args.length; i++) {
                int next = key(charArg(name, i, args[i]));
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
        public Object invoke(Object[] args) {
            int cp = charArg(name, 1, args[1]).codePoint;
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
        public Object invoke(Object[] args) {
            if (args[1] instanceof Long n && n >= 0 && n <= Character.MAX_CODE_POINT && !(n >= Character.MIN_SURROGATE && n <= Character.MAX_SURROGATE)) {
                return SChar.of((int) (long) n);
            }
            throw ControlPrimitives.notA("integer->char", 1, "Unicode scalar value", args[1]);
        }
    }

    // ---- Strings (§6.7) ---------------------------------------------------------------------

    /** The longest string we allocate: Java's array limit. */
    private static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

    /** {@code (make-string k [char])}: a new mutable string, filled with spaces by default. */
    static final class MakeString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            int k = indexArg("make-string", 1, args[1], 0, MAX_LENGTH);
            int fill = args.length > 2 ? charArg("make-string", 2, args[2]).codePoint : ' ';
            return SString.make(k, fill);
        }
    }

    /** {@code (string char ...)}. */
    static final class StringOf extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return fromChars(args);
        }
    }

    /** {@code (string char ...)}: a new mutable string of the arguments. */
    @TruffleBoundary
    static SString fromChars(Object[] args) {
        SString s = SString.make(args.length - 1, ' ');
        for (int i = 1; i < args.length; i++) {
            s.set(i - 1, charArg("string", i, args[i]).codePoint);
        }
        return s;
    }

    /** {@code (list->string list)}: a new mutable string of the list's elements. */
    @TruffleBoundary
    static SString fromList(Object[] chars) {
        SString s = SString.make(chars.length, ' ');
        for (int i = 0; i < chars.length; i++) {
            if (!(chars[i] instanceof SChar c)) {
                throw SchemeError.element("list->string", 1, "character", chars[i]);
            }
            s.set(i, c.codePoint);
        }
        return s;
    }

    static final class StringLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return (long) stringArg("string-length", 1, args[1]).length();
        }
    }

    static final class StringRef extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString s = stringArg("string-ref", 1, args[1]);
            return SChar.of(s.codePointAt(indexArg("string-ref", 2, args[2], 0, s.length() - 1)));
        }
    }

    static final class StringSet extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString s = mutableStringArg("string-set!", 1, args[1]);
            int k = indexArg("string-set!", 2, args[2], 0, s.length() - 1);
            s.set(k, charArg("string-set!", 3, args[3]).codePoint);
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
        public Object invoke(Object[] args) {
            for (int i = 1; i < args.length; i++) {
                stringArg(name, i, args[i]);
            }
            boolean result = true;
            for (int i = 2; i < args.length; i++) {
                SString a = (SString) args[i - 1];
                SString b = (SString) args[i];
                result &= holds(op, ci ? compareFolded(a, b) : SString.compare(a, b));
            }
            return result;
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
        public Object invoke(Object[] args) {
            return map(op, stringArg(name, 1, args[1]));
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
        public Object invoke(Object[] args) {
            SString s = stringArg(name, 1, args[1]);
            int start = args.length > 2 ? indexArg(name, 2, args[2], 0, s.length()) : 0;
            int end = args.length > 3 ? indexArg(name, 3, args[3], start, s.length()) : s.length();
            return copy(s, start, end);
        }

        @TruffleBoundary
        static SString copy(SString s, int start, int end) {
            SString result = SString.make(end - start, ' ');
            result.copyFrom(0, s, start, end);
            return result;
        }
    }

    /** {@code (string-append string ...)}: a new mutable string. */
    static final class StringAppend extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SString append(Object[] args) {
            long length = 0;
            for (int i = 1; i < args.length; i++) {
                length += stringArg("string-append", i, args[i]).length();
            }
            if (length > MAX_LENGTH) {
                throw SchemeError.of("string-append: the result is longer than the longest string, " + MAX_LENGTH + " characters");
            }
            SString result = SString.make((int) length, ' ');
            int at = 0;
            for (int k = 1; k < args.length; k++) {
                SString s = (SString) args[k];
                result.copyFrom(at, s, 0, s.length());
                at += s.length();
            }
            return result;
        }
    }

    /** {@code (string->list string [start [end]])}. */
    static final class StringToList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString s = stringArg("string->list", 1, args[1]);
            int start = args.length > 2 ? indexArg("string->list", 2, args[2], 0, s.length()) : 0;
            int end = args.length > 3 ? indexArg("string->list", 3, args[3], start, s.length()) : s.length();
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
        public Object invoke(Object[] args) {
            return fromList(Lists.toArray("list->string", 1, args[1]));
        }
    }

    /** {@code (string-copy! to at from [start [end]])}: overlapping ranges of one string copy as if through a temporary. */
    static final class StringCopyInto extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString to = stringArg("string-copy!", 1, args[1]);
            int at = indexArg("string-copy!", 2, args[2], 0, to.length());
            SString from = stringArg("string-copy!", 3, args[3]);
            int start = args.length > 4 ? indexArg("string-copy!", 4, args[4], 0, from.length()) : 0;
            int end = args.length > 5 ? indexArg("string-copy!", 5, args[5], start, from.length()) : from.length();
            if (end - start > to.length() - at) {
                throw tooShort(to, args[2], end - start);
            }
            if (to.isImmutable() && end > start) {
                throw immutable("string-copy!", 1, to);
            }
            copy(to, at, from, start, end);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void copy(SString to, int at, SString from, int start, int end) {
            to.copyFrom(at, from, start, end);
        }

        @TruffleBoundary
        private static SchemeError tooShort(SString to, Object at, int count) {
            return SchemeError.argument("string-copy!", 2, SchemeError.index(0, to.length() - count) + ", at which the " + count + " characters fit", at);
        }
    }

    /** {@code (string-fill! string char [start [end]])}. */
    static final class StringFill extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString s = stringArg("string-fill!", 1, args[1]);
            int fill = charArg("string-fill!", 2, args[2]).codePoint;
            int start = args.length > 3 ? indexArg("string-fill!", 3, args[3], 0, s.length()) : 0;
            int end = args.length > 4 ? indexArg("string-fill!", 4, args[4], start, s.length()) : s.length();
            if (s.isImmutable() && end > start) {
                throw immutable("string-fill!", 1, s);
            }
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
