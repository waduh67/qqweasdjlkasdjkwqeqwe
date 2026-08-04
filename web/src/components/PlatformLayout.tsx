import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { ThemeToggle } from './ThemeToggle'
import { EnvSwitcher } from './EnvSwitcher'
import {
  IconAudit,
  IconBuilding,
  IconDashboard,
  IconGauge,
  IconLogout,
  IconRoute,
  IconShield,
  IconSidebar,
  IconUsers,
} from './icons'
import type { ComponentType } from 'react'
import type { IconProps } from './icons'

/**
 * Shell KHUSUS Platform admin (SaaS super-admin), terpisah dari `Layout` operator
 * tenant. Menu hanya memuat urusan platform: tenant, langganan/billing SaaS,
 * infrastruktur, dan IAM tenant `platform` sendiri. Tetap difilter izin (cermin
 * RBAC server), meski platform admin lolos semua via flag.
 */
type NavItem = {
  to: string
  label: string
  permission: string | null
  icon: ComponentType<IconProps>
  end?: boolean
}

const GROUPS: Array<{ label: string | null; items: NavItem[] }> = [
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
    ],
  },
  {
    label: 'Infrastruktur',
    items: [
      { to: '/platform/vpn-servers', label: 'Server VPN', permission: 'vpn.server.view', icon: IconRoute },
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

const COLLAPSE_KEY = 'ftth.sidebarCollapsed'

export function PlatformLayout() {
  const { user, logout } = useAuth()
  const { can } = useCan()
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1')

  const toggleSidebar = () =>
    setCollapsed((v) => {
      const next = !v
      localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0')
      return next
    })

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
            <IconBuilding size={17} />
          </span>
          <span className="brand-text">NetOps · Platform</span>
        </div>

        <EnvSwitcher current="platform" />

        {GROUPS.map((group, i) => {
          const visible = group.items.filter((item) => item.permission === null || can(item.permission))
          if (visible.length === 0) return null
          return (
            <div key={group.label ?? i}>
              {group.label && <div className="nav-label">{group.label}</div>}
              <nav>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end ?? false} title={item.label}>
                    <item.icon size={18} />
                    <span className="nav-text">{item.label}</span>
                  </NavLink>
                ))}
              </nav>
            </div>
          )
        })}
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
            <button className="ghost icon-btn" onClick={() => void logout()} aria-label="Keluar" title="Keluar">
              <IconLogout size={18} />
            </button>
          </div>
        </header>

        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
