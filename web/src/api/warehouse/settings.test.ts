import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { delegationFixture, delegationViewFixture } from '@/test/warehouseSettingsFixture'
import { WarehouseDataError } from './codec'
import { createDelegation, delegationCandidates, delegationView, listDelegations, listPolicyHistory, revokeDelegation } from './settings'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects mismatched named references, impossible dates, self delegation and false revocation status', () => {
  const row = delegationViewFixture()
  expect(delegationView(row).approver?.name).toBe('Pemeriksa gudang')
  expect(() => delegationView({ ...row, location: { ...row.location, id: id.target } })).toThrow(WarehouseDataError)
  expect(() => delegationView({ ...row, delegate: { ...row.delegate, id: id.checker } })).toThrow(WarehouseDataError)
  expect(() => delegationView({ ...row, state: 'REVOKED' })).toThrow(WarehouseDataError)
  expect(() => delegationView({ ...row, delegation: { ...row.delegation, delegateId: id.checker } })).toThrow(WarehouseDataError)
  expect(() => delegationView({ ...row, delegation: { ...row.delegation, validUntil: row.delegation.validFrom } })).toThrow(WarehouseDataError)
})
it('uses bounded scoped workbench reads and binds delegate candidates to the chosen role and source', async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ items: [], page: 1, size: 25, totalElements: 0 }))); vi.stubGlobal('fetch', fetch)
  await listPolicyHistory(1); await listDelegations({ page: 2, locationId: id.source, state: 'REVOKED' })
  await delegationCandidates({ locationId: id.source, operation: 'ADJUSTMENT', kind: 'DELEGATE', approverId: id.checker, sourceRoleId: id.target, page: 1, query: 'Pemeriksa pengganti' })
  const paths = fetch.mock.calls as unknown as [string][]
  expect(paths[0][0]).toBe('/api/v1/warehouse/settings/workbench/policy-history?page=1&size=10')
  expect(new URL(paths[1][0], 'http://localhost').searchParams.get('state')).toBe('REVOKED')
  const url = new URL(paths[2][0], 'http://localhost')
  expect(Object.fromEntries(url.searchParams)).toEqual({ locationId: id.source, operation: 'ADJUSTMENT', kind: 'DELEGATE', approverId: id.checker, sourceRoleId: id.target, page: '1', query: 'Pemeriksa pengganti', size: '25' })
})
it('captures the contract create revision zero and replays the same reviewed delegation after response loss', async () => {
  const value = delegationFixture(1), input = { expectedRevision: 0 as const, approverId: value.approverId, delegateId: value.delegateId, sourceRoleId: id.target, locationId: value.locationId, operation: value.operation, validUntil: value.validUntil }
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(new Response(JSON.stringify(value))); vi.stubGlobal('fetch', fetch)
  const command = createDelegation(input); input.delegateId = id.requester
  await expect(command.execute()).rejects.toThrow('response lost'); await command.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(command.body)).toMatchObject({ expectedRevision: 0, delegateId: id.other, sourceRoleId: id.target })
  expect((fetch.mock.calls[0][1].headers as Headers).get('Idempotency-Key')).toBe((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key'))
})
it('revokes the actual selected delegation revision', async () => {
  const value = delegationFixture(17), command = revokeDelegation(value)
  value.revision = 18
  expect(command.path).toBe(`/api/v1/warehouse/settings/delegations/${value.id}/revoke`)
  expect(JSON.parse(command.body)).toEqual({ expectedRevision: 17 })
})
