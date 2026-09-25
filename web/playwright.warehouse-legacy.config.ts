import { defineConfig } from '@playwright/test'
import warehouse from './playwright.warehouse.config'
import { legacyPhase, legacyRunDirectory } from './e2e/warehouse-legacy/fixture'

export default defineConfig({
  ...warehouse,
  testDir: './e2e/warehouse-legacy',
  testMatch: legacyPhase === 'before' ? 'before.spec.ts' : 'cutover.spec.ts',
  outputDir: `${legacyRunDirectory}/${legacyPhase}-artifacts`,
  reporter: [['line'], ['json', { outputFile: `${legacyRunDirectory}/${legacyPhase}-report.json` }]],
})
