package scheme.builtins;

import scheme.runtime.PrimitiveNode;

/**
 * A one-argument type predicate ({@code pair?}, {@code symbol?}, ...), shared by every area. The
 * test is a final field of the node, so the compiler sees it as a constant and inlines it.
 */
final class TypePredicate extends PrimitiveNode {

    /**
     * The test. An interface of its own rather than {@code java.util.function.Predicate}: a native
     * image's analysis (design.md D17) must assume that a call through the field reaches every
     * implementation of the interface in the image, and every JDK predicate would then be
     * reachable from runtime-compiled code. Only these lambdas implement this one.
     */
    @FunctionalInterface
    interface Test {
        boolean test(Object value);
    }

    private final Test test;

    TypePredicate(Test test) {
        this.test = test;
    }

    @Override
    public Object invoke(Object[] args) {
        return test.test(args[1]);
    }
}
