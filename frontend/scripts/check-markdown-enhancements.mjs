import assert from 'node:assert/strict';
import {readFile, writeFile} from 'node:fs/promises';
import {chromium, firefox, webkit} from '../../e2e/node_modules/@playwright/test/index.mjs';

const base = process.argv[2] ?? 'http://localhost:8080';
const fixtures = JSON.parse(await readFile(new URL('../src/markdown/runtime/highlight-compatibility.json', import.meta.url)));
const results = [];
for (const [name, engine] of Object.entries({chromium, firefox, webkit})) {
  const browser = await engine.launch({headless: true});
  try {
    const page = await browser.newPage();
    const requests = [];
    const errors = [];
    page.on('request', request => requests.push(request.url()));
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/__markdown_check', route => route.fulfill({contentType: 'text/html', body: '<!doctype html><html><body><script type="module" src="/javascripts/markdown/yona-markdown-renderer.js"></script></body></html>'}));
    await page.goto(`${base}/__markdown_check`);
    await page.evaluate(() => customElements.whenDefined('yona-markdown-renderer'));
    const plain = await page.evaluate(async () => {
      const started = performance.now();
      const root = document.createElement('yona-markdown-renderer');
      root.setAttribute('mode', 'document');
      root.textContent = '# plain\n\nNo expensive enhancements';
      document.body.append(root);
      await root.updateComplete;
      const {promise, resolve} = Promise.withResolvers();
      requestAnimationFrame(() => requestAnimationFrame(resolve));
      await promise;
      return {heading: root.querySelector('h1')?.textContent, firstPaintMs: performance.now() - started};
    });
    assert.equal(plain.heading, 'plain#');
    assert(!requests.some(url => /mermaid|\/chunks\/(?:core|javascript|python)-/.test(url)), 'plain Markdown downloaded expensive enhancement');
    const coreRequests = requests.length;
    const languages = await page.evaluate(async fixtures => {
      const {highlightCode} = await import('/javascripts/markdown/yona-markdown-renderer.js');
      const results = [];
      const samples = new Map(Object.entries(fixtures.samples));
      for (const inventory of [fixtures.legacy, fixtures.current]) {
        for (const [language, aliases] of Object.entries(inventory)) {
          for (const identifier of [language, ...aliases]) {
            samples.set(identifier, fixtures.samples[fixtures.migrations[language] ?? language]);
          }
        }
      }
      for (const [identifier, sample] of samples) {
        const pre = document.createElement('pre');
        const code = document.createElement('code');
        code.textContent = sample;
        pre.append(code);
        document.body.append(pre);
        await highlightCode(code, identifier);
        results.push({identifier, marked: code.classList.contains('hljs'), preserved: code.textContent === sample,
          plaintext: sample === fixtures.samples.plaintext, tokens: code.querySelectorAll('span').length});
        pre.remove();
      }
      return results;
    }, fixtures);
    assert.equal(Object.keys(fixtures.legacy).length, 59);
    assert(languages.length >= Object.keys(fixtures.samples).length);
    for (const result of languages) {
      assert(result.marked, `unsupported grammar ${result.identifier}`);
      assert(result.preserved, `changed code text ${result.identifier}`);
      assert.equal(result.tokens > 0, !result.plaintext, `unexpected tokenization: ${result.identifier}`);
    }
    await page.evaluate(() => {
      window.diagramLongTasks = [];
      window.diagramStarted = performance.now();
      if (PerformanceObserver.supportedEntryTypes.includes('longtask')) {
        window.diagramObserver = new PerformanceObserver(list => {
          window.diagramLongTasks.push(...list.getEntries().map(entry => entry.duration));
        });
        window.diagramObserver.observe({type: 'longtask'});
      }
      document.body.replaceChildren();
      for (let i = 0; i < 10; i++) {
        const renderer = document.createElement('yona-markdown-renderer');
        renderer.textContent = '```mermaid\ngraph TD\n A[Start] --> B[End]\n```';
        document.body.append(renderer);
      }
    });
    await page.waitForFunction(() => document.querySelectorAll('.markdown-mermaid svg').length === 10, {timeout: 60000});
    const diagrams = await page.locator('.markdown-mermaid').count();
    const diagramMetrics = await page.evaluate(() => {
      window.diagramObserver?.disconnect();
      return {elapsedMs: performance.now() - window.diagramStarted, longTasks: window.diagramLongTasks};
    });
    assert.equal(diagrams, 10);
    assert.equal(await page.locator('.markdown-mermaid foreignObject, .markdown-mermaid script, .markdown-mermaid [onload]').count(), 0);
    await page.evaluate(() => {
      const renderer = document.createElement('yona-markdown-renderer');
      renderer.id = 'attack';
      renderer.textContent = '```mermaid\n%%{init: {"securityLevel":"loose","flowchart":{"htmlLabels":true}}}%%\ngraph TD\n A["<img src=x onerror=window.mermaidXss=true>"] --> B\n click A "javascript:window.mermaidXss=true"\n```';
      document.body.append(renderer);
    });
    await page.waitForSelector('#attack .markdown-mermaid svg');
    assert.equal(await page.locator('#attack foreignObject, #attack script, #attack img, #attack a, #attack [onerror]').count(), 0);
    assert.equal(await page.evaluate(() => window.mermaidXss), undefined);
    await page.evaluate(() => {
      const sources = [
        ['invalid', 'invalid ! diagram'],
        ['large', 'graph TD\n' + 'A-->B;'.repeat(9000)],
        ['edges', 'graph TD\n' + Array.from({length: 501}, (_, index) => `A${index}-->A${index + 1};`).join('\n')],
      ];
      for (const [id, source] of sources) {
        const renderer = document.createElement('yona-markdown-renderer');
        renderer.id = id;
        renderer.textContent = '```mermaid\n' + source + '\n```';
        document.body.append(renderer);
      }
    });
    await page.waitForFunction(() => document.querySelectorAll('.markdown-mermaid-error').length === 3, {timeout: 30000});
    assert.match(await page.locator('#invalid pre code').textContent(), /invalid ! diagram/);
    assert((await page.locator('#large pre code').textContent()).length > 50000);
    assert.match(await page.locator('#edges pre code').textContent(), /A500-->A501/);
    assert.deepEqual(errors, []);
    results.push({browser: name, coreRequests, firstPaintMs: plain.firstPaintMs,
      identifiers: languages.length, grammars: Object.keys(fixtures.samples).length,
      diagrams, diagramMetrics, requests: requests.length});
  } finally { await browser.close(); }
}
console.log(JSON.stringify(results, null, 2));
if (process.env.MARKDOWN_MEASUREMENTS) await writeFile(process.env.MARKDOWN_MEASUREMENTS, JSON.stringify(results, null, 2));
