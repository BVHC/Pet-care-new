import { defineConfig } from '@playwright/test';

// Uses the locally installed Chrome (channel) — no browser download needed.
export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 8_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    channel: 'chrome',
    viewport: { width: 1440, height: 900 },
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    // These specs drive the contract mock. A local `.env.development.local` with VITE_API_MOCK=false would point the app at a real
    // BE (which is usually down), so pin mock mode here. A dev server already running on 5173 is reused as is: start it in mock mode.
    env: { VITE_API_MOCK: 'true', VITE_API_MOCK_ONLY: '' },
    reuseExistingServer: true,
    timeout: 90_000,
  },
});
