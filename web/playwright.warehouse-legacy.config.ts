import { defineConfig } from '@playwright/test'
import warehouse from './playwright.warehouse.config'
import { legacyPhase, legacyRunDirectory } from './e2e/warehouse-legacy/fixture'

export default defineConfig({
  ...warehouse,
  // V172 mounts its WebGL map immediately. On Linux without a GPU, Firefox
  // needs a display (for example Xvfb with Mesa) to render that unchanged UI.
  use: { ...warehouse.use, headless: legacyPhase !== 'before' || process.env.WAREHOUSE_LEGACY_HEADED !== 'true' },
  testDir: './e2e/warehouse-legacy',
  testMatch: legacyPhase === 'before' ? 'before.spec.ts' : ['transition', 'reference-restart'].includes(legacyPhase ?? '') ? 'workflow.spec.ts' : 'cutover.spec.ts',
  outputDir: `${legacyRunDirectory}/${legacyPhase}-artifacts`,
  reporter: [['line'], ['json', { outputFile: `${legacyRunDirectory}/${legacyPhase}-report.json` }]],
})
