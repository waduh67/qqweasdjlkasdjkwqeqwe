import { useAuth } from '../auth/useAuth'

/**
 * Placeholder Phase 0. Mulai Phase 1 halaman ini menjadi ringkasan jaringan
 * (peta, status OLT/ONU, insiden aktif).
 */
export function DashboardPage() {
  const { user } = useAuth()

  return (
    <div className="stack">
      <h2 style={{ margin: 0 }}>Dashboard</h2>
      <div className="card stack">
        <p>
          Halo <strong>{user?.name}</strong> — kamu masuk pada tenant <span className="badge">{user?.tenantSlug}</span>.
        </p>
        <p className="muted">
          Fondasi (multi-tenancy, RBAC dinamis, audit) sudah berjalan. Modul inventory jaringan, peta GIS, monitoring,
          insiden, dan work order menyusul di fase berikutnya.
        </p>
        <div>
          <strong>Izin efektif kamu ({user?.permissions.length ?? 0})</strong>
          <div className="row" style={{ flexWrap: 'wrap', marginTop: '0.5rem' }}>
            {user?.permissions.slice(0, 40).map((permission) => (
              <span className="badge" key={permission}>
                {permission}
              </span>
            ))}
            {(user?.permissions.length ?? 0) > 40 && <span className="muted">…</span>}
          </div>
        </div>
      </div>
    </div>
  )
}
