import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, refreshSession, tokenStore } from './client'

/**
 * Rotasi refresh token adalah satu-satunya bagian klien HTTP yang punya keadaan lintas
 * panggilan — dan refresh token bersifat sekali-pakai, jadi salah sedikit di sini
 * berarti pengguna terlempar ke halaman login di tengah kerja tanpa sebab yang terlihat.
 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const SESSION = {
  accessToken: 'akses-baru',
  refreshToken: 'refresh-baru',
  user: { id: 'u1' },
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  tokenStore.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  tokenStore.clear()
})

describe('api.request', () => {
  it('menyertakan Bearer token pada request', async () => {
    tokenStore.setAccessToken('akses-lama')
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }))

    await api.get('/api/ping')

    const headers = (fetchMock.mock.calls[0][1] as RequestInit).headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer akses-lama')
  })

  it('memasang Content-Type JSON untuk body string, tidak untuk FormData', async () => {
    // Body Response hanya bisa dibaca sekali, jadi tiap panggilan harus dapat objek baru.
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({})))

    await api.post('/api/things', { a: 1 })
    await api.postForm('/api/upload', new FormData())

    const jsonHeaders = (fetchMock.mock.calls[0][1] as RequestInit).headers as Headers
    const formHeaders = (fetchMock.mock.calls[1][1] as RequestInit).headers as Headers
    expect(jsonHeaders.get('Content-Type')).toBe('application/json')
    // Boundary multipart hanya browser yang tahu — memaksa header di sini merusaknya.
    expect(formHeaders.get('Content-Type')).toBeNull()
  })

  it('mengubah ProblemDetail server jadi ApiError yang membawa status & kode', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ detail: 'Kredensial salah', code: 'TWO_FACTOR_REQUIRED' }, 400),
    )

    const err = (await api.get('/api/auth/login').catch((e) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(400)
    expect(err.message).toBe('Kredensial salah')
    expect(err.code).toBe('TWO_FACTOR_REQUIRED')
  })

  it('mengembalikan undefined untuk 204, bukan gagal mem-parse body kosong', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(api.del('/api/things/1')).resolves.toBeUndefined()
  })
})

describe('rotasi 401', () => {
  it('merotasi refresh token lalu mengulang request yang gagal', async () => {
    tokenStore.setRefreshToken('refresh-lama')
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ detail: 'kedaluwarsa' }, 401))
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValueOnce(jsonResponse({ name: 'Budi' }))

    await expect(api.get<{ name: string }>('/api/me')).resolves.toEqual({ name: 'Budi' })

    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/me', '/api/auth/refresh', '/api/me'])
    // Token hasil rotasi dipakai pada percobaan kedua, bukan token lama yang sudah mati.
    const retryHeaders = (fetchMock.mock.calls[2][1] as RequestInit).headers as Headers
    expect(retryHeaders.get('Authorization')).toBe('Bearer akses-baru')
    expect(tokenStore.getRefreshToken()).toBe('refresh-baru')
  })

  it('membersihkan sesi & memberi tahu aplikasi saat rotasi ikut ditolak', async () => {
    const lost = vi.fn()
    tokenStore.onSessionLost(lost)
    tokenStore.setRefreshToken('refresh-mati')
    fetchMock
      .mockResolvedValueOnce(jsonResponse({}, 401))
      .mockResolvedValueOnce(jsonResponse({ detail: 'ditolak' }, 401))

    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
    expect(lost).toHaveBeenCalledOnce()
    expect(tokenStore.getRefreshToken()).toBeNull()
    tokenStore.onSessionLost(() => {})
  })

  it('tak menembak /auth/refresh sama sekali bila tak ada refresh token', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ detail: 'anonim' }, 401))

    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  // Refresh token sekali-pakai: dua rotasi berbarengan berarti yang kedua membawa token
  // yang baru saja di-revoke → 401 → sesi terhapus. Keduanya harus berbagi satu panggilan.
  it('men-dedupe rotasi yang berbarengan jadi satu panggilan', async () => {
    tokenStore.setRefreshToken('refresh-lama')
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(path === '/api/auth/refresh' ? jsonResponse(SESSION) : jsonResponse({ ok: true })),
    )

    const [a, b] = await Promise.all([refreshSession(), refreshSession()])

    expect(a).toBe(b)
    expect(fetchMock.mock.calls.filter((c) => c[0] === '/api/auth/refresh')).toHaveLength(1)
  })

  it('membebaskan kunci rotasi setelah selesai agar rotasi berikutnya tetap bisa jalan', async () => {
    tokenStore.setRefreshToken('refresh-lama')
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(SESSION)))

    await refreshSession()
    await refreshSession()

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
