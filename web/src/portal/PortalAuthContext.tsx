import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { portalTokenStore, refreshPortalSession } from './portalClient'
import { portalLogin, portalLogout, type PortalProfile, type PortalTenantChoice } from './portalApi'

/**
 * Hasil `login` bagi halaman: sudah masuk, atau perlu memilih ISP dulu. Bentuk union ini
 * sengaja diteruskan apa adanya ke halaman — konteks tak menyimpan "login yang tertunda",
 * supaya password tak perlu hidup lebih lama dari satu penekanan tombol.
 */
export type PortalLoginOutcome = { done: true } | { done: false; choices: PortalTenantChoice[] }

interface PortalAuthState {
  customer: PortalProfile | null
  loading: boolean
  login: (identifier: string, password: string, tenant?: string) => Promise<PortalLoginOutcome>
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

  const login = useCallback(
    async (identifier: string, password: string, tenant?: string): Promise<PortalLoginOutcome> => {
      const result = await portalLogin(identifier, password, tenant)
      if (result.status === 'CHOOSE_TENANT' || !result.tokens) {
        return { done: false, choices: result.choices }
      }
      portalTokenStore.setAccessToken(result.tokens.accessToken)
      portalTokenStore.setRefreshToken(result.tokens.refreshToken)
      setCustomer(result.tokens.customer)
      return { done: true }
    },
    [],
  )

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
