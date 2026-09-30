import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { materialIds as id, materialPlanFixture, materialSku } from '@/test/warehouseMaterialFixture'
import { settlementFixture, usageViewFixture } from '@/test/warehouseExecutionFixture'
import { appendMaterialRework, getMaterialApprovalReview, materialObligationView } from './materialReview'
import { WarehouseDataError } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('binds obligation names and units to the returned issue line', () => {
  const value = { id: id.line, issueCode: 'ISS-MATERIAL', sku: materialSku, serial: null, lotCode: 'REEL-1', obligation: settlementFixture().obligations.lines[0] }
  expect(materialObligationView(value).sku.name).toBe('Kabel drop')
  expect(() => materialObligationView({ ...value, id: id.demandLine })).toThrow(WarehouseDataError)
  expect(() => materialObligationView({ ...value, sku: { ...materialSku, baseUnit: 'EA' } })).toThrow(WarehouseDataError)
})
it('reads the frozen approval version and rejects usage from another WO', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ review: { id: id.document, workOrderRevision: 11, usage: usageViewFixture() } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ review: { id: id.document, workOrderRevision: 11, usage: { ...usageViewFixture(), workOrderId: id.supplier } } })))
  vi.stubGlobal('fetch', fetch)
  expect((await getMaterialApprovalReview(id.source))?.usage?.useRevision).toBe(1)
  await expect(getMaterialApprovalReview(id.source)).rejects.toThrow(WarehouseDataError)
})
it('requires an actual named serialized source when a frozen review has no measured usage', async () => {
  const deployment = { authorizationId: id.evidence, workOrderId: id.source, assignmentId: id.line, assetId: id.piece, useRevision: 1,
    sku: { ...materialSku, name: 'ONU', tracking: 'SERIAL', baseUnit: 'EA' }, serial: 'ONU-REVIEW', actor: null, recordedAt: materialPlanFixture.recordedAt }
  const result = (deployments: unknown[]) => new Response(JSON.stringify({ review: { id: id.document, workOrderRevision: 11, usage: null, deployments } }))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(result([deployment])).mockResolvedValueOnce(result([]))
    .mockResolvedValueOnce(result([{ ...deployment, workOrderId: id.supplier }])).mockResolvedValueOnce(result([deployment, deployment])))
  const review = await getMaterialApprovalReview(id.source)
  expect(review?.usage).toBeNull(); expect(review?.deployments[0].serial).toBe('ONU-REVIEW')
  for (let index = 0; index < 3; index++) await expect(getMaterialApprovalReview(id.source)).rejects.toThrow(WarehouseDataError)
})
it('captures old plan, usage and evidence tokens through rework retry', async () => {
  const input = { expectedRevision: 1, workOrderRevision: 8, previousPlanId: id.plan, previousUsageId: id.evidence, expectedUsageRevision: 1, previousEvidenceRevision: 'old-proof', evidenceRevision: 'new-proof', reason: 'Tarikan tambahan', deltas: [{ skuId: id.sku, quantityBase: '10000', baseUnit: 'MM' as const, continuousCut: true }] }
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(new Response(JSON.stringify({ reworkId: id.supplier, previousPlan: materialPlanFixture, plan: { ...materialPlanFixture, id: id.supplier, planRevision: 2 } })))
  vi.stubGlobal('fetch', fetch); const operation = appendMaterialRework(id.source, input); input.evidenceRevision = 'changed'
  await expect(operation.execute()).rejects.toThrow('response lost'); await operation.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(operation.body)).toMatchObject({ expectedRevision: 1, expectedUsageRevision: 1, previousEvidenceRevision: 'old-proof', evidenceRevision: 'new-proof', deltas: [{ quantityBase: '10000' }] })
  expect((fetch.mock.calls[0][1].headers as Headers).get('Idempotency-Key')).toBe((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key'))
})
