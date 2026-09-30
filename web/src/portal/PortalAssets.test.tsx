import { afterEach, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { portalTokenStore } from './portalClient'
import { PortalAssets } from './PortalAssets'
import { getPortalAssets } from './portalAssets'

vi.mock('./PortalAuthContext', () => ({ usePortalAuth: () => ({ customer: { customerId: 'customer', tenantId: 'tenant' } }) }))
afterEach(() => { vi.unstubAllGlobals(); portalTokenStore.clear() })
it('uses the portal realm and presents safe ownership without warehouse identifiers or mutations', async () => {
  portalTokenStore.setAccessToken('portal-only')
  const fetch = vi.fn(async (_path: string, _input?: RequestInit) => new Response(JSON.stringify({ page: 0, size: 10, totalElements: 1, items: [{
    deviceLabel: 'ONU Rumah', serialNumber: 'ONU-A1', ownershipMode: 'SALE', legalOwner: 'CUSTOMER', provenance: 'RECEIPT', installedAt: '2026-09-25T01:00:00Z', removedAt: null,
    assignmentId: 'private-assignment', warehouseLocation: 'private-location', cost: 'private-cost',
  }] })))
  vi.stubGlobal('fetch', fetch)
  const safe = await getPortalAssets(0)
  expect(Object.keys(safe.items[0])).toEqual(['deviceLabel', 'serialNumber', 'ownershipMode', 'legalOwner', 'provenance', 'installedAt', 'removedAt'])
  render(<PortalAssets />); await screen.findByText(/Milik Anda/)
  expect(document.body.textContent).not.toContain('private-'); expect(screen.queryByRole('button', { name: /Pasang|Ganti|Lepas/ })).toBeNull()
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/portal/me/assets?'))).toBe(true)
  expect(new Headers(fetch.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer portal-only')
})
