import { defineConfig, devices } from '@playwright/test'

const browser = process.env.WAREHOUSE_E2E_BROWSER ?? 'chromium'
if (!['chromium', 'firefox'].includes(browser)) throw new Error('WAREHOUSE_E2E_BROWSER must be chromium or firefox')
const desktop = devices[browser === 'firefox' ? 'Desktop Firefox' : 'Desktop Chrome']

const baseURL = process.env.WAREHOUSE_E2E_WEB_URL
if (baseURL !== 'http://127.0.0.1:14188' || process.env.WAREHOUSE_E2E_BACKEND_URL !== 'http://127.0.0.1:17880' || !process.env.WAREHOUSE_ENVIRONMENT_MARKER) {
  throw new Error('Run warehouse browser tests through scripts/warehouse/qa.sh against its owned environment.')
}

export default defineConfig({
  testDir: './e2e/warehouse',
  outputDir: '../.omo/runtime/warehouse-playwright-artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['line'], ['json', { outputFile: process.env.PLAYWRIGHT_JSON_OUTPUT_NAME ?? '../.omo/runtime/warehouse-playwright.json' }]],
  use: { baseURL, actionTimeout: 20_000, navigationTimeout: 30_000, serviceWorkers: 'block', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'warehouse-desktop', use: { ...desktop, viewport: { width: 1280, height: 900 } } },
    { name: 'warehouse-mobile', use: { ...(browser === 'firefox' ? desktop : devices['Pixel 7']), viewport: { width: 375, height: 812 }, isMobile: browser !== 'firefox', hasTouch: true } },
  ],
})
