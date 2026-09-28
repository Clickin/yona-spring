import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env.YONA_BASE_URL;
if (!baseURL) throw new Error('YONA_BASE_URL must point to the active private queue fixture');

export default defineConfig({
  testDir: __dirname,
  testMatch: 'queue-admin.spec.ts',
  outputDir: process.env.YONA_QUEUE_BROWSER_OUTPUT ?? path.join(__dirname, 'test-results'),
  timeout: 90_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
      args: ['--disable-gpu'],
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
