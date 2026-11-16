package scheme.runtime;

import java.util.List;

/**
 * The feature identifiers this implementation supports (R7RS Appendix B), in one place: what
 * {@code cond-expand} recognizes and what {@code features} returns, so the two never disagree.
 */
public final class Features {

    /** In the order {@code features} lists them. */
    public static final List<String> NAMES = List.of("r7rs", "exact-closed", "exact-complex", "ieee-float", "full-unicode", "ratios", "truffle-scheme");

    private Features() {
    }
}
