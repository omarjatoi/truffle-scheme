# R7RS-small on GraalVM Truffle

A fully conformant R7RS-small Scheme on GraalVM Truffle, with re-entrant
multi-shot continuations and proper tail calls.

- `docs/design.md`: architecture and decisions
- `docs/plan.md`: milestones, current tasks, conformance status
- `AGENTS.md`: instructions for coding agents (`CLAUDE.md` links to it)

Development: `nix develop` (or `direnv allow`), then see `AGENTS.md` for
commands.
