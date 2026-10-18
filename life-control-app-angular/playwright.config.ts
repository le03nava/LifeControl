import { defineConfig, devices } from '@playwright/test';

const CI = process.env.CI === 'true';
const E2E_PORT = process.env.E2E_PORT || '4200';
const BASE_URL = `http://localhost:${E2E_PORT}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: CI,
  retries: CI ? 2 : 0,
  workers: CI ? 2 : undefined,
  reporter: [['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `npm start -- --port ${E2E_PORT}`,
    // `@angular/cli` 22 gives the `PORT` environment variable priority over `ng serve
    // --port`. With an ambient `PORT` the server would bind somewhere other than the URL
    // this config waits on and the run would hang until the timeout, so both are pinned to
    // E2E_PORT: the server the specs hit is the one this config started.
    env: { ...(process.env as Record<string, string>), PORT: E2E_PORT },
    url: BASE_URL,
    reuseExistingServer: !CI,
    timeout: 180_000,
  },
});
