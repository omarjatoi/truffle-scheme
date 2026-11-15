# R7RS-small on GraalVM Truffle

A fully conformant R7RS-small Scheme on GraalVM Truffle, with re-entrant
multi-shot continuations and proper tail calls.

- `docs/design.md`: architecture and decisions
- `docs/plan.md`: milestones, current tasks, conformance status
- `AGENTS.md`: instructions for coding agents (`CLAUDE.md` links to it)

Development: `nix develop` (or `direnv allow`). It provides GraalVM CE 25.5,
mx and chibi-scheme. Then see `AGENTS.md` for commands.

To run an agent session, open a coding agent in the repo and say `start`.
It picks the next available task from `docs/plan.md` and takes it to a PR
(see "When the user says start" in `AGENTS.md`).
