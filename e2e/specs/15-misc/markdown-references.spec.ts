import { test, expect } from '@playwright/test';
import { requireSeed } from '../../support/seed-store';

/**
 * Reference autolinking against the real resolve endpoint, matching the legacy server
 * AutoLinkRenderer markup: localized issue state, Hangul-adjacent references, @-only project
 * links and the user hover popover.
 */
test.use({ locale: 'ko-KR', extraHTTPHeaders: { 'Accept-Language': 'ko-KR' } });

test('renders legacy reference links with localized issue state', async ({ page }) => {
  const owner = requireSeed('projectOwner');
  const name = requireSeed('projectName');
  const issueNumber = requireSeed('issueNumber');
  const admin = requireSeed('adminLoginId');
  await page.goto(`/${owner}/${name}/issue/${issueNumber}`);
  await page.evaluate(({ owner, name, issueNumber, admin }) => {
    const renderer = document.createElement('yona-markdown-renderer');
    renderer.id = 'reference-fixture';
    renderer.setAttribute('owner', owner);
    renderer.setAttribute('project', name);
    renderer.textContent = `이슈#${issueNumber} @${admin} ${owner}/${name} @${owner}/${name}`;
    document.body.append(renderer);
  }, { owner, name, issueNumber, admin });
  const fixture = page.locator('#reference-fixture');

  const issue = fixture.locator('a.issueLink');
  await expect(issue).toHaveAttribute('href', `/${owner}/${name}/issue/${issueNumber}`);
  await expect(issue.locator('.issue-state')).toHaveText(/^(열림|닫힘)$/);

  const user = fixture.locator('a.user-link > span[data-toggle="popover"]');
  await expect(user).toHaveAttribute('data-content', new RegExp(` ${admin}$`));

  await expect(fixture.locator('a > span.project-link')).toHaveCount(1);
  await expect(fixture).toContainText(`${owner}/${name} @`);
});
