package scheme.compiler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.Syntax;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * Finds circular structure in code (R7RS §2.4: "It is an error for a program or library to include
 * circular references except in literals. In particular, it is an error for quasiquote (section
 * 4.2.8) to contain them."). The expander walks code as a tree, so a form that contains itself
 * would expand forever; {@code eval} of such a datum, or program text that makes one with datum
 * labels, is a syntax error instead (design.md D9).
 *
 * <p>Literals may be circular: the operand of a {@code (quote datum)} form, and a vector, which is
 * self-evaluating (R7RS §4.1.2). Inside a {@code quasiquote} or a {@code syntax-rules} form, whose
 * templates the expander walks, nothing may be. The forms are recognized by their head's name, before
 * expansion: a cycle passed to some other macro is an error even if that macro would quote it.
 * Shared structure that isn't circular is fine. No Java recursion (AGENTS.md rule 4).
 */
final class CircularCode {

    private CircularCode() {
    }

    /** Pushed after a node's children: they are done, so the node leaves the current path. */
    private record Exit(Object node) {
    }

    /** A node to visit; {@code element} is false for a list's tail (a cdr), true for a form. */
    private record Visit(Object node, boolean strict, boolean element) {
    }

    /** The first node found on a cycle of {@code form}'s code, or null if its code has none. */
    @TruffleBoundary
    static Object find(Object form) {
        // On the current path (the nodes being visited, whose descendants a back edge reaches);
        // done, in lenient and strict mode (bit 1 and bit 2). A node done in lenient mode may still
        // hold a cycle under a quote, which strict mode must see.
        IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        IdentityHashMap<Object, Integer> done = new IdentityHashMap<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(new Visit(form, false, true));
        while (!stack.isEmpty()) {
            Object top = stack.pop();
            if (top instanceof Exit exit) {
                path.remove(exit.node());
                continue;
            }
            Visit v = (Visit) top;
            Object node = v.node();
            if (!(node instanceof Syntax || node instanceof SPair || node instanceof SVector)) {
                continue;
            }
            if (path.containsKey(node)) {
                return node;
            }
            int bit = v.strict() ? 2 : 1;
            Integer seen = done.get(node);
            if (seen != null && (seen & bit) != 0) {
                continue;
            }
            done.put(node, seen == null ? bit : seen | bit);
            path.put(node, Boolean.TRUE);
            stack.push(new Exit(node));
            if (node instanceof Syntax s) {
                stack.push(new Visit(s.datum, v.strict(), v.element()));
            } else if (node instanceof SPair p) {
                boolean strict = v.strict();
                if (v.element()) {
                    String head = headName(p);
                    if (!strict && "quote".equals(head)) {
                        continue;
                    }
                    strict |= "quasiquote".equals(head) || "syntax-rules".equals(head);
                }
                stack.push(new Visit(p.cdr, strict, false));
                stack.push(new Visit(p.car, strict, true));
            } else if (v.strict()) {
                SVector vector = (SVector) node;
                for (int i = vector.length - 1; i >= 0; i--) {
                    stack.push(new Visit(vector.get(i), true, true));
                }
            }
        }
        return null;
    }

    /** The name of the symbol a list starts with, or null. */
    private static String headName(SPair p) {
        Object head = p.car;
        while (head instanceof Syntax s) {
            head = s.datum;
        }
        return head instanceof SSymbol symbol ? symbol.toString() : null;
    }
}
