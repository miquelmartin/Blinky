const { test } = require('node:test');
const assert = require('node:assert/strict');
const { androidNeeded, assertResults, assertCurrentReady } = require('./ready-ci.cjs');

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
    android: { result: 'success', outputs: {
      evidence: android === 'true' ? 'verified' : 'proven-unnecessary'
    } }
  };
}

test('aggregate rejects missing, skipped, failed or cancelled dependencies', () => {
  assertResults(results());
  assertResults(results('false'));
  for (const name of ['plan', 'python', 'android']) {
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
  const time = '2026-11-11T19:00:00Z';
  const pr = {
    number: 7, state: 'open', draft: false,
    head: { sha: 'head', ref: 'feature', repo: { id: 100 } },
    base: { ref: 'main', sha: 'base' }, merge_commit_sha: 'merge', updated_at: time
  };
  const timeline = [{ event: 'ready_for_review', created_at: time }];
  const context = {
    eventName: 'pull_request', runAttempt: 1, runId: 10, repo: { owner: 'owner', repo: 'repo' },
    sha: 'merge', payload: { action: 'ready_for_review', pull_request: structuredClone(pr) }
  };
  const github = {
    rest: {
      pulls: { get: async () => ({ data: pr }) },
      issues: { listEventsForTimeline: () => {} },
      actions: {
        getWorkflowRun: async () => ({ data: {
          workflow_id: 1, head_sha: 'head', run_attempt: 1, status: 'in_progress', conclusion: null
        } }),
        listWorkflowRuns: () => {}
      }
    },
    paginate: async endpoint => endpoint === github.rest.actions.listWorkflowRuns ?
      [{ id: 10, head_branch: 'feature', head_repository: { id: 100 } }] : timeline
  };
  return { github, context, pr, timeline };
}

test('only a current head, base, merge candidate and user readiness cycle may pass', async () => {
  await assertCurrentReady(fixture());
  const changes = [
    f => { f.context.eventName = 'push'; },
    f => { f.context.payload.action = 'synchronize'; },
    f => { f.context.runAttempt = 2; },
    f => { f.pr.state = 'closed'; },
    f => { f.pr.draft = true; },
    f => { f.pr.head.sha = 'pushed-head'; },
    f => { f.pr.base.sha = 'new-base'; },
    f => { f.pr.base.ref = 'other'; },
    f => { f.pr.merge_commit_sha = 'new-merge'; },
    f => { f.pr.merge_commit_sha = null; },
    f => { f.timeline.length = 0; },
    f => { f.timeline.push({ event: 'convert_to_draft', created_at: '2026-11-11T19:01:00Z' }); },
    f => { f.timeline.push({ event: 'ready_for_review', created_at: '2026-11-11T19:02:00Z' }); },
    f => { f.timeline.push({ ...f.timeline[0] }); },
    f => { f.github.rest.actions.getWorkflowRun = async () => ({ data: { head_sha: 'old-head' } }); },
    f => { f.github.rest.actions.getWorkflowRun = async () => ({ data: {
      head_sha: 'head', run_attempt: 1, status: 'completed', conclusion: 'cancelled'
    } }); },
    f => { f.github.paginate = async endpoint =>
      endpoint === f.github.rest.actions.listWorkflowRuns ? [
        { id: 10, head_branch: 'feature', head_repository: { id: 100 } },
        { id: 11, head_branch: 'feature', head_repository: { id: 100 } }
      ] : f.timeline; }
  ];
  for (const change of changes) {
    const f = fixture();
    change(f);
    await assert.rejects(assertCurrentReady(f));
  }
});

test('a verification run on another fork or branch cannot supersede this PR', async () => {
  const f = fixture();
  f.github.paginate = async endpoint =>
    endpoint === f.github.rest.actions.listWorkflowRuns ? [
      { id: 10, head_branch: 'feature', head_repository: { id: 100 } },
      { id: 11, head_branch: 'another-feature', head_repository: { id: 100 } },
      { id: 12, head_branch: 'feature', head_repository: { id: 200 } }
    ] : f.timeline;
  await assertCurrentReady(f);
});

test('a single ready event after conversion in the same second is unambiguous', async () => {
  const f = fixture();
  f.timeline.unshift({ event: 'convert_to_draft', created_at: f.timeline[0].created_at });
  await assertCurrentReady(f);
});

test('API failures cannot become successful verification', async () => {
  const f = fixture();
  f.github.rest.pulls.get = async () => { throw new Error('API unavailable'); };
  await assert.rejects(assertCurrentReady(f), /API unavailable/);
});

const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
const draftScript = readFileSync(join(__dirname, '../workflows/draft-on-push.yml'), 'utf8')
  .split('script: |')[1].split('\n').map(line => line.replace(/^ {12}/, '')).join('\n');
const draftOnPush = new AsyncFunction('github', 'context', 'core', draftScript);

function draftFixture() {
  const f = fixture();
  f.context.payload.pull_request.updated_at = '2026-11-11T19:01:00Z';
  f.pr.node_id = 'PR_node';
  f.mutations = [];
  f.github.graphql = async (query, variables) => f.mutations.push({ query, variables });
  f.core = { notice: () => {} };
  return f;
}

test('push controller returns the current ready head to draft, without marking ready', async () => {
  const f = draftFixture();
  await draftOnPush(f.github, f.context, f.core);
  assert.equal(f.mutations.length, 1);
  assert.match(f.mutations[0].query, /convertPullRequestToDraft/);
  assert.deepEqual(f.mutations[0].variables, { id: 'PR_node' });
});

test('late or duplicate pushes cannot undo a later ready cycle or mutate unrelated heads', async () => {
  const changes = [
    f => { f.pr.draft = true; },
    f => { f.pr.state = 'closed'; },
    f => { f.pr.head.sha = 'new-head'; },
    f => { f.timeline.push({ event: 'ready_for_review', created_at: '2026-11-11T19:02:00Z' }); }
  ];
  for (const change of changes) {
    const f = draftFixture();
    change(f);
    await draftOnPush(f.github, f.context, f.core);
    assert.equal(f.mutations.length, 0);
  }
});

test('token draft-mutation failures remain visible workflow failures', async () => {
  const f = draftFixture();
  f.github.graphql = async () => { throw new Error('Forbidden'); };
  await assert.rejects(draftOnPush(f.github, f.context, f.core), /Forbidden/);
});
