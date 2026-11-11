# Draft-to-ready verification

Open a **draft** PR targeting `main`. Push freely while it is a draft. When the
branch is up to date and ready, manually select **Ready for review**. This is the
only event that runs verification; opening a ready PR, pushing, merging, and
workflow reruns do not request verification.

Every subsequent push returns the PR to draft. To check a new head, update the
branch with `main` as needed, then select **Ready for review** again. To retry a
failed or cancelled run, select **Convert to draft**, then **Ready for review**;
do not use **Re-run jobs**. Updating a branch is a push and needs the same cycle.
Merge conflicts must be resolved first: GitHub does not start `pull_request`
workflows on conflicted merge candidates.

## What blocks merging

`Ready verification` is the **only required check context**, bound to the GitHub
Actions app (ID **15368**). The aggregate job always executes, even when a
dependency fails or is skipped, and accepts only actual successful dependencies.
It rejects a changed head, changed base, changed merge candidate, closed/draft PR,
missing or superseded readiness transition, API failure, and rerun attempts.
Ambiguous same-second ready events and superseded duplicate runs also
fail closed; retry with a new draft-to-ready cycle.
Concurrency cancels older verification runs for the same PR; cancellation is not
success. Checks belong to GitHub's immutable PR merge candidate, not a mutable
branch checkout. Strict up-to-date branch protection also prevents a previously
green result from authorizing a merge after `main` advances.

All CI jobs run on **Ubuntu 24.04**. The Python sender and release-configuration
tests are portable; none requires Windows. Linux also exercises the existing
POSIX `0600` private-file permission assertion, which Windows cannot enforce.
The Node policy tests use Node's built-in runner. Android verification runs debug
unit tests, APK build, lint, and the
existing instrumentation tests on an API 35 x86_64 emulator. No release signing,
Firebase client config, Play credentials, or protected environment is used.

Android is omitted only when every changed path is known-independent: `README.md`,
Markdown under `docs/` or `.github/skills/`, or files under `sender/`. The complete
base-to-merge diff includes deletions and both sides of renames. Empty diffs,
unknown files, non-Markdown documentation assets, Android sources, workflows,
build files, wrappers, and dependencies all require Android. The Android job
itself never skips: it records either completed verification or explicit
`proven-unnecessary` evidence, which the aggregate validates.

## Permissions and event behavior

`ready-verification.yml` uses `pull_request`, read-only permissions, checkout of
the event's merge commit, and no persisted checkout credentials. No write-capable
job runs PR source. `draft-on-push.yml` uses `pull_request_target` only to read PR
metadata and perform `convertPullRequestToDraft`, with `pull-requests: write`.
It never checks out code, loads PR scripts, or reads release secrets. It ignores
obsolete heads and delayed events superseded by a later explicit ready cycle.

The draft mutation uses `GITHUB_TOKEN`. Events produced by that token generally
do **not** start other workflows. This design does not rely on them: the
contributor must manually mark the PR ready, producing the real
`ready_for_review` event. No PAT, App installation, paid integration, automatic
ready transition, or privileged execution of PR code is needed.

GitHub delivers webhooks asynchronously. After a push, a new head has no passing
required check and cannot merge, even before the metadata workflow returns it to
draft. Missing, skipped, failed, cancelled and stale verification is never an
accepted replacement for a successful current aggregate. Do not copy the
`Ready verification` job name into another workflow.

## Protection bootstrap and readback

Protect the **live default branch**, currently `main`, with:

- Require a pull request (no additional human approval count is imposed).
- Required check: **`Ready verification`**, GitHub Actions app **15368**.
- Require branches to be up to date (`strict_required_status_checks_policy: true`).
- Enforce protection for administrators; no bypass allowances.
- Disallow force pushes and deletion.
- Enable repository auto-merge availability, not auto-merge on individual PRs.

The active additive [default-branch ruleset](https://github.com/miquelmartin/Blinky/rules/27062070)
has no bypass actors; readback reports `current_user_can_bypass: never`, including
for the repository administrator. It targets `~DEFAULT_BRANCH`, so it follows a
future default-branch rename. There were no existing branch protections or
rulesets at setup. Creating an independent ruleset avoids overwriting any stronger
existing or concurrent protection. Never weaken a rule or temporarily bypass it
to bootstrap CI.

The equivalent `POST /repos/miquelmartin/Blinky/rulesets` payload is shown below.
Inspect existing rulesets before creating one; do not create duplicates or
replace existing rules wholesale:

```json
{
  "name": "Draft-to-ready default branch",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": {
    "ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}
  },
  "rules": [
    {"type": "deletion"},
    {"type": "non_fast_forward"},
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          {"context": "Ready verification", "integration_id": 15368}
        ]
      }
    }
  ]
}
```

Enable availability separately with
`PATCH /repos/miquelmartin/Blinky` and `{"allow_auto_merge": true}`.
Read back `GET /repos/miquelmartin/Blinky/rulesets/27062070`,
`GET /repos/miquelmartin/Blinky/rules/branches/main`,
`GET /repos/miquelmartin/Blinky/branches/main`, and
`GET /repos/miquelmartin/Blinky` after any settings change. The classic
`branches/main/protection` endpoint does not report ruleset-only protection.
Readback confirmed `protected: true`, the required Actions-bound context with
strict checking, all four active rules, no bypass, and `allow_auto_merge: true`.
Exact API results, not documentation, determine
activation. If permissions or the GitHub plan prevent enforcement, stop rather
than weakening protections.

The verification workflow can run from a new PR, but the metadata-only target
workflow is **not active until this configuration is merged into `main`**.
Its absence never makes missing verification pass. Bootstrap through a PR with
the required verification; never direct-push or bypass admins. Before merging,
local policy/syntax checks do not prove GitHub event delivery, the token's draft
mutation permission, or hosted Linux/emulator behavior. After an approved
merge, exercise a draft PR: ready, verify green, push, observe draft and a missing
new-head check, ready again, then verify that failed/cancelled/stale checks block
merging. Do not merge the exercise PR or dispatch a release without approval.

## Releases remain separate

The existing **Android internal testing release** workflow remains manual-only
and unchanged: exact `main` ref and owner actor guard, serialized non-cancelling
execution, `android-testing` environment restricted to the `main` **branch**,
operation-specific signing/Play secrets, release configuration checks,
APK/AAB signature verification, checksums, seven-day artifacts, committed
internal-track verification, and always-run private-file cleanup.
Enabling auto-merge availability does not authorize a merge, release or deployment.
