package scheme.runtime.value;

/**
 * A literal's pair (R7RS §3.4: <i>"literal constants ... are immutable"</i>): quote's datum, a
 * self-evaluating vector's elements, quasiquote's constant parts (design.md D1). It is a pair to
 * everything but the mutators, {@code set-car!}, {@code set-cdr!} and {@code list-set!}, which
 * refuse it, and interop, whose members are read-only on it. It adds no field: the class is the mark.
 */
public final class LiteralPair extends SPair {

    public LiteralPair(Object car, Object cdr) {
        super(car, cdr);
    }
}
