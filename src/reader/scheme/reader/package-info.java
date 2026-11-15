/**
 * The R7RS reader (plan.md M1-04): the full datum syntax, every number syntax, datum labels, block
 * and datum comments, and the fold-case directives. Parsing uses explicit stacks, never Java
 * recursion proportional to nesting depth (AGENTS.md rule 4).
 */
package scheme.reader;
