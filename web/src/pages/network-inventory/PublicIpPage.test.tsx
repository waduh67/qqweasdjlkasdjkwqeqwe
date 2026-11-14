import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { PublicIpPage } from './PublicIpPage'

vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'admin', tenantId: 'tenant' } }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true, hasPermission: () => true }) }))
const id = '11111111-1111-4111-8111-111111111111'
const ip = { id, address: '203.0.113.1', subnet: '255.255.255.252', gateway: '', vendorId: null, vendorName: null, notes: '', revision: 0, clientId: null, clientName: null, usedBy: 'Router PT ABC', blockId: id, addressRole: 'HOST', probe: { status: 'UP', checkedAt: '2026-11-14T00:00:00Z', rttMillis: 2.5, error: null } }
afterEach(() => vi.unstubAllGlobals())
function setup() {
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    const content = String(url).includes('/ips') ? [ip, { ...ip, id: '22222222-2222-4222-8222-222222222222', address: '203.0.113.2', usedBy: '', probe: { ...ip.probe, status: 'DOWN', rttMillis: null } }, { ...ip, id: '33333333-3333-4333-8333-333333333333', address: '203.0.113.0', usedBy: '', addressRole: 'NETWORK', probe: null }] : []
    return new Response(JSON.stringify({ content, page: 0, size: 20, totalElements: content.length }), { headers: { 'Content-Type': 'application/json' } })
  }))
  render(<MemoryRouter><PublicIpPage /></MemoryRouter>)
}
it('shows free-text ownership independently from reachability and labels reserved addresses', async () => {
  setup()
  expect(await screen.findByText('Router PT ABC')).toBeTruthy()
  expect(screen.getAllByText('UP').length).toBeGreaterThan(0)
  expect(screen.getAllByText('DOWN').length).toBeGreaterThan(0)
  expect(screen.getByText('Network')).toBeTruthy()
  expect(screen.getByText(/2[.]5 ms/)).toBeTruthy()
  expect(screen.getAllByText(/Hasil lebih dari 10 menit lalu/).length).toBeGreaterThan(0)
})
it('preserves a block address while allowing the owner label to be edited', async () => {
  setup()
  fireEvent.click(await screen.findByText('203.0.113.1'))
  const address = await screen.findByRole('textbox', { name: 'Alamat IP public' }) as HTMLInputElement
  expect(address.disabled).toBe(true)
  const owner = screen.getByRole('textbox', { name: 'Dipakai oleh' }) as HTMLInputElement
  expect(owner.disabled).toBe(false)
  expect(owner.value).toBe('Router PT ABC')
})
