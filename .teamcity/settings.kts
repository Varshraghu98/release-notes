import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot
import jetbrains.buildServer.configs.kotlin.CheckoutMode
import jetbrains.buildServer.configs.kotlin.buildFeatures.sshAgent
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger

version = "2025.07"

project {
    // ---- You can override these at Project → Parameters in the TC UI
    params {
        param("env.GIT_USER_NAME", "Varshini Raghunath")
        param("env.GIT_USER_EMAIL", "you@example.com")
        // No PATs needed; SSH handles checkout & push
    }

    // VCS roots (SSH)
    vcsRoot(ReleaseNotesVcs)
    vcsRoot(ParentRepoVcs)

    // Build configs
    buildType(UpdateReleaseNotes)      // A
    buildType(BumpSubmoduleInParent)   // B

    // B runs after A (via snapshot dependency defined inside B)
}

/* ------------ VCS roots (SSH + uploaded key) ------------ */

object ReleaseNotesVcs : GitVcsRoot({
    id("ReleaseNotesVcs")
    name = "release-notes (SSH)"
    url = "git@github.com:Varshraghu98/release-notes.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey {
        uploadedKey = "tc-release-bot"   // TeamCity → Project Settings → SSH Keys
    }
})

object ParentRepoVcs : GitVcsRoot({
    id("ParentRepoVcs")
    name = "parent-maven-repo (SSH)"
    url = "git@github.com:Varshraghu98/reproducable-mvn-build.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey {
        uploadedKey = "tc-release-bot"
    }
})

/* ------------ A) Update notes repo (commit & push over SSH) ------------ */

object UpdateReleaseNotes : BuildType({
    id("UpdateReleaseNotes")
    name = "A) Update Release Notes"

    vcs {
        root(ReleaseNotesVcs)
        checkoutMode = CheckoutMode.ON_AGENT
    }

    params {
        // Will be set by the step using a TeamCity service message
        param("env.NOTES_SHA", "")
    }
    artifactRules = "notes-sha.txt"

    features {
        // Make the SSH private key available to the step for 'git push'
        sshAgent {
            teamcitySshKey = "tc-release-bot"
        }
    }

    steps {
        script {
            name = "fetch + commit + push (SSH)"
            scriptContent = """
#!/usr/bin/env bash
set -Eeuo pipefail

# (optional) debug
# set -x

# Trust GitHub host non-interactively
mkdir -p ~/.ssh
ssh-keyscan -H github.com >> ~/.ssh/known_hosts 2>/dev/null || true

git fetch origin main
git checkout main
git pull --rebase origin main

# Identity (repo-local)
git config --local user.name  "${'$'}GIT_USER_NAME"
git config --local user.email "${'$'}GIT_USER_EMAIL"

# Run your fetcher
chmod +x ./fetchReleaseNotes.sh
./fetchReleaseNotes.sh

# Commit only if there are changes under latest/
git add latest
if git diff --cached --quiet; then
  echo "No changes to commit."
else
  git commit -m "docs(notes): refresh latest release notes"
fi

# Push using SSH (TeamCity SSH Agent feature supplies the key)
if ! git diff --quiet origin/main..HEAD; then
  git push origin HEAD:main
else
  echo "Nothing to push."
fi

# Export resulting SHA for job B
NOTES_SHA="$(git rev-parse HEAD)"
echo "${'$'}NOTES_SHA" > notes-sha.txt
echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
echo "Notes SHA: ${'$'}NOTES_SHA"
""".trimIndent()
        }

    }
})

/* ------------ B) Bump submodule in parent repo (SSH) ------------ */

object BumpSubmoduleInParent : BuildType({
    id("BumpSubmoduleInParent")
    name = "B) Bump Submodule in Parent Repo"

    vcs {
        root(ParentRepoVcs)
        checkoutMode = CheckoutMode.ON_AGENT
    }


    features {
        sshAgent {
            teamcitySshKey = "tc-release-bot"
        }
    }

    // 👇 Auto-start B when A succeeds
    triggers {
        finishBuildTrigger {
            buildType = "${UpdateReleaseNotes.id}"   // watch A
            successfulOnly = true                    // only on SUCCESS
        }
    }

    steps {
        script {
            name = "advance release-notes/ to NOTES_SHA + push (SSH)"
            scriptContent = """
                #!/usr/bin/env bash
                set -Eeuo pipefail

                # Always operate inside the checkout dir TeamCity prepared
                cd "${TEAMCITY_BUILD_CHECKOUTDIR}"

                echo "PWD=${'$'}(pwd)"
                git rev-parse --is-inside-work-tree || { echo "Not inside a git work tree"; exit 1; }

                # Read SHA from the artifact subfolder
                if [[ ! -f .dep/update-notes/notes-sha.txt ]]; then
                  echo "notes-sha.txt not found from A in .dep/update-notes/"; exit 1
                fi
                NOTES_SHA="${'$'}(tr -d '[:space:]' < .dep/update-notes/notes-sha.txt)"
                echo "Using NOTES_SHA=${NOTES_SHA}"

                # Trust GitHub host
                mkdir -p ~/.ssh
                ssh-keyscan -H github.com >> ~/.ssh/known_hosts 2>/dev/null || true

                # Sync parent repo (this is your reproducable-mvn-build checkout)
                git fetch origin main
                git checkout main
                git pull --rebase origin main

                # Ensure submodule initialized
                git submodule update --init --recursive

                # Move the submodule to the exact SHA from A
                pushd release-notes >/dev/null
                  git fetch --prune origin
                  git checkout "${NOTES_SHA}"
                popd >/dev/null

                # Commit only if pointer changed
                git add release-notes
                if git diff --cached --quiet; then
                  echo "Submodule already at desired SHA."
                else
                  git config --local user.name  "${GIT_USER_NAME}"
                  git config --local user.email "${GIT_USER_EMAIL}"
                  SHORT="${'$'}(cd release-notes && git rev-parse --short HEAD)"
                  git commit -m "chore(release-notes): bump submodule to ${SHORT}"
                fi

                # Push via SSH Agent (TeamCity provides key)
                if ! git diff --quiet origin/main..HEAD; then
                  git push origin HEAD:main
                else
                  echo "Nothing to push."
                fi
""".trimIndent()
        }
    }

    // Run after A and receive env.NOTES_SHA
    dependencies {
        snapshot(UpdateReleaseNotes) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
        }
        artifacts(UpdateReleaseNotes) {
            // download into a safe subfolder under the checkout dir
            artifactRules = "notes-sha.txt => .dep/update-notes/"
            cleanDestination = true         // now safe; only cleans that folder
            buildRule = sameChainOrLastFinished()
        }
    }
})