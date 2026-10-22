import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { networkInventory } from './networkInventory'
import { decodeClientNetwork, emptyNetwork } from './networkModels'
import { b2b } from './b2b'
import { tokenStore } from './client'

const id = '11111111-1111-4111-8111-111111111111'
const vendor = { id, name: 'Telkom', contact: 'NOC', notes: '', active: true, revision: 0 }
const ip = { id, address: '157.15.122.100', subnet: '255.255.255.248', gateway: '157.15.122.99', vendorId: id, vendorName: 'Telkom', notes: '', revision: 0, clientId: null, clientName: null }
const setting = { name: 'Harper', address: 'Jl. Hotel', contact: 'PIC', technicianId: id, technicianName: 'NE', target: 4, active: true }
const network = { details: emptyNetwork, publicIps: [ip], tunnelPasswordSet: true }
const client = { id, revision: 0, createdMonth: '2026-10', current: { clientId: id, month: '2026-10', setting, counted: 0, remaining: 4, weeks: [] }, next: setting, network }
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('captures vendor bytes and key through a failed request and prevents cross-session retries', async () => {
  const input = { name: 'Telkom', contact: 'NOC', notes: '', active: true, expectedRevision: 0 }
  const operation = networkInventory.saveVendor(undefined, input)
  fetchMock.mockRejectedValueOnce(new TypeError('connection lost')).mockResolvedValueOnce(new Response(JSON.stringify(vendor)))
  await expect(operation.execute()).rejects.toThrow('connection lost')
  input.name = 'Changed'
  await expect(operation.execute()).resolves.toEqual(vendor)
  for (const [, init] of fetchMock.mock.calls) {
    expect(JSON.parse(init.body).name).toBe('Telkom')
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe(operation.key)
  }
  tokenStore.clear(); tokenStore.setAccessToken('new-account')
  expect(() => operation.execute()).toThrow('Sesi berubah')
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
it('encodes inventory filters and rejects invalid pagination instead of an empty success', async () => {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ content: [ip], page: 0, size: 20, totalElements: 1 })))
  await expect(networkInventory.ips({ q: 'Harper & hotel', page: 0, status: 'ALLOCATED', vendorId: id })).resolves.toMatchObject({ content: [ip] })
  const path = new URL(fetchMock.mock.calls[0][0], 'https://test.local')
  expect(path.searchParams.get('q')).toBe('Harper & hotel'); expect(path.searchParams.get('status')).toBe('ALLOCATED'); expect(path.searchParams.get('vendorId')).toBe(id)
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ content: [ip], page: 0, size: 20, totalElements: 0 })))
  await expect(networkInventory.ips({ q: '', page: 0 })).rejects.toThrow()
})
it('decodes multiple allocated addresses while discarding unexpected secrets', () => {
  expect(decodeClientNetwork({ ...network, tunnelPassword: 'must-not-display' })).toEqual(network)
  expect(() => decodeClientNetwork({ ...network, details: { ...emptyNetwork, mainInfrastructure: 'UNKNOWN' } })).toThrow()
  expect(() => decodeClientNetwork({ ...network, publicIps: [{ ...ip, id: 'invalid' }] })).toThrow()
})
it('keeps legacy clients readable and captures a complete network allocation without exposing a returned password', async () => {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ content: [{ ...client, network: undefined }], page: 0, size: 20, totalElements: 1 })))
  const legacy = await b2b.clients('', 0)
  expect(legacy.content[0].network).toBeUndefined()
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ ...client, network: { ...network, tunnelPassword: 'unexpected' } })))
  const publicIpIds = [id]
  const operation = b2b.save(undefined, { ...setting, expectedRevision: 0, network: { details: emptyNetwork, publicIpIds, tunnelPassword: 'new-secret', clearTunnelPassword: false } })
  publicIpIds.splice(0)
  const saved = await operation.execute()
  expect(saved.network).toEqual(network)
  expect(JSON.parse(operation.body).network.publicIpIds).toEqual([id])
  expect(JSON.stringify(saved)).not.toContain('unexpected')
})
