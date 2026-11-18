#!/usr/bin/env bash
# Builds CI's agent image (flake.nix `packages.x86_64-linux.ciImage`, .buildkite/image.nix) and
# pushes it to the Buildkite package registry that the hosted queue pulls it from, as
# `<repository>:<hash of the image>` and `<repository>:latest`. Run it after every change to
# flake.lock or to the `.#ci` shell (docs/design.md D11); until then, each job downloads what
# changed and the build says the image is stale.
#
#   BUILDKITE_PACKAGES_TOKEN=<token> .buildkite/push-image.sh [repository]
#
# The token is a Buildkite API access token with the Read Packages and Write Packages scopes.
# The image is x86_64-linux: on a Mac, Determinate Nix's native Linux builder builds it.
set -euo pipefail
repository="${1:-packages.buildkite.com/omarjatoi/ci/truffle-scheme}"
: "${BUILDKITE_PACKAGES_TOKEN:?an API access token with Read Packages and Write Packages}"
cd "$(dirname "$0")/.."

image="$(nix build --no-link --print-out-paths .#packages.x86_64-linux.ciImage)"
tag="$(basename "$image" | cut -c1-12)"

# The token goes to skopeo in a file only this user reads, not on its command line.
auth="$(mktemp -d)"
trap 'rm -rf "$auth"' EXIT
(
  umask 077
  printf '{"auths":{"%s":{"auth":"%s"}}}\n' "${repository%%/*}" \
    "$(printf 'buildkite:%s' "$BUILDKITE_PACKAGES_TOKEN" | base64 | tr -d '\n')" > "$auth/auth.json"
)
for t in "$tag" latest; do
  nix run --inputs-from . nixpkgs#skopeo -- --insecure-policy copy --authfile "$auth/auth.json" \
    "docker-archive:$image" "docker://$repository:$t"
done
echo "Pushed $repository:$tag and $repository:latest"
