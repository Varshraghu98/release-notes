import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot
import jetbrains.buildServer.configs.kotlin.CheckoutMode
import jetbrains.buildServer.configs.kotlin.buildFeatures.sshAgent
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.Dependencies.*

version = "2025.07"

project {
    params {
        // Commit identity for both repos
        param("env.GIT_USER_NAME", "Varshini Raghunath")
        param("env.GIT_USER_EMAIL", "you@example.com")

        // Paths / repo names
        param("env.GITHUB_PARENT_REPO", "Varshraghu98/reproducable-mvn-build")
        param("env.GITHUB_NOTES_REPO", "Varshraghu98/release-notes")
        param("env.VENDOR_DIR", "vendor/release-notes")
        param("env.PR_BASE", "main") // we’ll push directly to this branch
    }

    // VCS roots (SSH)
    vcsRoot(ReleaseNotesVcs)
    vcsRoot(ParentRepoVcs)

    // Build configs
    buildType(UpdateReleaseNotes)     // A (unchanged)
    buildType(VendorNotesDirectPush)  // B (new: direct push to main)
}

/* ------------ VCS roots (SSH) ------------ */

object ReleaseNotesVcs : GitVcsRoot({
    id("ReleaseNotesVcs")
    name = "release-notes (SSH)"
    url = "git@github.com:Varshraghu98/release-notes.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey { uploadedKey = "tc-release-bot" }
})

object ParentRepoVcs : GitVcsRoot({
    id("ParentRepoVcs")
    name = "parent-maven-repo (SSH)"
    url = "git@github.com:Varshraghu98/reproducable-mvn-build.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey { uploadedKey = "tc-release-bot" }
})

/* ------------ A) Update notes repo (unchanged) ------------ */

object UpdateReleaseNotes : BuildType({
    id("UpdateReleaseNotes")
    name = "A) Update Release Notes"

    vcs {
        root(ReleaseNotesVcs)
        checkoutMode = CheckoutMode.ON_AGENT
    }

    params { param("env.NOTES_SHA", "") }

    // keep archiving the SHA (and human-friendly copy)
    artifactRules = """
        notes-sha.txt
        notes.txt
    """.trimIndent()

    features { sshAgent { teamcitySshKey = "tc-release-bot" } }

    steps {
        script {
            name = "fetch + commit + push (SSH)"
            scriptContent = """
#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p ~/.ssh
ssh-keyscan -H github.com >> ~/.ssh/known_hosts 2>/dev/null || true

git fetch origin main
git checkout main
git pull --rebase origin main

git config --local user.name  "${'$'}GIT_USER_NAME"
git config --local user.email "${'$'}GIT_USER_EMAIL"

chmod +x ./fetchReleaseNotes.sh
./fetchReleaseNotes.sh

git add latest
if git diff --cached --quiet; then
  echo "No changes to commit."
else
  git commit -m "docs(notes): refresh latest release notes"
fi

if ! git diff --quiet origin/main..HEAD; then
  git push origin HEAD:main
else
  echo "Nothing to push."
fi

NOTES_SHA="$(git rev-parse HEAD)"
echo "${'$'}NOTES_SHA" > notes-sha.txt
echo "Latest release-notes commit: ${'$'}NOTES_SHA" > notes.txt
echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
echo "Notes SHA: ${'$'}NOTES_SHA"
""".trimIndent()
        }
    }
})

/* ------------ B) Vendor notes and push to main (SSH) ------------ */

