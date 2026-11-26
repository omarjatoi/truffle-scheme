package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.junit.Test;

import scheme.runtime.BuiltinLibrary;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;

/**
 * The primitives' registry (design.md D17, "The primitives' registry"): each area's enum gives every
 * primitive its name, arity and node, as the lambdas in its spec list did before.
 * {@code primitives.txt} lists the primitives as they were registered then; each must still be.
 * And each primitive's errors have design.md D8's form ("Messages", plan.md M14-07): called with
 * each argument in turn of a wrong type or out of range, or with the wrong number of arguments,
 * the message names it, the argument's position, and the argument, which is the irritant.
 */
public class PrimitiveRegistryTest {

    private static List<PrimitiveSpec> primitives() {
        PrimitiveRegistryTest.class.getModule().addUses(BuiltinLibrary.class);
        List<PrimitiveSpec> all = new ArrayList<>();
        for (BuiltinLibrary library : ServiceLoader.load(BuiltinLibrary.class, BuiltinLibrary.class.getClassLoader())) {
            all.addAll(library.primitives());
        }
        return all;
    }

    /** What primitives.txt has for a primitive: name, arity, returnsValue, allocates, node class. */
    private static String describe(PrimitiveSpec spec) {
        return spec.name() + " " + spec.minArgs() + " " + spec.maxArgs() + " " + spec.returnsValue() + " " + spec.allocates() + " " + spec.factory().get().getClass().getName();
    }

