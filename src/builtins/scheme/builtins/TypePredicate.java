package scheme.builtins;

import java.util.function.Predicate;

import scheme.runtime.PrimitiveNode;

/**
 * A one-argument type predicate ({@code pair?}, {@code symbol?}, ...), shared by every area. The
 * test is a final field of the node, so the compiler sees it as a constant and inlines it.
 */
final class TypePredicate extends PrimitiveNode {

    private final Predicate<Object> test;

    TypePredicate(Predicate<Object> test) {
        this.test = test;
    }

    @Override
    public Object invoke(Object[] args) {
        return test.test(args[1]);
    }
}
