package scheme.spike;

import java.util.Locale;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/** M0-03: {@code bench <fib|tak> <plain|protocol> <args...>} runs one benchmark call per execution. */
final class BenchRootNode extends RootNode {

    @Child private DirectCallNode callNode;
    private final Object[] arguments;

    private BenchRootNode(SpikeLanguage language, CallTarget target, Object[] arguments) {
        super(language);
        this.callNode = DirectCallNode.create(target);
        this.arguments = arguments;
    }

    static CallTarget create(SpikeLanguage language, String[] words) {
        SpikeBenchmarks.Variant variant = SpikeBenchmarks.Variant.valueOf(words[2].toUpperCase(Locale.ROOT));
        CallTarget target = switch (words[1]) {
            case "fib" -> SpikeBenchmarks.fib(language, variant);
            case "tak" -> SpikeBenchmarks.tak(language, variant);
            default -> throw new IllegalArgumentException("unknown benchmark: " + words[1]);
        };
        Object[] arguments = new Object[words.length - 3];
        for (int i = 3; i < words.length; i++) {
            arguments[i - 3] = Long.parseLong(words[i]);
        }
        return new BenchRootNode(language, target, arguments).getCallTarget();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return callNode.call(arguments);
    }
}
