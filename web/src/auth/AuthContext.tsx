import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, refreshSession, tokenStore } from '../api/client'
import { getSubscriptionLock, type SubscriptionLockView } from '../api/subscription'
import type { Profile, TokenResponse } from '../api/types'

interface AuthState {
  user: Profile | null
  loading: boolean
  /** `otpCode` diisi hanya pada percobaan kedua, setelah server minta faktor kedua. */
  login: (email: string, password: string, otpCode?: string) => Promise<void>
  logout: () => Promise<void>
  /** Ambil ulang profil (izin efektif terbaru) tanpa menunggu token kedaluwarsa. */
  refreshProfile: () => Promise<void>
  /**
   * Keadaan kunci baca-saja langganan aplikasi; null selama belum terbaca (atau untuk platform
   * admin, yang tenant-nya tak pernah terkunci). Dipakai banner dan halaman `/subscription`.
   */
  subscriptionLock: SubscriptionLockView | null
  /** Ringkasan `subscriptionLock.locked` — konsol sedang baca-saja karena langganan menunggak. */
  readOnly: boolean
  /** Baca ulang status kunci; dipanggil setelah pembayaran berhasil dan saat server balas 402. */
  refreshSubscriptionLock: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(true)
  const [subscriptionLock, setSubscriptionLock] = useState<SubscriptionLockView | null>(null)

  const refreshProfile = useCallback(async () => {
    setUser(await api.get<Profile>('/api/me'))
  }, [])

  /**
   * Gagal membaca status kunci dibiarkan diam dan konsol tetap terbuka. Penegakan yang
   * sebenarnya ada di server (402 `SUBSCRIPTION_LOCKED`); mengunci konsol karena satu
   * request gagal hanya akan memblokir ISP yang sebetulnya lunas.
   */
  const refreshSubscriptionLock = useCallback(async () => {
    try {
      setSubscriptionLock(await getSubscriptionLock())
    } catch {
      /* diabaikan sengaja — server tetap penjaga terakhir */
    }
  }, [])

  // Pulihkan sesi dari refresh token saat aplikasi dimuat.
  useEffect(() => {
    let cancelled = false

    async function restore() {
      // Rotasi lewat single-flight bersama: StrictMode memanggil effect ini dua
      // kali, tapi keduanya berbagi satu rotasi sehingga refresh token sekali-pakai
      // tak "dibelanjakan dua kali" (yang tadinya bikin ke-logout tiap refresh).
      const tokens = await refreshSession()
      if (tokens) {
        if (!cancelled) setUser(tokens.user)
      } else {
        tokenStore.clear()
      }
      if (!cancelled) setLoading(false)
    }

    tokenStore.onSessionLost(() => setUser(null))
    // Server bisa mengunci di tengah sesi (scheduler penagihan jalan tiap malam). Penolakan
    // 402 pertama itulah kabar pertama yang sampai ke klien — pakai untuk memuat ulang status.
    tokenStore.onSubscriptionLocked(() => void refreshSubscriptionLock())
    void restore()
    return () => {
      cancelled = true
    }
  }, [refreshSubscriptionLock])

  // Status kunci milik tenant, jadi ia diambil ulang setiap kali identitas penggunanya berganti.
  // Platform admin dilewati: tenant platform tak pernah punya langganan untuk ditunggak.
  useEffect(() => {
    if (!user || user.platformAdmin) {
      setSubscriptionLock(null)
      return
    }
    void refreshSubscriptionLock()
  }, [user, refreshSubscriptionLock])

  const login = useCallback(async (email: string, password: string, otpCode?: string) => {
    const tokens = await api.post<TokenResponse>('/api/auth/login', { email, password, otpCode })
    tokenStore.setAccessToken(tokens.accessToken)
    tokenStore.setRefreshToken(tokens.refreshToken)
    setUser(tokens.user)
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = tokenStore.getRefreshToken()
    if (refreshToken) {
      try {
        await api.post('/api/auth/logout', { refreshToken })
      } catch {
        /* sesi tetap dibersihkan di sisi klien */
      }
    }
    tokenStore.clear()
    setUser(null)
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      login,
      logout,
      refreshProfile,
      subscriptionLock,
      readOnly: subscriptionLock?.locked ?? false,
      refreshSubscriptionLock,
    }),
    [user, loading, login, logout, refreshProfile, subscriptionLock, refreshSubscriptionLock],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
