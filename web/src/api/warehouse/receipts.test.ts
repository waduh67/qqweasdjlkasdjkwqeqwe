import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { receiptFixture, receiptIds } from '@/test/warehouseReceiptFixture'
import { attachReceipt, receipt } from './receipts'
import { WarehouseDataError } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects numeric receipt quantities, missing revisions and string evidence visibility without claiming empty success', () => {
  const valid = receiptFixture()
  expect(receipt(valid)).toEqual(valid)
  expect(() => receipt({ ...valid, revision: undefined })).toThrow(WarehouseDataError)
  expect(() => receipt({ ...valid, costVisible: 'true' })).toThrow(WarehouseDataError)
  expect(() => receipt({ ...valid, lines: [{ ...valid.lines[0], quantityBase: 1000000 }] })).toThrow(WarehouseDataError)
  expect(() => receipt({ ...valid, lines: [{ ...valid.lines[0], tracking: 'SERIAL', serial: 'ONU1' }] })).toThrow(WarehouseDataError)
})

it('retries an ambiguous multipart upload with identical captured bytes revision and key and coalesces clicks', async () => {
  const file = new File(['original-proof-bytes'], 'proof.pdf', { type: 'application/pdf' })
  const operation = attachReceipt(receiptIds.document, 7, file)
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce(new Response(JSON.stringify({ id: receiptIds.evidence, documentId: receiptIds.document, contentType: 'application/pdf', sizeBytes: file.size, sha256: 'a'.repeat(64) }), { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const first = operation.execute()
  expect(operation.execute()).toBe(first)
  await expect(first).rejects.toThrow('lost response')
  await expect(operation.execute()).resolves.toMatchObject({ id: receiptIds.evidence })
  expect(fetch).toHaveBeenCalledTimes(2)
  for (const [, init] of fetch.mock.calls) {
    const data = init.body as FormData, bytes = data.get('file') as File
    expect(data.get('expectedRevision')).toBe('7')
    expect(bytes.name).toBe('proof.pdf')
    expect(await new Promise(resolve => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.readAsText(bytes) })).toBe('original-proof-bytes')
    expect((init.headers as Headers).get('Idempotency-Key')).toBe(operation.key)
    expect((init.headers as Headers).has('Content-Type')).toBe(false)
  }
})
