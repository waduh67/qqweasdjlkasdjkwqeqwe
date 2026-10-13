import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: 'map-site-picker.spec.ts', workers: 2, retries: 0, timeout: 45000,
  use: { baseURL: 'http://127.0.0.1:4192', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  outputDir: '../.omo/runtime/map-sites/browser', reporter: [['line'], ['json', { outputFile: '../.omo/runtime/map-sites/browser-results.json' }]],
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], launchOptions: { args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] } } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'], launchOptions: { headless: false, firefoxUserPrefs: { 'webgl.force-enabled': true, 'webgl.disabled': false } } } },
  ],
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 4192', url: 'http://127.0.0.1:4192', reuseExistingServer: false },
})
