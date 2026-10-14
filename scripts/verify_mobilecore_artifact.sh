#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly AAR="${1:-$ROOT/app/libs/mobilecore.aar}"
readonly -a REQUIRED_LIBRARIES=(
  "jni/arm64-v8a/libgojni.so"
  "jni/armeabi-v7a/libgojni.so"
)

if [[ ! -s "$AAR" ]]; then
  printf 'mobilecore AAR is missing or empty: %s\n' "$AAR" >&2
  exit 1
fi

mapfile -t libraries < <(unzip -Z1 "$AAR" | grep -E '^jni/[^/]+/libgojni\.so$' | sort)
if ((${#libraries[@]} == 0)); then
  printf 'mobilecore AAR contains no libgojni.so libraries: %s\n' "$AAR" >&2
  exit 1
fi

for required in "${REQUIRED_LIBRARIES[@]}"; do
  if ! printf '%s\n' "${libraries[@]}" | grep -Fx "$required" >/dev/null; then
    printf 'mobilecore AAR is missing required library: %s\n' "$required" >&2
    exit 1
  fi
done

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TEMP_DIR:?}"' EXIT

for library in "${libraries[@]}"; do
  abi="${library#jni/}"
  abi="${abi%%/*}"
  binary="$TEMP_DIR/$abi-libgojni.so"
  metadata="$TEMP_DIR/$abi-buildinfo.txt"

  unzip -p "$AAR" "$library" >"$binary"
  go version -m "$binary" | tee "$metadata"

  if ! awk '$1 == "dep" && $2 == "github.com/openlibrecommunity/olcrtc" { found = 1 } END { exit !found }' "$metadata"; then
    printf 'mobilecore %s does not contain the required olcRTC module\n' "$abi" >&2
    exit 1
  fi
  if ! awk '$1 == "=>" && ($2 ~ "OlConnect_manager" || $2 ~ "Olcrtc_manager") { found = 1 } END { exit !found }' "$metadata"; then
    printf 'mobilecore %s does not resolve olcRTC to manager fork\n' "$abi" >&2
    exit 1
  fi
done

printf 'Verified mobilecore AAR for %d ABIs\n' "${#libraries[@]}"
