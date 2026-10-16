import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: ['network-resource-details.spec.ts', 'splicing.spec.ts'], workers: 2, retries: 0, reporter: 'line',
  use: { baseURL: 'http://127.0.0.1:4193', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }, { name: 'firefox', use: devices['Desktop Firefox'] }],
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 4193', url: 'http://127.0.0.1:4193', reuseExistingServer: false },
})
