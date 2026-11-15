;; The input and output area's declarations in (r7rs %prelude) (AGENTS.md "Areas"): what it
;; exports, and its Scheme file. The standard libraries re-export these names.
(export
 current-output-port display write write-shared write-simple newline write-char flush-output-port
 write-string)
(include "io.scm")
