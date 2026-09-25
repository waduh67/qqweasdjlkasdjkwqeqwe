import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

export const legacyHead = 'abaecd9e'
export const legacyPhase = process.env.WAREHOUSE_LEGACY_PHASE
const run = process.env.WAREHOUSE_LEGACY_RUN_DIR
const schema = process.env.WAREHOUSE_LEGACY_SCHEMA
const database = process.env.WAREHOUSE_E2E_DATABASE
const runtime = resolve(import.meta.dirname, '../../../.omo/runtime')
if (!run || !new RegExp(`^${runtime}/warehouse-legacy-[a-f0-9]{32}$`).test(run) ||
  schema !== 'public' || !database || !/^warehouse_fixture_[a-f0-9]{32}$/.test(database) || !['before', 'after', 'restart'].includes(legacyPhase ?? '')) {
  throw new Error('Run the legacy browser journey through its isolated upgrade harness.')
}
export const legacyRunDirectory = run

export interface LegacyFixture {
  marker: string
  schema: string
  database: string
  head: string
  admin: { name: string; email: string; password: string }
  customer: { id: string; name: string }
  onu: { id: string; serialNumber: string }
  finalized?: { batchId: string; openingDocumentId: string; reviewHash: string; epoch: number }
}

function path(project: string) {
  if (!['warehouse-desktop', 'warehouse-mobile'].includes(project)) throw new Error('Unexpected browser project')
  return resolve(legacyRunDirectory, `${project}-fixture.json`)
}
export function saveLegacyFixture(project: string, fixture: LegacyFixture) {
  writeFileSync(path(project), JSON.stringify(fixture), { mode: 0o600 })
}
export function readLegacyFixture(project: string): LegacyFixture {
  const fixture: LegacyFixture = JSON.parse(readFileSync(path(project), 'utf8'))
  if (fixture.marker !== process.env.WAREHOUSE_ENVIRONMENT_MARKER || fixture.schema !== schema || fixture.database !== database || fixture.head !== legacyHead) {
    throw new Error('Legacy fixture belongs to another environment or application version')
  }
  return fixture
}
