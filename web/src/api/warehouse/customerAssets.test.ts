import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { assetHistoryFixture, assetJobFixture, assetSourceFixture, assetIds as id } from '@/test/customerAssetFixture'
import { acceptCustomerAsset, assetSource, deployCustomerAsset, getAssetAssignment, relocateCustomerAsset, removeCustomerAsset } from './customerAssets'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
const response = (value: unknown) => new Response(JSON.stringify(value))
it('rejects unacknowledged or unknown-origin serials and foreign customer history', async () => {
  const source = assetSourceFixture()
  expect(assetSource(source).id).toBe(id.piece)
  for (const bad of [ { ...source, provenance: 'UNKNOWN' }, { ...source, source: { ...source.source, initialUseSource: false } }, { ...source, source: { ...source.source, quantityBase: '2' } } ]) expect(() => assetSource(bad)).toThrow()
  vi.stubGlobal('fetch', vi.fn(async () => response({ ...assetHistoryFixture(), asset: { ...assetHistoryFixture().asset, customerId: id.supplier } })))
  await expect(getAssetAssignment(id.customer, id.assignment)).rejects.toThrow()
})
it('keeps both command stages immutable and retries only the lost installation response with its original key', async () => {
  const job = assetJobFixture(), source = assetSourceFixture(), topology = { odpId: id.allocation, portNumber: 2, installRxPowerDbm: -22 }
  let installs = 0
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (path.endsWith('/authorize')) return response({ authorizationId: id.plan, operationId: id.assignment, revision: 0 })
    if (++installs === 1) throw new TypeError('response lost')
    return response({ assignmentId: id.assignment, episodeId: id.assignment, customerId: id.customer, assetId: id.piece })
  }); vi.stubGlobal('fetch', fetch)
  const action = deployCustomerAsset(job, source, 'LOAN', topology)
  job.customerId = id.supplier; topology.portNumber = 9; source.id = id.document
  await expect(action.execute()).rejects.toThrow('response lost'); await expect(action.execute()).resolves.toMatchObject({ assignmentId: id.assignment })
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/authorize'))).toHaveLength(1)
  const writes = fetch.mock.calls.filter(([path]) => path.endsWith('/install'))
  expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1])
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ expectedRevision: 0, topology: { portNumber: 2 } })
  expect(writes[0][0]).toContain(id.customer)
})
it('uses actual ownership and episode revisions and requires matching work order purpose and signature', () => {
  const row = assetHistoryFixture(), job = assetJobFixture()
  row.asset.revision = 8; row.asset.titleRevision = 3
  expect(JSON.parse(acceptCustomerAsset(row, job).body)).toMatchObject({ expectedRevision: 8, expectedTitleRevision: 3, evidenceId: id.evidence })
  expect(() => acceptCustomerAsset(row, { ...job, signature: null })).toThrow()
  expect(() => removeCustomerAsset(row, job)).toThrow()
  expect(JSON.parse(removeCustomerAsset(row, { ...job, workType: 'DISMANTLE' }).body)).toMatchObject({ expectedRevision: 8, expectedTitleRevision: 3 })
  expect(JSON.parse(relocateCustomerAsset(row, { ...job, workType: 'MIGRATION' }, { odpId: id.allocation, portNumber: 1, installRxPowerDbm: null }).body)).toMatchObject({ expectedRevision: 3, expectedWorkOrderRevision: 7 })
  expect(() => deployCustomerAsset(job, assetSourceFixture(), 'LOAN', null, undefined, { id: id.document, serial: 'FOREIGN' })).toThrow()
})

it('authorizes a replacement of the reviewed prior assignment and preserves customer-owned removal title references', async () => {
  const previous = assetHistoryFixture(); previous.asset.ownershipMode = 'SALE'; previous.asset.legalOwner = 'CUSTOMER'; previous.asset.revision = 2; previous.asset.titleRevision = 1
  const source = assetSourceFixture(); source.id = id.document; source.source.id = id.document; source.source.serial = 'ONU-B2'
  const fetch = vi.fn(async (path: string, _input?: RequestInit) => path.endsWith('/authorize') ? response({ authorizationId: id.plan, operationId: id.allocation, revision: 0 }) : response({ operationId: id.allocation,
    replacement: { assignmentId: id.allocation, episodeId: id.allocation, customerId: id.customer, assetId: id.document } }))
  vi.stubGlobal('fetch', fetch)
  const action = deployCustomerAsset({ ...assetJobFixture(), workType: 'MIGRATION' }, source, 'LOAN', null, previous)
  await expect(action.execute()).resolves.toMatchObject({ operationId: id.allocation })
  expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toMatchObject({ purpose: 'REPLACE', previousAssignmentId: id.assignment, assetId: id.document })
  expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toMatchObject({ expectedAssignmentRevision: 2, expectedTitleRevision: 1, evidenceId: id.evidence })
})

it('provisions an observed serial only after eligible source authorization and binds the returned discovery', async () => {
  const fetch = vi.fn(async (path: string, _input?: RequestInit) => path.endsWith('/authorize') ? response({ authorizationId: id.plan, operationId: id.assignment, revision: 3 }) : response({ id: id.document, state: 'PROVISIONED', serialNumber: 'ONU-A1' }))
  vi.stubGlobal('fetch', fetch)
  await deployCustomerAsset(assetJobFixture(), assetSourceFixture(), 'LOAN', null, undefined, { id: id.document, serial: 'ONU-A1' }).execute()
  expect(fetch.mock.calls.map(([path]) => path)).toEqual([`/api/work-orders/${id.source}/assets/authorize`, `/api/monitoring/discovered-onus/${id.document}/provision`])
  expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toMatchObject({ customerId: id.customer, authorizationId: id.plan, expectedRevision: 3 })
})

it('does not continue an authorized installation under a changed account', async () => {
  const fetch = vi.fn(async () => { tokenStore.clear(); tokenStore.setAccessToken('another-user'); return response({ authorizationId: id.plan, operationId: id.assignment, revision: 0 }) })
  vi.stubGlobal('fetch', fetch)
  const action = deployCustomerAsset(assetJobFixture(), assetSourceFixture(), 'LOAN', null)
  await expect(action.execute()).rejects.toThrow('Sesi berubah')
  await expect(action.execute()).rejects.toThrow('Sesi berubah')
  expect(fetch).toHaveBeenCalledTimes(1)
})
