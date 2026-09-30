import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { PortalRingkasanPage } from './PortalRingkasanPage'

vi.mock('./PortalLayout', () => ({ usePortalData: () => ({ profile: null, billing: null, connection: null, ready: true, tenantSlug: 'isp', customerName: 'Pelanggan' }) }))
describe('portal unavailable billing', () => {
  it('never calls an account paid when billing failed to load', () => {
    render(<MemoryRouter><PortalRingkasanPage /></MemoryRouter>)
    expect(screen.queryByText('Lunas')).toBeNull()
    expect(screen.queryByText('Semua tagihanmu sudah lunas — terima kasih.')).toBeNull()
    expect(screen.getByText('Data tagihan gagal dimuat.')).toBeTruthy()
  })
})
