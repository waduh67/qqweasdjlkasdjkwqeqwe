import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { portalTokenStore, refreshPortalSession } from './portalClient'
import { portalLogin, portalLogout, type PortalProfile } from './portalApi'

interface PortalAuthState {
  customer: PortalProfile | null
  loading: boolean
  login: (tenant: string, login: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const PortalAuthContext = createContext<PortalAuthState | null>(null)

/**
 * Konteks sesi realm PORTAL — cermin `AuthProvider` operator, tapi sepenuhnya terisolasi
 * (klien, token store, endpoint sendiri). Dipulihkan dari `ftth.portal.refreshToken` saat
 * mount, jadi pelanggan tetap login setelah reload.
 */
export function PortalAuthProvider({ children }: { children: ReactNode }) {
  const [customer, setCustomer] = useState<PortalProfile | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false

    async function restore() {
      // Single-flight bersama: StrictMode memanggil dua kali; refresh token sekali-pakai
      // tak boleh "dibelanjakan dua kali".
      const tokens = await refreshPortalSession()
      if (tokens) {
        if (!cancelled) setCustomer(tokens.customer)
      } else {
        portalTokenStore.clear()
      }
      if (!cancelled) setLoading(false)
    }

    portalTokenStore.onSessionLost(() => setCustomer(null))
    void restore()
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (tenant: string, loginId: string, password: string) => {
    const tokens = await portalLogin(tenant, loginId, password)
    portalTokenStore.setAccessToken(tokens.accessToken)
    portalTokenStore.setRefreshToken(tokens.refreshToken)
    setCustomer(tokens.customer)
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = portalTokenStore.getRefreshToken()
    if (refreshToken) {
      try {
        await portalLogout(refreshToken)
      } catch {
        /* sesi tetap dibersihkan di sisi klien */
      }
    }
    portalTokenStore.clear()
    setCustomer(null)
  }, [])

  const value = useMemo<PortalAuthState>(
    () => ({ customer, loading, login, logout }),
    [customer, loading, login, logout],
  )

  return <PortalAuthContext.Provider value={value}>{children}</PortalAuthContext.Provider>
}

export function usePortalAuth() {
  const context = useContext(PortalAuthContext)
  if (!context) throw new Error('usePortalAuth harus dipakai di dalam <PortalAuthProvider>')
  return context
}
