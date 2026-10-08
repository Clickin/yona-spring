import {test, expect} from '@playwright/test';
type MarkdownElement = HTMLElement & {ready: Promise<unknown>};

test.beforeEach(async ({page}) => {
  await page.goto('/');
  await page.evaluate(() => customElements.whenDefined('yona-markdown-renderer'));
});

test('immutable snapshots preserve GFM, safe HTML, headings and comment breaks', async ({page}) => {
  await page.evaluate(async () => {
    const element = document.createElement('yona-markdown-renderer');
    element.id = 'markdown-fixture';
    element.setAttribute('mode', 'document');
    element.textContent = '# 한글 제목\n\n# 한글 제목\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n- [x] done\n- [ ] pending\n\n~~deleted~~ ~also~\n\nhttps://example.com test@example.com\n\n<details><summary>More</summary>safe</details>\n\n<script>window.markdownXss = true</script><img src=x onerror="window.markdownXss=true"><a href="javascript:alert(1)">bad</a><svg><foreignObject><iframe srcdoc="bad"></iframe></foreignObject></svg>\n\n```unknown-language\n<unsafe>\n```';
    document.body.replaceChildren(element);
    await (element as MarkdownElement).ready;
    if (!element.shadowRoot?.querySelector('.markdown-output')) throw new Error('Output must be inside Shadow DOM');
    if (element.querySelector('h1')) throw new Error('Rendered markup leaked into light DOM');
  });
  const output = page.locator('#markdown-fixture');
  await expect(output.locator('h1')).toHaveCount(2);
  await expect(output.locator('h1').nth(0)).toHaveAttribute('id', '한글-제목');
  await expect(output.locator('h1').nth(1)).toHaveAttribute('id', '한글-제목-1');
  await output.locator('h1 .heading-anchor').nth(1).click();
  expect(new URL(page.url()).hash).toBe(`#${encodeURIComponent('한글-제목-1')}`);
  await expect(output.locator('tbody td')).toHaveText(['1', '2']);
  await expect(output.locator('input[type=checkbox]').first()).toBeChecked();
  await expect(output.locator('del')).toHaveText(['deleted', 'also']);
  await expect(output.locator('details summary')).toHaveText('More');
  await expect(output.locator('script, svg, iframe, [onerror], a[href^="javascript:"]')).toHaveCount(0);
  await expect(output.locator('pre code')).toHaveText('<unsafe>\n');
  expect(await page.evaluate(() => Reflect.get(window, 'markdownXss'))).toBeUndefined();
  await page.evaluate(async () => {
    const element = document.querySelector('#markdown-fixture')!;
    element.remove();
    document.body.append(element);
    await (element as MarkdownElement).ready;
  });
  await expect(output.locator('h1').nth(1)).toHaveAttribute('id', '한글-제목-1');
  await page.evaluate(async () => {
    const original = document.querySelector('#markdown-fixture')!;
    const cached = original.cloneNode(true) as MarkdownElement;
    original.replaceWith(cached);
    await cached.ready;
  });
  await expect(output.locator('tbody td')).toHaveText(['1', '2']);
  await expect(output.locator('h1').nth(1)).toHaveAttribute('id', '한글-제목-1');
  await page.evaluate(async () => {
    const element = document.createElement('yona-markdown-renderer');
    element.id = 'comment-fixture';
    element.textContent = '# Heading\n\none\ntwo  \nthree\n\n- parent\n  - child\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n```unknown\na\nb\n```';
    document.body.append(element);
    await (element as MarkdownElement).ready;
  });
  await expect(page.locator('#comment-fixture p br')).toHaveCount(2);
  await expect(page.locator('#comment-fixture li br')).toHaveCount(0);
  await expect(page.locator('#comment-fixture .markdown-output > br')).toHaveCount(0);
  await expect(page.locator('#comment-fixture pre code')).toHaveText('a\nb\n');
});

