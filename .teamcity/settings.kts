import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot
import jetbrains.buildServer.configs.kotlin.CheckoutMode

version = "2025.07"

project {
    // ---- Global params you can override in TC UI ----
    params {
        param("env.GIT_USER_NAME", "Varshini Raghunath")
        param("env.GIT_USER_EMAIL", "your.email@example.com")
        // Add these as secure Password parameters in TC UI:
        // env.GH_PAT_NOTES  -> write access to release-notes repo
        // env.GH_PAT_PARENT -> write access to parent repo
    }

    // VCS roots
    vcsRoot(ReleaseNotesVcs)
    vcsRoot(ParentRepoVcs)

    // Build configs
    buildType(UpdateReleaseNotes)      // A
    buildType(BumpSubmoduleInParent)   // B

    // Run B after A (so NOTES_SHA flows)
    BumpSubmoduleInParent.dependsOn(UpdateReleaseNotes)
}

/* ------------ VCS roots ------------ */

object ReleaseNotesVcs : GitVcsRoot({
    id("ReleaseNotesVcs")
    name = "release-notes"
    url = "https://github.com/Varshraghu98/release-notes.git"
    branch = "refs/heads/main"
})

object ParentRepoVcs : GitVcsRoot({
    id("ParentRepoVcs")
    name = "parent-maven-repo"
    url = "https://github.com/Varshraghu98/reproducable-mvn-build.git"
    branch = "refs/heads/main"
})

/* ------------ A) Update notes repo ------------ */

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

    steps {
        script {
            name = "fetch + commit + push"
            scriptContent = """
                set -euo pipefail

                # Ensure main is current
                git fetch origin main
                git checkout main
                git pull --rebase origin main

                # Identity (repo-local)
                git config --local user.name  "${'$'}{env.GIT_USER_NAME}"
                git config --local user.email "${'$'}{env.GIT_USER_EMAIL}"

                # Run your script (make sure it writes into ./latest)
                chmod +x ./fetchReleaseNotes.sh
                ./fetchReleaseNotes.sh

                # Commit only if there are changes under latest/
                git add latest
                if git diff --cached --quiet; then
                  echo "No changes to commit."
                else
                  git commit -m "docs(notes): refresh latest release notes"
                fi

                # Prepare remote with PAT for push
                ORIGIN_URL="$(git remote get-url origin)"
                if [[ "${'$'}ORIGIN_URL" =~ ^https:// ]]; then
                  git remote set-url origin "https://x-access-token:${'$'}{GH_PAT_NOTES}@${'$'}{ORIGIN_URL#https://}"
                else
                  git remote set-url origin "https://x-access-token:${'$'}{GH_PAT_NOTES}@github.com/${'$'}{ORIGIN_URL#git@github.com:}"
                fi

                # Push only if new commit exists
                if ! git diff --quiet origin/main..HEAD; then
                  git push origin HEAD:main
                else
                  echo "Nothing to push."
                fi

                # Export resulting SHA for job B
                NOTES_SHA="$(git rev-parse HEAD)"
                echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
                echo "Notes SHA: ${'$'}NOTES_SHA"
            """.trimIndent()
        }
    }
})

/* ------------ B) Bump submodule in parent repo ------------ */

object BumpSubmoduleInParent : BuildType({
    id("BumpSubmoduleInParent")
    name = "B) Bump Submodule in Parent Repo"

    vcs {
        root(ParentRepoVcs)
        checkoutMode = CheckoutMode.ON_AGENT
    }

    steps {
        script {
            name = "advance notes/ to NOTES_SHA + push"
            scriptContent = """
                set -euo pipefail

                test -n "${'$'}{env.NOTES_SHA}" || { echo "NOTES_SHA not provided from A"; exit 1; }

                # Sync parent repo
                git fetch origin main
                git checkout main
                git pull --rebase origin main

                # Ensure submodule exists & is initialized
                git submodule update --init --recursive

                # Checkout the exact SHA produced by A
                pushd notes >/dev/null
                  git fetch --prune origin
                  git checkout "${'$'}{env.NOTES_SHA}"
                popd >/dev/null

                # Commit only if pointer changed
                git add notes
                if git diff --cached --quiet; then
                  echo "Submodule already at desired SHA."
                else
                  git config --local user.name  "${'$'}{env.GIT_USER_NAME}"
                  git config --local user.email "${'$'}{env.GIT_USER_EMAIL}"
                  SHORT="$(cd notes && git rev-parse --short HEAD)"
                  git commit -m "chore(notes): bump submodule to ${'$'}SHORT"
                fi

                # Push with PAT
                ORIGIN_URL="$(git remote get-url origin)"
                if [[ "${'$'}ORIGIN_URL" =~ ^https:// ]]; then
                  git remote set-url origin "https://x-access-token:${'$'}{GH_PAT_PARENT}@${'$'}{ORIGIN_URL#https://}"
                else
                  git remote set-url origin "https://x-access-token:${'$'}{GH_PAT_PARENT}@github.com/${'$'}{ORIGIN_URL#git@github.com:}"
                fi

                if ! git diff --quiet origin/main..HEAD; then
                  git push origin HEAD:main
                else
                  echo "Nothing to push."
                fi
            """.trimIndent()
        }
    }

    // Make sure A runs first and passes env.NOTES_SHA
    dependencies {
        snapshot(UpdateReleaseNotes) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
        }
    }
})
