import { defineConfig, devices } from '@playwright/test';
import { E2E_BASE_URL } from './e2e/support/config';

const isCI = Boolean(process.env.CI);

export default defineConfig({
  testDir: './e2e',
  // The suite shares one database and a single SMTP mailbox; run deterministically.
  fullyParallel: false,
  workers: 1,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: E2E_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      // Captures password-reset mail; never talks to a real provider.
      command: 'node e2e/smtp-sink.mjs',
      url: 'http://127.0.0.1:1026/health',
      reuseExistingServer: !isCI,
      timeout: 30_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      // Starts an isolated Postgres (5435) + backend (8081) with mail pointed
      // at the sink above and rate limits disabled.
      command: 'node e2e/start-backend.mjs',
      url: 'http://127.0.0.1:8081/actuator/health',
      reuseExistingServer: !isCI,
      timeout: 600_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      // Vite on 5174 proxies /api to the isolated backend on 8081.
      command: 'node e2e/start-frontend.mjs',
      url: E2E_BASE_URL,
      reuseExistingServer: !isCI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
