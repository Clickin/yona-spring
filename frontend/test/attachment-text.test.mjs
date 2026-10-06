import assert from 'node:assert/strict';
import {test} from 'node:test';
import {build} from 'esbuild';

const {outputFiles: [bundle]} = await build({
  entryPoints: [new URL('../src/attachments/attachment-text.ts', import.meta.url).pathname],
  bundle: true, format: 'esm', platform: 'neutral', write: false,
});
const text = await import(`data:text/javascript;base64,${Buffer.from(bundle.text).toString('base64')}`);

// Expectations follow legacy yona.Attachments/yona.Files and the Vue widget they replace.
test('builds image, file and video links like the legacy uploader', () => {
  assert.equal(text.linkText({name: 'a.png', url: '/files/1', mimeType: 'image/png'}), '![a.png](/files/1) ');
  assert.equal(text.linkText({name: 'a.txt', url: '/files/2', mimeType: 'text/plain'}), '[a.txt](/files/2) ');
  assert.equal(text.linkText({name: 'v.mp4', url: '/files/3', mimeType: 'video/mp4'}),
    '<video class="video-js" data-setup="{}" controls="controls"><source src="/files/3" type="video/mp4"></video>[v.mp4](/files/3) ');
});

test('converts tab-separated clipboard text to the legacy Markdown table', () => {
  assert.equal(text.markdownTable('Name\tTitle\r\nJane\tCEO'), '| Name | Title |\n|------|-------|\n| Jane | CEO   |');
  assert.equal(text.markdownTable('^cMid\t^rRight\r\na\tb'), '| Mid | Right |\n|:---:|------:|\n| a   | b     |');
});

test('detects spreadsheet clipboard data only when both text and an image are present', () => {
  const item = (kind, type) => ({kind, type});
  assert.equal(text.hasTextAndImage([item('string', 'text/plain'), item('file', 'image/png')]), true);
  assert.equal(text.hasTextAndImage([item('file', 'image/png')]), false);
  assert.equal(text.hasTextAndImage([item('string', 'text/plain'), item('string', 'text/html')]), false);
});

test('keeps the legacy time-based name for pasted images', () => {
  const date = new Date(2026, 9, 1, 22, 30, 5, 12);
  assert.equal(text.legacyUploadName(date), '512-2026-10-1-22-30');
});

test('formats sizes like the Vue widget', () => {
  assert.equal(text.readableSize(0), '0B');
  assert.equal(text.readableSize(1023), '1023B');
  assert.equal(text.readableSize(1536), '1.5KB');
  assert.equal(text.readableSize(5 * 1024 * 1024), '5.0MB');
});

test('creates distinct upload keys even without crypto.randomUUID (insecure http origins)', () => {
  const keys = new Set(Array.from({length: 1000}, () => text.uploadKey({getRandomValues: array => globalThis.crypto.getRandomValues(array)})));
  assert.equal(keys.size, 1000);
  for (const key of keys) assert.match(key, /^[0-9a-f-]{20,}$/);
});
