import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { Area, PageResponse, Role, User } from '../api/types'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { EmptyState, SearchInput, StatusBadge, Toolbar } from '../components/ui'
import { IconUsers } from '../components/icons'

interface NewUser {
  email: string
  name: string
  password: string
  roleIds: Set<string>
  areaIds: Set<string>
}

const EMPTY: NewUser = { email: '', name: '', password: '', roleIds: new Set(), areaIds: new Set() }

export function UsersPage() {
  const { can } = useCan()
  const [users, setUsers] = useState<User[]>([])
  const [roles, setRoles] = useState<Role[]>([])
  const [areas, setAreas] = useState<Area[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<NewUser | null>(null)
  const [editing, setEditing] = useState<User | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      const page = await api.get<PageResponse<User>>(
        `/api/users?size=50${query ? `&query=${encodeURIComponent(query)}` : ''}`,
      )
      setUsers(page.content)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal memuat pengguna')
    } finally {
      setLoading(false)
    }
  }, [query])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    void (async () => {
      try {
        if (can('iam.role.view')) setRoles(await api.get<Role[]>('/api/roles'))
        if (can('iam.area.view')) setAreas(await api.get<Area[]>('/api/areas'))
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Gagal memuat data')
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  function toggle(set: Set<string>, id: string): Set<string> {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  }

  const roleNames = (user: User) =>
    user.roleIds.map((id) => roles.find((r) => r.id === id)?.name ?? '?').join(', ')
  const areaCodes = (user: User) =>
    user.areaIds.length === 0
      ? 'semua'
      : user.areaIds.map((id) => areas.find((a) => a.id === id)?.code ?? '?').join(', ')

  const columns: Column<User>[] = [
    { key: 'name', header: 'Nama', sortValue: (u) => u.name, cell: (u) => u.name },
    { key: 'email', header: 'Email', sortValue: (u) => u.email, className: 'muted', cell: (u) => u.email },
    {
      key: 'status',
      header: 'Status',
      sortValue: (u) => u.status,
      cell: (u) => <StatusBadge status={u.status} />,
    },
    {
      key: 'role',
      header: 'Role',
      className: 'muted',
      sortValue: (u) => roleNames(u),
      cell: (u) => roleNames(u) || '–',
    },
    { key: 'area', header: 'Area', className: 'muted', sortValue: (u) => areaCodes(u), cell: (u) => areaCodes(u) },
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (user) => (
        <div className="row">
          {can('iam.user.assign') && <button onClick={() => setEditing(user)}>Akses</button>}
          {can('iam.user.update') && (
            <button
              onClick={() =>
                void run(() =>
                  api.post(`/api/users/${user.id}/${user.status === 'ACTIVE' ? 'disable' : 'enable'}`),
                )
              }
            >
              {user.status === 'ACTIVE' ? 'Nonaktifkan' : 'Aktifkan'}
            </button>
          )}
          {can('iam.user.delete') && (
            <button
              className="danger"
              onClick={() => confirm(`Hapus ${user.email}?`) && void run(() => api.del(`/api/users/${user.id}`))}
            >
              Hapus
            </button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div className="stack">
      <div className="spread">
        <h1 className="page-title">Pengguna</h1>
        {can('iam.user.create') && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY, roleIds: new Set(), areaIds: new Set() })}>
            Pengguna baru
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau email…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={users}
        rowKey={(u) => u.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query ? 'Tidak ada pengguna yang cocok' : 'Belum ada pengguna'}
            hint={query ? 'Coba ubah kata kunci pencarian.' : 'Tambahkan pengguna pertama untuk memberi akses ke sistem.'}
            icon={<IconUsers size={32} />}
          />
        }
      />

      {draft && (
        <div className="card stack">
          <h3 style={{ margin: 0 }}>Pengguna baru</h3>
          <label>
            <span>Nama</span>
            <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          </label>
          <label>
            <span>Email</span>
            <input type="email" value={draft.email} onChange={(e) => setDraft({ ...draft, email: e.target.value })} />
          </label>
          <label>
            <span>Password (min. 8 karakter)</span>
            <input
              type="password"
              value={draft.password}
              onChange={(e) => setDraft({ ...draft, password: e.target.value })}
            />
          </label>
          <RoleAreaPicker
            roles={roles}
            areas={areas}
            roleIds={draft.roleIds}
            areaIds={draft.areaIds}
            onToggleRole={(id) => setDraft({ ...draft, roleIds: toggle(draft.roleIds, id) })}
            onToggleArea={(id) => setDraft({ ...draft, areaIds: toggle(draft.areaIds, id) })}
          />
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/users', {
                    email: draft.email,
                    name: draft.name,
                    password: draft.password,
                    roleIds: [...draft.roleIds],
                    areaIds: [...draft.areaIds],
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

      {editing && (
        <AccessEditor
          user={editing}
          roles={roles}
          areas={areas}
          onCancel={() => setEditing(null)}
          onSave={(roleIds, areaIds) =>
            void run(async () => {
              await api.put(`/api/users/${editing.id}/access`, { roleIds, areaIds })
              setEditing(null)
            })
          }
        />
      )}
    </div>
  )
}

function RoleAreaPicker({
  roles,
  areas,
  roleIds,
  areaIds,
  onToggleRole,
  onToggleArea,
}: {
  roles: Role[]
  areas: Area[]
  roleIds: Set<string>
  areaIds: Set<string>
  onToggleRole: (id: string) => void
  onToggleArea: (id: string) => void
}) {
  return (
    <div className="row" style={{ alignItems: 'flex-start', gap: '2rem' }}>
      <div>
        <strong>Role</strong>
        {roles.map((role) => (
          <label key={role.id} className="row" style={{ marginTop: '0.4rem' }}>
            <input
              type="checkbox"
              style={{ width: 'auto' }}
              checked={roleIds.has(role.id)}
              onChange={() => onToggleRole(role.id)}
            />
            {role.name}
          </label>
        ))}
      </div>
      <div>
        <strong>Area (kosong = semua area)</strong>
        {areas.length === 0 && <p className="muted">Belum ada area.</p>}
        {areas.map((area) => (
          <label key={area.id} className="row" style={{ marginTop: '0.4rem' }}>
            <input
              type="checkbox"
              style={{ width: 'auto' }}
              checked={areaIds.has(area.id)}
              onChange={() => onToggleArea(area.id)}
            />
            {area.code} — {area.name}
          </label>
        ))}
      </div>
    </div>
  )
}

function AccessEditor({
  user,
  roles,
  areas,
  onCancel,
  onSave,
}: {
  user: User
  roles: Role[]
  areas: Area[]
  onCancel: () => void
  onSave: (roleIds: string[], areaIds: string[]) => void
}) {
  const [roleIds, setRoleIds] = useState(new Set(user.roleIds))
  const [areaIds, setAreaIds] = useState(new Set(user.areaIds))

  const toggle = (set: Set<string>, id: string) => {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  }

  return (
    <div className="card stack">
      <h3 style={{ margin: 0 }}>Akses — {user.email}</h3>
      <RoleAreaPicker
        roles={roles}
        areas={areas}
        roleIds={roleIds}
        areaIds={areaIds}
        onToggleRole={(id) => setRoleIds(toggle(roleIds, id))}
        onToggleArea={(id) => setAreaIds(toggle(areaIds, id))}
      />
      <p className="muted">
        Mengubah akses mencabut refresh token pengguna, sehingga izin baru berlaku setelah access-token kedaluwarsa.
      </p>
      <div className="row">
        <button className="primary" onClick={() => onSave([...roleIds], [...areaIds])}>
          Simpan
        </button>
        <button onClick={onCancel}>Batal</button>
      </div>
    </div>
  )
}
