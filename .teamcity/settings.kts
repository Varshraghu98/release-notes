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
    buildType(UpdateReleaseNotes)
    buildType(VendorNotesDirectPush)
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
            name = "Vendor release notes (script)"
            workingDir = "%teamcity.build.checkoutDir%"
            scriptContent = """
                #!/usr/bin/env bash
                set -euo pipefail

                # ── Configuration (override via TeamCity env) ───────────────────────────────────
                : "${'$'}{GITHUB_NOTES_REPO:=Varshraghu98/release-notes}"   # owner/repo of the notes source
                : "${'$'}{VENDOR_DIR:=vendor/release-notes}"                # parent repo path to place vendored files
                : "${'$'}{PR_BASE:=main}"                                   # parent repo branch to update
                # ────────────────────────────────────────────────────────────────────────────────

                NOTES_SOURCE_GIT_REPO="${'$'}GITHUB_NOTES_REPO"
                VENDORED_NOTES_DIR="${'$'}VENDOR_DIR"
                PARENT_REPO_TARGET_BRANCH="${'$'}PR_BASE"

                # Resolve which commit to vendor
                if [ -n "${'$'}{NOTES_SHA:-}" ]; then
                  NOTES_SOURCE_COMMIT_SHA="${'$'}NOTES_SHA"
                elif [ -f ".dep/update-notes/notes-sha.txt" ]; then
                  NOTES_SOURCE_COMMIT_SHA="${'$'}(tr -d '[:space:]' < .dep/update-notes/notes-sha.txt)"
                else
                  echo "ERROR: NOTES_SHA not set and .dep/update-notes/notes-sha.txt not found" >&2
                  exit 2
                fi

                echo "Vendoring release-notes from '${'$'}NOTES_SOURCE_GIT_REPO' at commit ${'$'}NOTES_SOURCE_COMMIT_SHA"

                # Ensure we are on the target branch in the parent repo
                git fetch origin "${'$'}PARENT_REPO_TARGET_BRANCH"
                git checkout "${'$'}PARENT_REPO_TARGET_BRANCH"
                git pull --rebase origin "${'$'}PARENT_REPO_TARGET_BRANCH"

                # Clone notes repo at the exact commit
                TEMP_NOTES_CLONE_DIR="${'$'}(mktemp -d)"
                cleanup_temp_dir() { rm -rf "${'$'}TEMP_NOTES_CLONE_DIR"; }
                trap cleanup_temp_dir EXIT

                git clone --no-checkout "git@github.com:${'$'}{NOTES_SOURCE_GIT_REPO}.git" "${'$'}TEMP_NOTES_CLONE_DIR/notes"
                git -C "${'$'}TEMP_NOTES_CLONE_DIR/notes" fetch --depth=1 origin "${'$'}NOTES_SOURCE_COMMIT_SHA":"${'$'}NOTES_SOURCE_COMMIT_SHA"
                git -C "${'$'}TEMP_NOTES_CLONE_DIR/notes" checkout --force "${'$'}NOTES_SOURCE_COMMIT_SHA"

                # Require manifest.txt
                MANIFEST_PATH="${'$'}TEMP_NOTES_CLONE_DIR/notes/manifest.txt"
                if [ ! -f "${'$'}MANIFEST_PATH" ]; then
                  echo "ERROR: manifest.txt not found in notes repo at commit ${'$'}NOTES_SOURCE_COMMIT_SHA" >&2
                  exit 1
                fi

                # Read expected checksum (MUST exist)
                EXPECTED_NOTES_SHA256="${'$'}(grep -E '^release_notes_sha256=' "${'$'}MANIFEST_PATH" | cut -d= -f2 || true)"
                if [ -z "${'$'}EXPECTED_NOTES_SHA256" ]; then
                  echo "ERROR: manifest.txt lacks 'release_txt_sha256='; cannot determine notes file." >&2
                  exit 1
                fi

                # Helper for SHA-256
                compute_sha256() {
                  local file_path="${'$'}1"
                  if command -v sha256sum >/dev/null 2>&1; then
                    sha256sum "${'$'}file_path" | awk '{print ${'$'}1}'
                  elif command -v shasum >/dev/null 2>&1; then
                    shasum -a 256 "${'$'}file_path" | awk '{print ${'$'}1}'
                  elif command -v openssl >/dev/null 2>&1; then
                    openssl dgst -sha256 -r "${'$'}file_path" | awk '{print ${'$'}1}'
                  else
                    echo ""
                  fi
                }

                # Gather candidate files in repo root (exclude manifest + dotfiles)
                CANDIDATE_ROOT_FILES=()
                while IFS= read -r -d '' f; do
                  CANDIDATE_ROOT_FILES+=("${'$'}f")
                done < <(find "${'$'}TEMP_NOTES_CLONE_DIR/notes" -maxdepth 1 -type f ! -name 'manifest.txt' ! -name '.*' -print0)

                # Find the single file whose SHA matches EXPECTED_NOTES_SHA256
                MATCHING_FILES=()
                for candidate in "${'$'}{CANDIDATE_ROOT_FILES[@]}"; do
                  candidate_sha="${'$'}(compute_sha256 "${'$'}candidate")"
                  if [ -z "${'$'}candidate_sha" ]; then
                    echo "ERROR: Need a SHA-256 tool (sha256sum/shasum/openssl) to verify manifest." >&2
                    exit 127
                  fi
                  if [ "${'$'}candidate_sha" = "${'$'}EXPECTED_NOTES_SHA256" ]; then
                    MATCHING_FILES+=("${'$'}candidate")
                  fi
                done

                if [ "${'$'}{#MATCHING_FILES[@]}" -eq 0 ]; then
                  echo "ERROR: No file in notes repo root matches manifest checksum: ${'$'}EXPECTED_NOTES_SHA256" >&2
                  echo "Checked candidates:" >&2
                  for c in "${'$'}{CANDIDATE_ROOT_FILES[@]}"; do
                    csha="${'$'}(compute_sha256 "${'$'}c")"
                    echo " - ${'$'}(basename "${'$'}c"): ${'$'}csha" >&2
                  done
                  exit 1
                elif [ "${'$'}{#MATCHING_FILES[@]}" -gt 1 ]; then
                  echo "ERROR: Multiple files match the manifest checksum (ambiguous):" >&2
                  for c in "${'$'}{MATCHING_FILES[@]}"; do echo " - ${'$'}(basename "${'$'}c")" >&2; done
                  exit 1
                fi

                RELEASE_NOTES_SOURCE_PATH="${'$'}{MATCHING_FILES[0]}"
                RELEASE_NOTES_FILENAME="${'$'}(basename "${'$'}RELEASE_NOTES_SOURCE_PATH")"
                echo "Verified release-notes file via SHA-256: ${'$'}RELEASE_NOTES_FILENAME"

                # Copy notes + manifest to vendor dir (preserve filename)
                mkdir -p "${'$'}VENDORED_NOTES_DIR"
                cp -f "${'$'}RELEASE_NOTES_SOURCE_PATH" "${'$'}VENDORED_NOTES_DIR/${'$'}RELEASE_NOTES_FILENAME"
                cp -f "${'$'}MANIFEST_PATH"             "${'$'}VENDORED_NOTES_DIR/manifest.txt"

                # Append vendored commit SHA to manifest (replace previous if any)
                sed -i.bak '/^release_notes_repo_commit=/d' "${'$'}VENDORED_NOTES_DIR/manifest.txt" || true
                rm -f "${'$'}VENDORED_NOTES_DIR/manifest.txt.bak"
                echo "release_notes_repo_commit=${'$'}NOTES_SOURCE_COMMIT_SHA" >> "${'$'}VENDORED_NOTES_DIR/manifest.txt"

                # Stage and push only if changes exist
                git add "${'$'}VENDORED_NOTES_DIR/${'$'}RELEASE_NOTES_FILENAME" "${'$'}VENDORED_NOTES_DIR/manifest.txt"
                if git diff --cached --quiet; then
                  echo "No changes to vendor directory. Nothing to push."
                  exit 0
                fi

                : "${'$'}{GIT_USER_NAME:=TeamCity Bot}"
                : "${'$'}{GIT_USER_EMAIL:=tc-bot@example.invalid}"
                git config --local user.name  "${'$'}GIT_USER_NAME"
                git config --local user.email "${'$'}GIT_USER_EMAIL"

                SHORT_NOTES_COMMIT="${'$'}(git rev-parse --short "${'$'}NOTES_SOURCE_COMMIT_SHA")"
                git commit -m "docs(notes): vendor ${'$'}{RELEASE_NOTES_FILENAME} @ ${'$'}SHORT_NOTES_COMMIT"
                git pull --rebase origin "${'$'}PARENT_REPO_TARGET_BRANCH"
                git push origin HEAD:"${'$'}PARENT_REPO_TARGET_BRANCH"

                echo "Pushed vendored notes (${'$'}RELEASE_NOTES_FILENAME) to ${'$'}PARENT_REPO_TARGET_BRANCH"
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
