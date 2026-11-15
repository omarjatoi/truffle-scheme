# Differential programs

Every `*.scm` here must give the same stdout and exit status under
chibi-scheme and under our implementation. `mx r7rs-diff-all` checks them all,
and the gate runs it. Add freely (AGENTS.md "Working style"): a program that
already matches is a regression test.
