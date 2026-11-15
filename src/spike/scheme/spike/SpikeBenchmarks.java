package scheme.spike;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;

/**
 * M0-03: fib and tak, each built twice. {@code plain} emits a bare call at every call site.
 * {@code protocol} emits what CallSiteEmitter will: call, then IsCapture, then SuspendFrame. No
 * capture ever happens in these programs, so the question is whether the JIT folds the check away.
 *
 * <pre>
 * fib(n)     = n < 2 ? n : fib(n - 1) + fib(n - 2)
 * tak(x,y,z) = y < x ? tak(tak(x-1,y,z), tak(y-1,z,x), tak(z-1,x,y)) : z
 * </pre>
 */
final class SpikeBenchmarks {

    enum Variant {
        PLAIN,
        PROTOCOL
    }

    private SpikeBenchmarks() {
    }

    static CallTarget fib(SpikeLanguage language, Variant variant) {
        FnRef fib = new FnRef("fib-" + variant);
        BytecodeRootNodes<SpikeNode> nodes = SpikeNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            b.beginIfThen();
            b.beginLt();
            b.emitLoadArgument(0);
            b.emitLoadConstant(2L);
            b.endLt();
            b.beginReturn();
            b.emitLoadArgument(0);
            b.endReturn();
            b.endIfThen();
            b.beginReturn();
            b.beginAdd();
            emitCall(b, variant, fib, () -> subArg(b, 0, 1L));
            emitCall(b, variant, fib, () -> subArg(b, 0, 2L));
            b.endAdd();
            b.endReturn();
            b.endRoot();
        });
        fib.target = nodes.getNode(0).getCallTarget();
        return fib.target;
    }

    static CallTarget tak(SpikeLanguage language, Variant variant) {
        FnRef tak = new FnRef("tak-" + variant);
        BytecodeRootNodes<SpikeNode> nodes = SpikeNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            b.beginIfThen();
            b.beginLt();
            b.emitLoadArgument(1);
            b.emitLoadArgument(0);
            b.endLt();
            b.beginReturn();
            emitCall(b, variant, tak,
                            () -> emitCall(b, variant, tak, () -> subArg(b, 0, 1L), () -> b.emitLoadArgument(1), () -> b.emitLoadArgument(2)),
                            () -> emitCall(b, variant, tak, () -> subArg(b, 1, 1L), () -> b.emitLoadArgument(2), () -> b.emitLoadArgument(0)),
                            () -> emitCall(b, variant, tak, () -> subArg(b, 2, 1L), () -> b.emitLoadArgument(0), () -> b.emitLoadArgument(1)));
            b.endReturn();
            b.endIfThen();
            b.beginReturn();
            b.emitLoadArgument(2);
            b.endReturn();
            b.endRoot();
        });
        tak.target = nodes.getNode(0).getCallTarget();
        return tak.target;
    }

    private static void subArg(SpikeNodeGen.Builder b, int index, long value) {
        b.beginSub();
        b.emitLoadArgument(index);
        b.emitLoadConstant(value);
        b.endSub();
    }

    /** One call site. In the PROTOCOL variant it's the shape CallSiteEmitter will emit. */
    private static void emitCall(SpikeNodeGen.Builder b, Variant variant, FnRef fn, Runnable... args) {
        BytecodeLocal result = null;
        if (variant == Variant.PROTOCOL) {
            b.beginBlock();
            result = b.createLocal();
            b.beginStoreLocal(result);
        }
        b.beginCallN();
        b.emitLoadConstant(fn);
        for (Runnable arg : args) {
            arg.run();
        }
        b.endCallN();
        if (variant == Variant.PROTOCOL) {
            b.endStoreLocal();
            b.beginConditional();
            b.beginIsCapture();
            b.emitLoadLocal(result);
            b.endIsCapture();
            b.beginSuspendFrame();
            b.emitLoadLocal(result);
            b.endSuspendFrame();
            b.emitLoadLocal(result);
            b.endConditional();
            b.endBlock();
        }
    }
}
