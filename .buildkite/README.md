# Buildkite

**Untested.** `pipeline.yml` was written without a Buildkite organization to run it in. It
runs the same Bazel targets as `.github/workflows/bazel.yml`, and is here to show what moving
CI from GitHub Actions to Buildkite would take. Nothing in the repository depends on it.

## What it runs

| Step | Commands | GitHub commit status |
|---|---|---|
| `Checks` | `bazel test --build_tests_only --test_tag_filters=lint //...` (builds every project with javac `-Werror`, runs the static checks) | `Checks` |
| `Tests` | `bazel test --test_tag_filters=-lint //...`, then the conformance status block as the build's annotation | `Tests` |

Both run inside `nix develop`, so the agents need only Nix: the dev shell brings `bazel`
(bazelisk, at `.bazelversion`) and chibi-scheme, and Bazel fetches the pinned GraalVM, the
Maven jars and Python itself. There's no Docker image to maintain.

## Agents

- **Queue `bazel`**: Linux x86_64 (or aarch64: `bazel/graalvm.bzl` pins both), Nix installed
  with flakes enabled (the Determinate installer does both), a `buildkite-agent` user with a
  persistent home directory. 8+ cores and 16 GB of memory keep the JIT tests' JVMs from
  queueing; the tests reserve two cores each for the JIT ones (`cpu:2` tags).
- **Persistent caches.** Unlike GitHub's runners, Buildkite agents usually outlive a build,
  and that is where most of the speed comes from:
  - Bazel's output base (under `~/.cache/bazel`): the fetched external repositories (the
    unpacked GraalVM among them), the analysis cache and the local action cache. Buildkite
    reuses one checkout directory per agent and pipeline by default
    (`BUILDKITE_BUILD_CHECKOUT_PATH`), so the Bazel server and its state survive between
    builds. That's the ~70 s a fully cached GitHub Actions run still spends.
  - The disk cache and repository cache (`--config=ci`: `~/.cache/bazel-disk-cache`,
    `~/.cache/bazel-repository-cache`), shared by every checkout on the agent. The disk cache
    is capped at 3 GB by `--experimental_disk_cache_gc_max_size`.
  - The Nix store: `nix develop` builds nothing after the first build on an agent.
- **Elastic CI Stack for AWS** works as is if the AMI has Nix (a bootstrap script can run the
  Determinate installer) and the instances' disks persist across jobs; with ephemeral
  instances, use a remote cache instead (below), or caches lose everything between builds.
- `--config=buildkite` (in `.bazelrc`) is `--config=ci` (disk and repository caches, plain
  log output) under a name of its own, for settings only Buildkite should get later.

## Remote cache and execution

With BuildBuddy (or any REAPI cache), add `--config=remote` to the commands and give the agents
`BUILDBUDDY_API_KEY` as a secret (Buildkite's secrets, or the agent's environment hook):
`--remote_header=x-buildbuddy-api-key=$BUILDBUDDY_API_KEY`. Agents then share one cache, which
also makes ephemeral agents cheap. Remote execution needs every tool to come from Bazel:
GraalVM and Python already do, chibi-scheme (`@chibi`, from the Nix shell) doesn't yet, so the
differential tests would need `tags = ["no-remote-exec"]` or chibi built by Bazel.

## Switching from GitHub Actions

1. **Pipeline.** Create a pipeline for the repository whose steps are uploaded from this file:
   `buildkite-agent pipeline upload .buildkite/pipeline.yml` as the pipeline's only setting.
   Build pull requests (including from forks, if wanted: they run on your agents, so review
   the security settings), and builds of `main`.
2. **GitHub app.** Install Buildkite's GitHub app on the repository so builds start on pushes
   and PRs and can post commit statuses.
3. **Required checks.** The steps post commit statuses named `Checks` and `Tests`
   (`notify: github_commit_status`), the names main's ruleset requires today. Turn off GitHub
   Actions' `Checks` and `Tests` (delete `.github/workflows/ci.yml` and `bazel.yml`) in the
   same change, or the ruleset sees two of each. A status from Buildkite is a commit
   *status*, not a check *run*: GitHub's rulesets accept both as required checks, but the
   rule must name the source (or "any source") that posts it.
4. **Secrets.** None for the build itself. The remote cache key (above) and, if Buildkite
   should comment on PRs, a GitHub token, go in Buildkite's secrets.
5. **`AGENTS.md`**: `gh pr checks <n> --watch` keeps working, since it reads commit statuses
   as well as check runs.

What GitHub Actions has that this doesn't: the per-section conformance tables in the run summary
(a step could post them with `buildkite-agent annotate`), and the `Conformance` jobs, which still
use mx.
