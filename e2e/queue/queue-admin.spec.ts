import { expect, request as playwrightRequest, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { APIRequestContext, Browser, BrowserContext, Page, Response, TestInfo } from '@playwright/test';

const baseUrl = process.env.YONA_BASE_URL;
const controlToken = process.env.YONA_QUEUE_TEST_CONTROL_TOKEN;
if (!baseUrl || !controlToken) {
  throw new Error('YONA_BASE_URL and YONA_QUEUE_TEST_CONTROL_TOKEN are required for the private queue fixture');
}

const base = new URL(baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`);
const appRootPath = base.pathname.replace(/\/+$/, '') || '/';
const appPath = (path: string) => new URL(path.replace(/^\/+/, ''), base).toString();
const apiPath = (path: string) => appPath(`api/admin/queue/v1/${path.replace(/^\/+/, '')}`);
const pagePath = () => appPath('site/admin/queue');
const pageName = new URL(pagePath()).pathname;
const eventsName = new URL(apiPath('events')).pathname;

// One URL predicate is shared by the live-change, reconnect, and auth scenarios.
function isQueueEvents(response: Response): boolean {
  return new URL(response.url()).pathname === eventsName;
}

type QueueRun = { runId: string; jobIds: string[] };
type QueueJob = {
  id: string;
  type: string;
  status: string;
  attemptCount: string;
  resources: string[];
};

async function freshSession(context: BrowserContext, role: string): Promise<void> {
  // Install the fixture session cookie at the configured context path, including /queue-it.
  const control = await playwrightRequest.newContext({
    extraHTTPHeaders: { 'X-Yona-Queue-Test-Control': controlToken! },
  });
  let cookie: string;
  try {
    const response = await control.post(appPath('__test__/queue/v1/sessions'), { data: { role } });
    expect(response.status(), `fixture session for ${role}`).toBe(200);
    cookie = (await response.json() as { cookie: string }).cookie;
  } finally {
    await control.dispose();
  }

  const separator = cookie.indexOf('=');
  expect(separator).toBeGreaterThan(0);
  await context.addCookies([{
    name: cookie.slice(0, separator),
    value: cookie.slice(separator + 1),
    domain: base.hostname,
    path: appRootPath,
    httpOnly: true,
    secure: base.protocol === 'https:',
    sameSite: 'Lax',
  }]);
}

async function queueFixture(
  request: APIRequestContext,
  scenario: string,
  resourceKey: string,
  delayMs?: number,
): Promise<QueueRun> {
  const response = await request.post(appPath('__test__/queue/v1/runs'), {
    headers: { 'X-Yona-Queue-Test-Control': controlToken! },
    data: { scenario, resourceKey, runKey: `pw-${randomUUID()}`, ...(delayMs === undefined ? {} : { delayMs }) },
  });
  expect(response.status(), `fixture job ${scenario}`).toBe(200);
  return response.json() as Promise<QueueRun>;
}

async function queueJob(request: APIRequestContext, id: string): Promise<QueueJob> {
  const response = await request.get(apiPath(`jobs/${id}`));
  expect(response.status()).toBe(200);
  return response.json() as Promise<QueueJob>;
}

async function waitForStatus(request: APIRequestContext, id: string, status: string): Promise<void> {
  await expect.poll(async () => (await queueJob(request, id)).status, {
    timeout: 30_000,
    intervals: [50, 100, 200, 500],
  }).toBe(status);
}

async function waitForFailedAttemptCount(request: APIRequestContext, id: string, count: number): Promise<void> {
  await expect.poll(async () => {
    const current = await queueJob(request, id);
    return current.status === 'FAILED' && current.attemptCount === String(count);
  }, { timeout: 30_000, intervals: [50, 100, 200, 500] }).toBe(true);
}

async function fixtureAction(request: APIRequestContext, runId: string, action: string): Promise<void> {
  const response = await request.post(appPath(`__test__/queue/v1/runs/${runId}/actions/${action}`), {
    headers: { 'X-Yona-Queue-Test-Control': controlToken! },
    data: {},
  });
  expect(response.status(), `fixture action ${action}`).toBe(204);
}

async function csrfToken(context: BrowserContext): Promise<string> {
  const response = await context.request.get(apiPath('jobs'));
  expect(response.status()).toBe(200);
  let value = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')?.value;
  if (!value) {
    await context.request.get(appPath('users/loginform'));
    value = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')?.value;
  }
  expect(value, 'fixture admin session receives the real CSRF cookie').toBeTruthy();
  return value!;
}

async function cancelThroughRest(context: BrowserContext, id: string): Promise<void> {
  const response = await context.request.post(apiPath(`jobs/${id}/cancel`), {
    headers: { 'X-XSRF-TOKEN': await csrfToken(context) },
    data: { commandId: randomUUID() },
  });
  expect([200, 202]).toContain(response.status());
}

async function jobRows(page: Page): Promise<string[]> {
  return page.locator('tr[data-job-id]').evaluateAll(rows => rows.map(row => row.getAttribute('data-job-id')!));
}

async function emptyContext(browser: Browser, javaScriptEnabled: boolean): Promise<BrowserContext> {
  return browser.newContext({
    javaScriptEnabled,
    storageState: { cookies: [], origins: [] },
  });
}

// This standalone suite deliberately avoids e2e global setup and .auth/admin.json.
let adminSequence = 0;
test.beforeEach(async ({ page }) => {
  await freshSession(page.context(), `capacity-admin-${adminSequence++}`);
});

test.describe('Thymeleaf queue console', () => {
  test('server rows, filters, exact large IDs, direct detail URLs, and browser history work without JavaScript', async ({ browser }) => {
    const context = await emptyContext(browser, false);
    try {
      await freshSession(context, 'admin');
      const page = await context.newPage();
      const initial = await queueFixture(context.request, 'large-id', `pw-large-${randomUUID()}`);
      let id = initial.jobIds[0];
      if (BigInt(id) % 2n === 0n) {
        const odd = await queueFixture(context.request, 'success', `pw-large-odd-${randomUUID()}`);
        id = odd.jobIds[0];
      }
      expect(BigInt(id)).toBeGreaterThan(BigInt(Number.MAX_SAFE_INTEGER));
      expect(BigInt(id) % 2n).toBe(1n);
      expect(Number.isSafeInteger(Number(id))).toBe(false);
      expect(String(Number(id))).not.toBe(id);
      await waitForStatus(context.request, id, 'SUCCEEDED');
      const job = await queueJob(context.request, id);
      const resource = job.resources[0];

      const response = await page.goto(pagePath());
      expect(response?.status()).toBe(200);
      await expect(page.getByRole('heading', { level: 1, name: '작업 큐' })).toBeVisible();
      const table = page.getByRole('table', { name: '작업 목록 (최신 ID순, 최대 50개)' });
      await expect(table).toBeVisible();
      const row = page.locator(`tr[data-job-id="${id}"]`);
      await expect(row).toBeVisible();
      await expect(row.getByRole('link', { name: id })).toHaveText(id);
      expect(await row.getAttribute('data-job-id')).toBe(id);
      expect(await row.locator('time').first().getAttribute('datetime')).toMatch(/Z$/);

      await page.getByLabel('상태').selectOption(['SUCCEEDED']);
      await page.getByLabel('작업 종류').fill(job.type);
      await page.getByLabel('자원').fill(resource);
      await page.getByRole('button', { name: '필터 적용' }).click();
      const filtered = new URL(page.url());
      expect(filtered.pathname).toBe(pageName);
      expect(filtered.searchParams.getAll('status')).toEqual(['SUCCEEDED']);
      expect(filtered.searchParams.get('type')).toBe(job.type);
      expect(filtered.searchParams.get('resource')).toBe(resource);
      await expect(page.locator(`tr[data-job-id="${id}"]`)).toBeVisible();

      await page.locator(`tr[data-job-id="${id}"]`).getByRole('link', { name: id }).click();
      await expect(page.locator(`#queue-detail[data-job-id="${id}"]`)).toBeVisible();
      const selected = new URL(page.url());
      expect(selected.searchParams.get('selected')).toBe(id);
      expect(selected.searchParams.get('resource')).toBe(resource);
      await page.reload();
      await expect(page.locator(`#queue-detail[data-job-id="${id}"]`)).toBeVisible();
      await page.goBack();
      await expect(page.locator('#queue-detail')).toHaveCount(0);
      await expect(page.locator(`tr[data-job-id="${id}"]`)).toBeVisible();
      await page.goForward();
      await expect(page.locator(`#queue-detail[data-job-id="${id}"]`)).toBeVisible();

      const noMatch = new URL(pagePath());
      noMatch.searchParams.set('status', 'SUCCEEDED');
      noMatch.searchParams.set('resource', `${resource}-absent`);
      await page.goto(noMatch.toString());
      await expect(page.getByText('조건에 맞는 작업이 없습니다.')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test('job cursor links paginate server rows and Back restores the exact prior page', async ({ page }) => {
    test.setTimeout(90_000);
    const ids: string[] = [];
    for (let index = 0; index < 51; index += 1) {
      const run = await queueFixture(page.request, 'success', `pw-page-${randomUUID()}`);
      ids.push(run.jobIds[0]);
    }
    const newestFirst = [...ids].sort((left, right) => BigInt(left) < BigInt(right) ? 1 : -1);

    await page.goto(pagePath());
    const firstPage = await jobRows(page);
    expect(firstPage).toEqual(newestFirst.slice(0, 50));
    await page.getByRole('link', { name: '다음 작업 50개' }).click();
    await expect(page).toHaveURL(url => url.searchParams.has('cursor'));
    const secondPage = await jobRows(page);
    expect(secondPage.length).toBeGreaterThan(0);
    expect(secondPage.length).toBeLessThanOrEqual(50);
    expect(secondPage[0]).toBe(newestFirst[50]);
    expect(new Set(secondPage).size).toBe(secondPage.length);
    expect(firstPage.some(id => secondPage.includes(id))).toBe(false);
    expect(secondPage.filter(id => ids.includes(id))).toEqual(newestFirst.slice(50));
    expect([...firstPage, ...secondPage].filter(id => ids.includes(id))).toEqual(newestFirst);
    await page.reload();
    expect(await jobRows(page)).toEqual(secondPage);
    await page.goBack();
    expect(await jobRows(page)).toEqual(firstPage);
    await page.goForward();
    expect(await jobRows(page)).toEqual(secondPage);
  });

  test('native CSRF retry submits as a real form with JavaScript disabled', async ({ browser }) => {
    const context = await emptyContext(browser, false);
    try {
      await freshSession(context, 'admin');
      const page = await context.newPage();
      const run = await queueFixture(context.request, 'manual-retry', `pw-native-retry-${randomUUID()}`);
      const id = run.jobIds[0];
      await waitForFailedAttemptCount(context.request, id, 1);
      const url = new URL(pagePath());
      url.searchParams.set('selected', id);
      await page.goto(url.toString());
      const retry = page.locator('form.queue-command[action$="/retry"]');
      await expect(retry).toBeVisible();
      const commandId = await retry.locator('input[name="commandId"]').inputValue();
      expect(commandId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);

      // The same session without a form token/header is denied and remains unchanged.
      const denied = await context.request.post(appPath(`site/admin/queue/jobs/${id}/retry`), {
        form: { commandId: randomUUID(), selected: id },
      });
      expect(denied.status()).toBe(403);
      expect((await queueJob(context.request, id)).status).toBe('FAILED');

      const submitted = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith(`/jobs/${id}/retry`));
      await retry.getByRole('button', { name: '재시도' }).click();
      expect((await submitted).status()).toBe(303);
      await expect(page).toHaveURL(url => url.searchParams.get('selected') === id);
      await waitForStatus(context.request, id, 'SUCCEEDED');
    } finally {
      await context.close();
    }
  });

  test('native CSRF cancel stays pending until the cooperative worker stops', async ({ browser }) => {
    const context = await emptyContext(browser, false);
    try {
      await freshSession(context, 'admin');
      const page = await context.newPage();
      const run = await queueFixture(context.request, 'gated-cancel', `pw-native-cancel-${randomUUID()}`);
      const id = run.jobIds[0];
      await expect.poll(async () => (await queueJob(context.request, id)).status, {
        timeout: 15_000,
        intervals: [50, 100, 200],
      }).toBe('RUNNING');
      const url = new URL(pagePath());
      url.searchParams.set('selected', id);
      await page.goto(url.toString());
      const cancel = page.locator('form.queue-command[action$="/cancel"]');
      await expect(cancel).toBeVisible();
      const submitted = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith(`/jobs/${id}/cancel`));
      await cancel.getByRole('button', { name: '취소 요청' }).click();
      expect((await submitted).status()).toBe(303);
      expect((await queueJob(context.request, id)).status).toBe('CANCEL_REQUESTED');
      await expect(page.getByText(/취소를 요청했습니다/)).toBeVisible();
      await fixtureAction(context.request, run.runId, 'release-gate');
      await waitForStatus(context.request, id, 'CANCELLED');
      await page.reload();
      await expect(page.locator('#queue-detail')).toContainText('CANCELLED');
    } finally {
      await context.close();
    }
  });

  test('retry validation preserves acknowledgement and Korean reason; valid native recovery retry succeeds', async ({ browser }) => {
    test.setTimeout(60_000);
    const context = await emptyContext(browser, false);
    try {
      await freshSession(context, 'admin');
      const page = await context.newPage();
      const run = await queueFixture(context.request, 'process-crash-unsafe', `pw-recovery-${randomUUID()}`);
      const id = run.jobIds[0];
      await expect.poll(async () => (await queueJob(context.request, id)).status, {
        timeout: 15_000,
        intervals: [50, 100, 200],
      }).toBe('RUNNING');
      await fixtureAction(context.request, run.runId, 'pause-old-attempt');
      await fixtureAction(context.request, run.runId, 'wait-for-lease-expiry');
      await waitForStatus(context.request, id, 'RECOVERY_REQUIRED');

      const url = new URL(pagePath());
      url.searchParams.set('selected', id);
      await page.goto(url.toString());
      const retry = page.locator('form.queue-command[action$="/retry"]');
      const acknowledge = retry.getByRole('checkbox', { name: /기존 부작용/ });
      const reason = retry.getByLabel('재시도 사유');
      await expect(acknowledge).not.toBeChecked();
      await expect(reason).toHaveValue('');
      await acknowledge.check();
      await reason.fill('한'.repeat(301));
      const commandId = await retry.locator('input[name="commandId"]').inputValue();
      const rejected = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith(`/jobs/${id}/retry`));
      await retry.getByRole('button', { name: '재시도' }).click();
      expect((await rejected).status()).toBe(400);
      await expect(page.getByRole('alert')).toContainText('INVALID_REASON');
      await expect(retry.locator('input[name="commandId"]')).toHaveValue(commandId);
      await expect(acknowledge).toBeChecked();
      await expect(reason).toHaveValue('한'.repeat(301));
      expect((await queueJob(context.request, id)).status).toBe('RECOVERY_REQUIRED');

      await fixtureAction(context.request, run.runId, 'release-gate');
      await reason.fill('한'.repeat(300));
      const accepted = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith(`/jobs/${id}/retry`));
      await retry.getByRole('button', { name: '재시도' }).click();
      expect((await accepted).status()).toBe(303);
      await waitForStatus(context.request, id, 'SUCCEEDED');
    } finally {
      await context.close();
    }
  });

  test('manual Turbo refresh replaces the visible server snapshot without changing the URL', async ({ page }) => {
    const run = await queueFixture(page.request, 'delayed', `pw-refresh-${randomUUID()}`, 60_000);
    const id = run.jobIds[0];
    await waitForStatus(page.request, id, 'QUEUED');
    await page.route('**/api/admin/queue/v1/events*', route => route.abort());
    let failNextFrame = true;
    await page.route('**/site/admin/queue*', async route => {
      if (failNextFrame && route.request().headers()['turbo-frame'] === 'queue-content') {
        failNextFrame = false;
        await route.abort();
      } else {
        await route.continue();
      }
    });
    await page.goto(pagePath());
    await expect(page.locator(`tr[data-job-id="${id}"]`)).toContainText('QUEUED');
    const currentUrl = page.url();
    const filterDraft = page.getByLabel('작업 종류');
    await filterDraft.fill('아직 적용하지 않은 필터');

    await cancelThroughRest(page.context(), id);
    expect((await queueJob(page.request, id)).status).toBe('CANCELLED');
    await page.getByRole('link', { name: '새로고침' }).click();
    const connection = page.locator('[data-queue-connection]');
    await expect(connection).toContainText('화면 갱신 실패', { timeout: 10_000 });
    await expect(page.locator(`tr[data-job-id="${id}"]`)).toContainText('QUEUED');
    await page.getByRole('link', { name: '새로고침' }).click();
    await expect(page.locator('turbo-frame#queue-content')).toBeVisible();
    await expect(page.locator(`tr[data-job-id="${id}"]`)).toContainText('CANCELLED');
    await expect(filterDraft).toHaveValue('아직 적용하지 않은 필터');
    expect(page.url()).toBe(currentUrl);
  });

  test('SSE changed preserves focused edits and refreshes after the command draft is cleared', async ({ page }) => {
    test.setTimeout(90_000);
    const run = await queueFixture(page.request, 'delayed', `pw-edit-safe-${randomUUID()}`, 60_000);
    const id = run.jobIds[0];
    await waitForStatus(page.request, id, 'QUEUED');
    const events = page.waitForResponse(
      response => isQueueEvents(response) && response.status() === 200,
      { timeout: 45_000 },
    );
    await page.goto(pagePath());
    await events;
    await expect(page.locator('[data-queue-connection]')).toContainText(
      '최신 서버 조회 결과입니다.', { timeout: 45_000 },
    );

    const selectedEvents = page.waitForResponse(
      response => isQueueEvents(response) && response.status() === 200,
      { timeout: 45_000 },
    );
    await page.locator(`tr[data-job-id="${id}"]`).getByRole('link', { name: id }).click();
    await selectedEvents;
    const connection = page.locator('[data-queue-connection]');
    await expect(connection).toContainText('최신 서버 조회 결과입니다.', { timeout: 45_000 });
    const reason = page.locator('form.queue-command[action$="/cancel"] input[name="reason"]');
    await reason.fill('작성 중인 취소 사유');
    await expect(reason).toBeFocused();
    await cancelThroughRest(page.context(), id);
    expect((await queueJob(page.request, id)).status).toBe('CANCELLED');

    await expect(connection).toContainText('입력과 키보드 초점을 보호하기 위해 갱신을 보류합니다.', { timeout: 45_000 });
    await expect(reason).toHaveValue('작성 중인 취소 사유');
    await expect(reason).toBeFocused();
    await reason.fill('');
    await page.getByLabel('작업 종류').focus();
    await expect(page.locator('#queue-detail')).toContainText('CANCELLED', { timeout: 45_000 });
    expect(new URL(page.url()).searchParams.get('selected')).toBe(id);
  });

  test('SSE reconnect reset reloads a durable change missed while the stream was unavailable', async ({ page }) => {
    test.setTimeout(90_000);
    const run = await queueFixture(page.request, 'delayed', `pw-reconnect-${randomUUID()}`, 60_000);
    const id = run.jobIds[0];
    await waitForStatus(page.request, id, 'QUEUED');
    let disconnected = true;
    let resolveFirstRequest!: () => void;
    const firstRequest = new Promise<void>(resolve => { resolveFirstRequest = resolve; });
    await page.route('**/api/admin/queue/v1/events*', async route => {
      if (disconnected) {
        resolveFirstRequest();
        await route.abort();
      } else {
        await route.continue();
      }
    });
    await page.goto(pagePath());
    await expect(page.locator(`tr[data-job-id="${id}"]`)).toContainText('QUEUED');
    await firstRequest;
    await cancelThroughRest(page.context(), id);
    expect((await queueJob(page.request, id)).status).toBe('CANCELLED');

    const reconnected = page.waitForResponse(
      response => isQueueEvents(response) && response.status() === 200,
      { timeout: 45_000 },
    );
    disconnected = false;
    await reconnected;
    await expect(page.locator(`tr[data-job-id="${id}"]`)).toContainText('CANCELLED', { timeout: 45_000 });
    expect(new URL(page.url()).pathname).toBe(pageName);
  });

  test('attempt history uses 50-row cursors and browser history restores each page exactly', async ({ page }) => {
    test.setTimeout(120_000);
    const run = await queueFixture(page.request, 'manual-retry-permanent', `pw-history-${randomUUID()}`);
    const id = run.jobIds[0];
    await waitForFailedAttemptCount(page.request, id, 1);
    const csrf = await csrfToken(page.context());

    for (let count = 2; count <= 105; count += 1) {
      const retry = await page.request.post(apiPath(`jobs/${id}/retry`), {
        headers: { 'X-XSRF-TOKEN': csrf },
        data: { commandId: randomUUID(), recoveryAcknowledged: false },
      });
      expect(retry.status()).toBe(202);
      await waitForFailedAttemptCount(page.request, id, count);
    }

    const url = new URL(pagePath());
    url.searchParams.set('selected', id);
    await page.goto(url.toString());
    const detail = page.locator(`#queue-detail[data-job-id="${id}"]`);
    const attempts = detail.locator('tr[data-attempt-no]');
    const attemptNos = async () => attempts.evaluateAll(rows => rows.map(row => row.getAttribute('data-attempt-no')!));
    expect(await attemptNos()).toEqual(Array.from({ length: 50 }, (_, index) => String(105 - index)));
    await detail.getByRole('link', { name: '이전 실행 50개' }).click();
    expect(new URL(page.url()).searchParams.get('attemptCursor')).toBeTruthy();
    const second = await attemptNos();
    expect(second).toEqual(Array.from({ length: 50 }, (_, index) => String(55 - index)));
    await detail.getByRole('link', { name: '이전 실행 50개' }).click();
    const third = await attemptNos();
    expect(third).toEqual(['5', '4', '3', '2', '1']);
    await expect(detail.getByRole('link', { name: '이전 실행 50개' })).toHaveCount(0);

    await page.goBack();
    expect(await attemptNos()).toEqual(second);
    await page.goBack();
    expect(await attemptNos()).toEqual(Array.from({ length: 50 }, (_, index) => String(105 - index)));
    await page.goForward();
    expect(await attemptNos()).toEqual(second);
    await page.reload();
    expect(await attemptNos()).toEqual(second);
  });

  test('result anchor downloads fixture bytes; non-admin sessions cannot read the queue or result', async ({ page, browser }, testInfo: TestInfo) => {
    const resultRun = await queueFixture(page.request, 'result-blob', `pw-download-${randomUUID()}`);
    const resultId = resultRun.jobIds[0];
    await waitForStatus(page.request, resultId, 'SUCCEEDED');
    const failedRun = await queueFixture(page.request, 'manual-retry-permanent', `pw-deny-command-${randomUUID()}`);
    const failedId = failedRun.jobIds[0];
    await waitForFailedAttemptCount(page.request, failedId, 1);

    const selected = new URL(pagePath());
    selected.searchParams.set('selected', resultId);
    await page.goto(selected.toString());
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('link', { name: `${resultRun.runId}.bin` }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe(`${resultRun.runId}.bin`);
    const savedFile = testInfo.outputPath(`${resultRun.runId}.bin`);
    await download.saveAs(savedFile);
    expect(await readFile(savedFile)).toEqual(Buffer.from(`queue-result-${resultRun.runId}`));

    for (const role of ['member', 'org-admin', 'pre2fa-admin']) {
      const context = await emptyContext(browser, true);
      try {
        await freshSession(context, role);
        const deniedPage = await context.newPage();
        const pageResponse = await deniedPage.goto(pagePath());
        expect(pageResponse?.status(), `${role} page`).toBe(403);
        await expect(deniedPage.locator('tr[data-job-id]')).toHaveCount(0);
        expect((await context.request.get(apiPath('jobs'))).status()).toBe(403);
        expect((await context.request.get(apiPath(`jobs/${resultId}`))).status()).toBe(403);
        expect((await context.request.get(apiPath(`jobs/${resultId}/result`))).status()).toBe(403);
        expect((await context.request.get(apiPath('events'), { timeout: 5_000 })).status()).toBe(403);
        const command = await context.request.post(apiPath(`jobs/${failedId}/retry`), {
          headers: { 'X-XSRF-TOKEN': 'not-a-csrf-token' },
          data: { commandId: randomUUID() },
        });
        expect(command.status(), `${role} command`).toBe(403);
      } finally {
        await context.close();
      }
    }

    const anonymous = await emptyContext(browser, true);
    try {
      const anonymousPage = await anonymous.newPage();
      expect((await anonymousPage.goto(pagePath()))?.status()).toBe(401);
      expect((await anonymous.request.get(apiPath('jobs'))).status()).toBe(401);
      expect((await anonymous.request.get(apiPath('events'), { timeout: 5_000 })).status()).toBe(401);
    } finally {
      await anonymous.close();
    }
  });
});
