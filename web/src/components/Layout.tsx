import { useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { ThemeToggle } from './ThemeToggle'
import { EnvSwitcher } from './EnvSwitcher'
import { Breadcrumbs } from './Breadcrumbs'
import { SidebarNav, type NavGroup } from './SidebarNav'
import {
  IconAlert,
  IconArea,
  IconAudit,
  IconChart,
  IconCustomers,
  IconDashboard,
  IconGauge,
  IconInbox,
  IconInventory,
  IconLogout,
  IconMap,
  IconMonitor,
  IconPackage,
  IconPlus,
  IconReceipt,
  IconRoute,
  IconShield,
  IconSidebar,
  IconUsers,
  IconWorkOrder,
} from './icons'
/**
 * Navigasi dikelompokkan menurut alur kerja (operasi jaringan vs administrasi),
 * bukan sekadar daftar datar — pada belasan menu, pengelompokan membuat operator
 * menemukan yang dicari tanpa memindai satu per satu. Tiap item tetap difilter
 * izin, cermin RBAC di server. Seksi berlabel bisa diciutkan (lihat [SidebarNav]).
 */
const GROUPS: NavGroup[] = [
  {
    label: null,
    items: [
      { to: '/', label: 'Dashboard', permission: null, icon: IconDashboard, end: true },
      { to: '/reports', label: 'Laporan', permission: 'reporting.report.view', icon: IconChart },
      { to: '/subscription', label: 'Langganan Aplikasi', permission: 'billing.subscription.view', icon: IconGauge },
    ],
  },
  {
    label: 'Jaringan',
    items: [
      { to: '/map', label: 'Peta Jaringan', permission: 'gis.map.view', icon: IconMap },
      { to: '/inventory', label: 'Inventory', permission: 'network.odp.view', icon: IconInventory },
      { to: '/bras', label: 'BRAS & RADIUS', permission: 'bng.nas.view', icon: IconGauge },
      { to: '/vpn', label: 'Akun VPN', permission: 'vpn.peer.view', icon: IconRoute },
      { to: '/monitoring', label: 'Monitoring', permission: 'monitoring.dashboard.view', icon: IconMonitor },
      { to: '/provisioning', label: 'Provisioning', permission: 'monitoring.provisioning.view', icon: IconInbox },
    ],
  },
  {
    label: 'Layanan Pelanggan',
    items: [
      { to: '/express-psb', label: 'PSB Ekspres', permission: 'customer.customer.create', icon: IconPlus },
      // Impor PPPoE tak lagi menu tersendiri — pintu masuknya kini tombol di halaman Pelanggan
      // (menyatu dengan rencana impor/ekspor pelanggan via CSV). Rute /import-pppoe tetap ada.
      { to: '/customers', label: 'Pelanggan', permission: 'customer.customer.view', icon: IconCustomers },
      { to: '/invoices', label: 'Tagihan', permission: 'billing.invoice.view', icon: IconReceipt },
      { to: '/catalog', label: 'Paket Internet', permission: 'catalog.plan.view', icon: IconPackage },
    ],
  },
  {
    label: 'Lapangan',
    items: [
      { to: '/incidents', label: 'Insiden', permission: 'incident.ticket.view', icon: IconAlert },
      // Papan dispatch (semua WO) di-gate izin dashboard = khusus operator; teknisi (yang
      // cuma punya `order.view`+`order.field`) tak melihatnya, hanya "Tugas Saya" di bawah.
      { to: '/work-orders', label: 'Work Order', permission: 'workorder.dashboard.view', icon: IconWorkOrder },
      { to: '/my-work-orders', label: 'Tugas Saya', permission: 'workorder.order.field', icon: IconInbox },
    ],
  },
  {
    label: 'Administrasi',
    items: [
      { to: '/users', label: 'Pengguna', permission: 'iam.user.view', icon: IconUsers },
      { to: '/roles', label: 'Role & Izin', permission: 'iam.role.view', icon: IconShield },
      { to: '/areas', label: 'Area', permission: 'iam.area.view', icon: IconArea },
      { to: '/audit', label: 'Jejak Audit', permission: 'audit.log.view', icon: IconAudit },
      { to: '/notifications', label: 'Notifikasi', permission: 'notification.settings.view', icon: IconAlert },
      { to: '/payment-gateway', label: 'Payment Gateway', permission: 'billing.gateway.view', icon: IconPackage },
      { to: '/tax-settings', label: 'Pajak & BHP/USO', permission: 'billing.tax.view', icon: IconReceipt },
    ],
  },
]

/**
 * Rute yang petanya (atau kanvas lain) mengisi penuh area konten — tanpa padding
 * & lebar maksimum, menempel di bawah header dan di samping sidebar.
 */
const FLUSH_ROUTES = new Set(['/map'])

const COLLAPSE_KEY = 'ftth.sidebarCollapsed'

export function Layout() {
  const { user, logout } = useAuth()
  const { can, isPlatformAdmin } = useCan()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1')

  const toggleSidebar = () =>
    setCollapsed((v) => {
      const next = !v
      localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0')
      return next
    })

  const flush = FLUSH_ROUTES.has(location.pathname)

  const initials = (user?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  return (
    <div className={`app${collapsed ? ' sidebar-collapsed' : ''}`}>
      <aside className="sidebar">
        <div className="brand">
          <span className="logo" aria-hidden>
            <IconMap size={17} />
          </span>
          <span className="brand-text">NetOps</span>
        </div>

        {/* Platform admin sedang menengok area tenant — switcher konteks di puncak sidebar. */}
        {isPlatformAdmin && <EnvSwitcher current="tenant" />}

        <SidebarNav groups={GROUPS} can={can} storageKey="ftth.navGroups.tenant" />
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="row" style={{ gap: '0.5rem' }}>
            <button
              className="ghost icon-btn"
              onClick={toggleSidebar}
              aria-label={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
              title={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            >
              <IconSidebar size={18} />
            </button>
            <span className="badge accent">{user?.tenantSlug}</span>
            {user?.platformAdmin && <span className="badge">platform admin</span>}
          </div>
          <div className="row" style={{ gap: '0.75rem' }}>
            <ThemeToggle />
            <div className="user-chip">
              <span className="avatar" aria-hidden>
                {initials}
              </span>
              <div style={{ lineHeight: 1.2 }}>
                <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{user?.name}</div>
                <div className="muted" style={{ fontSize: '0.75rem' }}>
                  {user?.email}
                </div>
              </div>
            </div>
            <button className="ghost icon-btn" onClick={() => void logout()} aria-label="Keluar" title="Keluar">
              <IconLogout size={18} />
            </button>
          </div>
        </header>

        <main className={flush ? 'content content-flush' : 'content'}>
          {!flush && (
            <div className="breadcrumb-bar">
              <Breadcrumbs />
            </div>
          )}
          <Outlet />
        </main>
      </div>
    </div>
  )
}
