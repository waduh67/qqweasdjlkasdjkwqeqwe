import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { custodyFixture, fieldContextFixture } from '@/test/warehouseExecutionFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { WarehouseDataError } from './codec'
import { getMaterialCustody, getMaterialUsage, materialCustody, reportMaterialUse } from './materialExecution'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects mismatched custody units and serialized quantities and strips raw posting metadata', () => {
  const value = custodyFixture()
  expect(materialCustody({ ...value, cost: { totalMinor: '99' }, source: { actor: id.supplier } })).toEqual(value)
  expect(() => materialCustody({ ...value, baseUnit: 'EA' })).toThrow(WarehouseDataError)
  expect(() => materialCustody({ ...value, quantityBase: '0' })).toThrow(WarehouseDataError)
  expect(() => materialCustody({ ...value, quantityBase: '2', sku: { ...value.sku, tracking: 'SERIAL', baseUnit: 'EA' }, baseUnit: 'EA', serial: 'ONU-1' })).toThrow(WarehouseDataError)
})
it('preserves exact measured source and revisions through response loss', async () => {
  const context = fieldContextFixture(), source = custodyFixture()
  const input = { expectedRevision: context.useRevision, workOrderRevision: context.workOrderRevision, planRevision: context.plan!.planRevision, materialMode: 'MATERIAL_REQUIRED' as const, evidenceReference: 'Pengukuran 82,5 m', lines: [{ receiptId: source.receiptId, issueLineId: source.issueLineId, stockIdentityId: source.id, quantityBase: '82500', baseUnit: source.baseUnit }] }
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(new Response(JSON.stringify({ usageId: id.evidence, workOrderId: id.source, useRevision: 1 }))); vi.stubGlobal('fetch', fetch)
  const command = reportMaterialUse(id.source, input); input.lines[0].quantityBase = '100000'
  await expect(command.execute()).rejects.toThrow('response lost'); await command.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(command.body)).toMatchObject({ expectedRevision: 0, workOrderRevision: 5, lines: [{ quantityBase: '82500' }] })
  expect((fetch.mock.calls[0][1].headers as Headers).get('Idempotency-Key')).toBe((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key'))
})
it('uses bounded WO-specific custody and usage pages without any actor selector', async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ items: [], page: 1, size: 25, totalElements: 0 }))); vi.stubGlobal('fetch', fetch)
  await getMaterialCustody(id.source, 1); await getMaterialUsage(id.source, 2)
  const paths = fetch.mock.calls as unknown as [string][]
  expect(paths.map(([path]) => path)).toEqual([`/api/work-orders/${id.source}/materials/workbench/custody?page=1&size=25`, `/api/work-orders/${id.source}/materials/workbench/usage?page=2&size=10`])
})
