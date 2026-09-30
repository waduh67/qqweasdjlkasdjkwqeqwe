import { afterEach, expect, it, vi } from 'vitest'
import { ApiError, tokenStore } from '../client'
import { WarehouseDataError } from './codec'
import { warehouseError } from './errors'
import { listSetupUsers, listUserWarehouseScopes } from './setup'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('does not convert malformed IAM paging into an empty successful directory', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ content: null, page: 0, size: 25, totalElements: 0 }), { headers: { 'Content-Type': 'application/json' } })))
  await expect(listSetupUsers('', 0)).rejects.toBeInstanceOf(WarehouseDataError)
})
it('does not infer revision zero from a malformed or missing existing scope revision', async () => {
  const id = '797b131a-ddaf-46e4-90a0-e20c6ef3c5ea'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id, userId: id, locationId: id, active: true }]), { headers: { 'Content-Type': 'application/json' } })))
  await expect(listUserWarehouseScopes(id)).rejects.toBeInstanceOf(WarehouseDataError)
})
it('preserves the server archive reason instead of incorrectly describing every409 as a stale edit', () => {
  expect(warehouseError(new ApiError(409, 'Selesaikan stok dan referensi terbuka sebelum arsip', undefined, 'SOURCE_NOT_VERIFIED'))).toBe('Selesaikan stok dan referensi terbuka sebelum arsip')
  expect(warehouseError(new ApiError(409, 'STALE_REVISION', undefined, 'STALE_REVISION'))).toContain('Muat ulang dokumen')
})
