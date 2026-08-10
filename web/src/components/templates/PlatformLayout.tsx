import { Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { useAppShellNav } from '@/hooks/useAppShellNav'
import { BrandMark, Button, ThemeToggle } from '@/components/atoms'
import { EnvSwitcher } from '@/components/molecules'
import { Breadcrumbs } from '@/components/molecules'
import { SidebarNav, type NavGroup } from '@/components/molecules'
import {
  IconAudit,
  IconBuilding,
  IconDashboard,
  IconFlask,
  IconGauge,
  IconLogout,
  IconMonitor,
  IconRoute,
  IconShield,
  IconSidebar,
  IconUsers,
} from '@/components/atoms/icons'

/**
 * Shell KHUSUS Platform admin (SaaS super-admin), terpisah dari `Layout` operator
 * tenant. Menu hanya memuat urusan platform: tenant, langganan/billing SaaS,
 * infrastruktur, dan IAM tenant `platform` sendiri. Tetap difilter izin (cermin
 * RBAC server), meski platform admin lolos semua via flag. Seksi berlabel bisa
 * diciutkan (lihat [SidebarNav]).
 */
const GROUPS: NavGroup[] = [
  {
    label: null,
    items: [{ to: '/platform', label: 'Dashboard', permission: null, icon: IconDashboard, end: true }],
  },
  {
    label: 'Tenant',
    items: [
      { to: '/platform/tenants', label: 'Tenant', permission: 'platform.tenant.view', icon: IconBuilding },
    ],
  },
  {
    label: 'Billing Langganan',
    items: [
      { to: '/platform/billing', label: 'Billing Langganan', permission: 'platform.billing.view', icon: IconGauge },
      {
        to: '/platform/payments/simulate',
        label: 'Simulasi Pembayaran',
        permission: 'platform.billing.view',
        icon: IconFlask,
      },
    ],
  },
  {
    label: 'Infrastruktur',
    items: [
      { to: '/platform/vpn-servers', label: 'Server VPN', permission: 'vpn.server.view', icon: IconRoute },
      { to: '/platform/jobs', label: 'Pekerjaan Latar', permission: 'platform.ops.view', icon: IconMonitor },
    ],
  },
  {
    label: 'Administrasi Platform',
    items: [
      { to: '/platform/users', label: 'Pengguna', permission: 'iam.user.view', icon: IconUsers },
      { to: '/platform/roles', label: 'Role & Izin', permission: 'iam.role.view', icon: IconShield },
      { to: '/platform/audit', label: 'Jejak Audit', permission: 'audit.log.view', icon: IconAudit },
    ],
  },
]

export function PlatformLayout() {
  const { user, logout } = useAuth()
  const { can } = useCan()
  const { collapsed, navOpen, toggleNav, closeNav, shellClass } = useAppShellNav()

  const initials = (user?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  return (
    <div className={shellClass}>
      {/* Header Azure full-width: anak langsung .app (grid-area topbar) agar bar biru
          membentang di atas sidebar & konten, bukan hanya kolom kanan. */}
      <header className="topbar">
        <div className="row" style={{ gap: '0.5rem' }}>
          <Button
            variant="subtle"
            icon={<IconSidebar size={18} />}
            onClick={toggleNav}
            aria-label={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            title={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            aria-expanded={navOpen}
          />
          <span className="badge accent">platform admin</span>
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
          <Button
            variant="subtle"
            icon={<IconLogout size={18} />}
            onClick={() => void logout()}
            aria-label="Keluar"
            title="Keluar"
          />
        </div>
      </header>

      {navOpen && <button type="button" className="nav-scrim" aria-label="Tutup menu" onClick={closeNav} />}

      <aside className="sidebar">
        <div className="brand">
          <span className="logo" aria-hidden>
            <BrandMark size={22} />
          </span>
          <span className="brand-text">NetOps · Platform</span>
        </div>

        <EnvSwitcher current="platform" />

        <SidebarNav groups={GROUPS} can={can} storageKey="ftth.navGroups.platform" />
      </aside>

      <div className="main">
        <main className="content">
          <div className="breadcrumb-bar">
            <Breadcrumbs />
          </div>
          <Outlet />
        </main>
      </div>
    </div>
  )
}
