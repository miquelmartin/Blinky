# Android dependency graph

GitHub parses the Python requirements and GitHub Actions manifests automatically.
Android's Gradle dependencies need dependency submission to include resolved
versions and transitive dependencies.

The [Android dependency submission workflow](../.github/workflows/dependency-submission.yml)
uses the pinned, supported `gradle/actions/dependency-submission` action. It resolves
the project's dependencies without compiling, testing, packaging, signing or
publishing the app. It requires no Firebase configuration, signing key, Play
credentials or repository secrets.

Once this workflow has been reviewed and merged into `main`, open **Actions >
Android dependency submission > Run workflow**, select **main**, and run it.
The workflow deliberately has no push, pull-request or release trigger. Re-run it
manually after dependency or build-tool changes; Android graph coverage does not
refresh automatically just because a Gradle file changes. Dispatches on other
branches skip the submission job.

Only the submission job receives `contents: write`, as required by GitHub's
Dependency Submission API; checkout does not retain Git credentials. Failures are
not ignored. The job submits dependency metadata to this repository's graph,
without uploading workflow artifacts, caching Gradle state or publishing Build
Scans to an external service. Dependency submission does not itself enable
Dependabot alerts or updates; those are managed separately as described below.
The stable job correlator lets subsequent submissions replace the previous
snapshot instead of retaining dependencies from a separate bootstrap submission.

Inspect the [dependency graph](https://github.com/miquelmartin/Blinky/network/dependencies)
after a successful run. Gradle snapshots should include Firebase Messaging and its
resolved transitive dependencies, along with build and test dependencies. GitHub
can take a few minutes to index a submission. This is separate from build/test
verification: a successful submission is not evidence that the app compiles,
passes tests, receives notifications or has been released.

The workflow is independent of pull request CI and manual Android releases. It
does not replace a required verification check or change release permissions.

## Dependabot alerts and updates

Dependabot alerts and security updates are enabled separately in this repository's
GitHub settings. Alerts identify known vulnerabilities; security updates propose
fixes when GitHub can produce one. Neither setting guarantees a fix exists for
every alert, including transitive Gradle dependencies controlled by a platform or
build plugin.

The [Dependabot configuration](../.github/dependabot.yml) prepares daily native
version-update checks for Gradle at the repository root, Python requirements in
`sender`, and GitHub Actions. This schedule only activates once the configuration
is reviewed and merged into `main`. GitHub's `daily` interval checks on weekdays;
security-fix PRs are independent of the version-update schedule. The configuration
does not enable automatic merging or release publishing.

Dependabot PRs need the same human review and
[current-head/base verification](pr-verification.md) as other changes. Normal
ready PRs run verification on opening and after bot pushes. No draft conversion
or token mutation permission is needed. Update the branch and verify again if
`main` advances; an unchanged merge candidate may rerun without a ready cycle.
No CI credentials or release secrets are added for Dependabot.

Update PRs do not merge themselves, so dependencies are only upgraded after a
reviewed merge. After merging Gradle changes, manually refresh the Android
dependency submission to keep the graph aligned with the new resolved versions.