    @Test
    public void everyPrimitiveIsRegisteredAsBefore() throws IOException {
        Map<String, String> now = new HashMap<>();
        for (PrimitiveSpec spec : primitives()) {
            assertFalse("registered twice: " + spec.name(), now.containsKey(spec.name()));
            now.put(spec.name(), describe(spec));
        }
        int before = 0;
        try (InputStream in = PrimitiveRegistryTest.class.getResourceAsStream("primitives.txt")) {
            assertNotNull("primitives.txt", in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty() || line.startsWith("# ")) {
                    continue;
                }
                String name = line.substring(0, line.indexOf(' '));
                assertEquals(name, line, now.get(name));
                before++;
            }
        }
        assertEquals(283, before);
    }

    /** A primitive's factory makes a new node each time: a call site runs a node of its own. */
    @Test
    public void everyPrimitiveMakesANewNode() {
        for (PrimitiveSpec spec : primitives()) {
            PrimitiveNode node = spec.factory().get();
            assertNotNull(spec.name(), node);
            assertNotSame(spec.name(), node, spec.factory().get());
            assertNotNull(spec.name(), spec.createNode());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Errors in design.md D8's form ("Messages")

    private static final String SCHEME = "scheme";

    /**
     * A valid call of every primitive, {@code name (call)}, or why it has none, {@code name -- why}.
     * A %-primitive is called through the public procedure that calls it (io.scm's, member's,
     * (graalvm polyglot)'s renames, a record type's procedures), whose name its messages give. An
     * argument {@code (any e)} is {@code e}, and is not replaced: it takes any value, or a wrong one
     * is another procedure's error ({@code apply}'s arguments are its procedure's).
     */
    private static final String CALLS = """
                    + (+ 1 2)
                    - (- 1 2)
                    * (* 1 2)
                    / (/ 1 2)
                    = (= 1 2)
                    < (< 1 2)
                    > (> 1 2)
                    <= (<= 1 2)
                    >= (>= 1 2)
                    number? (number? 1)
                    complex? (complex? 1)
                    real? (real? 1)
                    rational? (rational? 1)
                    integer? (integer? 1)
                    exact-integer? (exact-integer? 1)
                    exact? (exact? 1)
                    inexact? (inexact? 1)
                    finite? (finite? 1)
                    infinite? (infinite? 1)
                    nan? (nan? 1)
                    zero? (zero? 1)
                    positive? (positive? 1)
                    negative? (negative? 1)
                    odd? (odd? 1)
                    even? (even? 1)
                    max (max 1 2)
                    min (min 1 2)
                    abs (abs 1)
                    square (square 1)
                    quotient (quotient 7 2)
                    remainder (remainder 7 2)
                    modulo (modulo 7 2)
                    truncate-quotient (truncate-quotient 7 2)
                    truncate-remainder (truncate-remainder 7 2)
                    floor-quotient (floor-quotient 7 2)
                    floor-remainder (floor-remainder 7 2)
                    truncate/ (truncate/ 7 2)
                    floor/ (floor/ 7 2)
                    gcd (gcd 4 6)
                    lcm (lcm 4 6)
                    numerator (numerator 1/2)
                    denominator (denominator 1/2)
                    floor (floor 1.5)
                    ceiling (ceiling 1.5)
                    truncate (truncate 1.5)
                    round (round 1.5)
                    rationalize (rationalize 1/3 1/100)
                    exp (exp 1)
                    log (log 8 2)
                    sin (sin 1)
                    cos (cos 1)
                    tan (tan 1)
                    asin (asin 1)
                    acos (acos 1)
                    atan (atan 1 2)
                    sqrt (sqrt 4)
                    exact-integer-sqrt (exact-integer-sqrt 4)
                    expt (expt 2 3)
                    make-rectangular (make-rectangular 1 2)
                    make-polar (make-polar 1 2)
                    real-part (real-part 1+2i)
                    imag-part (imag-part 1+2i)
                    magnitude (magnitude 1+2i)
                    angle (angle 1+2i)
                    exact (exact 1.5)
                    inexact (inexact 1)
                    exact->inexact (exact->inexact 1)
                    inexact->exact (inexact->exact 1.5)
                    number->string (number->string 10 2)
                    string->number (string->number "10" 2)
                    symbol? (symbol? 'a)
                    symbol=? (symbol=? 'a 'b)
                    symbol->string (symbol->string 'a)
                    string->symbol (string->symbol "a")
                    char? (char? #\\a)
                    char=? (char=? #\\a #\\b)
                    char<? (char<? #\\a #\\b)
                    char>? (char>? #\\a #\\b)
                    char<=? (char<=? #\\a #\\b)
                    char>=? (char>=? #\\a #\\b)
                    char-ci=? (char-ci=? #\\a #\\b)
                    char-ci<? (char-ci<? #\\a #\\b)
                    char-ci>? (char-ci>? #\\a #\\b)
                    char-ci<=? (char-ci<=? #\\a #\\b)
                    char-ci>=? (char-ci>=? #\\a #\\b)
                    char-alphabetic? (char-alphabetic? #\\a)
                    char-numeric? (char-numeric? #\\a)
                    char-whitespace? (char-whitespace? #\\a)
                    char-upper-case? (char-upper-case? #\\a)
                    char-lower-case? (char-lower-case? #\\a)
                    digit-value (digit-value #\\1)
                    char->integer (char->integer #\\a)
                    char-upcase (char-upcase #\\a)
                    char-downcase (char-downcase #\\a)
                    char-foldcase (char-foldcase #\\a)
                    integer->char (integer->char 97)
                    string? (string? "a")
                    make-string (make-string 2 #\\a)
                    string (string #\\a #\\b)
                    string-length (string-length "abc")
                    string-ref (string-ref "abc" 1)
                    string-set! (string-set! (make-string 3) 1 #\\a)
                    string=? (string=? "a" "b")
                    string<? (string<? "a" "b")
                    string>? (string>? "a" "b")
                    string<=? (string<=? "a" "b")
                    string>=? (string>=? "a" "b")
                    string-ci=? (string-ci=? "a" "b")
                    string-ci<? (string-ci<? "a" "b")
                    string-ci>? (string-ci>? "a" "b")
                    string-ci<=? (string-ci<=? "a" "b")
                    string-ci>=? (string-ci>=? "a" "b")
                    string-upcase (string-upcase "a")
                    string-downcase (string-downcase "a")
                    string-foldcase (string-foldcase "a")
                    substring (substring "abc" 1 2)
                    string-copy (string-copy "abc" 1 2)
                    string-append (string-append "a" "b")
                    string->list (string->list "abc" 1 2)
                    list->string (list->string (list #\\a))
                    string-copy! (string-copy! (make-string 3) 1 "abc" 1 2)
                    string-fill! (string-fill! (make-string 3) #\\a 1 2)
                    cons (cons 1 2)
                    car (car (cons 1 2))
                    cdr (cdr (cons 1 2))
                    set-car! (set-car! (cons 1 2) (any 3))
                    set-cdr! (set-cdr! (cons 1 2) (any 3))
                    null? (null? 1)
                    pair? (pair? 1)
                    append (append (list 1) (list 2))
                    vector (vector 1 2)
                    vector? (vector? 1)
                    make-vector (make-vector 2 0)
                    vector-length (vector-length (vector 1))
                    vector-ref (vector-ref (vector 1 2) 1)
                    vector-set! (vector-set! (vector 1 2) 1 0)
                    list->vector (list->vector (list 1))
                    list? (list? 1)
                    make-list (make-list 2 0)
                    list-tail (list-tail (list 1 2 3) 1)
                    list-ref (list-ref (list 1 2 3) 1)
                    list-set! (list-set! (list 1 2 3) 1 0)
                    list-copy (list-copy (list 1))
                    length (length (list 1))
                    reverse (reverse (list 1))
                    memq (memq 1 (list 1 2))
                    memv (memv 1 (list 1 2))
                    %member (member 1 (list 1 2))
                    assq (assq 1 (list (cons 1 2)))
                    assv (assv 1 (list (cons 1 2)))
                    %assoc (assoc 1 (list (cons 1 2)))
                    %circular-list? -- map's and for-each's test, of any object
                    vector->list (vector->list (vector 1 2 3) 1 2)
                    vector->string (vector->string (vector #\\a #\\b #\\c) 1 2)
                    string->vector (string->vector "abc" 1 2)
                    vector-copy (vector-copy (vector 1 2 3) 1 2)
                    vector-copy! (vector-copy! (make-vector 3 0) 1 (vector 1 2 3) 1 2)
                    vector-append (vector-append (vector 1) (vector 2))
                    vector-fill! (vector-fill! (make-vector 3 0) 1 1 2)
                    bytevector? (bytevector? 1)
                    bytevector (bytevector 1 2)
                    make-bytevector (make-bytevector 2 0)
                    bytevector-length (bytevector-length (bytevector 1))
                    bytevector-u8-ref (bytevector-u8-ref (bytevector 1 2) 1)
                    bytevector-u8-set! (bytevector-u8-set! (bytevector 1 2) 1 0)
                    bytevector-copy (bytevector-copy (bytevector 1 2 3) 1 2)
                    bytevector-copy! (bytevector-copy! (make-bytevector 3 0) 1 (bytevector 1 2 3) 1 2)
                    bytevector-append (bytevector-append (bytevector 1) (bytevector 2))
                    utf8->string (utf8->string (bytevector 97 98 99) 1 2)
                    string->utf8 (string->utf8 "abc" 1 2)
                    eq? (eq? 1 2)
                    eqv? (eqv? 1 2)
                    equal? (equal? 1 2)
                    not (not 1)
                    boolean? (boolean? 1)
                    boolean=? (boolean=? #t #f)
                    procedure? (procedure? 1)
                    values (values 1 2)
                    %values->list -- call-with-values's, of its producer's values
                    apply (apply + (any 1) (list 2))
                    call/cc (call/cc (lambda (k) 1))
                    call-with-current-continuation (call-with-current-continuation (lambda (k) 1))
                    %capture-to-prompt -- the prelude's, of a prompt tag and a procedure it made
                    %torture-captures -- a counter for tests, no arguments
                    %spills -- a counter for tests, no arguments
                    %in-place-resumes -- a counter for tests, no arguments
                    %copied-resumes -- a counter for tests, no arguments
                    %stack-probes -- a counter for tests, no arguments
                    %apply-continuation -- the prelude's %continue, of a continuation it was called as
                    %reinstate -- the prelude's %continue, of a continuation it was called as
                    %continuation-composable? -- the prelude's %continue, of a continuation it was called as
                    %continuation-dynamic-env -- the prelude's %continue, of a continuation it was called as
                    %error -- case-lambda's, of any message and irritants
                    %set-receive! -- installs the prelude's procedure: replacing it would break the context
                    %set-escape! -- installs the prelude's procedure: replacing it would break the context
                    %set-unwind-root! -- installs the prelude's procedure: replacing it would break the context
                    %current-dynamic-env -- the dynamic environment, no arguments
                    %set-dynamic-env! -- the prelude's, of a dynamic environment it made: another would break the context
                    %make-dynamic-env -- the prelude's, of the parts of a dynamic environment
                    %dynamic-env? -- the prelude's test, of any object
                    %dynamic-env-winders -- the prelude's, of a dynamic environment it made
                    %dynamic-env-parameterization -- the prelude's, of a dynamic environment it made
                    %dynamic-env-handlers -- the prelude's, of a dynamic environment it made
                    %set-raise! -- installs the prelude's procedure: replacing it would break the context
                    %raise-uncaught -- raise's, of any object
                    %make-error-object -- error's, of any message and irritants
                    error-object? (error-object? 1)
                    error-object-message (error-object-message (guard (e (#t e)) (error "x" 1)))
                    error-object-irritants (error-object-irritants (guard (e (#t e)) (error "x" 1)))
                    read-error? (read-error? 1)
                    file-error? (file-error? 1)
                    %make-promise (make-promise 1)
                    promise? (promise? 1)
                    %promise-done? -- force's, of a promise
                    %promise-value -- force's, of a promise
                    %promise-update! -- force's, of promises
                    %make-record-type -- define-record-type's, of a name and fields it was given
                    %record-constructor (make-point (any 1) (any 2))
                    %record-predicate (point? 1)
                    %record-accessor (point-x (make-point 1 2))
                    %record-modifier (set-point-x! (make-point 1 2) (any 3))
                    %stdout-port -- the initial current output port, no arguments
                    %stderr-port -- the initial current error port, no arguments
                    %stdin-port -- the initial current input port, no arguments
                    port? (port? 1)
                    input-port? (input-port? 1)
                    output-port? (output-port? 1)
                    textual-port? (textual-port? 1)
                    binary-port? (binary-port? 1)
                    input-port-open? (input-port-open? (open-input-string ""))
                    output-port-open? (output-port-open? (open-output-string))
                    close-port (close-port (open-input-string ""))
                    close-input-port (close-input-port (open-input-string ""))
                    close-output-port (close-output-port (open-output-string))
                    eof-object (eof-object)
                    eof-object? (eof-object? 1)
                    open-input-string (open-input-string "a")
                    open-output-string (open-output-string)
                    get-output-string (get-output-string (open-output-string))
                    open-input-bytevector (open-input-bytevector (bytevector 1))
                    open-output-bytevector (open-output-bytevector)
                    get-output-bytevector (get-output-bytevector (open-output-bytevector))
                    open-input-file (open-input-file "no-such-file")
                    open-binary-input-file (open-binary-input-file "no-such-file")
                    open-output-file (open-output-file "no-such-file")
                    open-binary-output-file (open-binary-output-file "no-such-file")
                    file-exists? (file-exists? "no-such-file")
                    delete-file (delete-file "no-such-file")
                    %read (read (open-input-string "1"))
                    %read-char (read-char (open-input-string "a"))
                    %peek-char (peek-char (open-input-string "a"))
                    %read-line (read-line (open-input-string "a"))
                    %char-ready? (char-ready? (open-input-string "a"))
                    %read-string (read-string 1 (open-input-string "a"))
                    %read-u8 (read-u8 (open-input-bytevector (bytevector 1)))
                    %peek-u8 (peek-u8 (open-input-bytevector (bytevector 1)))
                    %u8-ready? (u8-ready? (open-input-bytevector (bytevector 1)))
                    %read-bytevector (read-bytevector 1 (open-input-bytevector (bytevector 1)))
                    %read-bytevector! (read-bytevector! (make-bytevector 3 0) (open-input-bytevector (bytevector 1)) 1 2)
                    %display (display (any 1) (open-output-string))
                    %write (write (any 1) (open-output-string))
                    %write-shared (write-shared (any 1) (open-output-string))
                    %write-simple (write-simple (any 1) (open-output-string))
                    %write-char (write-char #\\a (open-output-string))
                    %write-string (write-string "abc" (open-output-string) 1 2)
                    %write-u8 (write-u8 1 (open-output-bytevector))
                    %write-bytevector (write-bytevector (bytevector 1 2 3) (open-output-bytevector) 1 2)
                    %flush-output-port (flush-output-port (open-output-string))
                    %newline (newline (open-output-string))
                    %repl-write -- the REPL's echo, of any values
                    eval (eval (any ''1) (interaction-environment))
                    environment (environment '(scheme base))
                    interaction-environment (interaction-environment)
                    scheme-report-environment (scheme-report-environment 5)
                    null-environment (null-environment 5)
                    load (load "no-such-file" (interaction-environment))
                    emergency-exit -- of any object, which it ends the context with
                    command-line (command-line)
                    get-environment-variable (get-environment-variable "HOME")
                    get-environment-variables (get-environment-variables)
                    current-second (current-second)
                    current-jiffy (current-jiffy)
                    jiffies-per-second (jiffies-per-second)
                    features (features)
                    %polyglot-eval (polyglot-eval (any "scheme") "1")
                    %polyglot-eval-file (polyglot-eval-file (any "scheme") "no-such-file")
                    %polyglot-language-available? (polyglot-language-available? "scheme")
                    %polyglot-import (polyglot-import "array" (any #f))
                    %polyglot-export (polyglot-export "exported" (any 1))
                    %java-type (java-type "java.lang.String")
                    %foreign? (foreign? 1)
                    %foreign-null? (foreign-null? 1)
                    %foreign-ref (foreign-ref (polyglot-import "array") 0)
                    %foreign-set! (foreign-set! (polyglot-import "array") 0 (any 1))
                    %foreign-length (foreign-length (polyglot-import "array"))
                    %foreign-keys (foreign-keys (polyglot-import "object"))
                    %foreign->list (foreign->list (polyglot-import "array"))
                    %foreign->vector (foreign->vector (polyglot-import "array"))
                    %foreign-invoke (foreign-invoke (polyglot-import "object") "f")
                    %foreign-new (foreign-new (polyglot-import "array"))
                    %foreign-instance? (foreign-instance? (any 1) (polyglot-import "object"))
                    """;

    /**
     * {@code (%substitutions 'call)}: a line for each call that differs from {@code call} in one
     * argument, replaced by each of {@code %wrongs}: of a wrong type for most procedures, and out of
     * range (-1, a bignum, 0 as a divisor) for the others. Each line is the position, the wrong
     * value, the outcome ({@code ok}; {@code error}, the message and the first irritant;
     * {@code raised} and the object) and the arguments, each written, separated by tabs.
     * {@code (%arity proc n)}: the outcome of a call of {@code proc} with {@code n} arguments.
     */
    private static final String DRIVER = """
                    (define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))
                    (define %wrongs (list 'wrong #\\w -1 1.5 100000000000000000000 #t '() 0))
                    (define (%written x) (let ((p (open-output-string))) (write x p) (get-output-string p)))
                    (define (%outcome thunk)
                      (guard (e ((error-object? e)
                                 (let ((m (error-object-message e)) (irritants (error-object-irritants e)))
                                   (string-append "error\\t" (if (string? m) m (%written m))
                                                  "\\t" (if (pair? irritants) (%written (car irritants)) ""))))
                                (#t (string-append "raised\\t" (%written e) "\\t")))
                        (thunk)
                        "ok\\t\\t"))
                    (define (%any? a) (and (pair? a) (eq? (car a) 'any)))
                    (define (%substitutions call)
                      (let* ((args (cdr call))
                             (proc (eval (car call) (interaction-environment)))
                             (thunks (eval (cons 'list (map (lambda (a) (list 'lambda '() (if (%any? a) (cadr a) a))) args))
                                           (interaction-environment)))
                             (out (open-output-string)))
                        (let loop ((i 1) (as args))
                          (if (pair? as)
                              (begin
                                (if (not (%any? (car as)))
                                    (for-each
                                     (lambda (w)
                                       (let* ((actual (let fill ((k 1) (ts thunks))
                                                        (if (pair? ts) (cons (if (= k i) w ((car ts))) (fill (+ k 1) (cdr ts))) '())))
                                              (written (map %written actual))
                                              (outcome (%outcome (lambda () (apply proc actual)))))
                                         (write-string (number->string i) out)
                                         (write-string "\\t" out)
                                         (write-string (%written w) out)
                                         (write-string "\\t" out)
                                         (write-string outcome out)
                                         (for-each (lambda (a) (write-string "\\t" out) (write-string a out)) written)
                                         (newline out)))
                                     %wrongs))
                                (loop (+ i 1) (cdr as)))))
                        (get-output-string out)))
                    (define (%arity proc n) (%outcome (lambda () (apply proc (make-list n 0)))))
                    """;

    private static final Pattern ARGUMENT = Pattern.compile("^(.*?): argument (\\d+) (is not |has an element that is not )");

    @Test
    public void everyPrimitiveNamesItsArgumentInItsErrors() {
        Map<String, String> calls = new LinkedHashMap<>();
        Map<String, String> reasons = new HashMap<>();
        for (String line : CALLS.split("\n")) {
            String name = line.substring(0, line.indexOf(' '));
            String rest = line.substring(name.length() + 1);
            assertFalse("listed twice: " + name, calls.containsKey(name) || reasons.containsKey(name));
            if (rest.startsWith("-- ")) {
                reasons.put(name, rest.substring(3));
            } else {
                calls.put(name, rest);
            }
        }
        Map<String, PrimitiveSpec> registry = new LinkedHashMap<>();
        for (PrimitiveSpec spec : primitives()) {
            registry.put(spec.name(), spec);
            assertTrue("no call listed for " + spec.name(), calls.containsKey(spec.name()) || reasons.containsKey(spec.name()));
        }
        for (String name : calls.keySet()) {
            assertTrue("listed, not registered: " + name, registry.containsKey(name));
        }
        List<String> failures = new ArrayList<>();
        int errors = 0;
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream()).allowPolyglotAccess(PolyglotAccess.ALL).build()) {
            context.getPolyglotBindings().putMember("array", ProxyArray.fromArray(1, 2, 3));
            Map<String, Object> members = new HashMap<>();
            members.put("f", (ProxyExecutable) arguments -> 1);
            context.getPolyglotBindings().putMember("object", ProxyObject.fromMap(members));
            // In the interaction environment, where the thunks are evaluated: text that starts with
            // an import is a program of its own (design.md D15).
            context.eval(SCHEME, "#t (import (graalvm polyglot) (r7rs %primitives))");
            context.eval(SCHEME, DRIVER);
            for (Map.Entry<String, String> entry : calls.entrySet()) {
                String call = entry.getValue();
                String who = call.substring(1, call.indexOf(' ') > 0 ? call.indexOf(' ') : call.length() - 1);
                String lines = context.eval(SCHEME, "(%substitutions '" + call + ")").asString();
                for (String line : lines.split("\n")) {
                    if (!line.isEmpty() && checkSubstitution(entry.getKey(), who, line, failures)) {
                        errors++;
                    }
                }
            }
            // The wrong number of arguments, of every primitive: named as the program calls it.
            for (PrimitiveSpec spec : registry.values()) {
                String who = spec.name().startsWith("%") ? spec.name().substring(1) : spec.name();
                List<Integer> counts = new ArrayList<>();
                if (spec.maxArgs() >= 0) {
                    counts.add(spec.maxArgs() + 1);
                }
                if (spec.minArgs() > 0) {
                    counts.add(spec.minArgs() - 1);
                }
                for (int n : counts) {
                    String outcome = context.eval(SCHEME, "(%arity " + spec.name() + " " + n + ")").asString();
                    String[] f = outcome.split("\t", -1);
                    boolean one = spec.minArgs() == 1 && (spec.maxArgs() == 1 || spec.maxArgs() < 0);
                    if (!f[0].equals("error") || !f[1].startsWith(who + ": expected ") || !f[1].endsWith(" argument" + (one ? "" : "s") + ", got " + n)) {
                        failures.add(spec.name() + " with " + n + " arguments: " + outcome);
                    }
                }
            }
        }
        assertTrue(failures.size() + " failures:\n" + String.join("\n", failures), failures.isEmpty());
        // Most calls with a wrong argument fail (all but the predicates' and those of any object).
        assertTrue("errors: " + errors, errors > 1000);
    }

    /**
     * One substitution's line ({@code DRIVER}'s {@code %substitutions}): an error names an argument.
     * Whether the call failed.
     */
    private static boolean checkSubstitution(String name, String who, String line, List<String> failures) {
        String[] f = line.split("\t", -1);
        String kind = f[2];
        if (kind.equals("ok")) {
            return false;
        }
        String where = name + ", argument " + f[0] + " " + f[1] + ": ";
        if (!kind.equals("error")) {
            failures.add(where + "raised a non-error " + f[3]);
            return true;
        }
        String message = f[3];
        String irritant = f[4];
        List<String> arguments = List.of(f).subList(5, f.length);
        Matcher m = ARGUMENT.matcher(message);
        if (!m.find() || !m.group(1).equals(who)) {
            failures.add(where + "names no argument of " + who + ": " + message + ": " + irritant);
            return true;
        }
        int position = Integer.parseInt(m.group(2));
        if (position < 1 || position > arguments.size()) {
            failures.add(where + "names an argument it hasn't: " + message);
        } else if (m.group(3).startsWith("is") && !irritant.equals(arguments.get(position - 1))) {
            failures.add(where + "its irritant isn't argument " + position + ", " + arguments.get(position - 1) + ": " + message + ": " + irritant);
        }
        return true;
    }
}
