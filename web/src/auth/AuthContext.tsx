import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, tokenStore } from '../api/client'
import type { Profile, TokenResponse } from '../api/types'

interface AuthState {
  user: Profile | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
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
      const refreshToken = tokenStore.getRefreshToken()
      if (!refreshToken) {
        setLoading(false)
        return
      }
      try {
        const response = await fetch('/api/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        })
        if (!response.ok) throw new Error('sesi kedaluwarsa')
        const tokens: TokenResponse = await response.json()
        tokenStore.setAccessToken(tokens.accessToken)
        tokenStore.setRefreshToken(tokens.refreshToken)
        if (!cancelled) setUser(tokens.user)
      } catch {
        tokenStore.clear()
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    tokenStore.onSessionLost(() => setUser(null))
    void restore()
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await api.post<TokenResponse>('/api/auth/login', { email, password })
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
