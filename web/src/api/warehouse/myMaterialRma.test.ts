import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { myContext } from '@/test/myMaterialsFixture'
import { rmaDetailsFixture, rmaFixture, returnIds as id } from '@/test/warehouseReturnFixture'
import { acknowledgeMyRma, getMyMaterialRma, reinstallMyRma } from './myMaterialRma'

const context = () => ({ ...myContext(), id: id.rmaOrder, workOrderRevision: 17, field: null })
const received = () => rmaDetailsFixture({ ...rmaFixture(), state: 'RECEIVED', revision: 2, locationId: id.field })
const response = (value: unknown) => new Response(JSON.stringify(value))
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects foreign references, wrong serials and unreceived reinstall sources', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response(rmaDetailsFixture({ ...rmaFixture(), workOrderId: id.source }))))
  await expect(getMyMaterialRma(id.rmaOrder, id.rma)).rejects.toThrow()
  expect(() => acknowledgeMyRma(context(), rmaDetailsFixture(), id.rmaTechnician, 'OTHER', 'Bukti')).toThrow()
  expect(() => acknowledgeMyRma({ ...context(), workOrderRevision: 18 }, rmaDetailsFixture(), id.rmaTechnician, 'ONU-001', 'Bukti')).toThrow()
  expect(() => reinstallMyRma(context(), rmaDetailsFixture(), id.rmaTechnician, 'ONU-001', null)).toThrow()
  expect(() => reinstallMyRma(context(), received(), id.supplier, 'ONU-001', null)).toThrow()
})
it('retries the exact lost authorization before using its returned revision and frozen original-customer references', async () => {
  let authorizations = 0
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (path.endsWith('/authorize')) { if (++authorizations === 1) throw new TypeError('response lost'); return response({ authorizationId: id.plan, revision: 4, operationId: id.issue }) }
    return response({ assignmentId: id.demandLine, episodeId: id.document, customerId: id.evidence, assetId: id.piece })
  }); vi.stubGlobal('fetch', fetch)
  const row = received(), job = context(), topology = { odpId: id.source, portNumber: 2, installRxPowerDbm: -23 }
  const action = reinstallMyRma(job, row, id.rmaTechnician, 'ONU-001', topology)
  row.handover.customerId = id.supplier; row.handover.stockIdentityId = id.supplier; job.workOrderRevision = 100; topology.portNumber = 9
  await expect(action.execute()).rejects.toThrow('response lost'); await action.execute()
  expect(fetch.mock.calls).toHaveLength(3); expect(fetch.mock.calls[0]).toEqual(fetch.mock.calls[1])
  expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toMatchObject({ expectedRevision: 17, purpose: 'RETURN_CUSTOMER_RMA', ownershipMode: 'SALE', assetId: id.piece, previousAssignmentId: id.demandLine, repairCaseId: id.repair, issueLineId: null })
  expect(fetch.mock.calls[2][0]).toBe(`/api/customers/${id.evidence}/assets/install`)
  expect(JSON.parse(String(fetch.mock.calls[2][1]?.body))).toMatchObject({ expectedRevision: 4, topology: { portNumber: 2 } })
})
it('stops the second stage when logout or account switching occurs during authorization', async () => {
  const fetch = vi.fn(async (_path: string, _init?: RequestInit) => {
    tokenStore.clear(); tokenStore.setAccessToken('new-account')
    return response({ authorizationId: id.plan, revision: 0, operationId: id.issue })
  }); vi.stubGlobal('fetch', fetch)
  const action = reinstallMyRma(context(), received(), id.rmaTechnician, 'ONU-001', null)
  await expect(action.execute()).rejects.toThrow('Sesi berubah')
  await expect(action.execute()).rejects.toThrow('Sesi berubah')
  expect(fetch.mock.calls).toHaveLength(1)
})
