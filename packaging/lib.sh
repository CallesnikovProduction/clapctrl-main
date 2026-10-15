#!/usr/bin/env bash
# Shared source manifest. Includes current files, including uncommitted changes, without caches or recordings.
clapctrl_source_paths() {
  local root=$1 path
  for path in build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat gradle core platform app-desktop LICENSE README.md docs AGENTS.md CONTRIBUTING.md .gitignore .editorconfig .gitattributes packaging .github; do
    if [[ -e "$root/$path" ]]; then printf '%s\0' "$path"; fi
  done
}

clapctrl_source_tar() {
  local root=$1
  shift
  tar -C "$root" --exclude='*/build' --exclude='*/.gradle' --exclude='*/.kotlin' \
    --exclude='packaging/dist' --exclude='*/.local-data' --exclude='*/.gradle-user-home' \
    --exclude='packaging/arch/src' --exclude='packaging/arch/pkg' --exclude='packaging/arch/*.pkg.tar.*' --exclude='packaging/arch/*.log' \
    "$@" --null -T <(clapctrl_source_paths "$root")
}
