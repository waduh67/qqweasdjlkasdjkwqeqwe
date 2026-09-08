import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearOltOnusCache } from '@/api/oltOnus'
import type { OltView } from '@/api/network'
import { deferred, oltOnusSnapshot } from '@/api/oltOnus.test-support'
import { DialogProvider, ToastProvider } from '@/system'
import { OltDetail } from '@/pages/OltDetailPage'

const { can } = vi.hoisted(() => ({ can: vi.fn<(permission: string) => boolean>() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))

const olt: OltView = {
  id: 'olt-a', code: 'OLT-A', name: 'OLT Utara', siteId: 'site-1', siteName: 'POP Pusat',
  vendor: 'HUAWEI', model: null, managementIp: null, status: 'ACTIVE',
  snmpConfigured: true, snmpPort: 161, pollable: true, ponPortCount: 1,
  location: { longitude: 0, latitude: 0 }, areaId: null, description: null,
  snmpEnabled: true, snmpVersion: 'V2C', webEnabled: false, webProtocol: 'HTTP',
  webPort: null, webUsername: null, webPasswordConfigured: false,
}

afterEach(() => {
  clearOltOnusCache()
  vi.unstubAllGlobals()
  can.mockReset()
})

function renderDetail(compact = false, oltId = 'olt-a') {
  return <MemoryRouter><ToastProvider><DialogProvider><OltDetail oltId={oltId} compact={compact} /></DialogProvider></ToastProvider></MemoryRouter>
}

function allow(...permissions: string[]) {
  can.mockImplementation((permission) => permissions.includes(permission))
}

describe('shared OLT detail device inventory tab', () => {
  it.each([false, true])('loads only after selecting the tab, without customer/map/ODP permissions (compact=%s)', async (compact) => {
    allow('network.olt.view', 'monitoring.provisioning.view')
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(olt))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    render(renderDetail(compact))
    const tab = await screen.findByRole('tab', { name: 'ONU di OLT' })
    expect(screen.queryByRole('tab', { name: 'ONU Pelanggan' })).toBeNull()
    expect(screen.getByRole('tab', { name: 'ONU Baru' })).toBeDefined()
    expect(fetch).toHaveBeenCalledTimes(1)
    await user.click(tab)
    expect(await screen.findByText('HWTC00112233')).toBeDefined()
    await user.click(tab)
    expect(fetch.mock.calls.map(([path]) => path)).toEqual(['/api/olts/olt-a', '/api/monitoring/olts/olt-a/onus'])
  })

  it.each([
    { permissions: [] }, { permissions: ['network.olt.view'] }, { permissions: ['monitoring.provisioning.view'] },
  ])('requires BOTH endpoint permissions (%j)', async ({ permissions }) => {
    allow(...permissions)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(olt))
    vi.stubGlobal('fetch', fetch)
    render(renderDetail())
    await screen.findByRole('tab', { name: 'Ringkasan' })
    expect(screen.queryByRole('tab', { name: 'ONU di OLT' })).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('renames the existing customer ONU tab while preserving ONU Baru', async () => {
    allow('network.olt.view', 'monitoring.provisioning.view', 'gis.map.view', 'network.odp.view', 'customer.customer.view')
    vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(olt)))
    render(renderDetail())
    expect(await screen.findByRole('tab', { name: 'ONU Pelanggan' })).toBeDefined()
    expect(screen.getByRole('tab', { name: 'ONU Baru' })).toBeDefined()
    expect(screen.getByRole('tab', { name: 'ONU di OLT' })).toBeDefined()
    expect(screen.queryByRole('tab', { name: 'ONU' })).toBeNull()
  })

  it('finishes an in-flight read after leaving the tab and reuses it on reactivation', async () => {
    allow('network.olt.view', 'monitoring.provisioning.view')
    const pending = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(olt))
      .mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    render(renderDetail(true))
    await user.click(await screen.findByRole('tab', { name: 'ONU di OLT' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    const signal = fetch.mock.calls[1]?.[1]?.signal
    await user.click(screen.getByRole('tab', { name: 'Ringkasan' }))
    expect(signal?.aborted).not.toBe(true)
    await act(async () => pending.resolve(Response.json(oltOnusSnapshot())))
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    await user.click(screen.getByRole('tab', { name: 'ONU di OLT' }))
    expect(await screen.findByText('HWTC00112233')).toBeDefined()
    await user.click(screen.getByRole('tab', { name: 'Ringkasan' }))
    await user.click(screen.getByRole('tab', { name: 'ONU di OLT' }))
    expect(await screen.findByText('HWTC00112233')).toBeDefined()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('reads the new prop OLT while shared-detail metadata is still loading', async () => {
    allow('network.olt.view', 'monitoring.provisioning.view')
    const nextDetail = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (path) => {
      if (path === '/api/olts/olt-a') return Response.json(olt)
      if (path === '/api/olts/olt-b') return nextDetail.promise
      if (path === '/api/monitoring/olts/olt-a/onus') return Response.json(oltOnusSnapshot())
      if (path === '/api/monitoring/olts/olt-b/onus') return Response.json(oltOnusSnapshot({ oltId: 'olt-b', oltCode: 'OLT-B', onus: [] }))
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    const { rerender } = render(renderDetail(true))
    await user.click(await screen.findByRole('tab', { name: 'ONU di OLT' }))
    await screen.findByText('HWTC00112233')
    rerender(renderDetail(true, 'olt-b'))
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    expect(await screen.findByText(/Sumber: OLT-B/)).toBeDefined()
    await act(async () => nextDetail.resolve(Response.json({ ...olt, id: 'olt-b', code: 'OLT-B' })))
    expect(fetch.mock.calls.filter(([path]) => path === '/api/monitoring/olts/olt-b/onus')).toHaveLength(1)
    expect(screen.queryByText('HWTC00112233')).toBeNull()
  })
})
