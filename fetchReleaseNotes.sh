#!/usr/bin/env sh
set -euo

# ===== config (override in CI as needed) =====
: "${SRC_URL:=https://downloads.mysql.com/docs/mysql-9.0-relnotes-en.pdf}"   # marketing URL
MANIFEST_FILE="manifest.txt"
# ============================================

# curl with retries
CURL_RETRY_OPTS="--fail --location --silent --show-error --retry 5 --retry-delay 3"
if curl --help all 2>/dev/null | grep -q -- '--retry-connrefused'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-connrefused"
fi
if curl --help all 2>/dev/null | grep -q -- '--retry-all-errors'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-all-errors"
fi

# Download to an isolated temp dir, preserving the remote filename (-O -J)
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

echo "→ Downloading release notes from: $SRC_URL"
(
  cd "$tmpdir"
  # -O: save to local file named like remote; -J: honor Content-Disposition filename
  curl $CURL_RETRY_OPTS -OJ "$SRC_URL"
)

# Locate the single downloaded file
DOWNLOADED_PATH="$(find "$tmpdir" -maxdepth 1 -type f | head -n 1 || true)"
if [ -z "${DOWNLOADED_PATH:-}" ] || [ ! -s "$DOWNLOADED_PATH" ]; then
  echo "✗ Error: downloaded file is empty or missing." >&2
  exit 1
fi

# Keep the server-provided filename in repo root
NOTES_FILE="$(basename "$DOWNLOADED_PATH")"
mv -f "$DOWNLOADED_PATH" "./$NOTES_FILE"

# Compute SHA256 (sha256sum preferred; fallback to shasum or openssl)
if command -v sha256sum >/dev/null 2>&1; then
  NOTES_SHA="$(sha256sum "$NOTES_FILE" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  NOTES_SHA="$(shasum -a 256 "$NOTES_FILE" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
  NOTES_SHA="$(openssl dgst -sha256 -r "$NOTES_FILE" | awk '{print $1}')"
else
  echo "✗ Error: no SHA-256 tool found (need sha256sum, shasum, or openssl)." >&2
  exit 1
fi

FETCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Write manifest (unchanged keys)
{
  echo "source_url=$SRC_URL"
  echo "fetched_at_utc=$FETCHED_AT"
  echo "release_notes_sha256=$NOTES_SHA"
} > "$MANIFEST_FILE"

git add "$NOTES_FILE" "$MANIFEST_FILE"

# Commit only if there are changes
if git diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

git commit -m "Update release notes from $SRC_URL at $FETCHED_AT (saved as $NOTES_FILE)"
git push origin HEAD:main

# Print the commit pushed (for CI to capture)
RELNOTES_SHA="$(git rev-parse HEAD)"
echo "release_notes_repo_commit=$RELNOTES_SHA"
echo "✅ Updated $NOTES_FILE and $MANIFEST_FILE and pushed $RELNOTES_SHA"