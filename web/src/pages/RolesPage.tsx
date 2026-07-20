import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PermissionCatalog, Role } from '../api/types'
import { PermissionMatrix } from '../components/PermissionMatrix'
import { useCan } from '../auth/useCan'

type Draft = { id: string | null; name: string; description: string; permissionIds: Set<string> }

const EMPTY_DRAFT: Draft = { id: null, name: '', description: '', permissionIds: new Set() }

export function RolesPage() {
  const { can } = useCan()
  const [roles, setRoles] = useState<Role[]>([])
  const [catalog, setCatalog] = useState<PermissionCatalog | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const readOnly = !can('iam.role.update') && !can('iam.role.create')

  async function reload() {
    setRoles(await api.get<Role[]>('/api/roles'))
  }

  useEffect(() => {
    void (async () => {
      try {
        const [rolesData, catalogData] = await Promise.all([
          api.get<Role[]>('/api/roles'),
          api.get<PermissionCatalog>('/api/permissions/catalog'),
        ])
        setRoles(rolesData)
        setCatalog(catalogData)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Gagal memuat data')
      }
    })()
  }, [])

  async function save() {
    if (!draft) return
    setBusy(true)
    setError(null)
    try {
      const body = {
        name: draft.name,
        description: draft.description || null,
        permissionIds: [...draft.permissionIds],
      }
      if (draft.id) await api.put(`/api/roles/${draft.id}`, body)
      else await api.post('/api/roles', body)
      await reload()
      setDraft(null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal menyimpan role')
    } finally {
      setBusy(false)
    }
  }

  async function remove(role: Role) {
    if (!confirm(`Hapus role "${role.name}"?`)) return
    setError(null)
    try {
      await api.del(`/api/roles/${role.id}`)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal menghapus role')
    }
  }

  return (
    <div className="stack">
      <div className="spread">
        <h2 style={{ margin: 0 }}>Role &amp; Izin</h2>
        {can('iam.role.create') && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_DRAFT, permissionIds: new Set() })}>
            Role baru
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Nama</th>
              <th>Deskripsi</th>
              <th>Jumlah izin</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {roles.map((role) => (
              <tr key={role.id}>
                <td>
                  {role.name} {role.systemRole && <span className="badge">sistem</span>}
                </td>
                <td className="muted">{role.description ?? '–'}</td>
                <td>{role.permissionIds.length}</td>
                <td>
                  <div className="row">
                    <button
                      onClick={() =>
                        setDraft({
                          id: role.id,
                          name: role.name,
                          description: role.description ?? '',
                          permissionIds: new Set(role.permissionIds),
                        })
                      }
                    >
                      {readOnly ? 'Lihat' : 'Ubah'}
                    </button>
                    {can('iam.role.delete') && !role.systemRole && (
                      <button className="danger" onClick={() => void remove(role)}>
                        Hapus
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {roles.length === 0 && (
              <tr>
                <td colSpan={4} className="muted">
                  Belum ada role.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {draft && catalog && (
        <div className="card stack">
          <h3 style={{ margin: 0 }}>{draft.id ? 'Ubah role' : 'Role baru'}</h3>
          <div className="row" style={{ alignItems: 'flex-start' }}>
            <label style={{ flex: 1 }}>
              <span>Nama</span>
              <input
                value={draft.name}
                disabled={readOnly}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </label>
            <label style={{ flex: 2 }}>
              <span>Deskripsi</span>
              <input
                value={draft.description}
                disabled={readOnly}
                onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              />
            </label>
          </div>

          <div>
            <div className="spread">
              <strong>Izin</strong>
              <span className="muted">{draft.permissionIds.size} dipilih</span>
            </div>
            <PermissionMatrix
              catalog={catalog}
              selected={draft.permissionIds}
              disabled={readOnly}
              onChange={(next) => setDraft({ ...draft, permissionIds: next })}
            />
          </div>

          <div className="row">
            <button className="primary" onClick={() => void save()} disabled={busy || readOnly || !draft.name}>
              {busy ? 'Menyimpan…' : 'Simpan'}
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}
    </div>
  )
}
