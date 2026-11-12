const { test } = require('node:test');
const assert = require('node:assert/strict');
const { androidNeeded, assertResults, assertCurrentPR } = require('./ready-ci.cjs');

test('only known independent sender and Markdown documentation changes avoid Android', () => {
  assert.equal(androidNeeded(['README.md', 'docs/android-releases.md',
    '.github/skills/blinky-notify/SKILL.md', 'sender/send.py']), false);
  for (const path of ['app/src/main/AndroidManifest.xml', 'app/build.gradle.kts',
    'gradle.properties', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
    '.github/workflows/ready-verification.yml', '.github/scripts/prepare_android_release.py',
    'docs/build.sh', '.github/skills/helper.py', 'new-dependency.lock', 'unknown.md']) {
    assert.equal(androidNeeded(['README.md', path]), true, path);
  }
  assert.equal(androidNeeded([]), true);
  // --no-renames includes the deleted Android path as well as its new doc path.
  assert.equal(androidNeeded(['app/build.gradle.kts', 'docs/build.md']), true);
});

function results(android = 'true') {
  return {
    plan: { result: 'success', outputs: { android } },
    python: { result: 'success' },
    android_build: { result: 'success', outputs: {
      evidence: android === 'true' ? 'built' : 'proven-unnecessary', artifact_id: '123'
    } },
    android: { result: 'success', outputs: {
      evidence: android === 'true' ? 'verified' : 'proven-unnecessary'
    } }
  };
}

test('aggregate rejects missing, skipped, failed or cancelled dependencies', () => {
  assertResults(results());
  assertResults(results('false'));
  for (const name of ['plan', 'python', 'android_build', 'android']) {
    for (const result of ['skipped', 'failure', 'cancelled', undefined]) {
      const needs = results();
      needs[name].result = result;
      assert.throws(() => assertResults(needs));
    }
    const needs = results();
    delete needs[name];
    assert.throws(() => assertResults(needs));
  }
});

test('aggregate requires proof matching the Android decision', () => {
  for (const evidence of [undefined, 'verified', 'proven-unnecessary']) {
    const needs = results();
    needs.android_build.outputs.evidence = evidence;
    assert.throws(() => assertResults(needs));
  }
  for (const artifact_id of [undefined, '', '0', 'latest', '123,456']) {
    const needs = results();
    needs.android_build.outputs.artifact_id = artifact_id;
    assert.throws(() => assertResults(needs));
  }
  for (const decision of ['', 'FALSE', undefined]) {
    const needs = results();
    needs.plan.outputs.android = decision;
    assert.throws(() => assertResults(needs));
  }
  for (const decision of ['true', 'false']) {
    const needs = results(decision);
    for (const evidence of [undefined, 'skipped', decision === 'true' ? 'proven-unnecessary' : 'verified']) {
      needs.android.outputs.evidence = evidence;
      assert.throws(() => assertResults(needs));
    }
  }
});

function fixture() {
  const pr = {
    number: 7, state: 'open', draft: false,
    head: { sha: 'head', ref: 'feature', repo: { id: 100 } },
    base: { ref: 'main', sha: 'base' }, merge_commit_sha: 'merge'
  };
  const context = {
    eventName: 'pull_request', runAttempt: 1, runId: 10, repo: { owner: 'owner', repo: 'repo' },
    sha: 'merge', payload: { action: 'opened', pull_request: structuredClone(pr) }
  };
  const run = {
    workflow_id: 1, head_sha: 'head', head_branch: 'feature', head_repository: { id: 100 },
    event: 'pull_request', run_attempt: 1, status: 'in_progress', conclusion: null
  };
  const github = {
    rest: {
      pulls: { get: async () => ({ data: pr }) },
      actions: {
        getWorkflowRun: async () => ({ data: run })
      }
    }
  };
  return { github, context, pr, run };
}

test('executing gate accepts transient queued metadata but not terminal or missing run state', async () => {
  for (const status of ['queued', 'in_progress']) {
    const f = fixture();
    f.run.status = status;
    await assertCurrentPR(f);
  }
  for (const status of ['completed', 'waiting', undefined]) {
    const f = fixture();
    f.run.status = status;
    await assert.rejects(assertCurrentPR(f));
  }
  for (const conclusion of ['cancelled', 'failure', 'success', undefined]) {
    const f = fixture();
    f.run.status = 'queued';
    f.run.conclusion = conclusion;
    await assert.rejects(assertCurrentPR(f));
  }
});

test('only a current ready head, base, merge candidate and source may pass', async () => {
  await assertCurrentPR(fixture());
  const changes = [
    f => { f.context.eventName = 'push'; },
    f => { f.context.payload.action = 'closed'; },
    f => { f.pr.state = 'closed'; },
    f => { f.pr.draft = true; },
    f => { f.pr.head.sha = 'pushed-head'; },
    f => { f.pr.head.ref = 'other-feature'; },
    f => { f.pr.head.repo.id = 200; },
    f => { f.pr.base.sha = 'new-base'; },
    f => { f.pr.base.ref = 'other'; },
    f => { f.pr.merge_commit_sha = 'new-merge'; },
    f => { f.pr.merge_commit_sha = null; },
    f => { f.run.head_sha = 'old-head'; },
    f => { f.run.head_branch = 'another-feature'; },
    f => { f.run.head_repository.id = 200; },
    f => { f.run.event = 'workflow_dispatch'; },
    f => { f.run.run_attempt = 2; }
  ];
  for (const change of changes) {
    const f = fixture();
    change(f);
    await assert.rejects(assertCurrentPR(f));
  }
});

test('normal ready PR lifecycle events verify without a readiness epoch', async () => {
  for (const action of ['opened', 'reopened', 'synchronize', 'ready_for_review', 'edited']) {
    const f = fixture();
    f.context.payload.action = action;
    await assertCurrentPR(f);
  }
});

test('unchanged merge candidates can rerun and do not require a new ready transition', async () => {
  const f = fixture();
  f.context.runAttempt = 2;
  f.run.run_attempt = 2;
  await assertCurrentPR(f);
  f.pr.base.sha = 'advanced-base';
  await assert.rejects(assertCurrentPR(f), /Base changed/);
});

test('API failures cannot become successful verification', async () => {
  const f = fixture();
  f.github.rest.pulls.get = async () => { throw new Error('API unavailable'); };
  await assert.rejects(assertCurrentPR(f), /API unavailable/);
});

test('read-only bot and fork pull requests use the same current-source verification', async () => {
  const f = fixture();
  f.pr.head.ref = 'dependabot/gradle/upgrade';
  f.pr.head.repo.id = 200;
  f.context.payload.pull_request = structuredClone(f.pr);
  f.run.head_branch = f.pr.head.ref;
  f.run.head_repository.id = 200;
  await assertCurrentPR(f);
});
