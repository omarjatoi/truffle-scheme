package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * Owned segments (design.md "Owned segments", AGENTS.md rule 3): the frames a stack-depth spill
 * suspends are reachable only from the root prompt's pending list, so the driver resumes each of
 * them in place, once, without a copy. The moment a continuation may reach a segment (call/cc, a
 * delimited capture such as guard's, torture, a prompt's segments re-linked into a capture) it is
 * shared for good, and every resume of it copies the frame, so a continuation can be re-entered any
 * number of times and each re-entry sees the frames as they were when it was captured.
 *
 * <p>Every context spills past 50 nested calls (the {@code scheme.SpillDepth} option), however much
 * Java stack the thread has, so these recursions of a few thousand calls spill many times. The
 * counts of in-place and copied resumes ({@code (%in-place-resumes)}, {@code (%copied-resumes)})
 * are checked outside torture mode only: under torture, every non-tail call captures, so every
 * segment is shared and copied, as before, and only the results are checked.
 */
public class OwnedSegmentsTest {

    private static final String SCHEME = "scheme";
    private static final boolean TORTURE = Boolean.getBoolean("scheme.torture");

    /** The recursions' depth and what {@link #RECURSIONS}' {@code down} and {@code up} return for it. */
    private static final int N = 3000;
    private static final long SUM = 2L * N * (N + 1);

    /**
     * {@code (down i n w)} and {@code (up i n w)} recurse from i to n, and return the sum of
     * {@code 4i} over the frames, plus what the call/cc at frame w (capturing {@code k}) returned: 0
     * the first time. Each frame has locals live across its call ({@code a}, and {@code v} in
     * {@code down}), block-scoped locals set after it ({@code r}, and {@code v} in {@code up}), and
     * an operand pushed before the call and consumed after it ({@code a}, in the outer sum): a frame
     * run a second time from its original, interpreted, finds the sum of its first run where that
     * operand was. {@code down} captures on the way down, before frame w's call, {@code up} on the
     * way back up, once the frames below w have returned and those above are still pending.
     * {@code (count i n)} is a plain recursion; {@code (jump i n v)} recurses n deep and invokes k
     * with v from the bottom, abandoning its own frames.
     */
    private static final String RECURSIONS = """
                    (define k #f)
                    (define (down i n w)
                      (let ((a (* i 3)))
                        (let ((v (if (= i w) (call/cc (lambda (c) (set! k c) 0)) 0)))
                          (+ a (let ((r (if (= i n) 0 (down (+ i 1) n w))))
                                 (+ r v (car (list i))))))))
                    (define (up i n w)
                      (let ((a (* i 3)))
                        (+ a (let ((r (if (= i n) 0 (up (+ i 1) n w))))
                               (let ((v (if (= i w) (call/cc (lambda (c) (set! k c) 0)) 0)))
                                 (+ r v (car (list i))))))))
                    (define (count i n) (if (= i n) 0 (+ 1 (count (+ i 1) n))))
                    (define (jump i n v) (if (= i n) (k v) (+ 1 (jump (+ i 1) n v))))
                    """;

