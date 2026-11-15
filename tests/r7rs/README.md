# Vendored R7RS conformance suite

Read-only (AGENTS.md). License: chibi-scheme's BSD-3-Clause, in `COPYING`,
which `mx r7rs-vendor-chibi` copies along with the tests. These files are chibi-scheme 0.12's
`tests/r7rs-tests.scm`, split into one file per top-level section by
`mx r7rs-vendor-chibi`. Each file gets the original `import` form and a
provenance header; the section bodies are unchanged.

- `<section>.scm`: one section, e.g. `6.10.scm` for "6.10 Control Features".
  Nested groups ("Read syntax", "Numeric syntax") stay inside 6.13.
- `sections.tsv`: section id, title, and the number of tests chibi-scheme
  reports for that section. `mx r7rs-status` uses it as the denominator.

The tests import `(chibi test)`. Our portable shim for it lives in
`tests/lib/chibi/test.sld` (not read-only). Vendoring runs every section under
chibi-scheme with that shim and aborts unless chibi passes all of a section's
tests and reaches its end, which checks both the split and the shim.

To re-vendor (for example after a chibi-scheme bump in `flake.lock`):

```
mx r7rs-vendor-chibi $(nix build --no-link --print-out-paths --inputs-from . nixpkgs#chibi.src)/tests/r7rs-tests.scm --version <chibi version>
```
