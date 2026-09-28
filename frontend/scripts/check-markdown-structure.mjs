import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require = createRequire(new URL('../../e2e/package.json', import.meta.url));
const playwright = require('@playwright/test');
const {expect} = playwright;
const baseURL = process.argv[2] ?? 'http://localhost:9000';
const browsers = (process.env.BROWSERS ?? 'chromium,firefox,webkit').split(',');

for (const engine of browsers) {
  const browser = await playwright[engine].launch({headless: true});
  const context = await browser.newContext({baseURL});
  async function pageWithRenderer() {
    const page = await context.newPage();
    await page.goto('/');
    await page.evaluate(() => customElements.whenDefined('yona-markdown-renderer'));
    return page;
  }
  try {
    // Actual mounted components share one permission-metadata request; ignored nodes stay literal.
    const batchPage = await pageWithRenderer();
    const requests = [];
    await batchPage.route('**/markdown/references/resolve', async route => {
      const body = route.request().postDataJSON();
      requests.push(body);
      await route.fulfill({json: {items: body.items.flatMap(item => {
        if (item.value === '#404') return [];
        return [{key: `${item.type}:${item.value}`, type: item.type,
          href: item.value === '#2' ? 'javascript:alert(1)' : '/owner/project/issue/1',
          label: '<img src=x onerror="window.referenceXss=true">', state: 'open'}];
      })}});
    });
    await batchPage.evaluate(async () => {
      document.body.replaceChildren();
      const mounted = [];
      for (let index = 0; index < 100; index++) {
        const renderer = document.createElement('yona-markdown-renderer');
        renderer.setAttribute('owner', 'owner');
        renderer.setAttribute('project', 'project');
        renderer.textContent = '#1 #1 #2 #404 @reader @owner/project abcdef0 `#88` [#77](/already) <pre>#66</pre>';
        document.body.append(renderer);
        mounted.push(renderer.updateComplete);
      }
      await Promise.all(mounted);
    });
    await expect(batchPage.locator('a.issueLink')).toHaveCount(200);
    assert.equal(requests.length, 1, '100 simultaneous comments use one batch');
    assert.deepEqual(requests[0].items.map(item => `${item.type}:${item.value}`).sort(),
      ['issue:#1', 'issue:#2', 'issue:#404', 'user:@reader', 'project:owner/project', 'commit:abcdef0'].sort());
    await expect(batchPage.locator('img,[onerror],a[href^="javascript:"]')).toHaveCount(0);
    await expect(batchPage.locator('span[data-yona-reference="issue:#2"]')).toHaveCount(100);
    await expect(batchPage.locator('span[data-yona-reference="issue:#404"]')).toHaveCount(100);
    assert.equal(await batchPage.evaluate(() => window.referenceXss), undefined);
    await batchPage.evaluate(async () => {
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.setAttribute('owner', 'owner');
      renderer.setAttribute('project', 'project');
      renderer.textContent = '#1';
      document.body.append(renderer);
      await renderer.updateComplete;
    });
    await expect(batchPage.locator('a.issueLink')).toHaveCount(201);
    assert.equal(requests.length, 1, 'resolved metadata is cached for the page');
    await batchPage.close();

    // Distinct project contexts cannot share permission metadata.
    const scopedPage = await pageWithRenderer();
    const scopes = [];
    await scopedPage.route('**/markdown/references/resolve', async route => {
      scopes.push(new URL(route.request().url()).pathname);
      await route.fulfill({json: {items: []}});
    });
    await scopedPage.evaluate(async () => {
      document.body.replaceChildren();
      const mounted = ['first', 'second'].map(project => {
        const renderer = document.createElement('yona-markdown-renderer');
        renderer.setAttribute('owner', 'owner');
        renderer.setAttribute('project', project);
        renderer.textContent = '#1';
        document.body.append(renderer);
        return renderer.updateComplete;
      });
      await Promise.all(mounted);
    });
    await expect.poll(() => scopes.length).toBe(2);
    assert.deepEqual(scopes.sort(), ['/api/owner/first/markdown/references/resolve', '/api/owner/second/markdown/references/resolve']);
    await scopedPage.close();

    // Detaching cancels the request, and reconnecting resubscribes unresolved placeholders.
    const abortPage = await pageWithRenderer();
    let aborted = false;
    let firstRoute;
    let requestCount = 0;
    abortPage.on('requestfailed', request => { if (request.url().endsWith('/markdown/references/resolve')) aborted = true; });
    await abortPage.route('**/markdown/references/resolve', async route => {
      requestCount++;
      if (requestCount === 1) { firstRoute = route; return; }
      await route.fulfill({json: {items: [{key: 'issue:#31', type: 'issue', href: '/owner/project/issue/31', label: '#31.Readable'}]}});
    });
    await abortPage.evaluate(async () => {
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.setAttribute('owner', 'owner');
      renderer.setAttribute('project', 'project');
      renderer.setAttribute('mode', 'document');
      renderer.textContent = '# Heading\n\n#31';
      document.body.replaceChildren(renderer);
      window.movedRenderer = renderer;
      await renderer.updateComplete;
    });
    await expect.poll(() => requestCount).toBe(1);
    await abortPage.evaluate(() => window.movedRenderer.remove());
    await expect.poll(() => aborted).toBe(true);
    await firstRoute.abort().catch(() => {});
    await abortPage.evaluate(async () => {
      document.body.append(window.movedRenderer);
      await window.movedRenderer.updateComplete;
    });
    await expect(abortPage.locator('a.issueLink')).toHaveText('#31.Readable');
    await expect(abortPage.locator('h1')).toHaveAttribute('id', 'heading');
    await expect(abortPage.locator('h1 a.heading-anchor')).toHaveCount(1);
    assert.equal(requestCount, 2);
    await abortPage.close();

    const linksPage = await pageWithRenderer();
    await linksPage.route('**/markdown/references/resolve', route => route.fulfill({json: {items: []}}));
    await linksPage.evaluate(async () => {
      document.body.replaceChildren();
      let policy = document.querySelector('meta[name="yona-markdown-noreferrer"]');
      if (!policy) {
        policy = document.createElement('meta');
        policy.name = 'yona-markdown-noreferrer';
        document.head.append(policy);
      }
      policy.content = 'true';
      for (const [id, ref, path] of [['repository', 'feature/docs', 'docs/README.md'], ['wiki', '', 'Guide']]) {
        const renderer = document.createElement('yona-markdown-renderer');
        renderer.id = id;
        for (const [key, value] of Object.entries({mode: 'document', owner: 'owner', project: 'project', ref, path})) renderer.setAttribute(key, value);
        renderer.textContent = '[child](guide.md#section) ![picture](../images/pic.png) [site](/site) [external](https://example.com/path) [fragment](#part)';
        document.body.append(renderer);
        await renderer.updateComplete;
      }
    });
    await expect(linksPage.locator('#repository a').filter({hasText: 'child'})).toHaveAttribute('href', '/owner/project/code/feature%2Fdocs/docs/guide.md#section');
    await expect(linksPage.locator('#repository img')).toHaveAttribute('src', '/owner/project/files/feature%2Fdocs/images/pic.png');
    await expect(linksPage.locator('#wiki a').filter({hasText: 'child'})).toHaveAttribute('href', '/owner/project/wiki/guide.md#section');
    await expect(linksPage.locator('#repository a').filter({hasText: 'site'})).toHaveAttribute('href', '/site');
    await expect(linksPage.locator('#repository a').filter({hasText: 'external'})).toHaveAttribute('href', 'https://example.com/path');
    await expect(linksPage.locator('#repository a').filter({hasText: 'external'})).toHaveAttribute('rel', 'noopener noreferrer');
    assert.equal(await linksPage.locator('#repository a').filter({hasText: 'site'}).getAttribute('rel'), null);
    await expect(linksPage.locator('#repository a').filter({hasText: 'fragment'})).toHaveAttribute('href', '#part');
    await linksPage.close();

    // Explicit mount must enable only authorized persisted tasks and reuse the PATCH endpoint.
    const taskPage = await pageWithRenderer();
    await taskPage.addScriptTag({url: `${baseURL}/javascripts/common/yona.Tasklist.js`});
    assert.equal(await taskPage.evaluate(() => typeof window.yona?.initTasklist), 'function');
    let patch;
    await taskPage.route('**/markdown-task-fixture', async route => {
      patch = {method: route.request().method(), ...route.request().postDataJSON()};
      await route.fulfill({body: 'ok'});
    });
    await taskPage.evaluate(async () => {
      const fixture = document.createElement('section');
      fixture.innerHTML = '<section><form action="/markdown-task-fixture"><textarea>- [ ] task</textarea></form></section><div id="task-view"><div><span class="done-counter"></span><span class="bar"></span><span class="task-title"></span></div></div>';
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.className = 'markdown-wrap';
      renderer.dataset.allowedUpdate = 'true';
      renderer.textContent = '- [ ] task';
      fixture.querySelector('#task-view').append(renderer);
      document.body.replaceChildren(fixture);
      await renderer.updateComplete;
    });
    await expect(taskPage.locator('input[type=checkbox]')).toBeEnabled();
    await taskPage.locator('input[type=checkbox]').check();
    await expect.poll(() => patch).toEqual({method: 'PATCH', content: '- [x] task', original: '- [ ] task'});
    await expect(taskPage.locator('textarea')).toHaveValue('- [x] task');
    await expect(taskPage.locator('.done-counter')).toHaveText('(1/1)');
    await taskPage.evaluate(async () => {
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.textContent = '- [ ] read only';
      document.body.replaceChildren(renderer);
      await renderer.updateComplete;
    });
    await expect(taskPage.locator('input[type=checkbox]')).toBeDisabled();
    await taskPage.close();
    console.log(`${engine}: reference batching/cache/context/security/abort/reconnect, relative URLs, task PATCH passed`);
  } finally {
    await context.close();
    await browser.close();
  }
}
