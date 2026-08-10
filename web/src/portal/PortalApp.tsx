import { type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { PortalAuthProvider, usePortalAuth } from './PortalAuthContext'
import { PortalLoginPage } from './PortalLoginPage'
import { PortalForgotPasswordPage } from './PortalForgotPasswordPage'
import { PortalLayout } from './PortalLayout'
import { PortalRingkasanPage } from './PortalRingkasanPage'
import { PortalTagihanPage } from './PortalTagihanPage'
import { PortalKoneksiPage } from './PortalKoneksiPage'
import { PortalProfilPage } from './PortalProfilPage'
import { BantuanTab } from './PortalHelpTab'

/** Menahan rute sampai sesi portal dipulihkan, lalu arahkan ke login bila belum masuk. */
function RequirePortalAuth({ children }: { children: ReactNode }) {
  const { customer, loading } = usePortalAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (!customer) return <Navigate to="/portal/login" replace />
  return <>{children}</>
}

/**
 * Rute yang hanya masuk akal saat BELUM login (masuk, lupa password): kalau sesi sudah ada,
 * lempar ke dasbor — biar tak ada layar "lupa password" nongol untuk orang yang sudah masuk
 * (ganti password buat mereka ada di menu Profil, lewat password lama).
 */
function PortalGuestRoute({ children }: { children: ReactNode }) {
  const { customer, loading } = usePortalAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (customer) return <Navigate to="/portal" replace />
  return <>{children}</>
}

/**
 * Realm PORTAL pelanggan (`/portal/*`) — subtree yang SEPENUHNYA terpisah dari konsol
 * operator: provider, klien HTTP, dan token store sendiri. Dipisah di level rute (bukan
 * di dalam `AuthProvider` operator) supaya kedua sesi tak pernah bersinggungan, dan agar
 * portal gampang diangkat ke domain sendiri nanti — cukup mount `<PortalApp>` di root app.
 *
 * Isi portal BERUTE (bukan state tab lokal) karena sidebar bersama ([SidebarNav]) menandai
 * posisi lewat `NavLink`: tiap menu wajib punya alamatnya sendiri supaya bisa ditandai aktif,
 * ditautkan dari halaman lain, di-bookmark, dan dibuka ulang oleh tombol back peramban.
 */
export function PortalApp() {
  return (
    <PortalAuthProvider>
      <Routes>
        <Route
          path="login"
          element={
            <PortalGuestRoute>
              <PortalLoginPage />
            </PortalGuestRoute>
          }
        />
        <Route
          path="lupa-password"
          element={
            <PortalGuestRoute>
              <PortalForgotPasswordPage />
            </PortalGuestRoute>
          }
        />
        {/* Rute berlayout: profil/tagihan/koneksi ditarik sekali di `PortalLayout` lalu
            dibagikan ke anaknya lewat outlet context — pindah menu tak menembak ulang API. */}
        <Route
          element={
            <RequirePortalAuth>
              <PortalLayout />
            </RequirePortalAuth>
          }
        >
          <Route index element={<PortalRingkasanPage />} />
          <Route path="tagihan" element={<PortalTagihanPage />} />
          <Route path="koneksi" element={<PortalKoneksiPage />} />
          <Route path="bantuan" element={<BantuanTab />} />
          <Route path="profil" element={<PortalProfilPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/portal" replace />} />
      </Routes>
    </PortalAuthProvider>
  )
}
