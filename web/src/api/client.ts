import type { TokenResponse } from './types'

/**
 * Klien HTTP tipis untuk ftth-server.
 *
 * Access token disimpan di memori saja; refresh token di localStorage supaya
 * sesi bertahan saat reload. Saat server membalas 401, klien mencoba sekali
 * merotasi refresh token lalu mengulang request — transparan bagi pemanggil.
 */

const REFRESH_KEY = 'ftth.refreshToken'

let accessToken: string | null = null
let onSessionLost: (() => void) | null = null
let onSubscriptionLocked: (() => void) | null = null

/**
 * Rotasi refresh token yang sedang berjalan. Refresh token bersifat SEKALI-PAKAI —
 * server me-revoke token yang dipakai lalu menerbitkan yang baru. Jadi bila dua
 * pemanggil merotasi berbarengan (StrictMode memanggil restore dua kali, atau
 * boot-restore beradu dengan retry-401), keduanya HARUS berbagi satu panggilan;
 * kalau tidak, panggilan kedua membawa token yang sudah di-revoke → 401 → sesi
 * terhapus. Promise ini men-serialisasi mereka.
 */
let refreshInFlight: Promise<TokenResponse | null> | null = null

export const tokenStore = {
  setAccessToken(token: string | null) {
    accessToken = token
  },
  /**
   * Dipakai MapLibre lewat `transformRequest`: request tile berangkat dari dalam
   * pustaka peta, bukan lewat `api` di bawah, sehingga headernya harus dipasang
   * sendiri. Endpoint tile tetap butuh autentikasi seperti endpoint lain.
   */
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
  /**
   * Dipanggil saat server menolak sebuah aksi tulis karena langganan aplikasi menunggak
   * (402 `SUBSCRIPTION_LOCKED`). Ada dua sebab kenapa ini perlu kait sendiri, bukan sekadar
   * ditangani halaman pemanggil: (1) status kunci bisa berubah di sisi server setelah klien
   * memuat profilnya, jadi penolakan inilah kabar pertama yang sampai; (2) kuncinya berlaku
   * global, jadi yang harus bereaksi adalah kerangka aplikasi (banner + tombol mati),
   * bukan satu layar yang kebetulan sedang dibuka.
   */
  onSubscriptionLocked(handler: () => void) {
    onSubscriptionLocked = handler
  },
}

export class ApiError extends Error {
  status: number
  errors?: Record<string, string>
  /**
   * Kode mesin dari `ProblemDetail` server, bila ada. Dipakai untuk kegagalan yang
   * BUKAN pesan-untuk-dibaca melainkan instruksi bagi UI — mis. `TWO_FACTOR_REQUIRED`
   * yang berarti "tampilkan kolom kode", bukan "kredensial salah".
   */
  code?: string

  constructor(status: number, message: string, errors?: Record<string, string>, code?: string) {
    super(message)
    this.status = status
    this.errors = errors
    this.code = code
  }
}

async function parseError(response: Response): Promise<ApiError> {
  let detail = response.statusText
  let errors: Record<string, string> | undefined
  let code: string | undefined
  try {
    const body = await response.json()
    detail = body.detail ?? body.message ?? detail
    errors = body.errors
    code = body.code
  } catch {
    /* respons tanpa body JSON */
  }
  const error = new ApiError(response.status, detail, errors, code)
  // Dibunyikan di sini, di satu-satunya tempat semua kegagalan lewat, lalu error-nya TETAP
  // dilempar: halaman pemanggil tetap memunculkan toast-nya sendiri seperti biasa.
  if (error.status === 402 && error.code === 'SUBSCRIPTION_LOCKED') onSubscriptionLocked?.()
  return error
}

/**
 * Rotasi refresh token → sesi baru, ter-dedupe (single-flight). Pemanggil yang
 * datang saat rotasi sedang berjalan ikut menunggu promise yang sama alih-alih
 * menembak fetch kedua dengan token yang sama. Mengembalikan token+profil baru,
 * atau `null` bila tak ada refresh token / rotasi gagal.
 */
export function refreshSession(): Promise<TokenResponse | null> {
  if (refreshInFlight) return refreshInFlight

  const refreshToken = tokenStore.getRefreshToken()
  if (!refreshToken) return Promise.resolve(null)

  refreshInFlight = (async () => {
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!response.ok) return null

      const tokens: TokenResponse = await response.json()
      accessToken = tokens.accessToken
      tokenStore.setRefreshToken(tokens.refreshToken)
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
  // Hanya body JSON (string) yang diberi Content-Type di sini; untuk FormData
  // biarkan browser memasang `multipart/form-data` beserta boundary-nya sendiri.
  if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  return fetch(path, { ...init, headers })
}

/** Kirim request + rotasi refresh token sekali bila 401; kembalikan Response mentah. */
async function sendWithRefresh(path: string, init: RequestInit): Promise<Response> {
  let response = await send(path, init)

  if (response.status === 401 && tokenStore.getRefreshToken()) {
    if (await refreshSession()) {
      response = await send(path, init)
    } else {
      tokenStore.clear()
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

/**
 * Ambil konten biner terautentikasi (mis. foto bukti) sebagai Blob. Tag `<img>`
 * biasa tak bisa mengirim header Bearer, jadi konten diambil di sini lalu dijadikan
 * object URL oleh pemanggil.
 */
async function requestBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  const response = await sendWithRefresh(path, init)
  if (!response.ok) throw await parseError(response)
  return response.blob()
}

export const api = {
  request: <T>(path: string, init: RequestInit = {}) => request<T>(path, init),
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  blob: (path: string) => requestBlob(path),
  postForm: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
  putForm: <T>(path: string, form: FormData) => request<T>(path, { method: 'PUT', body: form }),
}
