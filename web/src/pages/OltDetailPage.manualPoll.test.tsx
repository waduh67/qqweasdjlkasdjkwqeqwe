import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearOltOnusCache, readCachedOltOnus } from '@/api/oltOnus'
import { deferred, oltOnusSnapshot, unsupportedOnu } from '@/api/oltOnus.test-support'
import type { OltView } from '@/api/network'
import { DialogProvider, ToastProvider } from '@/system'
import { OltDetail } from './OltDetailPage'

const { can } = vi.hoisted(() => ({ can: vi.fn<(permission: string) => boolean>() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))

const olt: OltView = {
  id: 'olt-a', code: 'OLT-01', name: 'OLT Utara', siteId: 'site-1', siteName: 'POP Pusat',
  vendor: 'HUAWEI', model: null, managementIp: '192.0.2.10', status: 'ACTIVE',
  snmpConfigured: true, snmpPort: 161, pollable: true, ponPortCount: 1,
  location: { longitude: 0, latitude: 0 }, areaId: null, description: null,
  snmpEnabled: true, snmpVersion: 'V2C', webEnabled: false, webProtocol: 'HTTP',
  webPort: null, webUsername: null, webPasswordConfigured: false,
}

const pollResult = {
  oltId: 'olt-a', oltCode: 'OLT-01', reachable: true, readingCount: 32,
  failureReason: null, checkedAt: '2026-09-11T03:04:05Z',
}

function allow(...permissions: string[]) {
  can.mockImplementation((permission) => permissions.includes(permission))
}

function renderDetail(props: Partial<ComponentProps<typeof OltDetail>> = {}) {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <DialogProvider>
          <OltDetail oltId="olt-a" compact {...props} />
        </DialogProvider>
      </ToastProvider>
    </MemoryRouter>,
  )
}

function countRequests(fetch: ReturnType<typeof vi.fn>, path: string, method = 'GET') {
  return fetch.mock.calls.filter(([url, init]) => url === path && (init?.method ?? 'GET') === method).length
}

afterEach(() => {
  clearOltOnusCache()
  vi.unstubAllGlobals()
  can.mockReset()
})

