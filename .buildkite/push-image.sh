#!/usr/bin/env bash
# Builds CI's agent image (flake.nix `packages.x86_64-linux.ciImage`, nix/image.nix) and
# pushes it to the Buildkite package registry that the hosted queue pulls it from, as
# `<repository>:<hash of the image>` and `<repository>:latest` (docs/design.md D11).
#
# On main, the pipeline's `image` step runs it whenever flake.nix, flake.lock or
# nix/image.nix changes. There it logs in with an OIDC token that buildkite-agent requests
# for the registry, which the registry's OIDC policy lets main builds of this pipeline write with.
# By hand, for the first push (before the queue runs the image) or to push again:
#
#   BUILDKITE_PACKAGES_TOKEN=<token> .buildkite/push-image.sh [repository]
#
# The token is a Buildkite API access token with the Read Packages and Write Packages scopes.
# The image is x86_64-linux: on a Mac, Determinate Nix's native Linux builder builds it.
set -euo pipefail
repository="${1:-packages.buildkite.com/omarjatoi/ci/truffle-scheme}"
registry="${repository%/*}"
if [ -z "${BUILDKITE_PACKAGES_TOKEN:-}" ] && [ "${BUILDKITE:-}" != true ]; then
  echo "Set BUILDKITE_PACKAGES_TOKEN to an API access token with Read Packages and Write Packages." >&2
  exit 1
fi
cd "$(dirname "$0")/.."

image="$(nix build --no-link --print-out-paths .#packages.x86_64-linux.ciImage)"
tag="$(basename "$image" | cut -c1-12)"
# The locked nixpkgs's skopeo, which copies the image's archive to the registry without a Docker
# daemon. Fetched before the first OIDC token, which lives five minutes.
skopeo="$(nix build --no-link --print-out-paths --inputs-from . 'nixpkgs#skopeo^out')/bin/skopeo"

# The credentials go to skopeo in a file only this user reads, not on its command line. In CI,
# a new OIDC token for each push, so that neither outlives its token.
auth="$(mktemp -d)"
trap 'rm -rf "$auth"' EXIT
login() {
  local token
  if [ -n "${BUILDKITE_PACKAGES_TOKEN:-}" ]; then
    token="$BUILDKITE_PACKAGES_TOKEN"
  else
    token="$(buildkite-agent oidc request-token --audience "https://$registry" --lifetime 300)"
  fi
  (
    umask 077
    printf '{"auths":{"%s":{"auth":"%s"}}}\n' "${registry%%/*}" \
      "$(printf 'buildkite:%s' "$token" | base64 | tr -d '\n')" > "$auth/auth.json"
  )
}
for t in "$tag" latest; do
  login
  "$skopeo" --insecure-policy copy --authfile "$auth/auth.json" \
    "docker-archive:$image" "docker://$repository:$t"
done
echo "Pushed $repository:$tag and $repository:latest"
