# Pull request verification

Push freely to feature branches while iterating. Branch-only pushes do not run
verification. When genuinely ready, update the branch onto current `main` and
open a normal, ready pull request. No draft lifecycle or ready-cycle epoch is
required.

Verification runs on PR opening, reopening, new commits, ready-for-review and
edits (including base retargeting). New PR code must pass fresh checks. If `main`
advances, update the branch and verify the resulting head/base again. Reruns are
allowed for an unchanged merge candidate; a passing result does not expire
because of a later ready transition. Resolve merge conflicts first: GitHub does
not start `pull_request` workflows on conflicted merge candidates.

There is no push, post-merge or scheduled verification trigger, no automatic
draft conversion and no automatic merging. A PR edit can rerun checks even when
it changes only metadata. Merging and deploying still require separate explicit
authorization.

## What blocks merging

`Ready verification` remains the only required check context, bound to the
GitHub Actions app (ID **15368**). Keeping this existing name preserves protection
during rollout; it no longer means a draft-to-ready lifecycle.

The aggregate always executes and requires successful classification, Python/
policy tests and Android verification or explicit evidence that Android is
unnecessary. Missing, failed, skipped or cancelled dependencies fail closed.
At both the start and the gate, read-only API checks reject a closed/draft PR,
changed head/source, changed base, changed or unavailable merge candidate,
wrong workflow source/attempt, inactive or concluded run, and API errors.
Concurrency cancels older runs for the same PR; cancellation is not success.
Transient `queued` run metadata is accepted only with no terminal conclusion
and actual successful job results plus a final cancellation guard.

Checkout uses GitHub's immutable PR merge candidate, not a mutable branch.
Strict up-to-date branch protection also blocks a previously green result after
`main` advances. An unchanged head/base/merge candidate can retain its successful
check without creating a new readiness event. Do not copy the required job name
into another workflow.

## Coverage and permissions

All verification runs on **Ubuntu 24.04**. Python sender/release-configuration
tests are portable; Linux also exercises the existing POSIX `0600` private-file
assertion. Node policy tests use its built-in runner. Android verification runs
debug unit tests, APK build, lint and all instrumentation tests on stable API 37
(SDK package `android-37.0`, Google APIs x86_64). This is the only instrumentation
runtime; compile/target SDK remains 36 and minimum SDK remains 26. No release
signing, Firebase client configuration, Play
credentials or protected environment is used.
The emulator uses an explicit Pixel 2 profile, four cores, 2 GiB RAM and a
512 MiB heap instead of the legacy no-profile AVD's 64 MiB heap. Boot timeout or
failed native tests still fail the required gate.

## Runtime and completion evidence

The Android job reuses one job-local Gradle daemon across build/lint/unit and
instrumentation invocations, then stops it in bounded, always-run cleanup.
Gradle's existing up-to-date task reuse is retained; no build task or test is
removed. No cross-PR cache, retries, release configuration or signing input is
introduced.

All 13 expressions still render at all 21 sampled frames with the same pixel,
wordmark and state assertions. The test no longer encodes and writes 273 PNGs
to emulator storage: no repository command or CI artifact consumed those files.
Assertion failures retain expression/frame/pixel coordinates; Gradle stack traces
and failure-only XML/HTML/lint reports remain available (three-day retention,
one-minute upload limit). Successful runs upload no diagnostic artifacts.

Both Android suites must produce XML reports matching every `@Test` method in
their respective source trees exactly once. Missing, empty, malformed, partial,
duplicate, unexpected, failed or skipped results fail verification even when
Gradle exits zero. Unsupported source test declarations fail closed and require
an explicit inventory-parser update, not an exemption.

This catches a real false-green baseline:
[run 43128841819](https://github.com/miquelmartin/Blinky/actions/runs/43128841819)
spent 7m24s in Android (2m46s build/lint/unit, 4m32s emulator/native), but its APK
installer reported `Requested internal only, but not enough space` before Gradle
printed `BUILD SUCCESSFUL`. That is **not** a complete-native-coverage timing
baseline. The API37 AVD now explicitly has a 4 GiB data partition, preserving its
Pixel 2/four-core/2 GiB RAM/512 MiB heap settings.

Verification job ceilings are 2 + 3 + 15 + 2 = **22 aggregate runner-minutes per
attempt**, down from 55. This is not measured savings, parallel wall time or a
billing cap; reruns and multiple PRs multiply usage. The Android limit includes
headroom over the observed 7m24s job, but complete coverage must fit or the run
fails. Build/lint/unit has a five-minute process deadline (six-minute step);
native execution has three minutes after emulator setup, boot has five minutes,
and its overall action has eight minutes. Process deadlines use a finite kill
grace, daemon cleanup has 30 seconds, and all remaining steps also have finite
limits. A timeout is failure, never proof that remaining tests were unnecessary.
The source/report validator and retained failure reports are the completion
evidence; a zero exit code alone is insufficient.

Android is omitted only when every changed path is known-independent: `README.md`,
Markdown under `docs/` or `.github/skills/`, or files under `sender/`. The complete
base-to-merge diff includes deletions and both sides of renames. Empty diffs,
unknown files, non-Markdown documentation assets, Android sources, workflows,
build files, wrappers and dependencies all require Android. The Android job
never skips: it records either completed verification or explicit
`proven-unnecessary` evidence, which the aggregate validates.

The workflow uses only `pull_request` events, read-only permissions and checkout
without persisted Git credentials. There is no write-capable PR source job or
`pull_request_target` controller. Dependabot and forks use the same checks without
draft-mutation permission, PATs or release secrets; GitHub may require a
maintainer to approve first-time external workflow execution.

## Protected rollout

The active additive [default-branch ruleset](https://github.com/miquelmartin/Blinky/rules/27062070)
continues to require a PR and `Ready verification` from Actions app **15368**,
with strict up-to-date checking, no bypass actors, no force pushes and no branch
deletion. Its `~DEFAULT_BRANCH` target follows a future default-branch rename.
No ruleset, administrator protection or merge permission is weakened by this
workflow change. Auto-merge availability is not authorization to enable it or
merge any individual PR.

Read back `GET /repos/miquelmartin/Blinky/rulesets/27062070`,
`GET /repos/miquelmartin/Blinky/rules/branches/main` and
`GET /repos/miquelmartin/Blinky/branches/main` when verifying enforcement.
Classic `branches/main/protection` does not report ruleset-only protection.
If protection or current-head verification cannot be established, stop rather
than bypassing it.

Until removal is merged, the old default-branch draft controller can still draft
the replacement PR. For this one-time bootstrap only, finish pushing, let that
controller settle and manually mark the PR ready. The replacement PR must pass
its current-head/base checks and all required coverage before an authorized
protected merge. Local tests do not establish hosted event delivery or emulator
behavior.

## Releases remain separate

The Android release workflow stays manual-only, with exact `main`/owner guards,
serialized non-cancelling execution, a protected `android-testing` environment,
operation-specific signing/Play secrets, fail-closed configuration checks,
APK/AAB signature verification, checksums, short-lived artifacts, internal-track
verification and always-run private-file cleanup. CI success does not authorize
a signed build, release or deployment.
