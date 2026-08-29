import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HotspotPortalPage } from './HotspotPortalPage'

const { getPublicHotspotPortalContext } = vi.hoisted(() => ({
  getPublicHotspotPortalContext: vi.fn(),
}))

vi.mock('@/api/hotspot', () => ({ getPublicHotspotPortalContext }))

afterEach(() => {
  getPublicHotspotPortalContext.mockReset()
})

function renderPortal(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/hotspot-portal/:portalId" element={<HotspotPortalPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('HotspotPortalPage', () => {
  it('menampilkan branding dan formulir nonaktif setelah state bertanda tangan valid', async () => {
    getPublicHotspotPortalContext.mockResolvedValue({
      displayName: 'Wi-Fi Lobi',
      logoUrl: null,
      redirectUrl: null,
      clientMac: null,
      clientIp: null,
    })

    renderPortal('/hotspot-portal/portal-1?state=signed-state')

    expect(await screen.findByRole('heading', { name: 'Wi-Fi Lobi' })).toBeDefined()
    expect(screen.getByRole('textbox', { name: 'Username atau kode voucher' })).toBeDefined()
    expect(screen.getByLabelText('Kata sandi')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Masuk ke Wi-Fi' }).hasAttribute('disabled')).toBe(true)
    expect(getPublicHotspotPortalContext).toHaveBeenCalledWith('signed-state')
  })

  it('menampilkan keadaan generik saat state tidak ada atau tidak valid', async () => {
    renderPortal('/hotspot-portal/portal-1')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Tautan portal tidak dapat digunakan' })).toBeDefined()
    })
    expect(screen.queryByText('Wi-Fi Lobi')).toBeNull()
  })
})