test('editor keeps native textarea ownership and takes one preview snapshot per entry', async ({page}) => {
  await page.evaluate(async () => {
    await customElements.whenDefined('yona-markdown-editor');
    const form = document.createElement('form');
    const editor = document.createElement('yona-markdown-editor');
    const textarea = document.createElement('textarea');
    textarea.name = 'body';
    textarea.defaultValue = '**initial**';
    editor.append(textarea);
    form.append(editor);
    document.body.replaceChildren(form);
    await (editor as MarkdownElement).ready;
    if (!editor.shadowRoot?.querySelector('slot[name="input"]')) throw new Error('Missing native textarea slot');
    if (textarea.getRootNode() !== document || textarea.form !== form) throw new Error('Textarea lost native form ownership');
  });
  const textarea = page.locator('textarea[name=body]');
  await expect(textarea).toBeVisible();
  await textarea.fill('**snapshot**');
  await expect(page.locator('yona-markdown-renderer')).toHaveCount(0);
  await page.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(page.locator('yona-markdown-renderer strong')).toHaveText('snapshot');
  expect(await page.evaluate(() => new FormData(document.querySelector('form')!).get('body'))).toBe('**snapshot**');
  await page.getByRole('button', {name: 'Edit', exact: true}).click();
  await expect(page.locator('yona-markdown-renderer')).toHaveCount(0);
  await textarea.fill('**second**');
  await page.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(page.locator('yona-markdown-renderer strong')).toHaveText('second');
  await page.evaluate(() => { Reflect.set(document.querySelector('yona-markdown-editor')!, 'value', 'restored'); });
  await expect(textarea).toBeVisible();
  await expect(textarea).toHaveValue('restored');
  await page.evaluate(() => document.querySelector('form')!.reset());
  await expect(textarea).toHaveValue('**initial**');
  await textarea.fill('before after');
  await textarea.evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(7, 7));
  await page.getByRole('button', {name: 'Add checklist', exact: true}).locator('i').click();
  await expect(textarea).toHaveValue('before \n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo Cafter');
  await textarea.fill('line');
  await textarea.press('End');
  await textarea.press('Tab');
  await expect(textarea).toHaveValue('line\t');
  await textarea.fill('\tline');
  await textarea.press('Home');
  await textarea.press('Shift+Tab');
  await expect(textarea).toHaveValue('line');
  await page.evaluate(async () => {
    const form = document.querySelector('form')!;
    const cached = form.cloneNode(true);
    form.replaceWith(cached);
    await (document.querySelector('yona-markdown-editor') as MarkdownElement).ready;
  });
  await expect(textarea).toHaveValue('line');
  await expect(page.locator('textarea')).toHaveCount(1);
  await expect(page.getByRole('button', {name: 'Preview', exact: true})).toHaveCount(1);
  await textarea.fill('cached');
  await page.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(page.locator('yona-markdown-renderer p')).toHaveText('cached');
  await page.evaluate(() => document.querySelector('form')!.reset());
  await expect(textarea).toBeVisible();
  await expect(textarea).toHaveValue('**initial**');
});

test.describe('editor geometry', () => {
  test.use({storageState: {cookies: [], origins: []}});

  test('preview preserves the resized editor bounds for short and overflowing content', async ({page}) => {
    for (const width of [1366, 390]) {
      await page.setViewportSize({width, height: 900});
      await page.evaluate(async () => {
        await customElements.whenDefined('yona-markdown-editor');
        const form = document.createElement('form');
        form.style.width = '90%';
        const editor = document.createElement('yona-markdown-editor');
        editor.append(document.createElement('textarea'));
        const following = document.createElement('p');
        following.id = 'following-editor';
        following.textContent = 'Following content';
        form.append(editor, following);
        document.body.replaceChildren(form);
        await (editor as MarkdownElement).ready;
      });
      const textarea = page.locator('textarea');
      await textarea.evaluate((element: HTMLTextAreaElement) => { element.style.height = '320px'; });
      const editor = page.locator('yona-markdown-editor');
      const before = (await editor.boundingBox())!;
      const textareaBefore = (await textarea.boundingBox())!;
      const followingBefore = (await page.locator('#following-editor').boundingBox())!;
      for (const source of ['Short **preview**.', 'Long paragraph.\n\n'.repeat(100)]) {
        await textarea.fill(source);
        await page.getByRole('button', {name: 'Preview', exact: true}).click();
        await expect(page.locator('yona-markdown-renderer')).toHaveAttribute('data-markdown-ready', '');
        expect((await editor.boundingBox())!.height).toBeCloseTo(before.height, 0);
        expect((await page.locator('#following-editor').boundingBox())!.y).toBeCloseTo(followingBefore.y, 0);
        if (source.startsWith('Long')) {
          expect(await page.locator('.markdown-preview').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
        }
        await page.getByRole('button', {name: 'Edit', exact: true}).click();
        await expect(textarea).toBeVisible();
        expect((await textarea.boundingBox())!).toEqual(textareaBefore);
        expect((await editor.boundingBox())!).toEqual(before);
        await expect(textarea).toHaveValue(source);
      }
    }
  });
});

test('checklist insertion targets its editor and exits a stale preview', async ({page}) => {
  await page.evaluate(async () => {
    const form = document.createElement('form');
    for (const name of ['first', 'second']) {
      const editor = document.createElement('yona-markdown-editor');
      const textarea = document.createElement('textarea');
      textarea.name = name;
      textarea.defaultValue = `${name} editor`;
      editor.append(textarea);
      form.append(editor);
    }
    document.body.replaceChildren(form);
    await Promise.all(Array.from(form.querySelectorAll('yona-markdown-editor'), editor => (editor as MarkdownElement).ready));
  });
  const second = page.locator('yona-markdown-editor').nth(1);
  await second.locator('textarea').evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(0, 0));
  await second.getByRole('button', {name: 'Preview', exact: true}).click();
  await expect(second.locator('yona-markdown-renderer p')).toHaveText('second editor');
  await second.getByRole('button', {name: 'Add checklist', exact: true}).click();
  await expect(second.locator('yona-markdown-renderer')).toHaveCount(0);
  await expect(page.locator('textarea[name=first]')).toHaveValue('first editor');
  await expect(second.locator('textarea')).toBeFocused();
  await second.locator('textarea').pressSequentially(' next');
  await expect(second.locator('textarea')).toHaveValue('second editor\n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo C next');
});

