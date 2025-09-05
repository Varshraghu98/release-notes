#!/usr/bin/env sh
set -eu

# -------- Config (override via env in TeamCity) --------
: "${GITHUB_NOTES_REPO:=Varshraghu98/release-notes}"   # owner/repo
: "${VENDOR_DIR:=vendor/release-notes}"                # where to copy in parent repo
: "${PR_BASE:=main}"                                   # branch to push to
# NOTES_SHA: preferred via env; otherwise read from .dep/update-notes/notes-sha.txt
# -------------------------------------------------------

# Determine which commit to vendor
if [ -n "${NOTES_SHA:-}" ]; then
  SHA="$NOTES_SHA"
elif [ -f ".dep/update-notes/notes-sha.txt" ]; then
  SHA=$(tr -d '[:space:]' < .dep/update-notes/notes-sha.txt)
else
  echo "ERROR: NOTES_SHA not set and .dep/update-notes/notes-sha.txt not found" >&2
  exit 2
fi

echo "Vendoring release-notes at commit: $SHA"

# Ensure we're on the target branch in the parent repo
git fetch origin "$PR_BASE"
git checkout "$PR_BASE"
git pull --rebase origin "$PR_BASE"

# Clone the release-notes repo at the exact commit
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

git clone --no-checkout "git@github.com:${GITHUB_NOTES_REPO}.git" "$TMP_DIR/notes"
git -C "$TMP_DIR/notes" fetch --depth=1 origin "$SHA":"$SHA"
git -C "$TMP_DIR/notes" checkout --force "$SHA"

# Copy ONLY root files: release.txt + manifest.txt
DEST_DIR="$VENDOR_DIR"
mkdir -p "$DEST_DIR"
cp -f "$TMP_DIR/notes/release.txt"  "$DEST_DIR/release.txt"
cp -f "$TMP_DIR/notes/manifest.txt" "$DEST_DIR/manifest.txt"

# Integrity check: if manifest has a recorded hash, verify it (requires sha256sum)
exp="$(grep '^release_txt_sha256=' "$DEST_DIR/manifest.txt" | cut -d= -f2 || true)"
if [ -n "$exp" ]; then
  if ! command -v sha256sum >/dev/null 2>&1; then
    echo "ERROR: sha256sum not found but manifest contains a checksum" >&2
    exit 127
  fi
  act="$(sha256sum "$DEST_DIR/release.txt" | awk '{print $1}')"
  if [ "$exp" != "$act" ]; then
    echo "ERROR: release.txt hash mismatch (manifest=$exp, actual=$act)" >&2
    exit 1
  fi
fi

# Update manifest with ONLY the vendored commit SHA
# (remove any prior line, then append the new one)
sed -i.bak '/^release_notes_repo_commit=/d' "$DEST_DIR/manifest.txt" || true
rm -f "$DEST_DIR/manifest.txt.bak"
echo "release_notes_repo_commit=$SHA" >> "$DEST_DIR/manifest.txt"

# Commit (only if there are changes) and push
git add "$DEST_DIR/release.txt" "$DEST_DIR/manifest.txt"
if git diff --cached --quiet; then
  echo "No changes to vendor directory. Nothing to push."
  exit 0
fi

: "${GIT_USER_NAME:=TeamCity Bot}"
: "${GIT_USER_EMAIL:=tc-bot@example.invalid}"
git config --local user.name  "$GIT_USER_NAME"
git config --local user.email "$GIT_USER_EMAIL"

SHORT="$(git rev-parse --short "$SHA")"
git commit -m "docs(notes): vendor release-notes @ $SHORT"
git pull --rebase origin "$PR_BASE"
git push origin HEAD:"$PR_BASE"

echo "Pushed vendored notes to $PR_BASE"