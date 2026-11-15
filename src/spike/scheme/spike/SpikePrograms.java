package scheme.spike;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;

/**
 * The test program: f1 calls f2, f2 calls f3, f3 calls callcc. Every function takes (box, mode).
 *
 * <pre>
 * f3(box, mode): l3 = 3
 *                t  = l3 + CALLSITE(callcc)        ; l3 is on the operand stack across the call
 *                box := box + 1                     ; after resume: shared box
 *                l3 = l3 + 1                        ; after resume: mutate a local
 *                return t + l3 * 10                 ; fresh run: v + 43
 *
 * f2(box, mode): { l2 = 20                          ; nested block scope
 *                  r  = l2 + CALLSITE(f3)           ; capture from inside the nested scope
 *                  l2 = l2 + 5
 *                  r  = r + l2 * 100 }
 *                { m = 777                          ; sibling scope: may reuse l2's slot
 *                  r = r + m }
 *                return r                           ; fresh run: v + 3340
 *
 * f1(box, mode): l1 = 100
 *                t  = l1 + CALLSITE(f2)
 *                l1 = l1 + 1
 *                return t + l1 * 1000 + box * 1000000  ; fresh run (box = 1): v + 1104440
 * </pre>
 *
 * CALLSITE(f) is what {@code CallSiteEmitter} will emit: call, then {@code IsCapture}, then
 * {@code SuspendFrame}.
 */
final class SpikePrograms {

    final CallTarget f1;

    private SpikePrograms(CallTarget f1) {
        this.f1 = f1;
    }

    static SpikePrograms build(SpikeLanguage language) {
        FnRef callcc = new FnRef("callcc");
        FnRef f3 = new FnRef("f3");
        FnRef f2 = new FnRef("f2");
        FnRef f1 = new FnRef("f1");
        BytecodeRootNodes<SpikeNode> nodes = SpikeNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            // callcc(box, mode)
            b.beginRoot();
            b.beginReturn();
            b.beginCallCC();
            b.emitLoadArgument(1);
            b.endCallCC();
            b.endReturn();
            b.endRoot();

            // f3(box, mode)
            b.beginRoot();
            BytecodeLocal l3 = b.createLocal("l3", null);
            BytecodeLocal t3 = b.createLocal("t", null);
            storeConstant(b, l3, 3L);
            b.beginStoreLocal(t3);
            b.beginAdd();
            b.emitLoadLocal(l3);
            emitCallSite(b, callcc);
            b.endAdd();
            b.endStoreLocal();
            b.beginBoxSet();
            b.emitLoadArgument(0);
            b.beginAdd();
            b.beginBoxGet();
            b.emitLoadArgument(0);
            b.endBoxGet();
            b.emitLoadConstant(1L);
            b.endAdd();
            b.endBoxSet();
            addConstant(b, l3, 1L);
            b.beginReturn();
            b.beginAdd();
            b.emitLoadLocal(t3);
            mulLocal(b, l3, 10L);
            b.endAdd();
            b.endReturn();
            b.endRoot();

            // f2(box, mode)
            b.beginRoot();
            BytecodeLocal r = b.createLocal("r", null);
            b.beginBlock();
            BytecodeLocal l2 = b.createLocal("l2", null);
            storeConstant(b, l2, 20L);
            b.beginStoreLocal(r);
            b.beginAdd();
            b.emitLoadLocal(l2);
            emitCallSite(b, f3);
            b.endAdd();
            b.endStoreLocal();
            addConstant(b, l2, 5L);
            b.beginStoreLocal(r);
            b.beginAdd();
            b.emitLoadLocal(r);
            mulLocal(b, l2, 100L);
            b.endAdd();
            b.endStoreLocal();
            b.endBlock();
            b.beginBlock();
            BytecodeLocal m = b.createLocal("m", null);
            storeConstant(b, m, 777L);
            b.beginStoreLocal(r);
            b.beginAdd();
            b.emitLoadLocal(r);
            b.emitLoadLocal(m);
            b.endAdd();
            b.endStoreLocal();
            b.endBlock();
            b.beginReturn();
            b.emitLoadLocal(r);
            b.endReturn();
            b.endRoot();

            // f1(box, mode)
            b.beginRoot();
            BytecodeLocal l1 = b.createLocal("l1", null);
            BytecodeLocal t1 = b.createLocal("t", null);
            storeConstant(b, l1, 100L);
            b.beginStoreLocal(t1);
            b.beginAdd();
            b.emitLoadLocal(l1);
            emitCallSite(b, f2);
            b.endAdd();
            b.endStoreLocal();
            addConstant(b, l1, 1L);
            b.beginReturn();
            b.beginAdd();
            b.beginAdd();
            b.emitLoadLocal(t1);
            mulLocal(b, l1, 1000L);
            b.endAdd();
            b.beginMul();
            b.beginBoxGet();
            b.emitLoadArgument(0);
            b.endBoxGet();
            b.emitLoadConstant(1000000L);
            b.endMul();
            b.endAdd();
            b.endReturn();
            b.endRoot();
        });
        callcc.target = nodes.getNode(0).getCallTarget();
        f3.target = nodes.getNode(1).getCallTarget();
        f2.target = nodes.getNode(2).getCallTarget();
        f1.target = nodes.getNode(3).getCallTarget();
        return new SpikePrograms(f1.target);
    }

    /** Call, then IsCapture, then SuspendFrame: the shape CallSiteEmitter will emit. A value expression. */
    private static void emitCallSite(SpikeNodeGen.Builder b, FnRef fn) {
        b.beginBlock();
        BytecodeLocal result = b.createLocal();
        b.beginStoreLocal(result);
        b.beginCall();
        b.emitLoadConstant(fn);
        b.emitLoadArgument(0);
        b.emitLoadArgument(1);
        b.endCall();
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

    private static void storeConstant(SpikeNodeGen.Builder b, BytecodeLocal local, long value) {
        b.beginStoreLocal(local);
        b.emitLoadConstant(value);
        b.endStoreLocal();
    }

    private static void addConstant(SpikeNodeGen.Builder b, BytecodeLocal local, long value) {
        b.beginStoreLocal(local);
        b.beginAdd();
        b.emitLoadLocal(local);
        b.emitLoadConstant(value);
        b.endAdd();
        b.endStoreLocal();
    }

    private static void mulLocal(SpikeNodeGen.Builder b, BytecodeLocal local, long factor) {
        b.beginMul();
        b.emitLoadLocal(local);
        b.emitLoadConstant(factor);
        b.endMul();
    }
}
