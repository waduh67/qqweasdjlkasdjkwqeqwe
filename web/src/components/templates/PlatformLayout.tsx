import { Navigation16Regular } from '@fluentui/react-icons'
import * as NavIcons from '@/components/molecules/navigationIcons'
import { Text } from '@fluentui/react-components'
import { Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { useAppShellNav } from '@/hooks/useAppShellNav'
import { BrandMark, Button, ThemeToggle } from '@/components/atoms'
import { EnvSwitcher } from '@/components/molecules'
import { Breadcrumbs } from '@/components/molecules'
import { SidebarNav, type NavGroup } from '@/components/molecules'
import { IconLogout } from '@/components/atoms/icons'

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
    items: [
      { to: '/platform', label: 'Dashboard', permission: null, icon: NavIcons.Dashboard, end: true },
      { to: '/platform/tenants', label: 'Tenant', permission: 'platform.tenant.view', icon: NavIcons.Building },
    ],
  },
  {
    label: 'Langganan',
    items: [
      { to: '/platform/billing', label: 'Billing Langganan', permission: 'platform.billing.view', icon: NavIcons.Gauge },
      {
        to: '/platform/payments/simulate',
        label: 'Simulasi Pembayaran',
        permission: 'platform.billing.view',
        icon: NavIcons.Flask,
      },
    ],
  },
  {
    label: 'Infrastruktur',
    items: [
      { to: '/platform/vpn-servers', label: 'Server VPN', permission: 'vpn.server.view', icon: NavIcons.Route },
      { to: '/platform/radius-servers', label: 'Server RADIUS', permission: 'radius.server.view', icon: NavIcons.Monitor },
      { to: '/platform/jobs', label: 'Pekerjaan Latar', permission: 'platform.ops.view', icon: NavIcons.Monitor },
    ],
  },
  {
    label: 'Administrasi',
    items: [
      { to: '/platform/email', label: 'Setelan Email', permission: 'platform.email.view', icon: NavIcons.Mail },
      { to: '/platform/users', label: 'Pengguna', permission: 'iam.user.view', icon: NavIcons.Users },
      { to: '/platform/roles', label: 'Role & Izin', permission: 'iam.role.view', icon: NavIcons.Shield },
      { to: '/platform/audit', label: 'Jejak Audit', permission: 'audit.log.view', icon: NavIcons.Audit },
    ],
  },
]

export function PlatformLayout() {
  const { user, logout } = useAuth()
  const { can } = useCan()
  const { navLabel, navExpanded, navOpen, toggleNav, toggleNavFromSidebar, toggleButtonRef, closeNav, shellClass } = useAppShellNav()

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
            size="medium"
            variant="subtle"
            icon={<Navigation16Regular />}
            onClick={toggleNav}
            ref={toggleButtonRef}
            aria-label={navLabel}
            title={navLabel}
            aria-expanded={navExpanded}
          />
          <span className="topbar-brand"><BrandMark size={20} /><span>NetOps</span></span>
          <EnvSwitcher current="platform" />
        </div>
        <div className="row" style={{ gap: '0.75rem' }}>
          <ThemeToggle />
          <div className="user-chip">
            <span className="avatar" aria-hidden>
              {initials}
            </span>
            <div>
              <Text as="span" block size={200} weight="semibold">{user?.name}</Text>
              <Text as="span" block className="muted" size={100}>{user?.email}</Text>
            </div>
          </div>
          <Button
            size="medium"
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
        <SidebarNav onToggle={toggleNavFromSidebar} expanded={navExpanded} groups={GROUPS} can={can} storageKey="ftth.navGroups.platform" />
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