describe('manual SNMP poll action', () => {
  it('wires map impact refresh while inventory leaves list refresh unwired', async () => {
    const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
    const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
    const [mapSource, inventorySource] = await Promise.all([
      readFile(resolve('src/pages/MapPage.tsx'), 'utf8'),
      readFile(resolve('src/pages/InventoryPage.tsx'), 'utf8'),
    ])
    const inventoryDetail = inventorySource.slice(
      inventorySource.indexOf('<OltDetail', inventorySource.indexOf('{openOlt &&')),
      inventorySource.indexOf('/>', inventorySource.indexOf('<OltDetail', inventorySource.indexOf('{openOlt &&'))),
    )

    expect(mapSource).toContain('onPollCompleted={refreshImpacted}')
    expect(inventoryDetail).not.toContain('onPollCompleted')
  })

  it('is hidden without collector-manage permission', async () => {
    allow('network.olt.view')
    vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(olt)))
    renderDetail()
    await screen.findByRole('tab', { name: 'Ringkasan' })
    expect(screen.queryByRole('button', { name: 'Cek SNMP' })).toBeNull()
  })

  it.each([
    { status: 'INACTIVE', pollable: true },
    { status: 'ACTIVE', pollable: false },
  ])('is disabled when status=$status and pollable=$pollable', async (eligibility) => {
    allow('monitoring.collector.manage')
    vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ ...olt, ...eligibility })))
    renderDetail()
    expect((await screen.findByRole('button', { name: 'Cek SNMP' })).hasAttribute('disabled')).toBe(true)
  })

  it('prevents duplicate calls, shows loading, reloads detail, and reports the exact reachable result', async () => {
    allow('monitoring.collector.manage')
    const pending = deferred<Response>()
    const onPollCompleted = vi.fn(async () => undefined)
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((path, init) => {
      if (path === '/api/olts/olt-a') return Promise.resolve(Response.json(olt))
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') return pending.promise
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    renderDetail({ onPollCompleted })
    const button = await screen.findByRole('button', { name: 'Cek SNMP' })

    act(() => {
      button.click()
      button.click()
    })

    const pollingButton = screen.getByRole('button', { name: 'Memeriksa…' })
    expect(pollingButton.hasAttribute('disabled')).toBe(true)
    expect(pollingButton.closest('[aria-busy="true"]')).not.toBeNull()
    expect(countRequests(fetch, '/api/monitoring/olts/olt-a/poll', 'POST')).toBe(1)
    await act(async () => pending.resolve(Response.json(pollResult)))

    const successAnnouncement = await screen.findByRole('status')
    expect(successAnnouncement.textContent).toBe('SNMP OLT-01 selesai · 32 ONU terbaca.')
    expect(screen.getAllByRole('status')).toHaveLength(1)
    await waitFor(() => expect(countRequests(fetch, '/api/olts/olt-a')).toBe(2))
    expect(onPollCompleted).toHaveBeenCalledTimes(1)
  })

  it('keeps a deferred poll busy across close and reopen, then completes on the current mount', async () => {
    allow('monitoring.collector.manage')
    const pending = deferred<Response>()
    const staleOnPollCompleted = vi.fn(async () => undefined)
    const currentOnPollCompleted = vi.fn(async () => undefined)
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((path, init) => {
      if (path === '/api/olts/olt-a') return Promise.resolve(Response.json(olt))
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') return pending.promise
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    const view = render(
      <MemoryRouter>
        <ToastProvider>
          <DialogProvider>
            <OltDetail oltId="olt-a" compact onPollCompleted={staleOnPollCompleted} />
          </DialogProvider>
        </ToastProvider>
      </MemoryRouter>,
    )

    await userEvent.click(await screen.findByRole('button', { name: 'Cek SNMP' }))
    expect(countRequests(fetch, '/api/monitoring/olts/olt-a/poll', 'POST')).toBe(1)

    view.rerender(
      <MemoryRouter>
        <ToastProvider>
          <DialogProvider>{null}</DialogProvider>
        </ToastProvider>
      </MemoryRouter>,
    )
    view.rerender(
      <MemoryRouter>
        <ToastProvider>
          <DialogProvider>
            <OltDetail oltId="olt-a" compact onPollCompleted={currentOnPollCompleted} />
          </DialogProvider>
        </ToastProvider>
      </MemoryRouter>,
    )

    const reopenedButton = await screen.findByRole('button', { name: 'Memeriksa…' })
    expect(reopenedButton.hasAttribute('disabled')).toBe(true)
    expect(reopenedButton.closest('[aria-busy="true"]')).not.toBeNull()
    act(() => reopenedButton.click())
    expect(countRequests(fetch, '/api/monitoring/olts/olt-a/poll', 'POST')).toBe(1)

    await act(async () => pending.resolve(Response.json(pollResult)))

    const announcement = await screen.findByRole('status')
    expect(announcement.textContent).toBe('SNMP OLT-01 selesai · 32 ONU terbaca.')
    expect(screen.getAllByRole('status')).toHaveLength(1)
    await waitFor(() => expect(countRequests(fetch, '/api/olts/olt-a')).toBe(3))
    expect(staleOnPollCompleted).not.toHaveBeenCalled()
    expect(currentOnPollCompleted).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Cek SNMP' }).hasAttribute('disabled')).toBe(false)
  })

  it('reports the exact unreachable result after a successful HTTP response', async () => {
    allow('monitoring.collector.manage')
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (path, init) => {
      if (path === '/api/olts/olt-a') return Response.json(olt)
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') {
        return Response.json({ ...pollResult, reachable: false, readingCount: 0, failureReason: 'timeout' })
      }
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Cek SNMP' }))
    const unreachableAnnouncement = await screen.findByRole('alert')
    expect(unreachableAnnouncement.textContent).toBe('OLT-01 tidak merespons SNMP.')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(countRequests(fetch, '/api/olts/olt-a')).toBe(2)
  })

  it('restores the action and refreshes nothing when the poll request is rejected', async () => {
    allow('monitoring.collector.manage')
    const onPollCompleted = vi.fn(async () => undefined)
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (path, init) => {
      if (path === '/api/olts/olt-a') return Response.json(olt)
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') {
        return Response.json({ detail: 'Polling SNMP sedang berjalan' }, { status: 409 })
      }
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    renderDetail({ onPollCompleted })

    await user.click(await screen.findByRole('button', { name: 'Cek SNMP' }))

    const errorAnnouncement = await screen.findByRole('alert')
    expect(errorAnnouncement.textContent).toBe('Polling SNMP sedang berjalan')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Cek SNMP' }).hasAttribute('disabled')).toBe(false)
    expect(screen.getByRole('tab', { name: 'Ringkasan' })).toBeDefined()
    expect(countRequests(fetch, '/api/olts/olt-a')).toBe(1)
    expect(onPollCompleted).not.toHaveBeenCalled()
  })

  it.each([
    { tab: 'ONU Pelanggan', path: '/api/gis/olts/olt-a/onus', response: { oltId: 'olt-a', onuCount: 0, onus: [] } },
    { tab: 'ONU Baru', path: '/api/monitoring/discovered-onus?state=DISCOVERED&oltId=olt-a', response: [] },
  ])('refetches $tab after polling completes', async ({ tab, path, response }) => {
    allow(
      'monitoring.collector.manage', 'monitoring.provisioning.view', 'network.olt.view',
      'gis.map.view', 'network.odp.view', 'customer.customer.view',
    )
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (url, init) => {
      if (url === '/api/olts/olt-a') return Response.json(olt)
      if (url === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') return Response.json(pollResult)
      if (url === path) return Response.json(response)
      throw new Error('Unexpected request: ' + String(url))
    })
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('tab', { name: tab }))
    await waitFor(() => expect(countRequests(fetch, path)).toBe(1))
    await user.click(screen.getByRole('button', { name: 'Cek SNMP' }))
    await waitFor(() => expect(countRequests(fetch, path)).toBe(2))
  })

  it('invalidates and immediately refetches the active device ONU tab', async () => {
    allow('monitoring.collector.manage', 'monitoring.provisioning.view', 'network.olt.view')
    let deviceReads = 0
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (path, init) => {
      if (path === '/api/olts/olt-a') return Response.json(olt)
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') return Response.json(pollResult)
      if (path === '/api/monitoring/olts/olt-a/onus') {
        deviceReads += 1
        return Response.json(deviceReads === 1 ? oltOnusSnapshot() : oltOnusSnapshot({ onus: [unsupportedOnu] }))
      }
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('tab', { name: 'ONU di OLT' }))
    await screen.findByText('HWTC00112233')
    await user.click(screen.getByRole('button', { name: 'Cek SNMP' }))
    expect(await screen.findByText('ZTEG44556677')).toBeDefined()
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    expect(deviceReads).toBe(2)
  })

  it('does not read an inactive device tab until activation, then bypasses its old cache', async () => {
    allow('monitoring.collector.manage', 'monitoring.provisioning.view', 'network.olt.view')
    let deviceReads = 0
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (path, init) => {
      if (path === '/api/monitoring/olts/olt-a/onus') {
        deviceReads += 1
        return Response.json(deviceReads === 1 ? oltOnusSnapshot() : oltOnusSnapshot({ onus: [unsupportedOnu] }))
      }
      if (path === '/api/olts/olt-a') return Response.json(olt)
      if (path === '/api/monitoring/olts/olt-a/poll' && init?.method === 'POST') return Response.json(pollResult)
      throw new Error('Unexpected request: ' + String(path))
    })
    vi.stubGlobal('fetch', fetch)
    await readCachedOltOnus('olt-a')
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Cek SNMP' }))
    await screen.findByText('SNMP OLT-01 selesai · 32 ONU terbaca.')
    expect(deviceReads).toBe(1)
    await user.click(screen.getByRole('tab', { name: 'ONU di OLT' }))
    expect(await screen.findByText('ZTEG44556677')).toBeDefined()
    expect(deviceReads).toBe(2)
  })
})
