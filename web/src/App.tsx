import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Suspense, lazy, type ReactNode } from 'react'
import { AuthProvider } from './auth/AuthContext'
import { ToastProvider } from './components/ui'
import { useAuth } from './auth/useAuth'
import { useCan } from './auth/useCan'
import { Layout } from './components/Layout'
import { PlatformLayout } from './components/PlatformLayout'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'
import { DashboardPage } from './pages/DashboardPage'
import { PlatformDashboardPage } from './pages/PlatformDashboardPage'
import { InventoryPage } from './pages/InventoryPage'
import { OltDetailPage } from './pages/OltDetailPage'
import { CustomersPage } from './pages/CustomersPage'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { InvoicesPage } from './pages/InvoicesPage'
import { ExpressPsbPage } from './pages/ExpressPsbPage'
import { ImportPppoePage } from './pages/ImportPppoePage'
import { MonitoringPage } from './pages/MonitoringPage'
import { ProvisioningPage } from './pages/ProvisioningPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { WorkOrdersPage } from './pages/WorkOrdersPage'
import { MyWorkOrdersPage } from './pages/MyWorkOrdersPage'
import { WorkOrderDetailPage } from './pages/WorkOrderDetailPage'
import { CatalogPage } from './pages/CatalogPage'
import { BngPage } from './pages/BngPage'
import { VpnPage } from './pages/VpnPage'
import { VpnServersPage } from './pages/VpnServersPage'

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
import { NotificationSettingsPage } from './pages/NotificationSettingsPage'
import { PaymentGatewaySettingsPage } from './pages/PaymentGatewaySettingsPage'
import { PlatformBillingSettingsPage } from './pages/PlatformBillingSettingsPage'
import { SubscriptionPage } from './pages/SubscriptionPage'
import { ReportsPage } from './pages/ReportsPage'
import { PortalApp } from './portal/PortalApp'

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

/**
 * Setelah login, Platform admin mendarat di shell platform (`/platform`); operator
 * tenant ke beranda tenant (`/`). Pengalihan hanya di sini — bukan di `/` — supaya
 * beranda tenant tetap bisa dibuka platform admin (mis. lewat "Tampilan Tenant")
 * tanpa terlempar balik ke `/platform`.
 */
function LoginRoute() {
  const { user, loading } = useAuth()
  const { isPlatformAdmin } = useCan()
  if (loading) return <div className="login-shell muted">Memuat…</div>
  if (!user) return <LoginPage />
  return <Navigate to={isPlatformAdmin ? '/platform' : '/'} replace />
}

/**
 * Penjaga area `/platform/*`: hanya Platform admin (SaaS super-admin). Bukan
 * penegak keamanan — server tetap otoritatif — melainkan agar operator tenant biasa
 * tak nyasar ke shell platform. Non-platform-admin dialihkan ke beranda tenant.
 */
function RequirePlatformAdmin({ children }: { children: ReactNode }) {
  const { isPlatformAdmin } = useCan()
  if (!isPlatformAdmin) return <Navigate to="/" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        {/* Realm pelanggan (`/portal/*`) di-mount TERPISAH dari konsol operator: provider,
            klien HTTP, dan token store sendiri (lihat PortalApp). Dua sesi tak bersinggungan. */}
        <Routes>
          <Route path="/portal/*" element={<PortalApp />} />
          <Route path="/*" element={<OperatorApp />} />
        </Routes>
      </ToastProvider>
    </BrowserRouter>
  )
}

