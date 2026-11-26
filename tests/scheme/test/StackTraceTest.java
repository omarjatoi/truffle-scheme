package scheme.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Test;

import scheme.runtime.SchemeLanguage;

/** Discovery: prints each case's trace and location. */
public class StackTraceTest {

    private static final String SCHEME = "scheme";

    static Map<String, String> cases() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("plain", """
                        (define (f) (list @))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("spill", """
                        (define (down n)
                          (if (= n 0)
                              (list @)
                              (+ 1 (down (- n 1)))))
                        (down 30)
                        """);
        c.put("reentry-once", """
                        (define k #f)
                        (define count 0)
                        (define (f)
                          (call/cc (lambda (c) (set! k c)))
                          (set! count (+ count 1))
                          (list (if (= count 2) @ count)))
                        (define (g) (list (f)))
                        (g)
                        (if (< count 2) (k #f))
                        """);
        c.put("reentry-twice", """
                        (define k #f)
                        (define count 0)
                        (define (f)
                          (call/cc (lambda (c) (set! k c)))
                          (set! count (+ count 1))
                          (list (if (= count 3) @ count)))
                        (define (g) (list (f)))
                        (g)
                        (if (< count 3) (k #f))
                        """);
        c.put("reentry-inner", """
                        (define k #f)
                        (define (f)
                          (list (call/cc (lambda (c) (set! k c) 1))))
                        (define (g) (list (f)))
                        (define (h) (list (g)))
                        (h)
                        (define (go) (list (k @)))
                        (go)
                        """);
        c.put("guard-reraise", """
                        (define (f)
                          (list (guard (e ((string? e) 'no))
                                  (list @))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("wind-before", """
                        (define (f)
                          (list (dynamic-wind (lambda () (list @)) (lambda () 1) (lambda () 2))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("wind-thunk", """
                        (define (f)
                          (list (dynamic-wind (lambda () 0) (lambda () (list @)) (lambda () 2))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("wind-after", """
                        (define (f)
                          (list (dynamic-wind (lambda () 0) (lambda () 1) (lambda () (list @)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("handler-raise", """
                        (define (f)
                          (list (with-exception-handler
                                  (lambda (e) (list @))
                                  (lambda () (list (raise 'c))))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("handler-continuable", """
                        (define (f)
                          (list (with-exception-handler
                                  (lambda (e) (list @))
                                  (lambda () (list (raise-continuable 'c))))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("handler-intrinsic", """
                        (define (f)
                          (list (with-exception-handler
                                  (lambda (e) (list @))
                                  (lambda () (list (car 'y))))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("callcc-receiver", """
                        (define (f)
                          (list (call/cc (lambda (k) (list @)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("callcc-escape", """
                        (define (f)
                          (list (call/cc (lambda (k) (k 1)))))
                        (define (g) (list (f) @))
                        (g)
                        """);
        c.put("map", """
                        (define (f)
                          (list (map (lambda (x) (list @)) '(1))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("for-each", """
                        (define (f)
                          (list (for-each (lambda (x) (list @)) '(1))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("apply", """
                        (define (f)
                          (list (apply (lambda (x) (list @)) '(1))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("cwv-producer", """
                        (define (f)
                          (list (call-with-values (lambda () (list @)) list)))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("cwv-consumer", """
                        (define (f)
                          (list (call-with-values (lambda () 1) (lambda (x) (list @)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("parameterize", """
                        (define p (make-parameter 0))
                        (define (f)
                          (list (parameterize ((p 1)) (list @))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("converter", """
                        (define p (make-parameter 0 (lambda (x) (if (= x 1) (list @) x))))
                        (define (f)
                          (list (parameterize ((p 1)) 0)))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("eval", """
                        (define (h) (list @))
                        (define (f)
                          (list (eval (list h) (environment '(scheme base)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("eval-datum", """
                        (define (f)
                          (list (eval '(list @) (environment '(scheme base) '(scheme process-context)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("load", """
                        (define (f)
                          (list (load "LOADED")))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("library", """
                        (define-library (test body)
                          (import (scheme base) (scheme process-context))
                          (begin
                            (define (h) (list @))
                            (list (h))))
                        (import (test body))
                        """);
        c.put("library-file", """
                        (import (scheme base) (test file))
                        """);
        c.put("after-replaces", """
                        (define (f)
                          (list (dynamic-wind (lambda () 0) (lambda () (list (car 'first))) (lambda () (list @)))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("trampoline", """
                        (define (f)
                          (list (apply PRIM (list ARG))))
                        (define (g) (list (f)))
                        (g)
                        """);
        c.put("tail-caller", """
                        (define (leave) @)
                        (define (f) (list (leave)))
                        (define (g) (list (f)))
                        (g)
                        """);
        return c;
    }

    static final String[][] RAISERS = {{"error", "(car 'x)", "car", "'x"}, {"raise", "(raise 'boom)", "raise", "'boom"}, {"exit", "(exit 3)", "exit", "3"}, {"emergency", "(emergency-exit 3)", "emergency-exit", "3"}};

    static String frames(PolyglotException e) {
        List<String> frames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection at = frame.getSourceLocation();
            String internal = at != null && at.getSource().isInternal() ? "~" : "";
            frames.add(internal + frame.getRootName() + " " + (at == null ? "?" : at.getSource().getName() + ":" + at.getStartLine()));
        }
        return String.join(", ", frames);
    }

    static String at(SourceSection s) {
        return s == null ? "none" : s.getSource().getName() + ":" + s.getStartLine() + " " + s.getCharacters();
    }

    @Test
    public void discover() throws IOException {
        Path dir = Files.createTempDirectory("traces");
        StringBuilder out = new StringBuilder();
        out.append("MODE torture=").append(SchemeLanguage.constant(SchemeLanguage.TORTURE)).append('\n');
        for (Map.Entry<String, String> c : cases().entrySet()) {
            for (String[] r : RAISERS) {
                String text = c.getValue().replace("@", r[1]).replace("PRIM", r[2]).replace("ARG", r[3]);
                if (c.getKey().equals("load")) {
                    Path loaded = dir.resolve("loaded-" + r[0] + ".scm");
                    Files.writeString(loaded, "(define (h) (list " + r[1] + "))\n(list (h))\n", StandardCharsets.UTF_8);
                    text = text.replace("LOADED", loaded.toString());
                }
                Context.Builder builder = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream()).allowIO(IOAccess.ALL).allowExperimentalOptions(true);
                if (c.getKey().equals("library-file")) {
                    Files.createDirectories(dir.resolve(r[0] + "/test"));
                    Files.writeString(dir.resolve(r[0] + "/test/file.sld"), """
                                    (define-library (test file)
                                      (import (scheme base) (scheme process-context))
                                      (begin
                                        (define (h) (list @))
                                        (list (h))))
                                    """.replace("@", r[1]), StandardCharsets.UTF_8);
                    builder.option("scheme.LibraryPath", dir.resolve(r[0]).toString());
                }
                if (c.getKey().equals("spill")) {
                    builder.option("scheme.SpillDepth", "5");
                }
                String name = c.getKey() + "-" + r[0] + ".scm";
                out.append(name).append(": ");
                try (Context context = builder.build()) {
                    context.eval(Source.newBuilder(SCHEME, text, name).buildLiteral());
                    out.append("NO EXCEPTION\n");
                } catch (PolyglotException e) {
                    out.append(e.isExit() ? "exit " + e.getExitStatus() : "error " + e.getMessage()).append(" | at ").append(at(e.getSourceLocation()).replace('\n', ' ')).append(" | ").append(frames(e)).append('\n');
                }
            }
        }
        System.out.println(out);
    }
}
