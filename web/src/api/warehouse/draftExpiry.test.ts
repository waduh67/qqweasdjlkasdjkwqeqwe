import { expect, it } from 'vitest'
import { receiptFixture } from '@/test/warehouseReceiptFixture'
import { transferFixture } from '@/test/warehouseTransferFixture'
import { countFixture } from '@/test/warehouseCountFixture'
import { materialPlanFixture, materialSummaryFixture } from '@/test/warehouseMaterialFixture'
import { provenanceOpeningFixture, provenanceOpeningSummaryFixture } from '@/test/warehouseProvenanceFixture'
import { migrationOpening, migrationOpeningSummary } from './provenanceModels'
import { receipt } from './receipts'
import { transferView } from './transfers'
import { countView } from './counts'
import { materialHistory, materialSummary } from './materialModels'
import { WarehouseDataError } from './codec'

const draftExpiry = { deadline: '2026-09-26T12:00:00Z', recordedAt: null, reason: 'IDLE_DEADLINE' }

it.each([[receipt, receiptFixture()], [transferView, transferFixture()], [countView, countFixture()]] as const)(
  'decodes terminal drafts without replacing the stored quantities or revision', (decode, source) => {
    const expired = decode({ ...source, state: 'EXPIRED', draftExpiry })
    expect(expired).toMatchObject({ id: source.id, revision: source.revision, state: 'EXPIRED', draftExpiry })
    expect(decode(source)).toEqual(source)
    expect(() => decode({ ...source, state: 'EXPIRED' })).toThrow(WarehouseDataError)
    expect(() => decode({ ...source, state: 'EXPIRED', draftExpiry: { ...draftExpiry, deadline: 'invalid' } })).toThrow(WarehouseDataError)
  })

it('does not turn expired transfer requests into dispatched quantities', () => {
  const source = transferFixture()
  expect(() => transferView({ ...source, state: 'EXPIRED', draftExpiry,
    lines: [{ ...source.lines[0], receivedBase: source.lines[0].quantityBase }] })).toThrow(WarehouseDataError)
})

it('keeps expired plan identity and revision in summary and historical reads', () => {
  const current = materialSummary({ ...materialSummaryFixture, planState: 'EXPIRED', demandState: 'DRAFT',
    demandDocumentId: null, demandRevision: null, lines: [], draftExpiry })
  expect(current).toMatchObject({ plan: { id: materialPlanFixture.id, planRevision: 1 }, planState: 'EXPIRED', draftExpiry })
  expect(materialHistory({ plan: materialPlanFixture, state: 'EXPIRED', demandDocumentId: null, draftExpiry }))
    .toMatchObject({ state: 'EXPIRED', plan: { id: materialPlanFixture.id, planRevision: 1 }, draftExpiry })
})

it('keeps the sealed opening manifest readable while exposing its current terminal state', () => {
  const original = provenanceOpeningFixture()
  expect(migrationOpening({ ...original, state: 'EXPIRED', draftExpiry })).toMatchObject({
    id: original.id, manifest: migrationOpening(original).manifest, state: 'EXPIRED', draftExpiry,
  })
  expect(migrationOpening(original)).not.toHaveProperty('draftExpiry')
  expect(migrationOpeningSummary({ ...provenanceOpeningSummaryFixture(), state: 'EXPIRED', draftExpiry })).toMatchObject({ state: 'EXPIRED', draftExpiry })
})
