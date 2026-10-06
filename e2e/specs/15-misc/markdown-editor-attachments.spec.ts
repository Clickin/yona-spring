import { test, expect, type Page } from '@playwright/test';
import { requireSeed } from '../../support/seed-store';

/**
 * Attachment input through the native Markdown editor textarea, following the legacy
 * yona.Files/yona.Attachments behavior: a pasted image inserts a temporary `<!--_id_-->` marker
 * that becomes a Markdown link after upload, and clicking an uploaded file inserts its link.
 * A file dropped on the textarea gets the same marker, and a tab-separated text+image clipboard
 * (spreadsheet copy) is pasted as a Markdown table instead of an image.
 */
test.skip(({ browserName }) => browserName !== 'chromium', 'synthetic clipboard/drag data is Chromium-only');

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const BODY = 'yona-markdown-editor textarea[name="body"]';

async function openIssueForm(page: Page) {
  await page.goto(`/${requireSeed('projectOwner')}/${requireSeed('projectName')}/issueform`);
  const textarea = page.locator(BODY);
  await expect(textarea).toBeVisible();
  await expect(page.locator('yona-attachments#upload')).toBeAttached();
  await textarea.fill('');
  await textarea.focus();
  return textarea;
}

/** Dispatches a paste or drop carrying the given files/strings on the textarea. */
async function dispatch(page: Page, type: 'paste' | 'drop', data: { png?: string; text?: string }, selector = BODY) {
  await page.locator(selector).evaluate((textarea, { type, data, png }) => {
    const transfer = new DataTransfer();
    if (data.text !== undefined) transfer.setData('text/plain', data.text);
    if (data.png) {
      const bytes = Uint8Array.from(atob(png), c => c.charCodeAt(0));
      transfer.items.add(new File([bytes], data.png, { type: 'image/png' }));
    }
    const init = { bubbles: true, cancelable: true, composed: true };
    textarea.dispatchEvent(type === 'paste'
      ? new ClipboardEvent('paste', { ...init, clipboardData: transfer })
      : new DragEvent('drop', { ...init, dataTransfer: transfer }));
  }, { type, data, png: PNG });
}

test('pasting an image into the editor inserts its Markdown image link after upload', async ({ page }) => {
  const textarea = await openIssueForm(page);
  await dispatch(page, 'paste', { png: 'clip.png' });
  await expect(textarea).toHaveValue(/^!\[[^\]]+\.png\]\(\/files\/\d+\) ?$/, { timeout: 15_000 });
  await expect(page.locator('yona-attachments#upload .attached-file.complete')).toHaveCount(1);
});

test('clicking an uploaded attachment inserts its link at the caret', async ({ page }) => {
  const textarea = await openIssueForm(page);
  await dispatch(page, 'drop', { png: 'listed.png' });
  const item = page.locator('yona-attachments#upload .attached-file.complete', { hasText: 'listed.png' });
  await expect(item).toBeVisible({ timeout: 15_000 });
  await textarea.fill('before after');
  await textarea.evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(7, 7));
  await item.locator('.name').click();
  await expect(textarea).toHaveValue(/^before !\[listed\.png\]\(\/files\/\d+\) /);
});

test('pasting an image into an issue comment inserts exactly one link and one upload', async ({ page }) => {
  await page.goto(`/${requireSeed('projectOwner')}/${requireSeed('projectName')}/issue/${requireSeed('issueNumber')}`);
  const selector = '#comment-form yona-markdown-editor textarea[name="contents"]';
  const textarea = page.locator(selector);
  await expect(textarea).toBeVisible();
  await textarea.focus();
  await dispatch(page, 'paste', { png: 'comment.png' }, selector);
  await expect(textarea).toHaveValue(/^!\[[^\]]+\.png\]\(\/files\/\d+\) ?$/, { timeout: 15_000 });
  await expect(page.locator('#comment-form .attached-file.complete')).toHaveCount(1);
});

test('dropping an image onto the editor uploads it and inserts its Markdown image link', async ({ page }) => {
  const textarea = await openIssueForm(page);
  await dispatch(page, 'drop', { png: 'dropped.png' });
  await expect(page.locator('yona-attachments#upload .attached-file.complete', { hasText: 'dropped.png' }))
    .toBeVisible({ timeout: 15_000 });
  await expect(textarea).toHaveValue(/^!\[dropped\.png\]\(\/files\/\d+\) ?$/);
});

test('pasting spreadsheet cells (text plus image) inserts a Markdown table, not an image', async ({ page }) => {
  const textarea = await openIssueForm(page);
  await dispatch(page, 'paste', { text: 'Name\tTitle\r\nJane\tCEO', png: 'cells.png' });
  await expect(textarea).toHaveValue('| Name | Title |\n|------|-------|\n| Jane | CEO   |');
  await expect(page.locator('yona-attachments#upload .attached-file')).toHaveCount(0);
});

test('Markdown help switches panels, closes them and survives a cached form', async ({ page }) => {
  await openIssueForm(page);
  const help = page.locator('yona-markdown-editor .markdown-help');
  const header = help.getByRole('button', { name: 'Header', exact: true });
  const styling = help.getByRole('button', { name: 'Text Style', exact: true });
  await header.click();
  await expect(help.locator('.markdownHeaders')).toBeVisible();
  await expect(help.locator('.markdownHeaders h1')).toBeVisible();
  await expect(header).toHaveAttribute('aria-expanded', 'true');
  await styling.focus();
  await styling.press('Enter');
  await expect(help.locator('.markdownHeaders')).toBeHidden();
  await expect(help.locator('.markdownStyling')).toBeVisible();
  await styling.press('Space');
  await expect(help.locator('.markdownStyling')).toBeHidden();
  await expect(styling).toHaveAttribute('aria-expanded', 'false');
  const image = help.getByRole('button', { name: 'Image', exact: true });
  await image.click();
  await expect(help.locator('.markdownImages img')).toBeVisible();
  await expect.poll(() => help.locator('.markdownImages img').evaluate((element: HTMLImageElement) => element.complete && element.naturalWidth > 0)).toBe(true);
  await image.click();
  await page.evaluate(() => {
    const form = document.querySelector('yona-markdown-editor')!.closest('form')!;
    form.replaceWith(form.cloneNode(true));
  });
  await header.click();
  await expect(help.locator('.markdownHeaders')).toBeVisible();
  await header.click();
  await expect(help.locator('.markdownHeaders')).toBeHidden();
});
