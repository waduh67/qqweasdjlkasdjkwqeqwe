import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { ThemeToggle } from './ThemeToggle'
import {
  IconAlert,
  IconArea,
  IconAudit,
  IconBuilding,
  IconCustomers,
  IconDashboard,
  IconInbox,
  IconInventory,
  IconLogout,
  IconMap,
  IconMonitor,
  IconShield,
  IconUsers,
  IconWorkOrder,
} from './icons'
import type { ComponentType } from 'react'
import type { IconProps } from './icons'

/**
 * Navigasi dikelompokkan menurut alur kerja (operasi jaringan vs administrasi),
 * bukan sekadar daftar datar — pada belasan menu, pengelompokan membuat operator
 * menemukan yang dicari tanpa memindai satu per satu. Tiap item tetap difilter
 * izin, cermin RBAC di server.
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
    items: [{ to: '/', label: 'Dashboard', permission: null, icon: IconDashboard, end: true }],
  },
  {
    label: 'Operasi Jaringan',
    items: [
      { to: '/map', label: 'Peta Jaringan', permission: 'gis.map.view', icon: IconMap },
      { to: '/inventory', label: 'Inventory', permission: 'network.odp.view', icon: IconInventory },
      { to: '/customers', label: 'Pelanggan', permission: 'customer.customer.view', icon: IconCustomers },
      { to: '/monitoring', label: 'Monitoring', permission: 'monitoring.dashboard.view', icon: IconMonitor },
      { to: '/provisioning', label: 'Provisioning', permission: 'monitoring.provisioning.view', icon: IconInbox },
      { to: '/incidents', label: 'Insiden', permission: 'incident.ticket.view', icon: IconAlert },
      { to: '/work-orders', label: 'Work Order', permission: 'workorder.order.view', icon: IconWorkOrder },
    ],
  },
  {
    label: 'Administrasi',
    items: [
      { to: '/users', label: 'Pengguna', permission: 'iam.user.view', icon: IconUsers },
      { to: '/roles', label: 'Role & Izin', permission: 'iam.role.view', icon: IconShield },
      { to: '/areas', label: 'Area', permission: 'iam.area.view', icon: IconArea },
      { to: '/audit', label: 'Jejak Audit', permission: 'audit.log.view', icon: IconAudit },
      { to: '/tenants', label: 'Tenant', permission: 'platform.tenant.view', icon: IconBuilding },
    ],
  },
]

export function Layout() {
  const { user, logout } = useAuth()
  const { can } = useCan()

  const initials = (user?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="logo" aria-hidden>
            <IconMap size={17} />
          </span>
          FTTH OSS
        </div>

        {GROUPS.map((group, i) => {
          const visible = group.items.filter((item) => item.permission === null || can(item.permission))
          if (visible.length === 0) return null
          return (
            <div key={group.label ?? i}>
              {group.label && <div className="nav-label">{group.label}</div>}
              <nav>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end ?? false}>
                    <item.icon size={18} />
                    {item.label}
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

        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