object VendorNotesDirectPush : BuildType({
    id("VendorNotesDirectPush")
    name = "B) Vendor Release Notes into Parent (Direct Push to main)"

    vcs {
        root(ParentRepoVcs)
        checkoutMode = CheckoutMode.ON_AGENT
    }

    features { sshAgent { teamcitySshKey = "tc-release-bot" } }

    // Auto-run B after A succeeds
    triggers {
        finishBuildTrigger {
            buildType = "${UpdateReleaseNotes.id}"
            successfulOnly = true
        }
    }

    steps {
        script {
            name = "Vendor notes @ NOTES_SHA + commit to main (SSH)"
            workingDir = "%teamcity.build.checkoutDir%"
            scriptContent = """
#!/usr/bin/env bash
set -Eeuo pipefail

: "${'$'}{GIT_USER_NAME:=TeamCity Bot}"
: "${'$'}{GIT_USER_EMAIL:=tc-bot@example.invalid}"
: "${'$'}{GITHUB_NOTES_REPO:?Missing env.GITHUB_NOTES_REPO (owner/repo)}"
: "${'$'}{VENDOR_DIR:=vendor/release-notes}"
: "${'$'}{PR_BASE:=main}"

# --- Input SHA from A
if [[ ! -f .dep/update-notes/notes-sha.txt ]]; then
  echo "ERROR: .dep/update-notes/notes-sha.txt not found"
  ls -la .dep || true; ls -la .dep/update-notes || true
  exit 1
fi
NOTES_SHA="$(tr -d '[:space:]' < .dep/update-notes/notes-sha.txt)"
echo "Vendoring release-notes at ${'$'}NOTES_SHA"

# --- Prep local repo on main
mkdir -p ~/.ssh
ssh-keyscan -H github.com >> ~/.ssh/known_hosts 2>/dev/null || true
git fetch origin "${'$'}PR_BASE"
git checkout "${'$'}PR_BASE"
git pull --rebase origin "${'$'}PR_BASE"

# --- Clone notes repo at exact SHA to temp
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${'$'}TMP_DIR"' EXIT
git clone --no-checkout "git@github.com:${'$'}GITHUB_NOTES_REPO.git" "${'$'}TMP_DIR/notes"
pushd "${'$'}TMP_DIR/notes" >/dev/null
  git fetch --depth=1 origin "${'$'}NOTES_SHA":"${'$'}NOTES_SHA"
  git checkout --force "${'$'}NOTES_SHA"
popd >/dev/null

# --- Copy content (assumes notes under 'latest/' in notes repo)
DEST_DIR="${'$'}VENDOR_DIR/latest"
rm -rf "${'$'}DEST_DIR"
mkdir -p "${'$'}DEST_DIR"
rsync -a --delete "${'$'}TMP_DIR/notes/latest/" "${'$'}DEST_DIR/"


echo "${'$'}NOTES_SHA" > "${'$'}VENDOR_DIR/notes-sha.txt"
{
  echo "Vendored-From: ${'$'}GITHUB_NOTES_REPO"
  echo "Source-Commit: ${'$'}NOTES_SHA"
  echo "Fetched-At: ${'$'}(date -u +%%%%Y-%%%%m-%%%%dT%%%%H:%%%%M:%%%%SZ)"
} > "${'$'}VENDOR_DIR/MODULE.txt"

# --- Commit if there are changes; push to main
git add "${'$'}VENDOR_DIR"
if git diff --cached --quiet; then
  echo "No changes to vendor directory. Nothing to push."
  exit 0
fi

git config --local user.name  "${'$'}GIT_USER_NAME"
git config --local user.email "${'$'}GIT_USER_EMAIL"
SHORT="$(git rev-parse --short "${'$'}NOTES_SHA")"
git commit -m "docs(notes): vendor release-notes @ ${'$'}SHORT"

# Pull --rebase once more to reduce push conflicts on a busy main
git pull --rebase origin "${'$'}PR_BASE"
git push origin HEAD:"${'$'}PR_BASE"

echo "Pushed vendored notes to ${'$'}PR_BASE"
""".trimIndent()
        }
    }

    dependencies {
        // artifacts from A
        artifacts(UpdateReleaseNotes) {
            artifactRules = "notes-sha.txt => .dep/update-notes/"
            cleanDestination = true
            buildRule = sameChainOrLastFinished()
        }
        // order
        snapshot(UpdateReleaseNotes) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
            reuseBuilds = ReuseBuilds.SUCCESSFUL
        }
    }
})
