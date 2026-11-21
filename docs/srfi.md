# SRFIs

The survey behind design.md D21: every final SRFI, what we do with it, and
the groups a milestone of SRFI tasks is drawn from. D21 holds the decisions
(scope, names, where the libraries live, loading, testing); this file is
the per-SRFI detail.

**Sources** (2026-11-21):

- Status, titles and library names: the SRFI editors' `admin/srfi-data.scm`
  in `scheme-requests-for-implementation/srfi-common` (commit `99b65c821b79`,
  2026-11-17), the data srfi.schemers.org's index is built from: 290 SRFIs,
  216 final, 61 withdrawn, 13 draft. Drafts (266, 268, 276, 280-289) and
  withdrawn SRFIs are out of scope (the maintainer: "all the SRFIs that are
  confirmed (not the draft ones)"); a draft is surveyed when it is
  finalized.
- Superseded: no final SRFI is marked superseded (the site's "Superseded"
  keyword is on withdrawn ones only: 40, 114, 121, 122, 124, 142, 159, 186,
  246). Class c below is our reading of each SRFI's text ("based on",
  "to supersede", a revision by the same author).
- Names: SRFI 97's table, SRFI 261's table, and srfi-data.scm's
  `library-name` (D21, "Library names").
- Implementations and tests: each SRFI's repository,
  `scheme-requests-for-implementation/srfi-<N>`, at its default branch.
  "Upstream tests" means the repository has a test file. Licences: every
  SRFI document and sample implementation is under the SRFI process's
  MIT-style licence (the SRFI FAQ: a sample implementation, or a
  derivative, may be used in an implementation), except a few older files
  with their author's own permissive notice (SRFI 1's and 13's by Olin
  Shivers: use as you please, keeping the notice). GitHub detects a
  licence file in 14 repositories only, because the others carry it in
  the HTML; each file's header is checked when it is vendored.
