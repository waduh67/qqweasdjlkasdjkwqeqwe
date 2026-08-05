import type { PortalTokenResponse } from './portalApi'

/**
 * Klien HTTP realm PORTAL pelanggan — SENGAJA terpisah dari klien operator
 * (`src/api/client.ts`): token store, kunci localStorage, dan endpoint refresh berbeda,
 * agar sesi operator & pelanggan tak pernah saling mencampuri. Isolasi ini juga yang
 * memungkinkan portal dipindah ke domain sendiri nanti tanpa menyentuh konsol operator.
 *
 * Access token di memori; refresh token di localStorage (`ftth.portal.refreshToken`).
 * Saat server balas 401, klien mencoba sekali merotasi lalu mengulang request.
 */

const REFRESH_KEY = 'ftth.portal.refreshToken'

let accessToken: string | null = null
let onSessionLost: (() => void) | null = null
let refreshInFlight: Promise<PortalTokenResponse | null> | null = null

export const portalTokenStore = {
  setAccessToken(token: string | null) {
    accessToken = token
  },
  getAccessToken(): string | null {
    return accessToken
  },
  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_KEY)
  },
  setRefreshToken(token: string | null) {
    if (token) localStorage.setItem(REFRESH_KEY, token)
    else localStorage.removeItem(REFRESH_KEY)
  },
  clear() {
    accessToken = null
    localStorage.removeItem(REFRESH_KEY)
  },
  onSessionLost(handler: () => void) {
    onSessionLost = handler
  },
}

export class PortalApiError extends Error {
  status: number
  errors?: Record<string, string>

  constructor(status: number, message: string, errors?: Record<string, string>) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

async function parseError(response: Response): Promise<PortalApiError> {
  let detail = response.statusText
  let errors: Record<string, string> | undefined
  try {
    const body = await response.json()
    detail = body.detail ?? body.message ?? detail
    errors = body.errors
  } catch {
    /* respons tanpa body JSON */
  }
  return new PortalApiError(response.status, detail, errors)
}

/**
 * Rotasi refresh token portal → sesi baru, ter-dedupe (single-flight) — sama seperti
 * klien operator, refresh token portal sekali-pakai jadi rotasi paralel harus berbagi
 * satu panggilan. Mengembalikan token+profil baru, atau `null` bila gagal/tak ada token.
 */
export function refreshPortalSession(): Promise<PortalTokenResponse | null> {
  if (refreshInFlight) return refreshInFlight

  const refreshToken = portalTokenStore.getRefreshToken()
  if (!refreshToken) return Promise.resolve(null)

  refreshInFlight = (async () => {
    try {
      const response = await fetch('/api/portal/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!response.ok) return null

      const tokens: PortalTokenResponse = await response.json()
      accessToken = tokens.accessToken
      portalTokenStore.setRefreshToken(tokens.refreshToken)
      return tokens
    } catch {
      return null
    } finally {
      refreshInFlight = null
    }
  })()

  return refreshInFlight
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  return fetch(path, { ...init, headers })
}

async function sendWithRefresh(path: string, init: RequestInit): Promise<Response> {
  let response = await send(path, init)

  if (response.status === 401 && portalTokenStore.getRefreshToken()) {
    if (await refreshPortalSession()) {
      response = await send(path, init)
    } else {
      portalTokenStore.clear()
      onSessionLost?.()
    }
  }
  return response
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await sendWithRefresh(path, init)
  if (!response.ok) throw await parseError(response)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const portalApiClient = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
}
