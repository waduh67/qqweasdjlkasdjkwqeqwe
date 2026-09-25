import { afterEach, expect, it, vi } from 'vitest'
import { approvalIds as id, approvalDetailsFixture, approvalPostedFixture } from '@/test/warehouseApprovalFixture'
import { migrationCaseFixture, migrationDocumentFixture } from '@/test/warehouseMigrationFixture'
import { tokenStore } from '@/api/client'
import { approvalDetails, approvalDocument, approvalAttachment } from './approvalReads'
import { approvalMigrationEvidence, migrationReviewCase } from './migrationReview'
import { WarehouseDataError } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('accepts explicit zero baseline without allowing empty ordinary documents or fabricated opening valuation', () => {
  const document = migrationDocumentFixture(true), prior = approvalDetailsFixture()
  expect(approvalDocument(document)).toMatchObject({ lines: [], migration: { baselineCount: 0, cutoff: '2026-09-25T01:00:00+00:00' } })
  expect(() => approvalDocument({ ...document, kind: 'RECEIPT' })).toThrow(WarehouseDataError)
  expect(() => approvalDocument({ ...document, migration: undefined })).toThrow(WarehouseDataError)
  expect(() => approvalDocument({ ...document, migration: { ...document.migration, baselineCount: 1 } })).toThrow(WarehouseDataError)
  const details = { ...prior, document, approval: { ...prior.approval, sourceRevision: 0 }, currentSourceRevision: 0, actions: { ...prior.actions, reworkSourceRevision: 0 } }
  expect(approvalDetails(details).cost).toBeNull()
  expect(() => approvalDetails({ ...details, cost: { numerator: '0', denominator: '1', currency: 'IDR' } })).toThrow(WarehouseDataError)
  const posted = approvalPostedFixture()
  expect(approvalDetails({ ...posted, document, approval: { ...posted.approval, sourceRevision: 0 }, currentSourceRevision: 1, currentSourceState: 'POSTED',
    actions: { ...posted.actions, reworkSourceRevision: 1 }, effect: { ...posted.effect, businessAction: 'OPENING_BALANCE' } }).effect?.businessAction).toBe('OPENING_BALANCE')
})
it('preserves unknown malformed legacy identity and binds each resolution to its exact case and source', () => {
  const row = migrationCaseFixture()
  expect(migrationReviewCase(row).resolution?.stock?.quantityBase).toBe('82500')
  expect(migrationReviewCase({ ...row, sourceSnapshot: { ...row.sourceSnapshot, serialNumber: '', macAddress: 'bad MAC' }, resolution: null }).source.serial).toBe('')
  expect(() => migrationReviewCase({ ...row, sourceSnapshot: { ...row.sourceSnapshot, id: id.other } })).toThrow(WarehouseDataError)
  expect(() => migrationReviewCase({ ...row, resolution: { ...row.resolution, caseId: id.other } })).toThrow(WarehouseDataError)
  expect(() => migrationReviewCase({ ...row, resolution: { ...row.resolution, stock: { ...row.resolution.stock, sourceUnit: 'EA' } } })).toThrow(WarehouseDataError)
  expect(() => migrationReviewCase({ ...row, resolution: { ...row.resolution, evidence: [] } })).toThrow(WarehouseDataError)
})
it('downloads through the approval scope and rejects bytes which no longer match the original proof', async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>('node:crypto')
  vi.stubGlobal('crypto', webcrypto)
  const bytes = new TextEncoder().encode('%PDF-original-evidence'), hash = Array.from(new Uint8Array(await webcrypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('')
  const fetch = vi.fn(async (_path: string) => new Response(bytes, { headers: { 'Content-Type': 'application/pdf' } })); vi.stubGlobal('fetch', fetch)
  const reference = { id: id.decision, sha256: hash }
  expect((await approvalMigrationEvidence(id.request, reference)).size).toBe(bytes.length)
  expect(fetch.mock.calls[0][0]).toBe('/api/v1/warehouse/approvals/' + id.request + '/attachments/' + id.decision)
  await expect(approvalMigrationEvidence(id.request, { ...reference, sha256: '0'.repeat(64) })).rejects.toThrow(WarehouseDataError)
  expect(approvalAttachment({ ...reference, kind: 'MIGRATION_EVIDENCE', label: 'Berita acara lama', caseId: id.line,
    recordedAt: '2026-09-25T01:00:00Z', contentType: 'application/pdf', sizeBytes: bytes.length }).label).toBe('Berita acara lama')
})
