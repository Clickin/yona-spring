import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { requireSeed } from '../../support/seed-store';
import { uniqueSuffix } from '../../support/unique';

/**
 * Screen: GET /{owner}/{projectName}/issueform (issue/create.html) -- the <yona-attachments>
 * custom element (a Vue 3 SFC compiled to a native custom element,
 * static/lib/yona-vue-widgets/yona-attachments-element.js) that owns the drop zone / file input /
 * uploaded-file list. AttachmentController.uploadFile (POST /files) is the endpoint it calls.
 *
 * The element renders a real <input type="file"> inside it (fileInputRef in the compiled source);
 * Playwright's locator engine pierces open shadow roots for plain CSS selectors, so no special
 * shadow-DOM handling is needed to reach it.
 */
test('uploading a file through the issue create form attachment widget', async ({ page }) => {
  const owner = requireSeed('projectOwner');
  const name = requireSeed('projectName');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'yona-e2e-attach-'));
  const fileName = `e2e-attachment-${uniqueSuffix()}.txt`;
  const filePath = path.join(dir, fileName);
  fs.writeFileSync(filePath, 'attachment content written by the e2e suite\n');

  await page.goto(`/${owner}/${name}/issueform`);
  const widget = page.locator('yona-attachments#upload');
  await expect(widget).toBeAttached();

  await widget.locator('input[type="file"]').setInputFiles(filePath);

  // Upload happens immediately on file selection (fetch POST /files), independent of the issue
  // form's own submit -- wait for the widget's own "complete" state rather than submitting the form.
  const uploadedEntry = widget.locator('.attached-file.complete', { hasText: fileName });
  await expect(uploadedEntry).toBeVisible({ timeout: 15_000 });

  fs.rmSync(dir, { recursive: true, force: true });
});

test('deleting an uploaded attachment removes it from the widget', async ({ page }) => {
  const owner = requireSeed('projectOwner');
  const name = requireSeed('projectName');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'yona-e2e-attach-'));
  const fileName = `e2e-attachment-delete-${uniqueSuffix()}.txt`;
  const filePath = path.join(dir, fileName);
  fs.writeFileSync(filePath, 'attachment content written by the e2e suite (delete test)\n');

  await page.goto(`/${owner}/${name}/issueform`);
  const widget = page.locator('yona-attachments#upload');
  await widget.locator('input[type="file"]').setInputFiles(filePath);

  const uploadedEntry = widget.locator('.attached-file.complete', { hasText: fileName });
  await expect(uploadedEntry).toBeVisible({ timeout: 15_000 });

  // AttachmentController.deleteFile is POST /files/{id} (not DELETE -- the widget's compiled JS
  // POSTs with a `_method=delete` body param, matching this codebase's other legacy-style
  // method-override endpoints). The delete trigger is `.btn-delete` inside the file's own row.
  const deleteButton = uploadedEntry.locator('.btn-delete');
  await expect(deleteButton).toBeVisible();
  await Promise.all([
    page.waitForResponse((res) => res.request().method() === 'POST' && /\/files\/\d+$/.test(new URL(res.url()).pathname)),
    deleteButton.click(),
  ]);
  await expect(uploadedEntry).toHaveCount(0);

  fs.rmSync(dir, { recursive: true, force: true });
});

test('validation redisplay retains uploaded files through hydration, removal and retry', async ({ page }) => {
  const owner = requireSeed('projectOwner');
  const name = requireSeed('projectName');
  const title = `redisplay-${uniqueSuffix()}`;
  await page.goto(`/${owner}/${name}/issueform`);
  const widget = page.locator('yona-attachments#upload');
  await widget.locator('input[type="file"]').setInputFiles([
    { name: `${title}-keep.txt`, mimeType: 'text/plain', buffer: Buffer.from('keep') },
    { name: `${title}-remove.txt`, mimeType: 'text/plain', buffer: Buffer.from('remove') },
  ]);
  await expect(widget.locator('.attached-file.complete')).toHaveCount(2);
  const initialIds = (await widget.locator('input[name=temporaryUploadFiles]').inputValue()).split(',').sort();
  await page.locator('#title').fill(title);
  await page.evaluate(() => {
    const form = document.querySelector<HTMLFormElement>('#issue-form')!;
    localStorage.setItem(new URL(form.action).pathname, 'Stale draft must not replace submitted answers');
    (form.querySelector('#isDraft') as HTMLInputElement).value = 'true';
    (form.querySelector('#issueDueDate') as HTMLInputElement).value = '2027-04-05';
    for (const [name, value] of Object.entries({
      templateId: 'deleted-template-regression',
      'answer.steps': 'Keep this answer after template deletion',
    })) {
      const input = document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    }
    form.submit();
  });
  await page.waitForURL(`**/${owner}/${name}/issues`);
  await expect(page.locator('#issue-form [role=alert]')).toBeVisible();
  await expect(page.locator('#title')).toHaveValue(title);
  await expect(page.locator('#isDraft')).toHaveValue('true');
  await expect(page.locator('#issueDueDate')).toHaveValue('2027-04-05');
  await expect(page.locator('#issue-form input[name=templateId]')).toHaveCount(0);
  await expect(page.locator('textarea[name=body]')).toHaveValue(/Keep this answer after template deletion/);
  await expect(widget.locator('.attached-file.complete')).toHaveCount(2);
  expect((await widget.locator('input[name=temporaryUploadFiles]').inputValue()).split(',').sort()).toEqual(initialIds);
  const removed = widget.locator('.attached-file.complete', { hasText: `${title}-remove.txt` });
  await removed.locator('.btn-delete').click();
  await expect(removed).toHaveCount(0);
  const retainedId = await widget.locator('input[name=temporaryUploadFiles]').inputValue();
  expect(initialIds).toContain(retainedId);
  expect(retainedId).not.toContain(',');
  await page.locator('#button-save').click();
  await page.waitForURL(new RegExp(`/${owner}/${name}/issue/\\d+$`));
  await page.evaluate(key => localStorage.removeItem(key), `/${owner}/${name}/issues`);
  await page.goto(`${page.url()}/editform`);
  await expect(widget.locator('.attached-file.complete', { hasText: `${title}-keep.txt` })).toBeVisible();
  await expect(widget.locator('input[name=temporaryUploadFiles]')).toHaveValue('');
});
