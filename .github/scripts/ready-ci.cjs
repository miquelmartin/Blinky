const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { appendFileSync } = require('node:fs');

function androidNeeded(paths) {
  if (!paths.length) return true;
  return paths.some(path => !(
    path === 'README.md' ||
    /^docs\/[^\\]+\.md$/.test(path) ||
    /^\.github\/skills\/[^\\]+\.md$/.test(path) ||
    /^sender\/[^\\]+$/.test(path)
  ));
}

function assertResults(needs) {
  for (const name of ['plan', 'python', 'android']) {
    assert.equal(needs[name]?.result, 'success', `${name} must actually succeed`);
  }
  const decision = needs.plan.outputs?.android;
  assert.ok(['true', 'false'].includes(decision), 'Missing Android classification');
  assert.equal(needs.android.outputs?.evidence,
    decision === 'true' ? 'verified' : 'proven-unnecessary',
    'Android verification must finish or explicitly prove it is unnecessary');
}

async function assertCurrentReady({ github, context }) {
  assert.equal(context.eventName, 'pull_request');
  assert.equal(context.payload.action, 'ready_for_review');
  assert.equal(Number(context.runAttempt ?? process.env.GITHUB_RUN_ATTEMPT), 1,
    'Reruns are not a new ready event; return to draft and mark ready again');
  const event = context.payload.pull_request;
  const { owner, repo } = context.repo;
  const { data: current } = await github.rest.pulls.get({
    owner, repo, pull_number: event.number
  });
  assert.equal(current.state, 'open');
  assert.equal(current.draft, false, 'PR is no longer ready');
  assert.equal(current.base.ref, 'main');
  assert.equal(current.head.sha, event.head.sha, 'PR head changed');
  assert.equal(current.base.sha, event.base.sha, 'Base changed; update and request fresh verification');
  assert.equal(current.merge_commit_sha, context.sha, 'Merge candidate changed or is unavailable');
  const timeline = await github.paginate(github.rest.issues.listEventsForTimeline, {
    owner, repo, issue_number: event.number, per_page: 100
  });
  const transitions = timeline.filter(item =>
    ['ready_for_review', 'convert_to_draft'].includes(item.event));
  const latest = transitions.at(-1);
  assert.equal(latest?.event, 'ready_for_review', 'Missing current ready transition');
  assert.equal(latest.created_at, event.updated_at, 'A newer readiness cycle superseded this run');
  assert.equal(transitions.filter(item =>
    item.event === 'ready_for_review' && item.created_at === latest.created_at).length, 1,
    'Ambiguous same-second readiness changes; return to draft and ready again');
  const { data: run } = await github.rest.actions.getWorkflowRun({
    owner, repo, run_id: context.runId
  });
  assert.equal(run.head_sha, current.head.sha, 'Workflow is not for the current head');
  assert.equal(run.run_attempt, 1, 'Reruns cannot reuse a readiness cycle');
  assert.equal(run.status, 'in_progress', 'Verification run is not active');
  assert.equal(run.conclusion, null, 'Verification run was already concluded');
  const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {
    owner, repo, workflow_id: run.workflow_id, head_sha: current.head.sha,
    branch: event.head.ref, event: 'pull_request', per_page: 100
  });
  const sameSource = runs.filter(item =>
    item.head_repository?.id === event.head.repo.id && item.head_branch === event.head.ref);
  assert.equal(Math.max(...sameSource.map(item => item.id)), context.runId,
    'A newer verification run superseded this run');
}

module.exports = { androidNeeded, assertResults, assertCurrentReady };

if (require.main === module) {
  assert.equal(process.argv[2], 'classify');
  assert.match(process.env.BASE_SHA ?? '', /^[0-9a-f]{40}$/);
  const names = execFileSync('git', [
    'diff', '--name-only', '--no-renames', '-z', process.env.BASE_SHA, 'HEAD', '--'
  ], { encoding: 'utf8' }).split('\0').filter(Boolean);
  appendFileSync(process.env.GITHUB_OUTPUT, `android=${androidNeeded(names)}\n`);
}
