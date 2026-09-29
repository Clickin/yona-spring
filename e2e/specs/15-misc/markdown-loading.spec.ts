import {test, expect} from '@playwright/test';
import {requireSeed} from '../../support/seed-store';

// Run specs/05-code/markdown-documents.spec.ts first to create docs/guide.md.
const guidePath = () => `/${requireSeed('projectOwner')}/${requireSeed('projectName')}/code/HEAD/docs/guide.md`;
const documentSelector = 'yona-markdown-renderer[mode=document]';

test('delayed module hides raw SSR Markdown without collapsing a long document', async ({page}, testInfo) => {
  await page.setViewportSize({width: 1280, height: 900});
  const {promise: blocked, resolve: requested} = Promise.withResolvers<void>();
  const {promise: gate, resolve: release} = Promise.withResolvers<void>();
  await page.route('**/javascripts/markdown/*.js', async route => {
    requested();
    await gate;
    await route.continue();
  });
  try {
    await page.goto(guidePath(), {waitUntil: 'commit'});
    await blocked;
    const renderer = page.locator(documentSelector);
    await expect(renderer).toBeAttached();
    await expect(renderer).toHaveCSS('visibility', 'hidden');
    await expect(renderer).not.toHaveAttribute('data-markdown-ready');
    expect(await page.evaluate(() => customElements.get('yona-markdown-renderer') === undefined)).toBe(true);
    const pending = await renderer.evaluate(element => {
      const short = element.cloneNode(false) as HTMLElement;
      short.textContent = 'Short **paragraph**.';
      element.after(short);
      const longHeight = element.getBoundingClientRect().height;
      const shortHeight = short.getBoundingClientRect().height;
      short.remove();
      return {height: longHeight, shortHeight, width: element.getBoundingClientRect().width};
    });
    expect(pending.height).toBeGreaterThan(2000);
    expect(pending.height).toBeGreaterThan(pending.shortHeight * 20);

    release();
    await expect(renderer).toHaveAttribute('data-markdown-ready', '');
    await expect(renderer).toHaveCSS('visibility', 'visible');
    await expect(renderer.locator('h1').first()).toContainText('한글 제목');
    await expect(renderer.locator('p strong')).toHaveCount(200);
    await expect(renderer.locator('p strong').first()).toBeVisible();
    await expect(renderer.locator('tbody td')).toHaveText(['legacy', 'GFM']);
    await expect(renderer.locator('img[alt="이미지"]')).toHaveJSProperty('naturalWidth', 1);
    const rendered = await renderer.boundingBox();
    expect(rendered).not.toBeNull();
    const shift = Math.abs(rendered!.height - pending.height);
    await testInfo.attach('markdown-height-reservation', {
      body: JSON.stringify({viewport: {width: 1280, height: 900}, pending, rendered, shift, tolerance: Math.max(120, rendered!.height * 0.25)}),
      contentType: 'application/json',
    });
    // Text reservation is approximate; this corpus has only one intrinsic 1px image.
    expect(shift).toBeLessThanOrEqual(Math.max(120, rendered!.height * 0.25));

    await page.setViewportSize({width: 720, height: 900});
    await expect.poll(async () => renderer.evaluate(element => {
      const output = element.querySelector('.markdown-output')!;
      const style = getComputedStyle(element);
      const chrome = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
        + parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
      return Math.abs(element.getBoundingClientRect().height - output.getBoundingClientRect().height - chrome);
    })).toBeLessThanOrEqual(24);
  } finally {
    release();
  }
});

test('Turbo-style clones hide stale readiness until their own output is mounted', async ({page}) => {
  await page.goto(guidePath());
  const renderer = page.locator(documentSelector);
  await expect(renderer).toHaveAttribute('data-markdown-ready', '');
  const cloneState = await renderer.evaluate(async original => {
    const cached = original.cloneNode(true) as HTMLElement;
    const restored = new Promise<void>(resolve => cached.addEventListener('markdown-rendered', () => resolve(), {once: true}));
    original.replaceWith(cached);
    const pending = {ready: cached.hasAttribute('data-markdown-ready'), visibility: getComputedStyle(cached).visibility};
    await restored;
    return {pending, ready: cached.hasAttribute('data-markdown-ready'), visibility: getComputedStyle(cached).visibility};
  });
  expect(cloneState.pending).toEqual({ready: false, visibility: 'hidden'});
  expect(cloneState).toMatchObject({ready: true, visibility: 'visible'});
  await expect(renderer.locator('h1').nth(1)).toHaveAttribute('id', '한글-제목-1');
  await expect(renderer.locator('p strong')).toHaveCount(200);
  await expect(renderer.locator('tbody td')).toHaveText(['legacy', 'GFM']);
  await expect(renderer.locator('.markdown-output')).toHaveCount(1);

  await renderer.evaluate(async element => {
    const parent = element.parentElement!;
    const next = element.nextSibling;
    const restored = new Promise<void>(resolve => element.addEventListener('markdown-rendered', () => resolve(), {once: true}));
    element.remove();
    parent.insertBefore(element, next);
    await restored;
  });
  await expect(renderer).toHaveAttribute('data-markdown-ready', '');
  await expect(renderer.locator('h1').first()).toBeVisible();
  await expect(renderer.locator('p strong')).toHaveCount(200);
});

test('a failed module download restores readable source instead of a permanent blank', async ({page}) => {
  await page.route('**/javascripts/markdown/*.js', route => route.abort());
  await page.goto(guidePath());
  const renderer = page.locator(documentSelector);
  await expect(renderer).toBeVisible();
  await expect(renderer).toContainText('# 한글 제목');
  await expect(renderer.locator('.markdown-output')).toHaveCount(0);
});

test.describe('without JavaScript', () => {
  test.use({javaScriptEnabled: false});

  test('the actual repository page keeps readable source as its fallback', async ({page}) => {
    await page.goto(guidePath());
    const renderer = page.locator(documentSelector);
    await expect(renderer).toBeVisible();
    await expect(renderer).toHaveCSS('visibility', 'visible');
    await expect(renderer).toContainText('# 한글 제목');
    await expect(renderer).toContainText('Long document **paragraph** with nested meaning.');
    await expect(renderer.locator('h1, .markdown-output')).toHaveCount(0);
    const bounds = await renderer.boundingBox();
    expect(bounds!.height).toBeGreaterThan(2000);
  });
});