    private static Context context(ByteArrayOutputStream out) {
        return Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "50").out(out).build();
    }

    private static Context context() {
        return context(new ByteArrayOutputStream());
    }

    /** The counts {@code (%spills)}, {@code (%in-place-resumes)} and {@code (%copied-resumes)}. */
    private static long[] counts(Context context) {
        Value v = context.eval(SCHEME, "(vector (%spills) (%in-place-resumes) (%copied-resumes))");
        return new long[]{v.getArrayElement(0).asLong(), v.getArrayElement(1).asLong(), v.getArrayElement(2).asLong()};
    }

    private static long[] since(long[] before, Context context) {
        long[] after = counts(context);
        return new long[]{after[0] - before[0], after[1] - before[1], after[2] - before[2]};
    }

    private static String eval(Context context, String program) {
        return context.eval(SCHEME, program).toString();
    }

    @Test
    public void ownedSegmentsResumeWithoutACopy() {
        try (Context context = context()) {
            context.eval(SCHEME, RECURSIONS);
            long[] before = counts(context);
            assertEquals("10000", eval(context, "(count 0 10000)"));
            long[] d = since(before, context);
            assertTrue("spills: " + d[0], d[0] > 100);
            if (TORTURE) {
                assertTrue("copied: " + d[2], d[2] > 0);
            } else {
                // Every frame but the last few, on the Java stack, spilled and was resumed in place.
                assertTrue("in place: " + d[1], d[1] >= 10000 - 100 && d[1] <= 10000);
                assertEquals("copied", 0, d[2]);
            }
        }
    }

    @Test
    public void reentryOnTheWayDownSeesTheOriginalFrames() {
        for (int w : new int[]{0, 1, 49, 50, 51, 777, N - 1, N}) {
            try (Context context = context()) {
                context.eval(SCHEME, RECURSIONS);
                // Each re-entry runs the recursion below w again: new spills, whose segments are
                // owned again, on top of k's shared ones.
                String results = eval(context, String.format(Locale.ROOT, """
                                (let ((runs 0) (results '()))
                                  (let ((x (down 0 %d %d)))
                                    (set! results (cons x results))
                                    (set! runs (+ runs 1))
                                    (if (< runs 3) (k runs) (reverse results))))
                                """, N, w));
                assertEquals("w = " + w, String.format(Locale.ROOT, "(%d %d %d)", SUM, SUM + 1, SUM + 2), results);
            }
        }
    }

    @Test
    public void reentryOnTheWayUpSeesTheOriginalFrames() {
        for (int w : new int[]{0, 1, 49, 50, 51, 777, N - 1, N}) {
            try (Context context = context()) {
                context.eval(SCHEME, RECURSIONS);
                // The frames above w were pending, owned, when k captured them: the first run must
                // copy them as it goes on, or the re-entries would find them already run.
                String results = eval(context, String.format(Locale.ROOT, """
                                (let ((runs 0) (results '()))
                                  (let ((x (up 0 %d %d)))
                                    (set! results (cons x results))
                                    (set! runs (+ runs 1))
                                    (if (< runs 4) (k (* runs 10)) (reverse results))))
                                """, N, w));
                assertEquals("w = " + w, String.format(Locale.ROOT, "(%d %d %d %d)", SUM, SUM + 10, SUM + 20, SUM + 30), results);
            }
        }
    }

    @Test
    public void aCaptureSharesThePendingSegmentsItReaches() {
        try (Context context = context()) {
            context.eval(SCHEME, RECURSIONS);
            int w = 1000;
            long[] before = counts(context);
            assertEquals(Long.toString(SUM), eval(context, String.format(Locale.ROOT, "(up 0 %d %d)", N, w)));
            long[] d = since(before, context);
            if (!TORTURE) {
                // The frames below w were resumed in place before the capture; the w frames above
                // it (and the program's) were pending when k captured them, so they are copied.
                assertTrue("in place: " + d[1], d[1] >= N - w - 100 && d[1] <= N - w);
                assertTrue("copied: " + d[2], d[2] >= w - 50 && d[2] <= w + 10);
            }
            // A plain recursion after the capture: its segments are owned again.
            before = counts(context);
            assertEquals("5000", eval(context, "(count 0 5000)"));
            d = since(before, context);
            if (!TORTURE) {
                assertTrue("in place: " + d[1], d[1] >= 5000 - 100);
                assertEquals("copied", 0, d[2]);
            }
        }
    }

    @Test
    public void continuationsCapturedWhileTheDriverGoesOn() {
        try (Context context = context()) {
            context.eval(SCHEME, RECURSIONS);
            long[] before = counts(context);
            // k is captured on the way up and the run goes on; then a deep recursion that never
            // captures (owned, in place) computes the value a second deep recursion passes to k from
            // its bottom, abandoning its own pending frames; then k again.
            String results = eval(context, String.format(Locale.ROOT, """
                            (let ((runs 0) (results '()))
                              (let ((x (up 0 %d 1000)))
                                (set! results (cons x results))
                                (set! runs (+ runs 1))
                                (cond ((= runs 1) (jump 0 4000 (- (count 0 4000) 3995)))
                                      ((= runs 2) (jump 0 4000 7))
                                      (else (reverse results)))))
                            """, N));
            assertEquals(String.format(Locale.ROOT, "(%d %d %d)", SUM, SUM + 5, SUM + 7), results);
            long[] d = since(before, context);
            if (!TORTURE) {
                assertTrue("in place: " + d[1], d[1] >= 5000);
                assertTrue("copied: " + d[2], d[2] >= 3 * 950);
            }
        }
    }

    @Test
    public void generatorsOverDeepRecursion() {
        try (Context context = context()) {
            // Two generators walk trees with the same fringe, 0 to 2000, recursing 2000 deep (one
            // through cars, one through cdrs), and switch with call/cc at every leaf.
            String result = eval(context, """
                            (define (make-gen tree)
                              (define return #f)
                              (define resume #f)
                              (define (walk t)
                                (cond ((null? t) 0)
                                      ((pair? t) (+ (walk (car t)) (walk (cdr t))))
                                      (else (call/cc (lambda (c) (set! resume c) (return t))) 1)))
                              (lambda ()
                                (call/cc (lambda (r)
                                  (set! return r)
                                  (if resume
                                      (resume #f)
                                      (let ((leaves (walk tree)))
                                        (return (list 'done leaves))))))))
                            (define (left-deep n)
                              (let loop ((i 1) (t (list 0)))
                                (if (> i n) t (loop (+ i 1) (list t i)))))
                            (define (right-deep n)
                              (let loop ((i n) (t '()))
                                (if (< i 0) t (loop (- i 1) (cons i t)))))
                            (define a (make-gen (left-deep 2000)))
                            (define b (make-gen (right-deep 2000)))
                            (let loop ((sum 0))
                              (let ((x (a)) (y (b)))
                                (cond ((pair? x) (list sum x y))
                                      ((eqv? x y) (loop (+ sum x)))
                                      (else (list 'differ x y)))))
                            """);
            assertEquals("(2001000 (done 2001) (done 2001))", result);
        }
    }

    @Test
    public void dynamicWindAcrossSpilledFramesReentered() {
        try (Context context = context()) {
            String result = eval(context, """
                            (define k #f)
                            (define ins 0)
                            (define outs 0)
                            (define (deep i n)
                              (cond ((= i n) (call/cc (lambda (c) (set! k c) 0)))
                                    ((= 0 (modulo i 100))
                                     (dynamic-wind
                                       (lambda () (set! ins (+ ins 1)))
                                       (lambda () (+ 1 (deep (+ i 1) n)))
                                       (lambda () (set! outs (+ outs 1)))))
                                    (else (+ 1 (deep (+ i 1) n)))))
                            (let ((runs 0) (results '()))
                              (let ((r (deep 0 3000)))
                                (set! results (cons (list r ins outs) results))
                                (set! runs (+ runs 1))
                                (if (< runs 3) (k runs) (reverse results))))
                            """);
            assertEquals("((3000 30 30) (3001 60 60) (3002 90 90))", result);
        }
    }

    @Test
    public void guardAndRaiseFromDeepSpilledFrames() {
        try (Context context = context()) {
            context.eval(SCHEME, """
                            (define (deep-raise i n obj) (if (= i n) (raise obj) (+ 1 (deep-raise (+ i 1) n obj))))
                            (define (deep-continuable i n) (if (= i n) (raise-continuable 'more) (+ 1 (deep-continuable (+ i 1) n))))
                            (define (deep-error i n) (if (= i n) (car '()) (+ 1 (deep-error (+ i 1) n))))
                            (define (mid i n)
                              (cond ((= i n) (raise 'deep))
                                    ((= i (quotient n 2)) (+ 1 (guard (e ((eq? e 'deep) 1000)) (mid (+ i 1) n))))
                                    (else (+ 1 (mid (+ i 1) n)))))
                            """);
            assertEquals("(caught boom)", eval(context, "(guard (e ((symbol? e) (list 'caught e))) (deep-raise 0 3000 'boom))"));
            assertEquals("3007", eval(context, "(with-exception-handler (lambda (e) 7) (lambda () (deep-continuable 0 3000)))"));
            assertEquals("caught", eval(context, "(guard (e (#t 'caught)) (deep-error 0 3000))"));
            // The guard's prompt is 1500 deep: the spills pass through it, and the frames outside
            // it go on with the handler's value.
            assertEquals("2501", eval(context, "(mid 0 3000)"));
            // A full continuation captured inside a guard's body, re-entered from its handler: each
            // re-entry raises again, inside the same guard.
            assertEquals("30", eval(context, """
                            (define k #f)
                            (define entries 0)
                            (define (g i n)
                              (if (= i n)
                                  (begin (call/cc (lambda (c) (set! k c)))
                                         (set! entries (+ entries 1))
                                         (raise entries))
                                  (+ 1 (g (+ i 1) n))))
                            (let ((r (guard (e ((number? e) (* 10 e))) (g 0 3000))))
                              (if (< r 30) (k #f) r))
                            """));
        }
    }

    @Test
    public void spillsThroughPromptsAndReinstatedContinuations() {
        try (Context context = context()) {
            context.eval(SCHEME, RECURSIONS);
            // A composable continuation over 3000 spilled frames, reinstated three times: its
            // segments are shared, so each reinstatement resumes copies.
            assertEquals("(3001 3010 6100)", eval(context, """
                            (define (deep-capture i n) (if (= i n) (%capture-to-prompt 'p (lambda (c) c)) (+ 1 (deep-capture (+ i 1) n))))
                            (define kk (%call-with-prompt 'p (lambda () (deep-capture 0 3000))))
                            (list (kk 1) (kk 10) (+ (kk 100) (count 0 3000)))
                            """));
            // Each frame of a reinstated continuation, once resumed, makes a recursion deep enough
            // to spill: new owned segments on top of the continuation's shared ones.
            assertEquals("(60000 60001 60002)", eval(context, """
                            (define (deep-then-count i n)
                              (if (= i n) (%capture-to-prompt 'p (lambda (c) c)) (+ (deep-then-count (+ i 1) n) (count 0 120))))
                            (define kc (%call-with-prompt 'p (lambda () (deep-then-count 0 500))))
                            (list (kc 0) (kc 1) (kc 2))
                            """));
            // A full continuation captured below an escape-only call/cc's prompt and a
            // %call-with-prompt, both flattened into the spills: re-entered, and escaped from a
            // re-entry.
            assertEquals("(5000 5001 -1)", eval(context, """
                            (define (deep-esc i n esc)
                              (if (= i n)
                                  (let ((v (call/cc (lambda (c) (set! k c) 0))))
                                    (if (eq? v 'escape) (esc -1) v))
                                  (+ 1 (deep-esc (+ i 1) n esc))))
                            (let ((runs 0) (results '()))
                              (let ((x (call/cc (lambda (esc)
                                         (+ (count 0 2000) (%call-with-prompt 'q (lambda () (deep-esc 0 3000 esc))))))))
                                (set! results (cons x results))
                                (set! runs (+ runs 1))
                                (cond ((= runs 1) (k 1))
                                      ((= runs 2) (k 'escape))
                                      (else (reverse results)))))
                            """));
        }
    }

    @Test
    public void interopReentersContinuationsOverSpilledFramesAcrossCalls() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = context(out)) {
            context.eval(SCHEME, RECURSIONS);
            // M6-02: each execute runs under a root prompt of its own; k, captured on the way up in
            // the first call, runs the rest of that call each time it is invoked, from Java or from
            // a later call's deep recursion.
            Value f = context.eval(SCHEME, String.format(Locale.ROOT, "(lambda () (up 0 %d 1000))", N));
            assertEquals(SUM, f.execute().asLong());
            Value k = context.getBindings(SCHEME).getMember("k");
            assertEquals(SUM + 10, k.execute(10).asLong());
            assertEquals(SUM + 20, k.execute(20).asLong());
            Value jump = context.eval(SCHEME, "(lambda (v) (jump 0 4000 v))");
            assertEquals(SUM + 30, jump.execute(30).asLong());
            Value count = context.eval(SCHEME, "(lambda (n) (count 0 n))");
            assertEquals(6000, count.execute(6000).asLong());
            assertEquals(SUM + 40, k.execute(40).asLong());
            // A generator over a deep recursion, driven one call at a time from Java.
            Value next = context.eval(SCHEME, """
                            (define (make-generator n)
                              (define return #f)
                              (define resume #f)
                              (define (walk i)
                                (if (= i n)
                                    0
                                    (let ((r (walk (+ i 1))))
                                      (call/cc (lambda (c) (set! resume c) (return i)))
                                      (+ r 1))))
                              (lambda ()
                                (call/cc (lambda (r)
                                  (set! return r)
                                  (if resume (resume #f) (return (list 'done (walk 0))))))))
                            (make-generator 1000)
                            """);
            for (int i = 999; i >= 0; i--) {
                assertEquals(i, next.execute().asInt());
            }
            assertEquals("(done 1000)", next.execute().toString());
        }
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }
}
