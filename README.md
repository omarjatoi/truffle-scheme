# Truffle Scheme

[R7RS-small](https://small.r7rs.org/) Scheme implementation on the GraalVM Truffle Framework. See https://www.graalvm.org/ and https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/ for additional information.

## Docs

- `docs/design.md`: architecture and decisions
- `docs/plan.md`: milestones, current tasks, conformance status
- `AGENTS.md`: instructions for coding agents

##  License

Licensed under either of

- Apache License, Version 2.0, ([LICENSE-APACHE](./LICENSE-APACHE) or https://www.apache.org/licenses/LICENSE-2.0)
- MIT license ([LICENSE-MIT](./LICENSE-MIT) or https://opensource.org/licenses/MIT)

at your option.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in the work by you, as defined in the Apache-2.0 license, shall be dual licensed as above, without any additional terms or conditions.

### Third-party code

`tests/r7rs/` is vendored from [chibi-scheme](https://github.com/ashinn/chibi-scheme)'s
R7RS test suite, and `tests/lib/chibi/test.sld` includes two procedures from
chibi-scheme's `(chibi test)`. Both are under chibi-scheme's BSD-3-Clause
license; see [tests/r7rs/COPYING](./tests/r7rs/COPYING).
