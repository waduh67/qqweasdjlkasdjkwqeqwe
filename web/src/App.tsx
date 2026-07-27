import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Suspense, lazy, type ReactNode } from 'react'
import { AuthProvider } from './auth/AuthContext'
import { ToastProvider } from './components/ui'
import { useAuth } from './auth/useAuth'
import { useCan } from './auth/useCan'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { InventoryPage } from './pages/InventoryPage'
import { CustomersPage } from './pages/CustomersPage'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { MonitoringPage } from './pages/MonitoringPage'
import { ProvisioningPage } from './pages/ProvisioningPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { WorkOrdersPage } from './pages/WorkOrdersPage'
import { BngPage } from './pages/BngPage'
import { VpnPage } from './pages/VpnPage'

/**
 * Halaman peta dimuat terpisah: MapLibre menyumbang sebagian besar ukuran
 * bundel, dan tidak ada alasan menyeretnya ikut saat pengguna baru membuka
 * layar login.
 */
const MapPage = lazy(() => import('./pages/MapPage').then((m) => ({ default: m.MapPage })))
import { UsersPage } from './pages/UsersPage'
import { RolesPage } from './pages/RolesPage'
import { AreasPage } from './pages/AreasPage'
import { AuditPage } from './pages/AuditPage'
import { TenantsPage } from './pages/TenantsPage'

/** Menahan rute sampai sesi dipulihkan, lalu mengarahkan ke login bila belum masuk. */
function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (!user) return <Navigate to="/login" replace />
  return <>{children}</>
}

/** Guard berbasis izin — server tetap penegak sebenarnya, ini demi UX. */
function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const { can } = useCan()
  if (!can(permission)) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">
          Kamu tidak punya izin <span className="badge">{permission}</span>.
        </p>
      </div>
    )
  }
  return <>{children}</>
}

function LoginRoute() {
  const { user, loading } = useAuth()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  return user ? <Navigate to="/" replace /> : <LoginPage />
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Routes>
          <Route path="/login" element={<LoginRoute />} />
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route index element={<DashboardPage />} />
            <Route
              path="map"
              element={
                <RequirePermission permission="gis.map.view">
                  <Suspense fallback={<div className="card muted">Memuat peta…</div>}>
                    <MapPage />
                  </Suspense>
                </RequirePermission>
              }
            />
            <Route
              path="inventory"
              element={
                <RequirePermission permission="network.odp.view">
                  <InventoryPage />
                </RequirePermission>
              }
            />
            <Route
              path="customers"
              element={
                <RequirePermission permission="customer.customer.view">
                  <CustomersPage />
                </RequirePermission>
              }
            />
            <Route
              path="customers/:id"
              element={
                <RequirePermission permission="customer.customer.view">
                  <CustomerDetailPage />
                </RequirePermission>
              }
            />
            <Route
              path="bng"
              element={
                <RequirePermission permission="bng.plan.view">
                  <BngPage />
                </RequirePermission>
              }
            />
            <Route
              path="vpn"
              element={
                <RequirePermission permission="vpn.server.view">
                  <VpnPage />
                </RequirePermission>
              }
            />
            <Route
              path="monitoring"
              element={
                <RequirePermission permission="monitoring.dashboard.view">
                  <MonitoringPage />
                </RequirePermission>
              }
            />
            <Route
              path="provisioning"
              element={
                <RequirePermission permission="monitoring.provisioning.view">
                  <ProvisioningPage />
                </RequirePermission>
              }
            />
            <Route
              path="incidents"
              element={
                <RequirePermission permission="incident.ticket.view">
                  <IncidentsPage />
                </RequirePermission>
              }
            />
            <Route
              path="work-orders"
              element={
                <RequirePermission permission="workorder.order.view">
                  <WorkOrdersPage />
                </RequirePermission>
              }
            />
            <Route
              path="users"
              element={
                <RequirePermission permission="iam.user.view">
                  <UsersPage />
                </RequirePermission>
              }
            />
            <Route
              path="roles"
              element={
                <RequirePermission permission="iam.role.view">
                  <RolesPage />
                </RequirePermission>
              }
            />
            <Route
              path="areas"
              element={
                <RequirePermission permission="iam.area.view">
                  <AreasPage />
                </RequirePermission>
              }
            />
            <Route
              path="audit"
              element={
                <RequirePermission permission="audit.log.view">
                  <AuditPage />
                </RequirePermission>
              }
            />
            <Route
              path="tenants"
              element={
                <RequirePermission permission="platform.tenant.view">
                  <TenantsPage />
                </RequirePermission>
              }
            />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}