- chibi: "yes" where chibi-scheme 0.12 (`@chibi_src`, the differential
  tests' reference) has `lib/srfi/<N>.sld`; it also has withdrawn 33, 121,
  124, 142, 154 and 159.

**Classes.**

- **a**: implement, as a library. **a, after threads**: the same, in the
  threads group, which waits for a threads decision of a later milestone
  (D21, "Threads").
- **b**: R7RS-small provides it; the library name re-exports the standard
  bindings (13 libraries), or there is no library (0, 30, 62: syntax).
- **c**: superseded by a later final SRFI we implement; provided anyway,
  as a re-exporting library where the later one covers it (4, 25, 28, 47,
  60, 69) or as its own where they are incompatible (43, 179).
- **d**: not applicable, with the reason.

| Class | Count | SRFIs |
|---|---:|---|
| a | 172 | below |
| a, after threads | 5 | 18, 21, 120, 216, 230 (and 226's thread libraries) |
| b | 17 | 0, 6, 9, 11, 16, 23, 30, 34, 38, 39, 45, 46, 62, 87, 98, 211 (`syntax-rules` only), 244 |
| c | 8 | 4, 25, 28, 43, 47, 60, 69, 179 |
| d | 14 | 7, 10, 22, 70, 72, 88, 89, 90, 96, 97, 138, 203, 213, 261 |
| Total | 216 | |

**Kind** (what an implementation needs): S Scheme only (a sample
implementation ported, or `syntax-rules`); J Java primitives too (`%`-names
of `(r7rs %primitives)`, renamed on export as D20's are); T runtime support
(a value representation or the record, closure or continuation machinery);
X the expander; R the reader; L the launcher.

**Interactions** that the tasks must test (D21, "Testing"):

- *Continuations*: 158's `make-coroutine-generator` and 190 capture a
  continuation per value; 226 and 248 expose D4's prompts; 41 and 127 build
  on promises. The torture modes run every suite.
- *Tail calls and space*: 41, 127, 158 and 171 promise iteration in
  constant space (rule 5).
- *The expander*: 139, 147, 148, 149, 188, 206, 212 and 251 change it; 42,
  57, 100 and 265 are large macros; 201, 202, 226, 237, 239, 240, 241, 255
  and 265 have `syntax-case` sample implementations, which need a
  `syntax-rules` (or Java) port, since `syntax-case` is excluded.
- *Rule 1*: hash tables with user hash functions (125), sorting (132),
  custom ports (181) and 17's setters call Scheme procedures, so their
  cores are Scheme, or the Java side returns to Scheme.
- *Redefined standard names*: 5, 17, 61, 63, 71, 101, 219 export their own
  `let`, `set!`, `cond`, `equal?`, `pair?`, `define`; a program imports them
  with `except` or `rename` on `(scheme base)`, as R7RS §5.2 requires.
- *R7RS-large*: none of `(scheme list)`, `(scheme comparator)`, ... is
  provided; a sample implementation that imports one is rewritten to
  `(srfi N)`.

## Groups and order

Each group is one area (AGENTS.md "Areas"), owning its directory of
`src/srfi/` and its Java file; a milestone task takes a few SRFIs of one
group. Groups run in parallel once their dependencies are in. The SRFIs
in a group are listed in a workable order; `(c)` marks a re-exporting
library of a superseded SRFI.

| Group | SRFIs | Depends on |
|---|---|---|
| INF: infrastructure | Name resolution and the `src/srfi` project (D21), `vendor-srfi`, the test targets, the docs' list; then the R7RS re-exports: 6, 9, 11, 16, 23, 34, 38, 39, 45, 46, 87, 98, 244 | — (first) |
| TEST: testing | 64, 78 (`check-ec` after 42), 269, 252 | INF; 252 after 158, 194 |
| LIST: lists | 1, 117, 116, 101, 132, 95, 134, 274 | INF, TEST; 274 after 133, 158, 160 |
| SYN: syntax libraries | 8, 2, 26, 31, 145, 156, 236, 232, 51, 86, 227, 219, 5, 61, 71, 17, 123, 197, 210, 42, 239, 257, 247, 241, 201, 202, 265 | INF, TEST; 123 after 17 |
| HASH: comparators and tables | 128 with 162, 125, 69 (c), 126, 250, 228, 67, 209, 113, 146, 153, 217, 224, 225 | INF, TEST; 217, 224 after 143, 151; 225 last |
| GEN: generators and streams | 158 with 221, 41, 127, 171, 196, 190 | INF, TEST; 190 after 139 |
| NUM: numbers and bits | 151, 60 (c), 141, 143, 144, 208, 278, 94, 27, 194 | INF, TEST; 194 after 158 |
| VEC: vectors and bytevectors | 133, 43 (c), 160, 4 (c), 66, 74, 207, 178, 214, 223 | INF, TEST; 178 after 151 |
| STR: strings and text | 14, 129, 175, 13 with 130 and 152, 135, 115, 264, 140, 118 with 185 | INF, TEST |
| FMT: formatting | 48, 28 (c), 54, 272, 165, 166 | INF, TEST; 48's `~y` after 272 |
| REC: records, conditions, boxes | 99 (runtime), 131, 136, 137, 150, 237, 240, 57, 100, 35, 36, 255, 222, 111 with 195, 161, 189, 229, 259 | INF, TEST |
| EXP: expander | 139, 188, 212, 206, 147, 148, 149, 251, 211's `syntax-rules` re-export | INF, TEST; 251 after its D14 decision |
| CTL: control | 226 (but its thread libraries), 248 | A design decision for 226 first (D21, "Control") |
| IO: ports, OS, time | 193, 112, 176, 174, 19, 170, 238 (MISC), 29, 59, 180, 233, 275, 106, then 181 with 192, 271, 277 | INF, TEST; 181 after its rule-1 decision |
| ARR: arrays | 231, 179 (c), 63, 47 (c), 164, 25 (c) | VEC's 160 |
| RDR: reader syntax | 169, 267, 270, 207's `#u8"..."`, 160's `#s8(...)`, 58, 163, 105, 108, 107, 109, 49, 110, 119 | The libraries they read into (58 after 63, 163 after 164, 107 and 109 after 108) |
| MISC | 37, 172, 173, 215, 234, 235, 253, 273, 263, 279, 258, 260, 44, 55, 167, 168, 254 | INF, TEST; 167, 168 after 128, 146, 158, 173 |
| THR: threads | 18, 21, 226's threads, thread locals, interrupts, mutexes and condition variables, 230, 120, 216 | The threads decision of a later milestone |

## Library-name collisions

A name more than one final SRFI has (marked ² below) is no `(srfi <name>)`
alias (D21): `and-let*` (2, 202), `arrays` (47, 63), `comparators` (128,
162), `intervals-and-arrays` (179, 231), `let` (5, 71), `records` (9, 57,
99, 131, 136, 240), `strings` (13, 140, 152), `tagged-procedures` (229,
259), `vectors` (43, 133). SRFI 261 gives 237 the name `combinators`,
which is 235's: an erratum (237 is R6RS records), so 237 has no name.

## The survey

| SRFI | Title | Class | Group | Kind | SRFI 97/261 names | chibi | Upstream tests | Notes |
|---:|---|---|---|---|---|---|---|---|
| 0 | Feature-based conditional expansion construct | b | INF | — | — |  |  | R7RS §4.2.1's `cond-expand`, a core form; no library (SRFI 261 omits it). See D21, "Feature identifiers". |
| 1 | List Library | a | LIST | S | `lists` | yes |  | Sample implementation (Shivers, permissive notice). R7RS's `map`, `for-each`, `member`, `assoc`, `list-copy`, `make-list` already take SRFI 1's arguments: re-exported, not redefined. |
| 2 | AND-LET*: an AND with local bindings, a guarded LET* special form | a | SYN | S | `and-let*`² | yes |  | `and-let*`, `syntax-rules`. |
| 4 | Homogeneous numeric vector datatypes | c | VEC | S | `numeric-vectors` |  | yes | Superseded by 160 ("based on SRFI 4"): `(srfi 4)` re-exports 160's `@vector` basics. Its `#s8(...)` syntax is 160's. |
| 5 | A compatible let form with signatures and rest arguments | a | SYN | S | `let`² |  |  | Exports its own `let`; a program excludes `(scheme base)`'s. |
| 6 | Basic String Ports | b | INF | — | `basic-string-ports` | yes |  | R7RS's string ports; re-export. |
| 7 | Feature-based program configuration language | d | — | — | — |  |  | A configuration language distinct from Scheme (SRFI 261); R7RS programs and `define-library` replace it. |
| 8 | receive: Binding to multiple values | a | SYN | S | `receive` | yes |  | `receive`, `syntax-rules`. |
| 9 | Defining Record Types | b | INF | — | `records`² | yes |  | R7RS's `define-record-type`; re-export. |
| 10 | #, external form | d | — | — | — |  |  | `#,(tag datum ...)` calls a Scheme constructor from `define-reader-ctor`'s table while reading: the reader is a Java leaf (D5) and can't call Scheme, and the table is global reader state. |
| 11 | Syntax for receiving multiple values | b | INF | — | `let-values` | yes |  | R7RS's `let-values`, `let*-values`; re-export. |
| 13 | String Libraries | a | STR | S | `strings`² |  |  | Sample implementation (Shivers). Shares its kernel with 130 and 152; its `string-map`, `string-for-each` and `string-copy!` signatures differ from R7RS's, so those are its own bindings. |
| 14 | Character-set Library | a | STR | J | `char-sets` | yes | yes | Char-sets over the JDK's Unicode tables (D9): a Java value (an inversion list) and primitives; the set algebra in Scheme. Upstream tests. |
| 16 | Syntax for procedures of variable arity | b | INF | — | `case-lambda` | yes |  | `(scheme case-lambda)`; re-export. |
| 17 | Generalized set! | a | SYN | S | `generalized-set!` |  |  | Exports its own `set!` (a `syntax-rules` over the core form) and `setter`, whose table of setters is keyed on procedures (`eq?`). |
| 18 | Multithreading support | a, after threads | THR | J | `multithreading` | yes |  | Threads, mutexes, condition variables; time objects shared with 19 and 226. |
| 19 | Time Data Types and Procedures | a | IO | J | `time` |  | yes | Time and date types: Scheme over a primitive for the current time with nanoseconds and the zone offset (java.time); the leap-second table is the sample implementation's. Upstream test suite. |
| 21 | Real-time multithreading support | a, after threads | THR | J | `real-time-multithreading` |  |  | Extends 18 with priorities and real-time scheduling. |
| 22 | Running Scheme Scripts on Unix | d | — | — | — |  |  | Interpreter names for R4RS, R5RS and IEEE Scheme and a `main` entry point; R7RS programs and 193's `command-line` replace them. Skipping a script's `#!` first line is a launcher matter (Follow-ups). |
| 23 | Error reporting mechanism | b | INF | — | `error` | yes |  | R7RS's `error`; re-export. |
| 25 | Multi-dimensional Array Primitives | c | ARR | S | `multi-dimensional-arrays` |  | yes | Superseded by 164, which extends it: `(srfi 25)` re-exports 164's SRFI 25 names. |
| 26 | Notation for Specializing Parameters without Currying | a | SYN | S | `cut` | yes | yes | `cut`, `cute`; sample implementation and its checks. |
| 27 | Sources of Random Bits | a | NUM | J | `random-bits` | yes | yes | Random sources: the MRG32k3a generator as a Java primitive (state externalizable as the SRFI requires), the rest Scheme; `default-random-source` per context. |
| 28 | Basic Format Strings | c | FMT | S | `basic-format-strings` |  |  | Superseded by 48, whose `format` handles 28's directives: `(srfi 28)` re-exports it. |
| 29 | Localization | a | IO | S | `localization` |  |  | Bundles in Scheme; `current-language` and `current-country` default from the JVM's locale at context creation; `load-bundle!`/`store-bundle!` through `TruffleFile`. |
| 30 | Nested Multi-line Comments | b | INF | — | — |  |  | `#| |#` is R7RS lexical syntax; no library. |
| 31 | A special form `rec' for recursive evaluation | a | SYN | S | `rec` |  |  | `rec`, `syntax-rules`. |
| 34 | Exception Handling for Programs | b | INF | — | `exception-handling` |  |  | R7RS's `with-exception-handler`, `raise`, `guard`; re-export. |
| 35 | Conditions | a | REC | S | `conditions` | yes |  | Condition types over records; `condition?` and the `&error`/`&message` types also recognize D8's error objects (decided in the task). |
| 36 | I/O Conditions | a | REC | S | — |  |  | I/O condition types after 35; D8's file and read errors are instances. |
| 37 | args-fold: a program argument processor | a | MISC | S | `args-fold` |  |  | `args-fold`; sample implementation. |
| 38 | External Representation for Data With Shared Structure | b | INF | — | `with-shared-structure` | yes |  | R7RS's `write` and `read` handle datum labels: `(srfi 38)` renames them (`write-with-shared-structure`, `write/ss`, `read-with-shared-structure`, `read/ss`). |
| 39 | Parameter objects | b | INF | — | `parameters` | yes |  | R7RS's `make-parameter`, `parameterize`; re-export. |
| 41 | Streams | a | GEN | S | `streams` | yes | yes | Streams over R7RS's `delay-force` (= SRFI 45's `lazy`); sublibraries `(srfi 41 primitive)`, `(srfi 41 derived)`. Must run in constant space (rule 5): `//tests:space` cases. |
| 42 | Eager Comprehensions | a | SYN | S | `eager-comprehensions` |  |  | Eager comprehensions: a large `syntax-rules` sample implementation, a test of the expander's speed on deep macros. |
| 43 | Vector library | c | VEC | S | `vectors`² |  |  | Superseded by 133, which is incompatible (43's `vector-map` passes the index): provided anyway, from its sample implementation, since code still uses it. |
| 44 | Collections | a | MISC | S | `collections` |  | yes | Collections: the sample implementation is on Tiny-CLOS; ported onto records with a small dispatch. Upstream test suite. |
| 45 | Primitives for Expressing Iterative Lazy Algorithms | b | INF | S | `lazy` |  |  | R7RS's `delay`, `force`, `make-promise`, `delay-force` (= `lazy`): `(srfi 45)` renames `delay-force` to `lazy` and defines `eager`, which always makes a new promise. |
| 46 | Basic Syntax-rules Extensions | b | INF | — | `syntax-rules` | yes |  | R7RS's `syntax-rules` has custom ellipses and tail patterns; re-export. |
| 47 | Array | c | ARR | S | `arrays`² |  |  | Superseded by 63 ("to supersede SRFI-47"): `(srfi 47)` re-exports 63's names it shares, if they agree (checked in the task). |
| 48 | Intermediate Format Strings | a | FMT | S | `intermediate-format-strings` |  | yes | `format` (Ken Dickey's implementation); `~w` is our `write`, `~y` the pretty printer of 272. Upstream tests. |
| 49 | Indentation-sensitive syntax | a | RDR | R | — |  |  | Indentation-sensitive syntax (I-expressions): a reader mode a `#!` directive turns on for the rest of the port, never on by default. |
| 51 | Handling rest list | a | SYN | S | `rest-values` |  |  | Rest-list helpers, `syntax-rules`. |
| 54 | Formatting | a | FMT | S | `cat` |  |  | `cat`; sample implementation. |
| 55 | require-extension | a | MISC | X | `require-extension` | yes |  | `require-extension` expands into `import` where an import may appear (the REPL, `eval`, a program's import declarations), as chibi's does. |
| 57 | Records | a | REC | S | `records`² |  |  | Labelled records with polymorphism: a large `syntax-rules` sample implementation. |
| 58 | Array Notation | a | RDR | R | — |  |  | `#2A(...)` array literals for 63's arrays; shares one external representation with 163 (decided in the task). |
| 59 | Vicinity | a | IO | S | `vicinities` |  |  | Vicinities: path strings from the Env (home and working directories, the library path). |
| 60 | Integers as Bits | c | NUM | S | `integer-bits`, `integers-bits` |  |  | Superseded by 151: `(srfi 60)` re-exports 151's procedures under 60's names (`logand` is `bitwise-and`), defining the few whose arguments differ. |
| 61 | A more general cond clause | a | SYN | S | `cond` |  |  | Exports its own `cond` with guarded `=>` clauses. |
| 62 | S-expression comments | b | INF | — | — |  |  | `#;` is R7RS lexical syntax; no library. |
| 63 | Homogeneous and Heterogeneous Arrays | a | ARR | S | `arrays`² |  |  | Homogeneous and heterogeneous arrays over vectors and 160's vectors; exports its own `equal?`. |
| 64 | A Scheme API for test suites | a | TEST | S | `testing` | yes | yes | The test API: sample implementation (Taylan Kammer's). First, since vendored suites use it. |
| 66 | Octet Vectors | a | VEC | S | `octet-vectors` |  |  | Octet vectors are bytevectors: renames of R7RS's bytevector procedures plus `u8vector-compare`. |
| 67 | Compare Procedures | a | HASH | S | `compare-procedures` |  |  | Compare procedures; sample implementation and examples. |
| 69 | Basic hash tables | c | HASH | S | `basic-hash-tables` | yes |  | Superseded by 125, which keeps 69's names as deprecated: `(srfi 69)` re-exports them. |
| 70 | Numbers | d | — | — | — |  |  | Rewrites R5RS's numbers chapter, not a library; R7RS §6.2 is ours (SRFI 261's reason). |
| 71 | Extended LET-syntax for multiple values | a | SYN | S | `let`² |  |  | Exports its own `let`, `let*`, `letrec` with `(values ...)` clauses. |
| 72 | Hygienic macros | d | — | — | — |  | yes | A syntax-case macro system; the maintainer excluded `syntax-case`. |
| 74 | Octet-Addressed Binary Blocks | a | VEC | S | `blobs` |  |  | Blobs are bytevectors; integer access by size and endianness in Scheme, Java primitives if a profile asks. |
| 78 | Lightweight testing | a | TEST | S | `lightweight-testing` |  | yes | `check` and, after 42, `check-ec`. |
| 86 | MU and NU simulating VALUES & CALL-WITH-VALUES, and their related LET-syntax | a | SYN | S | `mu-and-nu` |  |  | `mu`, `nu` and their let forms, `syntax-rules`. |
| 87 | => in case clauses | b | INF | — | `case` |  |  | R7RS's `case` takes `=>`; re-export. |
| 88 | Keyword objects | d | — | — | — |  |  | `foo:` is an R7RS identifier, which a keyword reader would read differently. |
| 89 | Optional positional and named parameters | d | — | — | — |  | yes | Needs 88's keyword syntax. |
| 90 | Extensible hash table constructor | d | — | — | — |  |  | Needs 88's keyword syntax. |
| 94 | Type-Restricted Numerical Functions | a | NUM | S | — |  |  | Type-restricted functions (`real-sqrt`, `integer-log`, `quo`): R7RS's infinities are the ones SRFI 70 introduced, which is all 94 assumes. |
| 95 | Sorting and Merging | a | LIST | S | `sorting-and-merging`, `sort-and-merging` | yes |  | `sort`, `merge` with `(sort seq less? [key])`, a different argument order from 132's: its own bindings over 132's merge sort. |
| 96 | SLIB Prerequisites | d | — | — | — |  |  | The interface SLIB needs to be hosted; hosting SLIB is not a goal. |
| 97 | SRFI Libraries | d | INF | — | — |  |  | Meta: its library names are adopted ("Library names"), as aliases. |
| 98 | An interface to access environment variables | b | INF | — | `os-environment-variables` | yes |  | R7RS's `get-environment-variable(s)`; re-export. |
| 99 | ERR5RS Records | a | REC | T | `records`² | yes |  | Procedural, inspection and syntactic records with single inheritance: the record runtime (D1's Static Object Model shapes) gains parents and first-class descriptors. |
| 100 | define-lambda-object | a | REC | S | `define-lambda-object` |  |  | `define-lambda-object`: a large `syntax-rules` sample implementation. |
| 101 | Purely Functional Random-Access Pairs and Lists | a | LIST | S | `random-access-lists` | yes | yes | Random-access lists; exports its own `pair?`, `cons`, `car`, ... Sample implementation (R6RS) and tests. |
| 105 | Curly-infix-expressions | a | RDR | R | — |  |  | Curly-infix: `{` is reserved by R7RS, so reading `{a + b}` changes no valid program; on with `#!curly-infix`. |
| 106 | Basic socket interface | a | IO | J | — |  |  | JDK socket channels, only when `Env.isSocketIOAllowed()` (the embedder's `IOAccess`), the one exception to D10's "all I/O through the Env"; blocking calls interruptible (D21, "The maintainer's calls"). |
| 107 | XML reader syntax | a | RDR | R | — |  |  | XML literals `#<tag>...</tag>`: `#<` is an error in R7RS; expands into 108's constructors. |
| 108 | Named quasi-literal constructors | a | RDR | R | — |  |  | `&name{...}`: `{` and `[` are reserved by R7RS, so no valid program reads differently. |
| 109 | Extended string quasi-literals | a | RDR | R | — |  |  | `&{...}` string quasi-literals, after 108. |
| 110 | Sweet-expressions (t-expressions) | a | RDR | R | — |  |  | Sweet-expressions: a reader mode turned on by `#!sweet`. |
| 111 | Boxes | a | REC | J | `boxes` | yes |  | Boxes: one Java value type with 195's multiple-value boxes; `(srfi 111)` is 195's single-value subset. |
| 112 | Environment Inquiry | a | IO | J | `environment-inquiry` |  |  | Environment inquiry: implementation name and version, CPU, OS, machine name, from the JVM (constant per image). |
| 113 | Sets and bags | a | HASH | S | `sets-and-bags` | yes | yes | Sets and bags over 125 and 128; sample implementation and tests (MIT). |
| 115 | Scheme Regular Expressions | a | STR | S | `regexp` | yes | yes | SRE regular expressions: the sample implementation is chibi's `(chibi regexp)` (BSD-3), ported. TRegex would need a new artifact (Follow-ups). |
| 116 | Immutable List Library | a | LIST | S | `immutable-lists` | yes | yes | Immutable pairs as their own record type; sample implementation and tests (MIT). |
| 117 | Queues based on lists | a | LIST | S | `list-queues` | yes | yes | List queues; sample implementation and tests. |
| 118 | Simple adjustable-size strings | a | STR | J | — |  |  | `string-append!`, `string-replace!` change a string's length: `SString`'s length is final, so its storage becomes growable (D1), measured against string-heavy benchmarks. |
| 119 | wisp: simpler indentation-sensitive scheme | a | RDR | R | — |  |  | Wisp: a reader mode turned on by a `#!` directive (Python-like indentation). |
| 120 | Timer APIs | a, after threads | THR | S | — |  | yes | Timers run tasks at a later time on another thread; the sample implementation uses 18. |
| 123 | Generic accessor and modifier operators | a | SYN | S | — |  | yes | Generic `ref` and `~` with 17's `setter`; sample implementation and tests. |
| 125 | Intermediate hash tables | a | HASH | J | `hashtables` | yes | yes | Hash tables. Rule 1: a table whose hash or equality is a user procedure is Scheme (open addressing over vectors); tables over `eq?`, `eqv?`, `equal?`, `string=?` and `string-ci=?` are a Java value whose hashing and probing need no callback. Upstream tests. |
| 126 | R6RS-based hashtables | a | HASH | J | `r6rs-hashtables` |  | yes | R6RS-style hashtables on 125's core; weak tables are Java weak references. |
| 127 | Lazy Sequences | a | GEN | S | `lazy-sequences` | yes | yes | Lazy sequences over 158; sample implementation and tests (MIT). |
| 128 | Comparators (reduced) | a | HASH | S | `comparators`² | yes | yes | Comparators (with 162's additions); `default-hash`, `string-hash` as Java primitives. Sample implementation and tests (MIT). |
| 129 | Titlecase procedures | a | STR | J | `titlecase` | yes | yes | `char-title-case?`, `char-titlecase`, `string-titlecase` over the JDK's tables, word breaks as the SRFI defines them. |
| 130 | Cursor-based string library | a | STR | S | `string-cursors` | yes | yes | Cursor strings: cursors are indexes, which the SRFI permits; shares 13's kernel. Upstream tests. |
| 131 | ERR5RS Record Syntax (reduced) | a | REC | S | `records`² |  |  | Reduced ERR5RS record syntax over 99's runtime. |
| 132 | Sort Libraries | a | LIST | S | `sorting` | yes | yes | Sort libraries: the sample implementation's merge and quick sorts in Scheme (the comparison is a user procedure, rule 1). Upstream tests. |
| 133 | Vector Library (R7RS-compatible) | a | VEC | S | `vectors`² | yes | yes | Vector library; R7RS's `vector-map`, `vector-fill!`, ... re-exported. Sample implementation and tests (MIT). |
| 134 | Immutable Deques | a | LIST | S | `deques` | yes | yes | Immutable deques; sample implementation and tests. |
| 135 | Immutable Texts | a | STR | S | — | yes | yes | Immutable texts: the sample implementation (Clinger, portable R7RS); a `TruffleString`-backed kernel later if a profile asks. Upstream tests. |
| 136 | Extensible record types | a | REC | T | `records`² |  | yes | Extensible record types over 99's runtime. |
| 137 | Minimal Unique Types | a | REC | S | `types` |  | yes | `make-type`: unique types over a record. |
| 138 | Compiling Scheme programs to executables | d | — | L | `compiling` |  |  | A `compile-r7rs` command that builds executables: not provided (the maintainer, 2026-11-21): standalone-executable tooling is deferred until it is designed with the maintainer. |
| 139 | Syntax parameters | a | EXP | X | `syntax-parameters` | yes |  | Syntax parameters: `define-syntax-parameter` and `syntax-parameterize` as expander core forms (rebinding a keyword's transformer for the extent of an expansion). |
| 140 | Immutable Strings | a | STR | J | `strings`² |  | yes | Immutable strings: `SString`'s immutable flag (literals already have it) as `istring?`; the library's own string procedures return immutable strings. |
| 141 | Integer division | a | NUM | S | `integer-division` | yes |  | Integer division: R7RS's `floor/` and `truncate/` families re-exported, `ceiling/`, `round/`, `euclidean/`, `balanced/` added. |
| 143 | Fixnums | a | NUM | J | `fixnums` | yes | yes | Fixnums: the range of our `long` fixnums (`fx-width` 64); `fx+` and friends as Bytecode DSL operations that never overflow into big integers. |
| 144 | Flonums | a | NUM | J | — | yes | yes | Flonums: `java.lang.Math` primitives; gamma, erf and Bessel functions from the sample implementation's portable Scheme (`144.special.scm`). Upstream tests. |
| 145 | Assumptions | a | SYN | S | `assume` | yes |  | `assume`: checks its expression always (the SRFI permits it). |
| 146 | Mappings | a | HASH | S | `mappings` | yes | yes | Mappings: red-black trees and `(srfi 146 hash)`'s HAMT, sample implementation (MIT) over 128. Upstream tests. |
| 147 | Custom macro transformers | a | EXP | X | — | yes | yes | Custom macro transformers: a transformer spec may be a keyword or a macro use that expands into one; `define-syntax`, `let-syntax`, `letrec-syntax` exported with that rule. |
| 148 | Eager syntax-rules | a | EXP | S | — |  | yes | Eager `syntax-rules`: the sample implementation, over 147. |
| 149 | Basic Syntax-rules Template Extensions | a | EXP | X | — |  |  | `syntax-rules` with more ellipses in templates: a variant `(srfi 149)` exports, since D18 keeps the base `syntax-rules`'s syntax errors for them. |
| 150 | Hygienic ERR5RS Record Syntax (reduced) | a | REC | X | — |  | yes | Hygienic record field names: `define-record-type` whose field names are identifiers, over 99's runtime. |
| 151 | Bitwise Operations | a | NUM | J | `bitwise-operations` | yes | yes | Bitwise operations on `long` and `SBigInteger` as Bytecode DSL operations. Upstream tests. |
| 152 | String Library (reduced) | a | STR | S | `strings`² |  | yes | Reduced string library on 13's kernel; R7RS's string procedures re-exported. |
| 153 | Ordered Sets | a | HASH | S | `ordered-sets` |  | yes | Ordered sets over 128; sample implementation and tests. |
| 156 | Syntactic combiners for binary predicates | a | SYN | S | `predicate-combiners` |  | yes | `is`, `isnt`, `syntax-rules`. |
| 158 | Generators and Accumulators | a | GEN | S | `generators-and-accumulators` | yes | yes | Generators and accumulators (with 221's additions). `make-coroutine-generator` captures a continuation per value: the torture modes and a loop's allocation matter (D12). Sample implementation (MIT). |
| 160 | Homogeneous numeric vector libraries | a | VEC | J | — | yes | yes | Homogeneous vectors (`s8` ... `f64`, `c64`, `c128`) as Java primitive arrays; `u8` vectors are bytevectors. Printer, `equal?` and the `#s8(...)` external representation. Upstream tests. |
| 161 | Unifiable Boxes | a | REC | S | — |  | yes | Unifiable boxes (union-find) over a record. |
| 162 | Comparators sublibrary | a | HASH | S | `comparators`² |  | yes | Exported by `(srfi 128)` as the SRFI asks, and by `(srfi 162)`. |
| 163 | Enhanced array literals | a | RDR | R | — |  |  | Array literals for 164's arrays, sharing one representation with 58. |
| 164 | Enhanced multi-dimensional Arrays | a | ARR | S | — |  |  | Arrays extending 25 (Kawa's), without its reader syntax (163). |
| 165 | The Environment Monad | a | FMT | S | `environment-monad` | yes | yes | The environment monad; sample implementation. |
| 166 | Monadic Formatting | a | FMT | S | `formatting` | yes |  | Monadic formatting: chibi's `(srfi 166)` (BSD-3) with its sublibraries `base`, `pretty`, `columnar`, `unicode`, `color`. |
| 167 | Ordered Key Value Store | a | MISC | S | `ordered-key-value-store` |  | yes | Ordered key-value store, with the sample implementation's in-memory engine; after 158, 173. |
| 168 | Generic Tuple Store Database | a | MISC | S | `ordered-tuple-store` |  | yes | Tuple store over 167. |
| 169 | Underscores in numbers | a | RDR | R | — |  | yes | `1_000_000`: an error in R7RS, so always on; the reader only (not `string->number`, as the SRFI allows). |
| 170 | POSIX API | a | IO | J | `posix` |  | yes | POSIX through the Env: files, directories, links, file info, temporary files and the working directory through `TruffleFile`; time; environment variables read. What the Env doesn't reach (file descriptors and `fdes->port`, `umask`, `nice`, user and group databases, setting environment variables, `terminal?`) raises a `posix-error?` with errno `ENOSYS`, listed in the docs; the sandbox's refusals raise `EACCES`. |
| 171 | Transducers | a | GEN | S | `transducers` |  | yes | Transducers; sample implementation and tests. |
| 172 | Two Safer Subsets of R7RS | a | MISC | S | `safer-subsets-of-r7rs` |  |  | Two safer subsets of R7RS: re-exporting libraries. |
| 173 | Hooks | a | MISC | S | `hooks` |  | yes | Hooks; sample implementation and tests. |
| 174 | POSIX Timespecs | a | IO | S | `posix-timespecs` |  | yes | Timespecs as a record; before 170. |
| 175 | ASCII character library | a | STR | S | `ascii` |  | yes | ASCII character library; sample implementation and tests. |
| 176 | Version flag | a | IO | L | `version` |  |  | `scheme --version` prints SRFI 176's S-expressions: the launcher framework's own `--version` is replaced (D17). |
| 178 | Bitvector library | a | VEC | S | `bitvectors` |  | yes | Bitvectors over 160's `u8` vectors and 151; sample implementation and tests. |
| 179 | Nonempty Intervals and Generalized Arrays (Updated) | c | ARR | S | `intervals-and-arrays`² | yes | yes | Superseded by 231, its author's revision, which is incompatible in places: provided anyway from its sample implementation, as chibi does, after 231. |
| 180 | JSON | a | IO | S | `json` |  | yes | JSON reader and writer in Scheme (the sample implementation); its large upstream test corpus. |
| 181 | Custom ports (including transcoded ports) | a | IO | J | `custom-ports` |  |  | Custom ports call Scheme procedures, so rule 1 decides first how `read-char` and the rest reach them (a design decision before the task); transcoders over Java's charsets. |
| 185 | Linear adjustable-length strings | a | STR | J | — |  |  | Linear-update `string-append!`, `string-replace!`, with 118. |
| 188 | Splicing binding constructs for syntactic keywords | a | EXP | X | — | yes | yes | `splicing-let-syntax`, `splicing-letrec-syntax` as core forms. |
| 189 | Maybe and Either: optional container types | a | REC | S | `maybe-and-either` |  | yes | Maybe and Either; sample implementation and tests (MIT). |
| 190 | Coroutine Generators | a | GEN | S | `coroutine-generators` |  | yes | Coroutine generators: `define-coroutine-generator` with a `yield` syntax parameter (139) over 158. |
| 192 | Port Positioning | a | IO | J | `port-positioning` |  | yes | Port positions: file ports through the Env's seekable byte channels, string and bytevector ports by index. |
| 193 | Command line | a | IO | J | `command-line` | yes | yes | `command-line`, `command-name`, `command-args`, `script-file`, `script-directory` from what the launcher passes. |
| 194 | Random data generators | a | NUM | S | `random-data-generators` |  | yes | Random data generators over 27 and 158; sample implementation and tests. |
| 195 | Multiple-value boxes | a | REC | J | `multiple-value-boxes` |  |  | Multiple-value boxes: 111's type. |
| 196 | Range Objects | a | GEN | S | `ranges` |  | yes | Ranges; sample implementation and tests. |
| 197 | Pipeline Operators | a | SYN | S | `pipeline-operators` |  | yes | Pipeline operators: the sample's `syntax-rules` version. |
| 201 | Syntactic Extensions to the Core Scheme Bindings | a | SYN | S | `syntactic-extensions` |  |  | Pattern-matching `lambda`, `let`, `let*`, `or`, `define`: the sample implementations (Guile, Racket) use `syntax-case`, so a `syntax-rules` port. |
| 202 | Pattern-matching Variant of the and-let* Form that Supports Multiple Values | a | SYN | S | `and-let*`² |  |  | Pattern-matching `and-let*`, with 201's matcher; the same port. |
| 203 | A Simple Picture Language in the Style of SICP | d | — | J | `picture-language` |  | yes | The SICP picture language needs somewhere to draw: a terminal language has no canvas, and writing SVG to a port or file is a product choice; not provided (the maintainer, 2026-11-21). |
| 206 | Auxiliary Syntax Keywords | a | EXP | X | `auxiliary-syntax-keywords` |  | yes | `define-auxiliary-syntax`: auxiliary keywords are already bindings (D7); the sample implementation's `syntax-case` part is not needed. |
| 207 | String-notated bytevectors | a | VEC | S | `bytestrings` |  | yes | Bytestring procedures (sample implementation, MIT); the `#u8"..."` syntax is in the reader group. |
| 208 | NaN procedures | a | NUM | J | `nan` |  |  | NaN payload and sign through `Double.doubleToRawLongBits`. |
| 209 | Enums and Enum Sets | a | HASH | S | `enums` |  | yes | Enums and enum sets; sample implementation and tests (MIT). |
| 210 | Procedures and Syntax for Multiple Values | a | SYN | S | `multiple-values` |  | yes | Multiple-value procedures and syntax; sample implementation and tests. |
| 211 | Scheme Macro Libraries | b | EXP | X | `macros` | yes |  | `(srfi 211 syntax-rules)` re-exports R7RS's; `(srfi 211 syntax-case)` is excluded with `syntax-case`; the low-level sublibraries (`er-macro-transformer`, `ir-`, `sc-`/`rsc-`, `identifier-syntax`, `variable-transformer`) a macro system beyond `syntax-rules`, are not provided (the maintainer, 2026-11-21). |
| 212 | Aliases | a | EXP | X | `aliases` |  | yes | `alias`: a core form binding an identifier to another's binding. |
| 213 | Identifier Properties | d | — | — | — |  | yes | Identifier properties are read by `syntax-case` transformers (`lookup`), which the maintainer excluded. |
| 214 | Flexvectors | a | VEC | S | `flexvectors` |  | yes | Flexvectors; sample implementation and tests. |
| 215 | Central Log Exchange | a | MISC | S | `logging` |  | yes | Central log exchange; sample implementation and tests. |
| 216 | SICP Prerequisites (Portable) | a, after threads | THR | S | `sicp` |  | yes | SICP prerequisites: small, but `parallel-execute` runs its procedures concurrently, so the library waits for threads rather than ship without it. |
| 217 | Integer Sets | a | HASH | S | `integer-sets` |  | yes | Integer sets (Patricia tries) over 143 and 151; sample implementation and tests. |
| 219 | Define higher-order lambda | a | SYN | S | `higher-order-lambda`, `define` | yes | yes | Exports its own curried `define`. |
| 221 | Generator/accumulator sub-library | a | GEN | S | `generators&accumulators` |  | yes | Exported by `(srfi 158)` as the SRFI recommends, and by `(srfi 221)`. |
| 222 | Compound Objects | a | REC | S | `compound-objects` |  | yes | Compound objects; sample implementation and tests. |
| 223 | Generalized binary search procedures | a | VEC | S | `bisect` |  | yes | Generalized binary search; sample implementation and tests. |
| 224 | Integer Mappings | a | HASH | S | `integer-mappings` |  | yes | Integer mappings over 143, 151, 128, 158, 189; sample implementation and tests (MIT). |
| 225 | Dictionaries | a | HASH | S | `dictionaries` |  | yes | Dictionaries over every map type above (69, 125, 126, 146, 224, alists); sample implementation and tests. |
| 226 | Control Features | a | CTL | T | `control-features` |  | yes | Prompts, composable continuations, `shift`/`reset`, continuation marks, parameterizations, fluids, initial continuations, promises, exceptions: the runtime's control (D4, D12) exposed, after its own design decision (marks on frames and on tail calls, `call/cc` delimited by the default prompt, R7RS's `parameterize` and `guard` the same objects). Its threads, thread locals, interrupts, mutexes and condition variables are in the threads group. The sample implementation (R6RS, `syntax-case`) is a reference, not a port. |
| 227 | Optional Arguments | a | SYN | S | `optional-arguments` | yes | yes | `opt-lambda`, `let-optionals`; the R7RS sample implementation. |
| 228 | Composing Comparators | a | HASH | S | `composing-comparators` |  | yes | Composing comparators over 128, 162. |
| 229 | Tagged Procedures | a | REC | T | `tagged-procedures`² | yes | yes | Tagged procedures: a closure that carries a tag (`SClosure`), `procedure-tag` reading it. |
| 230 | Atomic Operations | a, after threads | THR | J | `atomic-operations` |  | yes | Atomic boxes and fences: trivial in a single-threaded context, meaningful with threads; with them. |
| 231 | Intervals and Generalized Arrays | a | ARR | S | `intervals-and-arrays`² | yes | yes | Intervals and generalized arrays: the sample implementation (Lucier, MIT), with 160's storage classes. Upstream tests. |
| 232 | Flexible curried procedures | a | SYN | S | `currying` |  | yes | `curried`, `syntax-rules`. |
| 233 | INI files | a | IO | S | `ini` |  | yes | INI files over ports; sample implementation and tests. |
| 234 | Topological Sorting | a | MISC | S | `topological-sorting` |  | yes | Topological sorting; sample implementation and tests. |
| 235 | Combinators | a | MISC | S | `combinators` |  | yes | Combinators; sample implementation and tests. |
| 236 | Evaluating expressions in an unspecified order | a | SYN | S | `independently` |  | yes | `independently`, `syntax-rules`. |
| 237 | R6RS Records (refined) | a | REC | T | — |  | yes | R6RS records refined: descriptors with parents, sealed, opaque and nongenerative types on 99's runtime; the sample implementation uses `syntax-case`, so a port. |
| 238 | Codesets | a | MISC | S | `codesets` |  | yes | Codesets, with an errno codeset per platform (Linux, macOS) shared with 170. |
| 239 | Destructuring Lists | a | SYN | S | `list-case` |  | yes | `list-case`; the sample uses `syntax-case`, so a `syntax-rules` port. |
| 240 | Reconciled Records | a | REC | T | `records`² |  | yes | Reconciled records: R7RS's `define-record-type` extended with 237's clauses; the sample uses `syntax-case`. |
| 241 | Match — Simple Pattern-Matching Syntax to Express Catamorphisms on Scheme Data | a | SYN | S | `match` |  | yes | `match` with catamorphisms: the sample implementation is `syntax-case`, so a `syntax-rules` port (or an expander form if that can't be done). |
| 244 | Multiple-value Definitions | b | INF | — | `define-values` |  | yes | R7RS's `define-values`; re-export. |
| 247 | Syntactic Monads | a | SYN | S | `syntactic-monads` |  | yes | Syntactic monads; the sample implementation. |
| 248 | Minimal delimited continuations | a | CTL | S | `delimited-continuations` |  | yes | Minimal delimited continuations: handlers get the continuation up to their `guard` or `with-unwind-handler` prompt, which is D4's capture; after 226's design decision. |
| 250 | Insertion-ordered hash tables | a | HASH | S | — |  | yes | Insertion-ordered hash tables on 125's core; sample implementation and tests. |
| 251 | Mixing groups of definitions with expressions within bodies | a | EXP | X | `body-mixtures` |  |  | Groups of definitions and expressions in bodies: D14 already mixes them with letrec* over the whole body, 251 scopes each group over what follows; either `(srfi 251)`'s own body forms or D14 changed to 251's semantics (a design.md change first). |
| 252 | Property Testing | a | TEST | S | `property-testing` |  | yes | Property testing over 64, 158, 194. |
| 253 | Data (Type-)Checking | a | MISC | S | `type-checking` |  | yes | Type checking (`check-arg`, `define-checked`); sample implementation. |
| 254 | Ephemerons and Guardians | a | MISC | J | — |  | yes | Ephemerons fit Java's weak references (sound, never breaking one whose value reaches its key), but a JVM can't resurrect an object, so guardians would never return one: the SRFI's "when it proves" makes that conforming and useless. Shipped with inert guardians (the maintainer, 2026-11-21). |
| 255 | Restarting conditions | a | REC | S | `restarting-conditions` |  | yes | Restarting conditions over 35 and `raise-continuable`; the sample uses `syntax-case`, so a port. |
| 257 | Simple extendable pattern matcher with backtracking | a | SYN | S | — |  | yes | Extendable matcher with backtracking: the sample implementation is `syntax-rules`. |
| 258 | Uninterned symbols | a | MISC | J | `uninterned-symbols` |  | yes | Uninterned symbols: `SSymbol`s outside the symbol table; the printer, `read`, and the prelude image's symbol records must keep them distinct. |
| 259 | Tagged procedures with type safety | a | REC | T | `tagged-procedures`² |  |  | Tagged procedures with tagging protocols, on 229's runtime. |
| 260 | Generated Symbols | a | MISC | J | `generated-symbols` |  | yes | `generate-symbol`: an interned symbol with an unguessable name (`SecureRandom` through `java.util.UUID`). |
| 261 | Portable SRFI Library Reference | d | INF | — | — |  |  | Meta: its `(srfi srfi-N)` and `(srfi name-N)` forms are adopted ("Library names"). |
| 263 | Prototype Object System | a | MISC | S | — |  | yes | Prototype objects; sample implementation and tests. |
| 264 | String Syntax for Scheme Regular Expressions | a | STR | S | — |  | yes | String syntax for SREs, over 115; sample implementation and tests. |
| 265 | The CFG Language | a | SYN | S | `cfg` |  | yes | The CFG language: the sample implementation is a `syntax-case` compiler, so the largest port of the syntax group. |
| 267 | Raw String Syntax | a | RDR | R | — |  | yes | Raw strings `#"X(...)X"`: `#"` is an error in R7RS, so always on. |
| 269 | Portable Test Definitions | a | TEST | S | — |  |  | Portable test definitions, run by 64. |
| 270 | Hexadecimal Floating-Point Constants | a | RDR | R | — |  | yes | Hexadecimal flonums `#x1.8p3` in the reader and `string->number` (`Double.parseDouble` reads `0x1.8p3`). |
| 271 | Random port libraries | a | IO | J | — |  | yes | Random binary input ports: `randomized` from `SecureRandom`, `determinized` xoshiro256++ seeded; a port type of the I/O area. |
| 272 | Pretty Printing | a | FMT | S | — |  | yes | Pretty printing; sample implementation and its tests, with shared structure as `write` does. |
| 273 | Extensions to Data (Type-)Checking | a | MISC | S | — |  | yes | Extensions to 253. |
| 274 | Extended List Conversion Procedures | a | LIST | S | — |  | yes | Extended list conversions for every sequence type here; after 133, 158, 160. |
| 275 | URIs, IRIs, and basic paths | a | IO | S | — |  | yes | URIs and IRIs; sample implementation and tests. |
| 277 | Cyclic ports | a | IO | J | — |  | yes | Cyclic ports, beside 271's. |
| 278 | Supplemental Numerics | a | NUM | J | `numerics` |  | yes | Supplemental numerics: hyperbolic functions, `exact-integer-log`, nth roots, R6RS's predicates; `Math` and `BigInteger` primitives. |
| 279 | In(tro)spection Protocol | a | MISC | S | — |  |  | Inspection protocol for records, hash tables and the types above. |
