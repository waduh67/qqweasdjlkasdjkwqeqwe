import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { migrationHash } from '@/test/warehouseMigrationFixture'
import { provenanceCaseFixture, provenanceEvidenceFixture, provenanceFinalizationFixture, provenanceFinalizationReviewFixture,
  provenanceOpeningSummaryFixture, provenanceSummaryFixture } from '@/test/warehouseProvenanceFixture'
import { migrationCase, migrationEvidence, migrationFinalization, migrationFinalizationReview, migrationSummary } from './provenanceModels'
import { beginMigration, downloadMigrationEvidence, finalizeMigration, getMigrationCase, listMigrationOpenings, uploadMigrationEvidence } from './provenance'
import { WarehouseDataError } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
it('keeps explicit zero counts and rejects an incomplete source inventory or another batch', () => {
  const empty = provenanceSummaryFixture('VALIDATING', true)
  expect(migrationSummary(empty).sourceCount).toBe(0)
  const missing = { ...empty.sourceCounts }; delete missing.onu
  expect(() => migrationSummary({ ...empty, sourceCounts: missing })).toThrow(WarehouseDataError)
  expect(() => migrationSummary({ ...empty, sourceCount: 1 })).toThrow(WarehouseDataError)
  expect(() => migrationSummary({ ...empty, batch: { ...empty.batch, sourceHash: 'b'.repeat(64) } })).toThrow(WarehouseDataError)
  expect(() => migrationSummary({ ...empty, cutover: { ...empty.cutover, migrationBatchId: id.target } })).toThrow(WarehouseDataError)
})
it('preserves blank raw serial invalid MAC and unknown quantity units without manufacturing a valid claim', () => {
  const row = provenanceCaseFixture()
  const view = migrationCase({ ...row, sourceSnapshot: { ...row.sourceSnapshot, serialNumber: '', macAddress: 'bad MAC', legacyQuantity: '82.5', baseUnit: null },
    claims: [{ id: null, identityType: 'MAC', rawValue: 'bad MAC', canonicalValue: null, state: null, admittedAssetId: null, candidateCount: 0 }] })
  expect(view.source).toMatchObject({ serial: '', mac: 'bad MAC', legacyQuantity: '82.5', baseUnit: null })
  expect(view.claims[0].state).toBeNull()
  expect(() => migrationCase({ ...row, sourceSnapshot: { ...row.sourceSnapshot, id: id.other } })).toThrow(WarehouseDataError)
})
it('binds a frozen begin command and completed finalization to the reviewed hash and epoch', async () => {
  const fetch = vi.fn(async (_path: string, _init?: RequestInit) => json(provenanceSummaryFixture())); vi.stubGlobal('fetch', fetch)
  const cmd = beginMigration(migrationSummary(provenanceSummaryFixture('LEGACY')))
  await cmd.execute(); await cmd.execute()
  expect(fetch.mock.calls[0][1]?.body).toBe(fetch.mock.calls[1][1]?.body)
  expect(new Headers(fetch.mock.calls[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(fetch.mock.calls[1][1]?.headers).get('Idempotency-Key'))
  expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({ expectedEpoch: 0, expectedPreservationHash: migrationHash })
  fetch.mockImplementation(async () => json(provenanceFinalizationFixture()))
  const final = finalizeMigration(id.other, { expectedEpoch: 1, openingDocumentId: id.document, expectedReviewHash: migrationHash, reason: 'Pemeriksaan selesai' })
  expect((await final.execute()).cutover.epoch).toBe(2)
  fetch.mockImplementation(async () => json({ ...provenanceFinalizationFixture(), openingDocumentId: id.target }))
  await expect(final.execute()).rejects.toThrow(WarehouseDataError)
})
it('reads a final zero baseline without changing it into an unapproved opening', () => {
  const completed = provenanceFinalizationFixture(true)
  expect(migrationFinalization(completed).baselineTotals).toEqual({})
  expect(migrationFinalizationReview({ ...completed, finalization: completed, issues: ['CUTOVER_NOT_VALIDATING'] }).finalization?.id).toBe(id.effect)
  expect(migrationFinalizationReview(provenanceFinalizationReviewFixture(false)).issues).toEqual(['APPROVED_OPENING_REQUIRED'])
  expect(() => migrationFinalization({ ...completed, openingDocumentId: null })).toThrow(WarehouseDataError)
  expect(() => migrationFinalization({ ...completed, baselineTotals: { MM: '0' } })).toThrow(WarehouseDataError)
})
it('recovers saved openings through bounded pages and rejects cross-batch or wrong-case responses', async () => {
  const fetch = vi.fn(async (_path: string) => json({ items: [provenanceOpeningSummaryFixture()], page: 1, size: 25, totalElements: 26 }))
  vi.stubGlobal('fetch', fetch)
  expect((await listMigrationOpenings(id.other, 1)).items[0].code).toBe('OPEN-LEGACY')
  expect(fetch.mock.calls[0][0]).toBe('/api/v1/warehouse/provenance/batches/' + id.other + '/opening?page=1&size=25')
  fetch.mockImplementation(async () => json({ items: [{ ...provenanceOpeningSummaryFixture(), batchId: id.target }], page: 0, size: 25, totalElements: 1 }))
  await expect(listMigrationOpenings(id.other)).rejects.toThrow(WarehouseDataError)
  fetch.mockImplementation(async () => json(provenanceCaseFixture()))
  await expect(getMigrationCase(id.target)).rejects.toThrow(WarehouseDataError)
})
it('retries the identical evidence bytes and verifies private downloads against the recorded checksum', async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>('node:crypto')
  vi.stubGlobal('crypto', webcrypto)
  const source = migrationCase(provenanceCaseFixture()), bytes = new TextEncoder().encode('%PDF-original-proof!')
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), n => n.toString(16).padStart(2, '0')).join('')
  const evidence = migrationEvidence({ ...provenanceEvidenceFixture(), sizeBytes: bytes.length, sha256 })
  const fetch = vi.fn(async (_path: string, _init?: RequestInit) => json(evidence)); vi.stubGlobal('fetch', fetch)
  const upload = uploadMigrationEvidence(id.other, source, 1, evidence.label, new File([bytes], 'preuve.pdf', { type: 'application/pdf' }))
  await upload.execute(); await upload.execute()
  const forms = fetch.mock.calls.map(([, init]) => init?.body as FormData)
  expect(forms[0].get('request')).toBe(forms[1].get('request'))
  expect(JSON.parse(String(forms[0].get('request')))).toEqual({ expectedEpoch: 1, expectedCaseHash: migrationHash, label: evidence.label })
  expect(await (forms[0].get('file') as Blob).arrayBuffer()).toEqual(await (forms[1].get('file') as Blob).arrayBuffer())
  expect(new Headers(fetch.mock.calls[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(fetch.mock.calls[1][1]?.headers).get('Idempotency-Key'))
  fetch.mockImplementation(async () => new Response(bytes, { headers: { 'Content-Type': 'application/pdf' } }))
  expect((await downloadMigrationEvidence(id.other, source, evidence)).size).toBe(bytes.length)
  await expect(downloadMigrationEvidence(id.other, source, { ...evidence, sha256: '0'.repeat(64) })).rejects.toThrow(WarehouseDataError)
})
