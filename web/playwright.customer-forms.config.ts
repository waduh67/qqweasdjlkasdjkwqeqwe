import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: ['customer-forms.spec.ts', 'resource-layers.spec.ts', 'customer-migration.spec.ts'], workers: 2, retries: 0, reporter: 'line',
  use: { baseURL: 'http://127.0.0.1:4192', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }, { name: 'firefox', use: devices['Desktop Firefox'] }],
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 4192', url: 'http://127.0.0.1:4192', reuseExistingServer: false },
})
