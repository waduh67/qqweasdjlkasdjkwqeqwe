import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { useCan } from '../auth/useCan'

interface Tenant {
  id: string
  slug: string
  name: string
  status: string
}

const EMPTY = { slug: '', name: '', adminEmail: '', adminName: '', adminPassword: '' }

/** Halaman platform admin: daftar tenant + onboarding tenant baru beserta admin awalnya. */
export function TenantsPage() {
  const { can } = useCan()
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [draft, setDraft] = useState<typeof EMPTY | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function reload() {
    const page = await api.get<PageResponse<Tenant>>('/api/platform/tenants?size=50')
    setTenants(page.content)
  }

  useEffect(() => {
    void reload().catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat tenant'))
  }, [])

  async function run(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  return (
    <div className="stack">
      <div className="spread">
        <h2 style={{ margin: 0 }}>Tenant (Platform)</h2>
        {can('platform.tenant.create') && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY })}>
            Onboarding tenant
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Slug</th>
              <th>Nama</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tenants.map((tenant) => (
              <tr key={tenant.id}>
                <td>{tenant.slug}</td>
                <td className="muted">{tenant.name}</td>
                <td>
                  <span className="badge">{tenant.status}</span>
                </td>
                <td>
                  {can('platform.tenant.manage') && tenant.slug !== 'platform' && (
                    <button
                      onClick={() =>
                        void run(() =>
                          api.post(
                            `/api/platform/tenants/${tenant.id}/${tenant.status === 'ACTIVE' ? 'suspend' : 'activate'}`,
                          ),
                        )
                      }
                    >
                      {tenant.status === 'ACTIVE' ? 'Suspend' : 'Aktifkan'}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {draft && (
        <div className="card stack">
          <h3 style={{ margin: 0 }}>Onboarding tenant baru</h3>
          <p className="muted">
            Membuat tenant, role &quot;Tenant Admin&quot; berisi seluruh izin non-platform, dan user admin pertamanya.
          </p>
          <div className="row" style={{ alignItems: 'flex-start' }}>
            <label style={{ flex: 1 }}>
              <span>Slug</span>
              <input value={draft.slug} onChange={(e) => setDraft({ ...draft, slug: e.target.value })} placeholder="pt-fiber" />
            </label>
            <label style={{ flex: 2 }}>
              <span>Nama</span>
              <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            </label>
          </div>
          <div className="row" style={{ alignItems: 'flex-start' }}>
            <label style={{ flex: 1 }}>
              <span>Nama admin</span>
              <input value={draft.adminName} onChange={(e) => setDraft({ ...draft, adminName: e.target.value })} />
            </label>
            <label style={{ flex: 1 }}>
              <span>Email admin</span>
              <input
                type="email"
                value={draft.adminEmail}
                onChange={(e) => setDraft({ ...draft, adminEmail: e.target.value })}
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Password admin</span>
              <input
                type="password"
                value={draft.adminPassword}
                onChange={(e) => setDraft({ ...draft, adminPassword: e.target.value })}
              />
            </label>
          </div>
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/platform/tenants', draft)
                  setNotice(`Tenant "${draft.slug}" siap. Admin bisa langsung masuk dengan tenant tersebut.`)
                  setDraft(null)
                })
              }
            >
              Buat
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}
    </div>
  )
}
