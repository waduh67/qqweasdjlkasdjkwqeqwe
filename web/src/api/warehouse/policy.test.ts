import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { policyDetailsFixture, policyVersionFixture } from '@/test/warehousePolicyFixture'
import { WarehouseDataError } from './codec'
import { policyApprovers, policyDetails, policyVersion, savePolicy } from './policy'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('keeps exact integer thresholds and binds saved policy to all named locations', () => {
  const details = policyDetailsFixture(), version = policyVersionFixture()
  version.rules[0].tiers[0].minimumMinor = '900719925474099312345678'
  expect(policyVersion(version).rules[0].tiers[0].minimumMinor).toBe('900719925474099312345678')
  expect(policyDetails(details)).toEqual(details)
  expect(policyDetails(policyDetailsFixture(null)).configured).toBe(false)
  expect(() => policyDetails({ ...details, current: null })).toThrow(WarehouseDataError)
  expect(() => policyDetails({ ...details, references: { ...details.references, locations: [] } })).toThrow(WarehouseDataError)
  expect(() => policyVersion({ ...version, rules: [{ operation: 'ADJUSTMENT', tiers: [{ minimumMinor: 100, userIds: [id.checker], roleIds: [] }] }] })).toThrow(WarehouseDataError)
  expect(() => policyVersion({ ...version, rules: [{ operation: 'ADJUSTMENT', tiers: [{ minimumMinor: '100', userIds: [id.checker], roleIds: [] }, { minimumMinor: '50', userIds: [id.other], roleIds: [] }] }] })).toThrow(WarehouseDataError)
})
it('preserves the reviewed policy revision and exact body through response-loss replay', async () => {
  const version = policyVersionFixture(8), input = { expectedRevision: 7, currency: version.currency, expiryHours: version.expiryHours, warehouseIds: version.warehouseIds, rules: version.rules }
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(new Response(JSON.stringify(version)))
  vi.stubGlobal('fetch', fetch)
  const command = savePolicy(input)
  input.expectedRevision = 0; input.rules[0].tiers[0].minimumMinor = '999'
  await expect(command.execute()).rejects.toThrow('response lost')
  await expect(command.execute()).resolves.toMatchObject({ revision: 8 })
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toMatchObject({ expectedRevision: 7, rules: [{ tiers: [{ minimumMinor: '100' }] }] })
  expect((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key')).toBe(command.key)
})
it('sends every selected location to the bounded role picker and reads no global IAM directory', async () => {
  const fetch = vi.fn(async (path: string) => { expect(path).toContain('/policy/approvers?'); return new Response(JSON.stringify({ items: [], page: 1, size: 25, totalElements: 0 })) })
  vi.stubGlobal('fetch', fetch)
  await policyApprovers([id.source, id.target], 'ROLE', 'Gudang A', 1)
  const url = new URL(String(fetch.mock.calls[0][0]), 'http://localhost')
  expect(url.pathname).toBe('/api/v1/warehouse/settings/policy/approvers')
  expect(url.searchParams.getAll('locationId')).toEqual([id.source, id.target])
  expect(url.searchParams.get('query')).toBe('Gudang A'); expect(url.searchParams.get('kind')).toBe('ROLE')
})
