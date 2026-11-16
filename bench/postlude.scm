;;; Our postlude for the vendored benchmark sets (bench/README.md). `mx benchmark` puts it where
;;; ecraven's bench script puts an implementation's postlude: after the program and the suite's
;;; src/common.scm, before the call that runs the benchmark. Before it, the driver defines
;;; %r7rs-bench-implementation and %r7rs-bench-iterations. A count other than the program's own
;;; (`--count`) is the driver's business: it rewrites the count in the program's .input file.

;; ecraven's common.scm reports each result under this name (each implementation's prelude or
;; postlude defines it).
(define (this-scheme-implementation-name) %r7rs-bench-implementation)

;; Runs the suite's own harness, common.scm's run-r7rs-benchmark (time `count` runs of the thunk,
;; then check the last result), %r7rs-bench-iterations times in this one process, so that the
;; later iterations measure compiled code. Each iteration prints the harness's "Elapsed time"
;; line, or its ERROR line when the check fails.
(set! run-r7rs-benchmark
      (let ((run-once run-r7rs-benchmark))
        (lambda (name count thunk ok?)
          (let loop ((i 1) (result #f))
            (if (> i %r7rs-bench-iterations)
                result
                (loop (+ i 1) (run-once name count thunk ok?)))))))
