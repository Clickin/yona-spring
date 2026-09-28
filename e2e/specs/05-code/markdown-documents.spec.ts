import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {requireSeed} from '../../support/seed-store';
import {uniqueSuffix} from '../../support/unique';

test('README and long Markdown documents preserve legacy source semantics and real relative resources', async ({page, baseURL}) => {
  const owner = requireSeed('projectOwner');
  const project = requireSeed('projectName');
  const url = new URL(baseURL!);
  url.username = requireSeed('adminLoginId');
  url.password = requireSeed('adminPassword');
  url.pathname = `/git/${owner}/${project}`;
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yona-markdown-corpus-'));
  try {
    const git = (args: string[]) => execFileSync('git', args, {cwd: directory, stdio: 'pipe'});
    git(['clone', url.href, '.']);
    git(['checkout', 'main']);
    git(['config', 'user.email', 'admin@yona-e2e.test']);
    git(['config', 'user.name', 'E2E Admin']);
    fs.mkdirSync(path.join(directory, 'docs'), {recursive: true});
    fs.mkdirSync(path.join(directory, 'images'), {recursive: true});
    fs.writeFileSync(path.join(directory, 'README.md'), `# Document corpus ${uniqueSuffix()}\n\n[Guide](docs/guide.md)\n`);
    fs.writeFileSync(path.join(directory, 'docs/guide.md'), '# 한글 제목\n\n# 한글 제목\n\n~~삭제~~\n\n- [x] 작업\n\n| 항목 | 값 |\n| --- | --- |\n| legacy | GFM |\n\n<details><summary>내용</summary>safe HTML</details>\n\n![이미지](../images/fixture.png)\n\n[홈](../README.md)\n\n```kotlin\nval answer = 42\n```\n\n' + 'Long document **paragraph** with nested meaning.\n\n'.repeat(200));
    fs.writeFileSync(path.join(directory, 'images/fixture.png'), await page.screenshot({type: 'png', scale: 'css', clip: {x: 0, y: 0, width: 1, height: 1}}));
    git(['add', 'README.md', 'docs/guide.md', 'images/fixture.png']);
    git(['commit', '-m', 'E2E Markdown semantic corpus']);
    git(['push', 'origin', 'main']);
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
  await page.goto(`/${owner}/${project}`);
  await page.locator('yona-markdown-renderer').getByRole('link', {name: 'Guide', exact: true}).click();
  await expect(page).toHaveURL(/\/code\/HEAD\/docs\/guide\.md$/);
  const document = page.locator('yona-markdown-renderer[mode=document]');
  await expect(document.locator('h1').nth(0)).toHaveAttribute('id', '한글-제목');
  await expect(document.locator('h1').nth(1)).toHaveAttribute('id', '한글-제목-1');
  await expect(document.locator('del')).toHaveText('삭제');
  await expect(document.locator('input[type=checkbox]')).toBeChecked();
  await expect(document.locator('input[type=checkbox]')).toBeDisabled();
  await expect(document.locator('tbody td')).toHaveText(['legacy', 'GFM']);
  await expect(document.locator('details summary')).toHaveText('내용');
  await expect(document.locator('pre code.hljs')).toHaveText('val answer = 42\n');
  await expect(document.locator('p strong')).toHaveCount(200);
  await expect(document.locator('img[alt="이미지"]')).toHaveJSProperty('naturalWidth', 1);
  await document.getByRole('link', {name: '홈', exact: true}).click();
  await expect(page).toHaveURL(/\/code\/HEAD\/README\.md$/);
  await expect(page.locator('yona-markdown-renderer h1')).toContainText('Document corpus');
});
