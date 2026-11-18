package scheme.test;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;

/**
 * M10-02: HotSpot compiles the bytecode interpreter's {@code continueAt} with every handler a
 * program has run inlined into it, and compiles it again each time the program reaches an
 * instruction it hadn't (design.md "The host compiler"). A call's path, the primitives' generic path
 * behind the typed operations, and raising an error in a frame are cut from that inlining
 * ({@code @InliningCutoff}): each is a call of its own, compiled once, so {@code continueAt} is
 * smaller and quicker to compile.
 */
public class HostInliningCutoffTest {

    private static void assertCut(Class<?> c, String name) {
        Method[] methods = Arrays.stream(c.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toArray(Method[]::new);
        assertTrue(c.getName() + "." + name + " not found", methods.length > 0);
        for (Method m : methods) {
            assertTrue(m + " isn't an inlining cutoff", m.isAnnotationPresent(InliningCutoff.class));
        }
    }

    @Test
    public void callsAreCutFromTheInterpretersHostInlining() throws ClassNotFoundException {
        Class<?> call = Class.forName("scheme.runtime.SchemeRootNode$Call");
        assertCut(call, "doDirect");
        assertCut(call, "doIndirect");
    }

    @Test
    public void slowPathsAreCutFromTheInterpretersHostInlining() throws ClassNotFoundException {
        assertCut(Class.forName("scheme.runtime.SchemeRootNode"), "raiseHere");
        assertCut(Class.forName("scheme.runtime.Intrinsics$IntrinsicNode"), "generic");
    }
}
