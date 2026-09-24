import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { myContext, myIssue } from '@/test/myMaterialsFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { acknowledgeMyMaterial, getMyMaterialContext, getMyMaterialIssue, myMaterialIssue } from './myMaterials'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects broken quantity conservation and inconsistent serial lines', () => {
  const issue = myIssue(); issue.lines[0].acceptedBase = '1'
  expect(() => myMaterialIssue(issue)).toThrow()
  issue.lines[0].remainingBase = '99999'; expect(myMaterialIssue(issue).lines[0].remainingBase).toBe('99999')
  issue.lines[0].sku.tracking = 'SERIAL'; expect(() => myMaterialIssue(issue)).toThrow()
})
it('binds fresh contexts and issue projections to the requested work order', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ ...myContext(), id: id.supplier, field: null })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...myIssue(), workOrderId: id.supplier })))
  vi.stubGlobal('fetch', fetch)
  await expect(getMyMaterialContext(id.source)).rejects.toThrow()
  await expect(getMyMaterialIssue(id.source, id.issue)).rejects.toThrow()
})
it('retries acknowledged quantities with exactly the captured key and original body', async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce(new Response(JSON.stringify({ receiptId: id.document, revision: 3, state: 'PART_RECEIVED' })))
  vi.stubGlobal('fetch', fetch)
  const input = { issueId: id.issue, expectedRevision: 2, workOrderRevision: 5, evidenceReference: 'Signed', lines: [{ issueLineId: id.line, stockIdentityId: id.piece, baseUnit: 'MM' as const, acceptedBase: '60000', missingBase: '40000', rejectedBase: '0', reason: 'Short delivery' }] }
  const captured = acknowledgeMyMaterial(id.source, input); input.lines[0].acceptedBase = '100000'
  await expect(captured.execute()).rejects.toThrow(); await captured.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(fetch.mock.calls[1][1].body).lines[0].acceptedBase).toBe('60000')
  expect(fetch.mock.calls[0][1].headers.get('Idempotency-Key')).toBe(fetch.mock.calls[1][1].headers.get('Idempotency-Key'))
})
