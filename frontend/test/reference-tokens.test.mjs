import assert from 'node:assert/strict';
import {test} from 'node:test';
import {build} from 'esbuild';

// Bundled at test time so the suite runs on CI runners without TypeScript type stripping.
const {outputFiles: [bundle]} = await build({
  entryPoints: [new URL('../src/markdown/plugins/reference-tokens.ts', import.meta.url).pathname],
  bundle: true, format: 'esm', platform: 'neutral', write: false,
});
const {linkLegacyReferences} = await import(`data:text/javascript;base64,${Buffer.from(bundle.text).toString('base64')}`);

/** Renders pieces as text, with resolved references shown as [type:value]. */
function link(text, valid = []) {
  const known = new Set(valid);
  const {pieces} = linkLegacyReferences(text, ({type, value}) => known.has(`${type}:${value}`));
  return pieces.map(piece => typeof piece === 'string' ? piece : `[${piece.type}:${piece.value}]`).join('');
}

function candidates(text) {
  return linkLegacyReferences(text, () => undefined).unknown.map(({type, value}) => `${type}:${value}`);
}

// Expectations follow the server AutoLinkRenderer: five ordered passes, ASCII \w boundaries.
test('links an issue directly after Hangul, as Java ASCII \\w boundaries did', () => {
  assert.equal(link('이슈#3 확인', ['issue:#3']), '이슈[issue:#3] 확인');
});

test('links a commit directly after Hangul', () => {
  assert.equal(link('커밋abc1234 반영', ['commit:abc1234']), '커밋[commit:abc1234] 반영');
});

test('does not link or look up a bare owner/project without @', () => {
  assert.equal(link('owner/project 참고', ['project:owner/project']), 'owner/project 참고');
  assert.ok(!candidates('owner/project 참고').includes('project:owner/project'));
});

test('links @owner/project as a project', () => {
  assert.equal(link('see @owner/project', ['project:owner/project']), 'see [project:owner/project]');
});

test('resolves the fork shorthand user#12 and user@sha', () => {
  assert.equal(link('user#12', ['issue:user#12']), '[issue:user#12]');
  assert.equal(link('user@abc1234', ['commit:user@abc1234']), '[commit:user@abc1234]');
});

test('does not fall back to #12 when an ASCII word character precedes it', () => {
  assert.equal(link('abc#12', ['issue:#12']), 'abc#12');
  assert.equal(link('#12a', ['issue:#12']), '#12a');
  assert.equal(link('(#12)', ['issue:#12']), '([issue:#12])');
});

test('prefers a commit over a user for @sha, then falls back to the user', () => {
  assert.equal(link('@abc1234', ['commit:abc1234', 'user:@abc1234']), '[commit:abc1234]');
  assert.equal(link('@deadbeef', ['user:@deadbeef']), '[user:@deadbeef]');
});

test('keeps resolved references out of later passes', () => {
  assert.equal(link('owner/project#3 @owner/project', ['issue:owner/project#3', 'project:owner/project']),
    '[issue:owner/project#3] [project:owner/project]');
});

test('reports every pass candidate that has not been looked up yet', () => {
  assert.deepEqual(candidates('#1 @a').sort(), ['issue:#1', 'user:@a']);
});
