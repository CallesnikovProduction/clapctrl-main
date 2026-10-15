#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
[[ "$(uname -s)" == Linux ]] || { echo 'Build the Linux distribution on Linux.' >&2; exit 2; }
[[ $# -eq 0 || ( $# -eq 1 && $1 == --package-only ) ]] || { echo 'Usage: bash packaging/linux/build-portable.sh [--package-only]' >&2; exit 2; }
version=${CLAPCTRL_VERSION:-0.1.0}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'CLAPCTRL_VERSION must be x.y.z' >&2; exit 2; }
export GRADLE_USER_HOME="$root/.gradle-user-home"
if [[ $# -eq 0 ]]; then
  bash "$root/gradlew" -p "$root" --no-daemon test :app-desktop:createDistributable
fi
image="$root/app-desktop/build/compose/binaries/main/app/ClapCtrl"
[[ -x "$image/bin/ClapCtrl" ]] || { echo "Missing Linux application image: $image; build on Linux first." >&2; exit 1; }
output="$root/packaging/dist"
mkdir -p -- "$output"
archive="clapctrl-$version-linux-$(uname -m).tar.gz"
# Tar preserves executable modes, unlike a directly uploaded directory artifact.
tar -C "$(dirname -- "$image")" -czf "$output/$archive" ClapCtrl
(cd -- "$output" && sha256sum "$archive" > "$archive.sha256")
printf 'Linux portable image: %s\nRun after extracting: ./ClapCtrl/bin/ClapCtrl\n' "$output/$archive"
