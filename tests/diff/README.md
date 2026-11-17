# Differential programs

Every `*.scm` here must give the same stdout and exit status under
chibi-scheme and under our implementation. `bazel test //tests:diff` checks them all
(normally, under torture and under owned torture). Add freely (AGENTS.md "Working style"): a program that
already matches is a regression test.
