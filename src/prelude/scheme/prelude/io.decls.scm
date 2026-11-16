;; The input and output area's declarations in (r7rs %prelude) (AGENTS.md "Areas"): what it
;; exports, and its Scheme file. The standard libraries re-export these names.
(export
 current-input-port current-output-port current-error-port
 read read-char peek-char read-line char-ready? read-string read-u8 peek-u8 u8-ready?
 read-bytevector read-bytevector!
 display write write-shared write-simple newline write-char write-u8 flush-output-port write-string
 write-bytevector
 call-with-port call-with-input-file call-with-output-file with-input-from-file with-output-to-file)
(include "io.scm")