test('autocomplete uses local emoji and treats server labels as text', async ({page}) => {
  const requests: string[] = [];
  await page.route('**/api/owner/project/mentionList*', async route => {
    requests.push(route.request().url());
    const issue = new URL(route.request().url()).searchParams.get('mentionType') === 'issue';
    await route.fulfill({json: {result: issue
      ? [{issueNo: '17', title: '<img src=x onerror=alert(1)> issue'}]
      : [{loginid: 'reader', name: '<script>alert(1)</script>', searchText: 'reader', image: 'javascript:alert(1)'}]}});
  });
  await page.evaluate(async () => {
    const editor = document.createElement('yona-markdown-editor');
    editor.setAttribute('data-mention-url', '/api/owner/project/mentionList');
    editor.append(document.createElement('textarea'));
    document.body.replaceChildren(editor);
    await (editor as MarkdownElement).ready;
  });
  const textarea = page.locator('textarea');
  await textarea.pressSequentially(':smile');
  await expect(page.getByRole('option').first()).toContainText('smile');
  await textarea.press('Enter');
  await expect(textarea).not.toHaveValue(/:smile/);
  expect(requests).toEqual([]);
  await textarea.fill('');
  await textarea.pressSequentially('@read');
  await expect(page.getByRole('option')).toHaveText('<script>alert(1)</script> @reader');
  await expect(page.locator('.markdown-suggestions script, .markdown-suggestions img')).toHaveCount(0);
  await textarea.press('Enter');
  await expect(textarea).toHaveValue('@reader ');
  await textarea.fill('');
  await textarea.pressSequentially('#17');
  await expect(page.getByRole('option')).toHaveText('#17 <img src=x onerror=alert(1)> issue');
  await expect(page.locator('.markdown-suggestions img')).toHaveCount(0);
  await textarea.press('Enter');
  await expect(textarea).toHaveValue('#17 ');
});

test('image Viewer belongs to renderer mount lifetime', async ({page}) => {
  await page.evaluate(async () => {
    const renderer = document.createElement('yona-markdown-renderer');
    renderer.textContent = '![Yona](/images/favicon.ico)';
    document.body.replaceChildren(renderer);
    await (renderer as MarkdownElement).ready;
  });
  await page.locator('yona-markdown-renderer img').click();
  await expect(page.locator('.viewer-container')).toBeVisible();
  await page.evaluate(() => document.querySelector('yona-markdown-renderer')!.remove());
  await expect(page.locator('.viewer-container')).toHaveCount(0);
});

test('malformed raw HTML cannot create executable markup or successful form controls', async ({page}) => {
  const attacks = [
    '<img src=x onerror="window.markdownXss=true">',
    '<svg><g onload="window.markdownXss=true"></g></svg>',
    '<math><mtext><table><mglyph><style><!--</style><img title="--><img src=x onerror=window.markdownXss=true>">',
    '<svg><foreignObject><iframe srcdoc="<script>parent.markdownXss=true</script>"></iframe></foreignObject></svg>',
    '<a href="java&#x09;script:window.markdownXss=true">click</a>',
    '[click](javascript:window.markdownXss=true)',
    '<form><input type=hidden name=body value=overwrite><textarea name=body>overwrite</textarea><button autofocus>submit</button></form>',
    '<details open ontoggle="window.markdownXss=true"><summary>safe text</summary></details>',
  ];
  await page.evaluate(async sources => {
    document.body.replaceChildren();
    for (const source of sources) {
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.textContent = source;
      document.body.append(renderer);
      await (renderer as MarkdownElement).ready;
    }
  }, attacks);
  await expect(page.locator('.markdown-output script, .markdown-output svg, .markdown-output math, .markdown-output iframe, .markdown-output form, .markdown-output textarea, .markdown-output button, .markdown-output input:not([type=checkbox]), .markdown-output [onerror], .markdown-output [ontoggle], .markdown-output a[href^="javascript:"]')).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, 'markdownXss'))).toBeUndefined();
  await expect(page.locator('.markdown-output summary')).toHaveText('safe text');
});
