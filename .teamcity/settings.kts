import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot
import jetbrains.buildServer.configs.kotlin.CheckoutMode

version = "2025.07"

project {
    params {
        param("env.GIT_USER_NAME", "Varshini Raghunath")
        param("env.GIT_USER_EMAIL", "your.email@example.com")
        // Add as secure Password parameters in TC UI:
        // env.GH_PAT_NOTES  (write to release-notes repo)
        // env.GH_PAT_PARENT (write to parent repo)
    }

    vcsRoot(ReleaseNotesVcs)
    vcsRoot(ParentRepoVcs)

    buildType(UpdateReleaseNotes)
    buildType(BumpSubmoduleInParent)
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
        param("env.NOTES_SHA", "")
    }

    steps {
        script {
            name = "fetch + commit + push"
            scriptContent = """
        /usr/bin/env bash <<'BASH'
        set -Eeuo pipefail

        git fetch origin main
        git checkout main
        git pull --rebase origin main

        # Use your TC params (env.GIT_USER_NAME / env.GIT_USER_EMAIL)
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

        # Push using the credentials already configured in the VCS root
        if ! git diff --quiet origin/main..HEAD; then
          git push origin HEAD:main
        else
          echo "Nothing to push."
        fi

        NOTES_SHA="$(git rev-parse HEAD)"
        echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
        echo "Notes SHA: ${'$'}NOTES_SHA"
        BASH
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

                git fetch origin main
                git checkout main
                git pull --rebase origin main

                git submodule update --init --recursive

                pushd notes >/dev/null
                  git fetch --prune origin
                  git checkout "${'$'}{env.NOTES_SHA}"
                popd >/dev/null

                git add notes
                if git diff --cached --quiet; then
                  echo "Submodule already at desired SHA."
                else
                  git config --local user.name  "${'$'}{env.GIT_USER_NAME}"
                  git config --local user.email "${'$'}{env.GIT_USER_EMAIL}"
                  SHORT="$(cd notes && git rev-parse --short HEAD)"
                  git commit -m "chore(notes): bump submodule to ${'$'}SHORT"
                fi

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

    // <<< The correct way to enforce order: snapshot dependency >>>
    dependencies {
        snapshot(UpdateReleaseNotes) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
        }
    }
})