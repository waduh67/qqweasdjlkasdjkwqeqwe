import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'

/** Item navigasi hanya tampil bila izinnya dimiliki — cermin RBAC di server. */
const NAV = [
  { to: '/', label: 'Dashboard', permission: null, end: true },
  { to: '/map', label: 'Peta Jaringan', permission: 'gis.map.view' },
  { to: '/inventory', label: 'Inventory', permission: 'network.odp.view' },
  { to: '/customers', label: 'Pelanggan', permission: 'customer.customer.view' },
  { to: '/users', label: 'Pengguna', permission: 'iam.user.view' },
  { to: '/roles', label: 'Role & Izin', permission: 'iam.role.view' },
  { to: '/areas', label: 'Area', permission: 'iam.area.view' },
  { to: '/audit', label: 'Jejak Audit', permission: 'audit.log.view' },
  { to: '/tenants', label: 'Tenant (Platform)', permission: 'platform.tenant.view' },
] as const

export function Layout() {
  const { user, logout } = useAuth()
  const { can } = useCan()

  return (
    <div className="app">
      <aside className="sidebar">
        <h1>FTTH OSS</h1>
        <nav>
          {NAV.filter((item) => item.permission === null || can(item.permission)).map((item) => (
            <NavLink key={item.to} to={item.to} end={'end' in item ? item.end : false}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <main className="content">
        <div className="spread" style={{ marginBottom: '1.5rem' }}>
          <div>
            <strong>{user?.name}</strong>{' '}
            <span className="badge">{user?.tenantSlug}</span>{' '}
            {user?.platformAdmin && <span className="badge">platform admin</span>}
            <div className="muted">{user?.email}</div>
          </div>
          <button onClick={() => void logout()}>Keluar</button>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
