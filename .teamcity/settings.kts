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
        param("env.REL_PATH", "mysql-9.0-relnotes-en.pdf")
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
            name = "Vendor release notes"
            scriptContent = """
            chmod +x buildscripts/vendor-release-notes.sh
            buildscripts/vendor-release-notes.sh
        """.trimIndent()
            param("env.GITHUB_NOTES_REPO", "%env.GITHUB_NOTES_REPO%")
            param("env.VENDOR_DIR",        "%env.VENDOR_DIR%")
            param("env.PR_BASE",           "%env.PR_BASE%")
            param("env.REL_PATH",          "%env.REL_PATH%")
            param("env.GIT_USER_NAME",     "%env.GIT_USER_NAME%")
            param("env.GIT_USER_EMAIL",    "%env.GIT_USER_EMAIL%")
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
