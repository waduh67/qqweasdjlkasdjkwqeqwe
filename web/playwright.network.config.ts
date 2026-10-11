import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: ['network-workflows.spec.ts', 'helpdesk-workflows.spec.ts'], fullyParallel: true, workers: 2, retries: 0,
  reporter: [['line'], ['json', { outputFile: '../.omo/runtime/network-ui/browser-results.json' }]],
  outputDir: '../.omo/runtime/network-ui/browser-artifacts',
  use: { baseURL: 'http://127.0.0.1:4191', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }, { name: 'firefox', use: { ...devices['Desktop Firefox'] } }],
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 4191', url: 'http://127.0.0.1:4191', reuseExistingServer: false },
})
