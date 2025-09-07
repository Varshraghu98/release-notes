#!/usr/bin/env sh
set -eu

#SRC_URL is the canonical marketing link to fetch the current release notes.
#MANIFEST_FILE records the inputs + content hash so future builds can verify
#exactly what was fetched (reproducibility & audit trail).
: "${SRC_URL:=https://downloads.mysql.com/docs/mysql-9.0-relnotes-en.pdf}"
MANIFEST_FILE="manifest.txt"

#Marketing site availability MUST NOT make the pipeline brittle.
#Enabling retries.
CURL_RETRY_OPTS="--fail --location --silent --show-error --retry 5 --retry-delay 3"
if curl --help all 2>/dev/null | grep -q -- '--retry-connrefused'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-connrefused"
fi
if curl --help all 2>/dev/null | grep -q -- '--retry-all-errors'; then
  CURL_RETRY_OPTS="$CURL_RETRY_OPTS --retry-all-errors"
fi

#Download into a unique temp directory to avoid polluting the repo and to
#ensure repeated runs don't reuse stale files. Always cleaned up on exit.
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

echo "→ Downloading release notes from: $SRC_URL"
(
  cd "$tmpdir"
  curl $CURL_RETRY_OPTS -OJ "$SRC_URL"
)

# Locate the single downloaded file
DOWNLOADED_PATH="$(find "$tmpdir" -maxdepth 1 -type f | head -n 1 || true)"
if [ -z "${DOWNLOADED_PATH:-}" ] || [ ! -s "$DOWNLOADED_PATH" ]; then
  echo "✗ Error: downloaded file is empty or missing." >&2
  exit 1
fi


# - Keep the server-provided filename to avoid accidental renames that would
#   break reproducibility or diffs between runs.
NOTES_FILE="$(basename "$DOWNLOADED_PATH")"
mv -f "$DOWNLOADED_PATH" "./$NOTES_FILE"

# Compute SHA256
if command -v sha256sum >/dev/null 2>&1; then
  NOTES_SHA="$(sha256sum "$NOTES_FILE" | awk '{print $1}')"
fi

FETCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"


# MANIFEST
# - Records:
#   * source_url     → what we fetched
#   * fetched_at_utc → when we fetched it (normalized)
#   * release_notes_sha256 → exact content identity
# - Downstream steps can diff manifests or verify hash equality across commits.
{
  echo "source_url=$SRC_URL"
  echo "fetched_at_utc=$FETCHED_AT"
  echo "release_notes_sha256=$NOTES_SHA"
} > "$MANIFEST_FILE"

# GIT PUBLISH
# Stage notes + manifest. Commit only on change → idempotent reruns
# (no noisy commits if the content hasn’t changed).
git add "$NOTES_FILE" "$MANIFEST_FILE"
if git diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

# Commit message carries URL + timestamp for traceability in history.
git commit -m "Update release notes from $SRC_URL at $FETCHED_AT (saved as $NOTES_FILE)"
git push origin HEAD:main

# Print the commit pushed (for CI to capture)
RELNOTES_SHA="$(git rev-parse HEAD)"
echo "release_notes_repo_commit=$RELNOTES_SHA"
echo "✅ Updated $NOTES_FILE and $MANIFEST_FILE and pushed $RELNOTES_SHA"