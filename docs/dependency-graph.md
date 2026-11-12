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
resolved transitive dependencies, along with build and test dependencies. The
submission labels `debugRuntimeClasspath` and `releaseRuntimeClasspath` dependencies
as runtime and keeps build, lint and test dependencies in development scope; this
classification does not remove tooling dependencies or suppress their alerts.
GitHub can take a few minutes to index a submission. This is separate from build/test
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

## Toolchain security upgrades

The upgraded build uses AGP 9.5.1, Gradle 9.7.0, Kotlin 2.4.21 with AGP's
built-in Kotlin integration, Google Services 4.6.0 and Firebase BoM 35.2.0
(Messaging 26.0.2). The wrapper is regenerated and checksum-pinned. App identity,
minimum/compile/target SDK, saved data, signing requirements and manual releases
are unchanged. Instrumentation CI uses stable Android 17/API 37 only, not a
compile/target SDK migration.

[AGP's compatibility requirements](https://developer.android.com/build/releases/agp-9-5-0-release-notes)
require Gradle 9.6.0 or newer, Build Tools 36.0.0 and JDK 17. Gradle stays at 9.7.0,
not the newer 9.9.x, because it is the upper fully supported version in
[Kotlin 2.4.21's table](https://kotlinlang.org/docs/gradle-configure-project.html).
That table's fully tested AGP range ends at 9.3.1; Kotlin permits newer AGP with
possible compatibility caveats, so 9.5.1 is locally verified rather than claimed
to be inside that range.
[AGP documents the explicit newer KGP classpath dependency](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version);
the incompatible external `kotlin-android` plugin is removed.

Upstream AGP still requests vulnerable tooling libraries. Narrow Gradle
constraints upgrade Guava, Bouncy Castle, jose4j, JDOM and Commons Lang without
changing unrelated configurations or replacing incompatible major APIs. Lint
resolves an isolated `androidLintTool` configuration, so it needs its own
Guava/Bouncy Castle/Commons Lang constraints and compatible HttpClient 4.5.14.
These are real resolved-version changes, not advisory dismissals or dependency
exclusions. Recheck both the build classpath and isolated lint graph when
removing a constraint after a future upstream fix.

Python's google-auth floor is 2.62.1; the upper bound remains below 3. Version
2.62.2 is published, but the available package index and public-wheel TLS
failures prevented local validation of it. TLS checks were not disabled. Other
compatible sender dependencies resolve through the existing requirements.
GitHub Actions are pinned to verified immutable releases; already-current
Gradle, emulator and Play-upload actions retain their pins.

Local validation resolved and scanned 248 version-qualified Maven coordinates,
12 installed Python packages and seven Actions against non-withdrawn
GitHub-reviewed advisories, with no matches. This is not a guarantee against
unknown vulnerabilities or proof that live default-branch alerts have closed.
The prior 58 open alerts remain attributable to the old main snapshot until the
upgrades receive approval, are merged and the manual graph refresh succeeds.

Debug unit/build/lint and all nine API 37 native tests pass without Firebase or
signing credentials. Unchanged main reproduces the duration-scale test race:
the test waited for a callback it had already removed, rather than asynchronous
animator cancellation. Tests now await actual native/lifecycle effects with the
same bounded timeout. The visibility test uses deterministic callback timing and
durable expression history instead of sampling a transient blink; a deliberate
postponement mutation in an isolated baseline copy fails that assertion.
No application behavior or meaningful cancellation/rendering assertion is
removed. Hosted Linux/current-head validation is still required. Signed
packaging, Firebase-configured plugin execution and actual message delivery
require separate authorized validation; the release guard still rejects missing
configuration.
