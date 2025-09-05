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
        param("env.GIT_USER_EMAIL", "varshini@gmail.com")

        // Paths / repo names
        param("env.GITHUB_PARENT_REPO", "Varshraghu98/reproducable-mvn-build")
        param("env.GITHUB_NOTES_REPO", "Varshraghu98/release-notes")
        param("env.VENDOR_DIR", "vendor/release-notes")
        param("env.PR_BASE", "main") // we push directly to this branch
    }

    // VCS roots (SSH)
    vcsRoot(ReleaseNotesVcs)
    vcsRoot(ParentRepoVcs)

    // Build configs
    buildType(UpdateReleaseNotes)     // A
    buildType(VendorNotesDirectPush)  // B
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

/* ------------ A) Fetch + Update release-notes repo ------------ */

object UpdateReleaseNotes : BuildType({
    id("UpdateReleaseNotes")
    name = "Fetch and Update Release Notes"

    vcs {
        root(ReleaseNotesVcs)
        checkoutMode = CheckoutMode.ON_AGENT
        cleanCheckout = true
    }

    params {
        // Will be set after commit
        param("env.NOTES_SHA", "")
    }

    // Archive the updated content + the SHA we produced
    artifactRules = """
        notes-sha.txt
        release.txt
        manifest.txt
    """.trimIndent()

    features { sshAgent { teamcitySshKey = "tc-release-bot" } }

    steps {
        script {
            name = "Run fetchReleaseNotes.sh (commit + push)"
            scriptContent = """
                set -eu
                git config --local user.name  "%env.GIT_USER_NAME%"
                git config --local user.email "%env.GIT_USER_EMAIL%"

                chmod +x ./fetchReleaseNotes.sh
                ./fetchReleaseNotes.sh

                NOTES_SHA=$(git rev-parse HEAD)
                printf "%s\n" "${'$'}NOTES_SHA" > notes-sha.txt
                echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
                echo "Updated release-notes to ${'$'}NOTES_SHA"
            """.trimIndent()
        }
    }
})

/* ------------ B) Vendor notes into parent repo (direct push) ------------ */

object VendorNotesDirectPush : BuildType({
    id("VendorNotesDirectPush")
    name = "Vendor Release Notes into Parent (Direct Push to main)"

    vcs {
        root(ParentRepoVcs)
        checkoutMode = CheckoutMode.ON_AGENT
        cleanCheckout = true
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
                #!/usr/bin/env sh
                set -eu
                : "${'$'}{GITHUB_NOTES_REPO:=%env.GITHUB_NOTES_REPO%}"
                : "${'$'}{VENDOR_DIR:=%env.VENDOR_DIR%}"
                : "${'$'}{PR_BASE:=%env.PR_BASE%}"

                # --- Input SHA from A
                if [ ! -f .dep/update-notes/notes-sha.txt ]; then
                  echo "ERROR: .dep/update-notes/notes-sha.txt not found"; exit 1
                fi
                NOTES_SHA=$(tr -d '[:space:]' < .dep/update-notes/notes-sha.txt)
                echo "Vendoring release-notes at ${'$'}NOTES_SHA"

                # --- Prep local repo on PR_BASE
                git fetch origin "${'$'}PR_BASE"
                git checkout "${'$'}PR_BASE"
                git pull --rebase origin "${'$'}PR_BASE"

                # --- Clone notes repo at exact SHA to temp
                TMP_DIR=$(mktemp -d)
                trap 'rm -rf "${'$'}TMP_DIR"' EXIT
                git clone --no-checkout "git@github.com:${'$'}GITHUB_NOTES_REPO.git" "${'$'}TMP_DIR/notes"
                git -C "${'$'}TMP_DIR/notes" fetch --depth=1 origin "${'$'}NOTES_SHA":"${'$'}NOTES_SHA"
                git -C "${'$'}TMP_DIR/notes" checkout --force "${'$'}NOTES_SHA"

                # --- Copy ONLY root files: release.txt + manifest.txt
                DEST_DIR="${'$'}VENDOR_DIR"
                mkdir -p "${'$'}DEST_DIR"
                cp -f "${'$'}TMP_DIR/notes/release.txt"  "${'$'}DEST_DIR/release.txt"
                cp -f "${'$'}TMP_DIR/notes/manifest.txt" "${'$'}DEST_DIR/manifest.txt"

                # --- Integrity: ensure manifest's hash matches the file (if present)
                exp=$(grep '^release_txt_sha256=' "${'$'}DEST_DIR/manifest.txt" | cut -d= -f2 || true)
                if [ -n "${'$'}exp" ]; then
                  act=$(sha256sum "${'$'}DEST_DIR/release.txt" | awk '{print $1}')
                  if [ "${'$'}exp" != "${'$'}act" ]; then
                    echo "ERROR: release.txt hash mismatch (manifest=${'$'}exp, actual=${'$'}act)"; exit 1
                  fi
                fi

                # --- Update manifest with the vendored commit + metadata
                # remove old lines if present
                sed -i.bak '/^release_notes_repo_commit=/d;/^vendored_/d' "${'$'}DEST_DIR/manifest.txt" || true
                rm -f "${'$'}DEST_DIR/manifest.txt.bak"
                {
                  echo "release_notes_repo_commit=${'$'}NOTES_SHA"
                  echo "vendored_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
                  echo "vendored_parent_repo=$(git remote get-url origin 2>/dev/null || echo parent)"
                } >> "${'$'}DEST_DIR/manifest.txt"

                # --- Commit if there are changes; push to main
                git add "${'$'}DEST_DIR/release.txt" "${'$'}DEST_DIR/manifest.txt"
                if git diff --cached --quiet; then
                  echo "No changes to vendor directory. Nothing to push."
                  exit 0
                fi

                git config --local user.name  "%env.GIT_USER_NAME%"
                git config --local user.email "%env.GIT_USER_EMAIL%"
                SHORT=$(git rev-parse --short "${'$'}NOTES_SHA")
                git commit -m "docs(notes): vendor release-notes @ ${'$'}SHORT"
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
