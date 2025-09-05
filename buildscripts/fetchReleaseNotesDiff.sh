#!/usr/bin/env bash
set -euo pipefail

# Version to store under (can be passed as first arg, default v1.0.0)
VERSION="${1:-1.0.0}"

# Source URL (override by exporting SRC_URL=... if you ever want to change it)
SRC_URL="${SRC_URL:-https://nginx.org/en/CHANGES}"

# Base folder of your release-notes repo (this script lives inside it)
NOTES_DIR="$(cd "$(dirname "$0")" && pwd)"

# Target folder
TARGET_DIR="$NOTES_DIR/$VERSION"
mkdir -p "$TARGET_DIR"

# ---- curl with retries (compatible across curl versions) ----
CURL_RETRY_OPTS=(--retry 5 --retry-delay 3 --fail --location --silent --show-error)
# Add extra retry flags if supported by this curl
if curl --help all 2>&1 | grep -q -- '--retry-connrefused'; then
  CURL_RETRY_OPTS+=(--retry-connrefused)
fi
if curl --help all 2>&1 | grep -q -- '--retry-all-errors'; then
  CURL_RETRY_OPTS+=(--retry-all-errors)
fi

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT

echo "→ Downloading release notes from: $SRC_URL"
curl "${CURL_RETRY_OPTS[@]}" "$SRC_URL" -o "$tmp"

# Sanity check
if [[ ! -s "$tmp" ]]; then
  echo "✗ Error: downloaded file is empty or missing." >&2
  exit 1
fi

# Save as RELEASE_NOTES.txt
TARGET_FILE="$TARGET_DIR/RELEASE_NOTES.txt"
mv "$tmp" "$TARGET_FILE"

# ---- Compute SHA256 (Linux/macOS) ----
if command -v sha256sum >/dev/null 2>&1; then
  HASH="$(sha256sum "$TARGET_FILE" | awk '{print $1}')"
else
  HASH="$(shasum -a 256 "$TARGET_FILE" | awk '{print $1}')"
fi

# Write SHA256SUMS (standard format)
printf "%s  %s\n" "$HASH" "RELEASE_NOTES.txt" > "$TARGET_DIR/SHA256SUMS"

# Write SOURCE.txt (provenance)
{
  echo "Source-URL: $SRC_URL"
  echo "Retrieved-At-UTC: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "SHA256: $HASH"
  echo "File: RELEASE_NOTES.txt"
  echo "Version: $VERSION"
} > "$TARGET_DIR/SOURCE.txt"

echo "✅ Release notes downloaded to: $TARGET_FILE"
echo "🧾 Wrote provenance: $TARGET_DIR/SOURCE.txt"
echo "🔐 Wrote checksum:  $TARGET_DIR/SHA256SUMS"