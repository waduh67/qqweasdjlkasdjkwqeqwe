import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, refreshSession, tokenStore } from '../api/client'
import type { Profile, TokenResponse } from '../api/types'

interface AuthState {
  user: Profile | null
  loading: boolean
  /** `otpCode` diisi hanya pada percobaan kedua, setelah server minta faktor kedua. */
  login: (email: string, password: string, otpCode?: string) => Promise<void>
  logout: () => Promise<void>
  /** Ambil ulang profil (izin efektif terbaru) tanpa menunggu token kedaluwarsa. */
  refreshProfile: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(true)

  const refreshProfile = useCallback(async () => {
    setUser(await api.get<Profile>('/api/me'))
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
    void restore()
    return () => {
      cancelled = true
    }
  }, [])

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
    () => ({ user, loading, login, logout, refreshProfile }),
    [user, loading, login, logout, refreshProfile],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
