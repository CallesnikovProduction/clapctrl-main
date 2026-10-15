#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
source "$root/packaging/lib.sh"
version=${CLAPCTRL_VERSION:-0.1.0}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'CLAPCTRL_VERSION must be x.y.z' >&2; exit 2; }
epoch=${SOURCE_DATE_EPOCH:-0}
[[ "$epoch" =~ ^[0-9]+$ ]] || { echo 'SOURCE_DATE_EPOCH must be an integer' >&2; exit 2; }
output="$root/packaging/dist"
mkdir -p -- "$output"
archive="clapctrl-$version-source.tar.gz"
# GNU tar + gzip -n make source archives deterministic for identical bytes and modes.
clapctrl_source_tar "$root" --sort=name --format=gnu --owner=0 --group=0 --numeric-owner \
  --mtime="@$epoch" --transform="s,^,clapctrl-$version/," -cf - | gzip -n > "$output/$archive"
(cd -- "$output" && sha256sum "$archive" > "$archive.sha256")
printf 'Source archive: %s\nChecksum: %s\n' "$output/$archive" "$output/$archive.sha256"
