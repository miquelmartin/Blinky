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

async function assertCurrentPR({ github, context }) {
  assert.equal(context.eventName, 'pull_request');
  assert.ok(['opened', 'reopened', 'synchronize', 'ready_for_review', 'edited']
    .includes(context.payload.action), 'Unsupported verification event');
  const event = context.payload.pull_request;
  const { owner, repo } = context.repo;
  const { data: current } = await github.rest.pulls.get({
    owner, repo, pull_number: event.number
  });
  assert.equal(current.state, 'open');
  assert.equal(current.draft, false, 'PR is no longer ready');
  assert.equal(current.base.ref, 'main');
  assert.equal(current.head.sha, event.head.sha, 'PR head changed');
  assert.equal(current.head.ref, event.head.ref, 'PR source branch changed');
  assert.equal(current.head.repo.id, event.head.repo.id, 'PR source repository changed');
  assert.equal(current.base.sha, event.base.sha, 'Base changed; update the branch and verify again');
  assert.equal(current.merge_commit_sha, context.sha, 'Merge candidate changed or is unavailable');
  const { data: run } = await github.rest.actions.getWorkflowRun({
    owner, repo, run_id: context.runId
  });
  assert.equal(run.head_sha, current.head.sha, 'Workflow is not for the current head');
  assert.equal(run.head_branch, current.head.ref, 'Workflow source branch changed');
  assert.equal(run.head_repository.id, current.head.repo.id, 'Workflow source repository changed');
  assert.equal(run.event, 'pull_request', 'Workflow is not a PR verification');
  assert.equal(run.run_attempt, Number(context.runAttempt ?? process.env.GITHUB_RUN_ATTEMPT),
    'Workflow attempt changed');
  // GitHub can still report queued while a downstream job is executing.
  assert.ok(['queued', 'in_progress'].includes(run.status), 'Verification run is not active');
  assert.equal(run.conclusion, null, 'Verification run was already concluded');
}

module.exports = { androidNeeded, assertResults, assertCurrentPR };

if (require.main === module) {
  assert.equal(process.argv[2], 'classify');
  assert.match(process.env.BASE_SHA ?? '', /^[0-9a-f]{40}$/);
  const names = execFileSync('git', [
    'diff', '--name-only', '--no-renames', '-z', process.env.BASE_SHA, 'HEAD', '--'
  ], { encoding: 'utf8' }).split('\0').filter(Boolean);
  appendFileSync(process.env.GITHUB_OUTPUT, `android=${androidNeeded(names)}\n`);
}
