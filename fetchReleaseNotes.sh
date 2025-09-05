#!/usr/bin/env sh
set -eu

# ===== config (override in CI as needed) =====
: "${SRC_URL:=https://nginx.org/en/docs/njs/changes.html}"   # marketing URL
NOTES_FILE="release.txt"
MANIFEST_FILE="manifest.txt"
# ============================================

# curl with retries (portable)
CURL_RETRY_OPTS="--fail --location --silent --show-error --retry 5 --retry-delay 3"
if curl --help all 2>/dev/null | grep -q -- '--retry-connrefused'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-connrefused"
fi
if curl --help all 2>/dev/null | grep -q -- '--retry-all-errors'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-all-errors"
fi

tmpfile="$(mktemp)"
trap 'rm -f "$tmpfile"' EXIT

echo "→ Downloading release notes from: $SRC_URL"
curl $CURL_RETRY_OPTS "$SRC_URL" -o "$tmpfile"

# Sanity check
if [ ! -s "$tmpfile" ]; then
  echo "✗ Error: downloaded file is empty or missing." >&2
  exit 1
fi

# Write release.txt at repo root
mv "$tmpfile" "$NOTES_FILE"

# Compute SHA256 (sha256sum preferred; fallback to shasum)
if command -v sha256sum >/dev/null 2>&1; then
  NOTES_SHA="$(sha256sum "$NOTES_FILE" | awk '{print $1}')"
fi

FETCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Write manifest (no commit SHA here)
{
  echo "source_url=$SRC_URL"
  echo "fetched_at_utc=$FETCHED_AT"
  echo "release_txt_sha256=$NOTES_SHA"
} > "$MANIFEST_FILE"

git add "$NOTES_FILE" "$MANIFEST_FILE"

# Commit only if there are changes
if git diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

git commit -m "Update release notes from $SRC_URL at $FETCHED_AT"

git push origin HEAD:main

# Print the commit we pushed (for CI to capture if desired)
RELNOTES_SHA="$(git rev-parse HEAD)"
echo "release_notes_repo_commit=$RELNOTES_SHA"
echo "✅ Updated $NOTES_FILE and $MANIFEST_FILE and pushed $RELNOTES_SHA"
