;; The control features and exceptions area's declarations in (r7rs %prelude) (AGENTS.md "Areas"): what it
;; exports, and its Scheme file. The standard libraries re-export these names.
(export
 dynamic-wind call-with-values %rewind %receive %continue with-exception-handler raise
 raise-continuable error guard make-parameter parameterize)
(include "control.scm")
