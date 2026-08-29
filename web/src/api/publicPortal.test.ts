import { describe, expect, it, vi } from 'vitest'
import { resolvePublicPortalContext } from './publicPortal'

describe('resolvePublicPortalContext', () => {
  it('mengirim state hanya ke endpoint publik portal hosted', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      displayName: 'Wi-Fi Tamu',
      logoUrl: null,
      redirectUrl: null,
      clientMac: null,
      clientIp: null,
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await resolvePublicPortalContext({ state: 'state-aman' })

    expect(fetchMock).toHaveBeenCalledWith('/api/public/hotspot/portal-context/resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ state: 'state-aman' }),
    })
  })
})
