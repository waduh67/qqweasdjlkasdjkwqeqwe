import { expect, it } from 'vitest'
import { custodyFixture, fieldContextFixture } from '@/test/warehouseExecutionFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { eligibleUseSource, materialUseInput } from './materialUseDraft'

it('measures exact metres using actual receipt IDs and refuses excess, duplicate and serialized use', () => {
  const context = fieldContextFixture(), source = custodyFixture(), row = { key: 'a', source, quantity: '82,500' }
  expect(materialUseInput(context, [row], 'Pengukuran', '')).toMatchObject({ expectedRevision: 0, planRevision: 1, workOrderRevision: 5, lines: [{ receiptId: id.document, issueLineId: id.line, stockIdentityId: id.piece, quantityBase: '82500' }] })
  expect(() => materialUseInput(context, [{ ...row, quantity: '100,001' }], 'Bukti', '')).toThrow('melebihi')
  expect(() => materialUseInput(context, [row, { ...row, key: 'b' }], 'Bukti', '')).toThrow('sekali')
  expect(eligibleUseSource(context, { ...source, sku: { ...source.sku, tracking: 'SERIAL' } })).toBe(false)
  expect(() => materialUseInput(context, [row], '', '')).toThrow('bukti')
})
it('requires the latest immutable usage source for positive correction, or actual rework context', () => {
  const context = { ...fieldContextFixture(), useRevision: 4, latestUsageId: id.evidence }, source = { ...custodyFixture(), initialUseSource: false, sourceUsageId: id.evidence, quantityBase: '17500' }
  expect(materialUseInput(context, [{ key: 'a', source, quantity: '7,500' }], 'Pengukuran tambahan', 'Tambahan penarikan')).toMatchObject({ expectedRevision: 4, previousUsageId: id.evidence, quantityBase: '7500' })
  expect(eligibleUseSource(context, { ...source, sourceUsageId: id.supplier })).toBe(false)
  expect(() => materialUseInput(context, [{ key: 'a', source, quantity: '0' }], 'Bukti', 'Koreksi')).toThrow()
  expect(materialUseInput({ ...context, reworkId: id.plan, evidenceRevision: 'proof-revision-2' }, [{ key: 'a', source: { ...source, sourceUsageId: null }, quantity: '5' }], 'Bukti', 'Pengerjaan ulang')).toMatchObject({ reworkId: id.plan, evidenceRevision: 'proof-revision-2', quantityBase: '5000' })
})
it('reports first measured sources after deployment using the current physical revision', () => {
  const context = { ...fieldContextFixture(), useRevision: 2 }, source = custodyFixture()
  const input = materialUseInput(context, [{ key: 'a', source, quantity: '82,500' },
    { key: 'b', source: { ...source, id: id.supplier, issueLineId: id.evidence }, quantity: '1' }], 'Pengukuran setelah pemasangan', '')
  expect(input).toMatchObject({ expectedRevision: 2, materialMode: 'MATERIAL_REQUIRED', lines: [{ quantityBase: '82500' }, { quantityBase: '1000' }] })
  expect(input).not.toHaveProperty('previousUsageId')
})
it('records a declared no-material plan once with an explicit reason and no invented allocations', () => {
  const context = fieldContextFixture(); context.plan = { ...context.plan!, materialMode: 'NONE', reason: 'Pemeriksaan saja', lines: [] }
  expect(materialUseInput(context, [], 'Foto pemeriksaan', 'Tidak ada barang dipakai')).toMatchObject({ expectedRevision: 0, materialMode: 'NONE', lines: [], reason: 'Tidak ada barang dipakai' })
  expect(() => materialUseInput(context, [], 'Bukti', '')).toThrow('alasan')
  expect(() => materialUseInput({ ...context, useRevision: 1 }, [], 'Bukti', 'Tidak pakai')).toThrow('sekali')
})
