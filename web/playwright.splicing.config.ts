import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: 'splicing.spec.ts', workers: 2, retries: 0, timeout: 45000,
  use: { baseURL: 'http://127.0.0.1:4193', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  outputDir: '../.omo/runtime/splicing-single-view/browser', reporter: [['line'], ['json', { outputFile: '../.omo/runtime/splicing-single-view/browser-results.json' }]],
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 4193', url: 'http://127.0.0.1:4193', reuseExistingServer: false },
})
