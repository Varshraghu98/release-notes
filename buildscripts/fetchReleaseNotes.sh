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
elif command -v shasum >/dev/null 2>&1; then
  NOTES_SHA="$(shasum -a 256 "$NOTES_FILE" | awk '{print $1}')"
else
  echo "Need sha256sum or shasum on the agent" >&2
  exit 127
fi

FETCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Write manifest *without* commit first (we'll amend after commit)
{
  echo "source_url=$SRC_URL"
  echo "fetched_at_utc=$FETCHED_AT"
  echo "release_txt_sha256=$NOTES_SHA"
  # release_notes_repo_commit will be filled after commit
} > "$MANIFEST_FILE"

git add "$NOTES_FILE" "$MANIFEST_FILE"

# Commit only if there are changes
if git diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

git commit -m "Update release notes from $SRC_URL at $FETCHED_AT"

# Now amend manifest with the commit SHA of this repo
RELNOTES_SHA="$(git rev-parse HEAD)"
if grep -q '^release_notes_repo_commit=' "$MANIFEST_FILE"; then
  # portable in-place edit
  sed -i.bak "s/^release_notes_repo_commit=.*/release_notes_repo_commit=$RELNOTES_SHA/" "$MANIFEST_FILE" || true
  rm -f "${MANIFEST_FILE}.bak"
else
  printf "release_notes_repo_commit=%s\n" "$RELNOTES_SHA" >> "$MANIFEST_FILE"
fi

git add "$MANIFEST_FILE"
git commit --amend --no-edit

# Push to main (adjust branch if needed)
git push origin HEAD:main

echo "✅ Wrote $NOTES_FILE and $MANIFEST_FILE and pushed commit $RELNOTES_SHA"