/** Konsol OPERATOR/PLATFORM — seluruh rute tenant di dalam satu `AuthProvider`. */
function OperatorApp() {
  return (
    <AuthProvider>
      <Routes>
          <Route path="/login" element={<LoginRoute />} />
          {/* Pendaftaran mandiri ISP — publik, di luar guard auth. */}
          <Route path="/signup" element={<SignupPage />} />
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
              path="olts/:id"
              element={
                <RequirePermission permission="network.olt.view">
                  <OltDetailPage />
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
              path="express-psb"
              element={
                <RequirePermission permission="customer.customer.create">
                  <ExpressPsbPage />
                </RequirePermission>
              }
            />
            <Route
              path="import-pppoe"
              element={
                <RequirePermission permission="customer.customer.create">
                  <ImportPppoePage />
                </RequirePermission>
              }
            />
            <Route
              path="catalog"
              element={
                <RequirePermission permission="catalog.plan.view">
                  <CatalogPage />
                </RequirePermission>
              }
            />
            <Route
              path="invoices"
              element={
                <RequirePermission permission="billing.invoice.view">
                  <InvoicesPage />
                </RequirePermission>
              }
            />
            <Route
              path="bras"
              element={
                <RequirePermission permission="bng.nas.view">
                  <BngPage />
                </RequirePermission>
              }
            />
            {/* Kompat: URL lama /bng dialihkan ke /bras (label & slug kini selaras). */}
            <Route path="bng" element={<Navigate to="/bras" replace />} />
            <Route
              path="vpn"
              element={
                <RequirePermission permission="vpn.peer.view">
                  <VpnPage />
                </RequirePermission>
              }
            />
            {/* Pindah ke area platform; jaga bookmark lama. */}
            <Route path="vpn-servers" element={<Navigate to="/platform/vpn-servers" replace />} />
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
                <RequirePermission permission="workorder.dashboard.view">
                  <WorkOrdersPage />
                </RequirePermission>
              }
            />
            <Route
              path="work-orders/:id"
              element={
                <RequirePermission permission="workorder.order.view">
                  <WorkOrderDetailPage backTo="/work-orders" backLabel="Work Order" />
                </RequirePermission>
              }
            />
            <Route
              path="my-work-orders"
              element={
                <RequirePermission permission="workorder.order.field">
                  <MyWorkOrdersPage />
                </RequirePermission>
              }
            />
            <Route
              path="my-work-orders/:id"
              element={
                <RequirePermission permission="workorder.order.view">
                  <WorkOrderDetailPage backTo="/my-work-orders" backLabel="Tugas Saya" />
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
            {/* Pindah ke area platform; jaga bookmark lama. */}
            <Route path="tenants" element={<Navigate to="/platform/tenants" replace />} />
            <Route path="platform-billing" element={<Navigate to="/platform/billing" replace />} />
            <Route
              path="notifications"
              element={
                <RequirePermission permission="notification.settings.view">
                  <NotificationSettingsPage />
                </RequirePermission>
              }
            />
            <Route
              path="payment-gateway"
              element={
                <RequirePermission permission="billing.gateway.view">
                  <PaymentGatewaySettingsPage />
                </RequirePermission>
              }
            />
            <Route
              path="subscription"
              element={
                <RequirePermission permission="billing.subscription.view">
                  <SubscriptionPage />
                </RequirePermission>
              }
            />
            <Route
              path="reports"
              element={
                <RequirePermission permission="report.view">
                  <ReportsPage />
                </RequirePermission>
              }
            />
          </Route>

          {/* Area khusus Platform admin (SaaS) — shell & dashboard terpisah dari tenant.
              Halaman platform yang dipakai ulang tak berubah, hanya di-mount di sini. */}
          <Route
            path="platform"
            element={
              <RequireAuth>
                <RequirePlatformAdmin>
                  <PlatformLayout />
                </RequirePlatformAdmin>
              </RequireAuth>
            }
          >
            <Route index element={<PlatformDashboardPage />} />
            <Route
              path="tenants"
              element={
                <RequirePermission permission="platform.tenant.view">
                  <TenantsPage />
                </RequirePermission>
              }
            />
            <Route
              path="billing"
              element={
                <RequirePermission permission="platform.billing.view">
                  <PlatformBillingSettingsPage />
                </RequirePermission>
              }
            />
            <Route
              path="vpn-servers"
              element={
                <RequirePermission permission="vpn.server.view">
                  <VpnServersPage />
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
              path="audit"
              element={
                <RequirePermission permission="audit.log.view">
                  <AuditPage />
                </RequirePermission>
              }
            />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}
