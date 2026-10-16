import { afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CustomerOnuPanel } from './CustomerOnuPanel'

const access = vi.hoisted(() => ({ assign: true }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => access.assign }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ readOnly: false }) }))
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); access.assign = true })
const response = (value: unknown) => new Response(JSON.stringify(value))
const onu = { id: 'detected', serialNumber: '024654000002', oltCode: 'OLT-CRB-001', ponPortLabel: 'PON1', lastStatus: 'ONLINE' }

it('selects a discovered ONU and retries the reviewed link without installing warehouse stock', async () => {
  let lost = true
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/monitoring/discovered-onus') return response([onu])
    if (init?.method === 'POST') {
      expect(path).toBe('/api/onboarding/migrations/customer/onus')
      expect(JSON.parse(init.body as string)).toEqual({ serialNumber: onu.serialNumber })
      if (lost) { lost = false; throw new TypeError('response lost') }
      return response([{ onuId: 'linked', ...onu }])
    }
    return response({ customerId: 'customer', onus: [] })
  })
  vi.stubGlobal('fetch', fetch)
  const changed = vi.fn()
  render(<MemoryRouter><CustomerOnuPanel customerId="customer" onus={[]} onChanged={changed} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Hubungkan ONU terpasang' }))
  fireEvent.click(screen.getByRole('combobox', { name: 'ONU terpasang' }))
  fireEvent.click(await screen.findByRole('option', { name: /024654000002/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
  expect(screen.getByText('OLT-CRB-001')).toBeTruthy()
  expect(screen.getByText('Asal dan kepemilikan aset gudang belum dicatat.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Hubungkan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba lagi' }))
  await waitFor(() => expect(changed).toHaveBeenCalledTimes(1))
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(2)
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('does not offer migration linkage for ordinary customers or users without device permission', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response(null)))
  const mounted = render(<MemoryRouter><CustomerOnuPanel customerId="ordinary" onus={[]} onChanged={vi.fn()} /></MemoryRouter>)
  await waitFor(() => expect(fetch).toHaveBeenCalled())
  expect(screen.queryByRole('button', { name: 'Hubungkan ONU terpasang' })).toBeNull()
  mounted.unmount(); access.assign = false
  vi.stubGlobal('fetch', vi.fn(async () => response({ customerId: 'migrated', onus: [] })))
  render(<MemoryRouter><CustomerOnuPanel customerId="migrated" onus={[]} onChanged={vi.fn()} /></MemoryRouter>)
  await waitFor(() => expect(fetch).toHaveBeenCalled())
  expect(screen.queryByRole('button', { name: 'Hubungkan ONU terpasang' })).toBeNull()
})
