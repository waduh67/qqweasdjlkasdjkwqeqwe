import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { handoverGrantFixture } from '@/test/materialHandoverFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { dispatchMaterialHandover, getMaterialHandoverGrant, handoverGrant } from './materialHandover'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects a non-independent grant, changed target binding and another work orders response', async () => {
  const grant = handoverGrantFixture()
  expect(() => handoverGrant({ ...grant, receiver: grant.sender })).toThrow()
  expect(() => handoverGrant({ ...grant, location: { ...grant.location, id: id.document } })).toThrow()
  expect(() => handoverGrant({ ...grant, request: { ...grant.request, expectedReceiverId: id.inspection } })).toThrow()
  const legacy = { ...grant, request: { ...grant.request, expectedSenderId: undefined, expectedReceiverId: undefined } }
  expect(handoverGrant(legacy).receiver.id).toBe(id.demandLine)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...grant, workOrderId: id.supplier }))))
  await expect(getMaterialHandoverGrant(id.source, id.plan)).rejects.toThrow()
})
it('captures the exact authorized request for same-key dispatch retry', async () => {
  const grant = handoverGrantFixture()
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce(new Response(JSON.stringify({ id: id.document, workOrderId: id.source, purpose: 'HANDOVER' })))
  vi.stubGlobal('fetch', fetch); const captured = dispatchMaterialHandover(grant); grant.request.quantityBase = '17500'
  await expect(captured.execute()).rejects.toThrow(); await captured.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toMatchObject({ authorizationId: id.plan, quantityBase: '7500', usageId: id.evidence, stockIdentityId: id.piece, workOrderRevision: 9, expectedSenderId: id.inspection, expectedReceiverId: id.demandLine })
  expect(fetch.mock.calls[0][1].headers.get('Idempotency-Key')).toBe(fetch.mock.calls[1][1].headers.get('Idempotency-Key'))
})
