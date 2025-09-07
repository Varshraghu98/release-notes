import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.sshAgent
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2025.07"

project {

    vcsRoot(GitGithubComVarshraghu98releaseNotesGitRefsHeadsMain)
    vcsRoot(ParentRepoVcs)
    vcsRoot(ReleaseNotesVcs)

    buildType(VendorNotesDirectPush)
    buildType(UpdateReleaseNotes)

    params {
        param("env.VENDOR_DIR", "vendor/release-notes")
        param("env.REL_PATH", "mysql-9.0-relnotes-en.pdf")
        param("env.GITHUB_PARENT_REPO", "Varshraghu98/reproducable-mvn-build")
        param("env.GIT_USER_EMAIL", "varshini@gmail.com")
        param("env.GITHUB_NOTES_REPO", "Varshraghu98/release-notes")
        param("env.PR_BASE", "main")
        param("env.GIT_USER_NAME", "Varshini Raghunath")
    }
}

object UpdateReleaseNotes : BuildType({
    name = "Fetch and Update Release Notes"

    artifactRules = """
        notes-sha.txt
        manifest.txt
    """.trimIndent()

    params {
        param("env.NOTES_SHA", "")
    }

    vcs {
        root(ReleaseNotesVcs)

        checkoutMode = CheckoutMode.ON_AGENT
        cleanCheckout = true
    }

    steps {
        script {
            name = "Run fetchReleaseNotes.sh (commit + push)"
            scriptContent = """
                set -eu
                git config --local user.name  "%env.GIT_USER_NAME%"
                git config --local user.email "%env.GIT_USER_EMAIL%"
                
                chmod +x ./fetchReleaseNotes.sh
                ./fetchReleaseNotes.sh
                
                NOTES_SHA=${'$'}(git rev-parse HEAD)
                printf "%s\n" "${'$'}NOTES_SHA" > notes-sha.txt
                echo "##teamcity[setParameter name='env.NOTES_SHA' value='${'$'}NOTES_SHA']"
                echo "Updated release-notes to ${'$'}NOTES_SHA"
            """.trimIndent()
        }
    }

    features {
        sshAgent {
            teamcitySshKey = "tc-release-bot"
        }
    }
})

object VendorNotesDirectPush : BuildType({
    name = "Vendor Release Notes into Parent (Direct Push to main)"

    vcs {
        root(ParentRepoVcs)

        checkoutMode = CheckoutMode.ON_AGENT
        cleanCheckout = true
    }

    steps {
        script {
            name = "Vendor release notes"
            scriptContent = """
                chmod +x buildscripts/vendor-release-notes.sh
                buildscripts/vendor-release-notes.sh
            """.trimIndent()
            param("env.VENDOR_DIR", "%env.VENDOR_DIR%")
            param("env.REL_PATH", "%env.REL_PATH%")
            param("env.GIT_USER_EMAIL", "%env.GIT_USER_EMAIL%")
            param("env.GITHUB_NOTES_REPO", "%env.GITHUB_NOTES_REPO%")
            param("env.PR_BASE", "%env.PR_BASE%")
            param("env.GIT_USER_NAME", "%env.GIT_USER_NAME%")
        }
    }

    triggers {
        finishBuildTrigger {
            buildType = "${UpdateReleaseNotes.id}"
            successfulOnly = true
        }
    }

    features {
        sshAgent {
            teamcitySshKey = "tc-release-bot"
        }
    }

    dependencies {
        dependency(UpdateReleaseNotes) {
            snapshot {
                onDependencyFailure = FailureAction.FAIL_TO_START
                onDependencyCancel = FailureAction.CANCEL
            }

            artifacts {
                cleanDestination = true
                artifactRules = "notes-sha.txt => .dep/update-notes/"
            }
        }
    }
})

object GitGithubComVarshraghu98releaseNotesGitRefsHeadsMain : GitVcsRoot({
    name = "git@github.com:Varshraghu98/release-notes.git#refs/heads/main"
    url = "git@github.com:Varshraghu98/release-notes.git"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    authMethod = uploadedKey {
        uploadedKey = "tc-release-bot"
    }
})

object ParentRepoVcs : GitVcsRoot({
    name = "parent-maven-repo (SSH)"
    url = "git@github.com:Varshraghu98/reproducable-mvn-build.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey {
        uploadedKey = "tc-release-bot"
    }
})

object ReleaseNotesVcs : GitVcsRoot({
    name = "release-notes (SSH)"
    url = "git@github.com:Varshraghu98/release-notes.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey {
        uploadedKey = "tc-release-bot"
    }
})
