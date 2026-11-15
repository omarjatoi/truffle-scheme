# R7RS-small on GraalVM Truffle

R7RS-small Scheme on GraalVM Truffle.

- `docs/design.md`: architecture and decisions
- `docs/plan.md`: milestones, current tasks, conformance status
- `AGENTS.md`: instructions for coding agents (`CLAUDE.md` links to it)

Development: `nix develop` (or `direnv allow`), then see `AGENTS.md` for
commands.

To run an agent session, open a coding agent in the repo and say `start`.
It picks the next available task from `docs/plan.md` and takes it to a PR
(see "When the user says start" in `AGENTS.md`).
