import { test, expect } from '@playwright/test';
import { uniqueSuffix } from '../../support/unique';

test('assignee names survive selection, reload, individual removal and clearing', async ({ page, browser, baseURL }) => {
  const suffix = uniqueSuffix();
  const loginId = `assignee${suffix}`;
  const name = `Assignee ${suffix}`;
  const signup = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  try {
    const userPage = await signup.newPage();
    await userPage.goto('/signup');
    await userPage.fill('#loginId', loginId);
    await userPage.fill('#uname', name);
    await userPage.fill('#email', `${loginId}@yona-e2e.test`);
    await userPage.fill('#password', 'AssigneePassw0rd!');
    await userPage.fill('#retypedPassword', 'AssigneePassw0rd!');
    await userPage.click('form[action="/signup"] button[type=submit]');
    await expect(userPage).not.toHaveURL(/\/signup$/);
  } finally {
    await signup.close();
  }

  await page.goto('/projectform');
  await page.fill('input[name=name]', `assignees-${suffix}`);
  await page.click('#newProjectForm button');
  await expect(page).toHaveURL(new RegExp(`/assignees-${suffix}$`));
  const project = new URL(page.url()).pathname;
  const owner = project.split('/')[1];
  await page.goto(`${project}/members`);
  await page.fill('#addNewMember #loginId', loginId);
  await page.click('#addNewMember button[type=submit]');
  await expect(page.locator('.member-id').filter({ hasText: `@${loginId}` })).toBeVisible();

  await page.goto(`${project}/issueform`);
  const picker = page.locator('#assignee + .ts-wrapper');
  const search = picker.locator('input');
  await search.fill(loginId);
  await expect(picker.locator(`.option[data-value="${loginId}"]`)).toContainText(name);
  await picker.locator(`.option[data-value="${loginId}"]`).click();
  await search.fill(owner);
  const ownerOption = picker.locator(`.option[data-value="${owner}"]`);
  await expect(ownerOption).toBeVisible();
  const ownerName = await ownerOption.locator('.name').innerText();
  await ownerOption.click();
  await expect(picker.locator(`.item[data-value="${loginId}"]`)).toContainText(name);
  await expect(picker.locator(`.item[data-value="${owner}"]`)).toContainText(ownerName);
  await page.fill('#title', `Assignee regression ${suffix}`);
  await page.locator('textarea[data-editor-mode="content-body"]').fill('Assignee persistence regression.', { force: true });
  await page.click('#button-save');
  await expect(page).toHaveURL(new RegExp(`${project}/issue/\\d+$`));
  await page.reload();
  await expect.poll(async () => page.locator('#assignee').evaluate((select: HTMLSelectElement) =>
    Array.from(select.selectedOptions, option => option.value).sort()
  )).toEqual([loginId, owner].sort());
  await expect(picker.locator(`.item[data-value="${loginId}"] .name`)).toHaveText(name);
  await expect(picker.locator(`.item[data-value="${owner}"] .name`)).toHaveText(ownerName);

  await picker.hover();
  const [removed] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/assignees') && response.request().method() === 'POST'),
    picker.locator(`.item[data-value="${loginId}"] .remove`).click(),
  ]);
  expect(removed.status()).toBe(200);
  await page.reload();
  await expect(page.locator('#assignee')).toHaveValues([owner]);
  const [cleared] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/assignees') && response.request().method() === 'POST'),
    page.click('[data-clear-assignees]'),
  ]);
  expect(cleared.status()).toBe(200);
  await page.reload();
  await expect(page.locator('#assignee')).toHaveValues([]);
});
