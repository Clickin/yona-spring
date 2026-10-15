import {test, expect} from '@playwright/test';
import {requireSeed} from '../../support/seed-store';

test('server preview does not validate or clear the unsaved issue and recovers after a failed request', async ({page}) => {
  await page.goto(`/${requireSeed('projectOwner')}/${requireSeed('projectName')}/issueform`);
  const editor = page.locator('yona-markdown-editor');
  const textarea = editor.locator('textarea');
  await page.locator('input[name="title"]').fill('');
  await textarea.fill('# Unsaved preview\n\n```kotlin\nval answer = 42\n```\n\n- [ ] task');
  const draftKey = await textarea.evaluate(element => {
    const key = (window as Window & {yonaDraftKey(element: Element): string}).yonaDraftKey(element);
    localStorage.setItem(key, (element as HTMLTextAreaElement).value);
    return key;
  });
  const source = await textarea.inputValue();
  await editor.getByRole('button', {name: 'Preview', exact: true}).click();
  const frame = editor.locator('turbo-frame');
  await expect(frame.locator('h1')).toHaveText('Unsaved preview');
  await expect(frame.locator('code .hljs-keyword')).toHaveText('val');
  await expect(frame.locator('input[type="checkbox"]')).toBeDisabled();
  expect(await frame.evaluate(element => element.getRootNode() === document)).toBe(true);
  expect(await page.evaluate(key => localStorage.getItem(key), draftKey)).toBe(source);
  await expect(page.locator('input[name="title"]')).toHaveValue('');
  await expect(page.locator('.modal:visible')).toHaveCount(0);

  await editor.getByRole('button', {name: 'Edit', exact: true}).click();
  await page.route('**/markdown/*/*/preview', route => route.abort());
  await editor.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(editor.getByRole('alert')).toContainText('Preview could not be loaded');
  await expect(textarea).toHaveValue(source);
  await page.unroute('**/markdown/*/*/preview');
  await editor.getByRole('button', {name: 'Edit', exact: true}).click();
  await textarea.fill('# Recovered');
  await editor.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(frame.locator('h1')).toHaveText('Recovered');
  await expect(editor.getByRole('alert')).toHaveCount(0);
});

test('a superseded server preview cannot overwrite the latest draft', async ({page}) => {
  await page.goto(`/${requireSeed('projectOwner')}/${requireSeed('projectName')}/issueform`);
  const editor = page.locator('yona-markdown-editor');
  let releaseOld!: () => void;
  let oldSeen!: () => void;
  let oldFinished!: () => void;
  const oldGate = new Promise<void>(resolve => { releaseOld = resolve; });
  const oldReady = new Promise<void>(resolve => { oldSeen = resolve; });
  const oldDone = new Promise<void>(resolve => { oldFinished = resolve; });
  await page.route('**/markdown/*/*/preview', async route => {
    if (new URLSearchParams(route.request().postData() ?? '').get('body') !== '# Old') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    oldSeen();
    await oldGate;
    try { await route.fulfill({response}); } finally { oldFinished(); }
  });
  await editor.locator('textarea').fill('# Old');
  await editor.getByRole('button', {name: 'Preview', exact: true}).click();
  await oldReady;
  await editor.getByRole('button', {name: 'Edit', exact: true}).click();
  await editor.locator('textarea').fill('# Latest');
  await editor.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(editor.locator('turbo-frame h1')).toHaveText('Latest');
  releaseOld();
  await oldDone;
  await expect(editor.locator('turbo-frame h1')).toHaveText('Latest');
  await expect(editor.getByRole('alert')).toHaveCount(0);
});
