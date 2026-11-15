# GraalVM Truffle Scheme

[R7RS-small](https://small.r7rs.org/) Scheme on GraalVM Truffle. https://www.graalvm.org/ and https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/ for additional information.

- `docs/design.md`: architecture and decisions
- `docs/plan.md`: milestones, current tasks, conformance status
- `AGENTS.md`: instructions for coding agents (`CLAUDE.md` links to it)

Development: `nix develop` (or `direnv allow`). It provides GraalVM CE 25.5,
mx and chibi-scheme. Then see `AGENTS.md` for commands.

To run an agent session, open a coding agent in the repo and say `start`.
It picks the next available task from `docs/plan.md` and takes it to a PR
(see "When the user says start" in `AGENTS.md`).
