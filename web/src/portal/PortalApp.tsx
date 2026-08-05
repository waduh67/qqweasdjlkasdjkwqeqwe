import { type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { PortalAuthProvider, usePortalAuth } from './PortalAuthContext'
import { PortalLoginPage } from './PortalLoginPage'
import { PortalDashboard } from './PortalDashboard'

/** Menahan rute sampai sesi portal dipulihkan, lalu arahkan ke login bila belum masuk. */
function RequirePortalAuth({ children }: { children: ReactNode }) {
  const { customer, loading } = usePortalAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (!customer) return <Navigate to="/portal/login" replace />
  return <>{children}</>
}

/** Di halaman login: kalau sudah masuk, lempar ke dasbor. */
function PortalLoginRoute() {
  const { customer, loading } = usePortalAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (customer) return <Navigate to="/portal" replace />
  return <PortalLoginPage />
}

/**
 * Realm PORTAL pelanggan (`/portal/*`) — subtree yang SEPENUHNYA terpisah dari konsol
 * operator: provider, klien HTTP, dan token store sendiri. Dipisah di level rute (bukan
 * di dalam `AuthProvider` operator) supaya kedua sesi tak pernah bersinggungan, dan agar
 * portal gampang diangkat ke domain sendiri nanti — cukup mount `<PortalApp>` di root app.
 */
export function PortalApp() {
  return (
    <PortalAuthProvider>
      <Routes>
        <Route path="login" element={<PortalLoginRoute />} />
        <Route
          index
          element={
            <RequirePortalAuth>
              <PortalDashboard />
            </RequirePortalAuth>
          }
        />
        <Route path="*" element={<Navigate to="/portal" replace />} />
      </Routes>
    </PortalAuthProvider>
  )
}
