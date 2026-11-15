/**
 * The Scheme value types of design.md D1. Exact integers are {@code long} (overflowing into
 * {@link scheme.runtime.value.SBigInteger}), inexact reals are {@code double}, booleans are
 * {@code boolean}; everything else is a class here. Arithmetic beyond fixnums and flonums arrives
 * with the numeric tower (M4).
 */
package scheme.runtime.value;
