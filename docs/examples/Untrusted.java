import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;

public class Untrusted {
    public static void main(String[] args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme")
                        .sandbox(SandboxPolicy.UNTRUSTED)
                        .in(InputStream.nullInputStream())
                        .out(output)
                        .err(output)
                        .option("engine.MaxIsolateMemory", "512MB")
                        .option("sandbox.MaxHeapMemory", "128MB")
                        .option("sandbox.MaxCPUTime", "2s")
                        .option("sandbox.MaxASTDepth", "50")
                        .option("sandbox.MaxThreads", "1")
                        .option("sandbox.MaxOutputStreamSize", "1MB")
                        .option("sandbox.MaxErrorStreamSize", "1MB")
                        .build()) {
            context.eval("scheme", "(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (display (fact 30))");
            System.out.println(output.toString(StandardCharsets.UTF_8));
            context.eval("scheme", "(let loop () (loop))");
        } catch (PolyglotException e) {
            System.out.println(e.isResourceExhausted() + ": " + e.getMessage());
        }
    }
}
