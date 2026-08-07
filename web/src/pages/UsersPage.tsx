import { useCallback, useEffect, useState } from 'react'
import { KeyRound, Plus, Power, RefreshCw, Trash2 } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { Area, PageResponse, Role, User } from '../api/types'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { Blade } from '@/components/organisms'
import { EmptyState, StatusBadge, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useConfirm } from '@/system'
import { IconUsers } from '@/components/atoms/icons'

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
  const confirm = useConfirm()
  const [users, setUsers] = useState<User[]>([])
  const [roles, setRoles] = useState<Role[]>([])
  const [areas, setAreas] = useState<Area[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<NewUser | null>(null)
  const [editing, setEditing] = useState<User | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)

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
  ]

  // Aksi per-baris kini di menu `…` ala Azure DataGrid — Akses/Aktivasi/Hapus.
  const canAssign = can('iam.user.assign')
  const canUpdate = can('iam.user.update')
  const canDelete = can('iam.user.delete')
  const hasRowActions = canAssign || canUpdate || canDelete
  const rowActions = (user: User): RowAction[] => {
    const list: RowAction[] = []
    if (canAssign)
      list.push({ key: 'access', label: 'Akses', icon: <KeyRound size={16} />, onClick: () => setEditing(user) })
    if (canUpdate)
      list.push({
        key: 'toggle',
        label: user.status === 'ACTIVE' ? 'Nonaktifkan' : 'Aktifkan',
        icon: <Power size={16} />,
        onClick: () =>
          void run(() =>
            api.post(`/api/users/${user.id}/${user.status === 'ACTIVE' ? 'disable' : 'enable'}`),
          ),
      })
    if (canDelete)
      list.push({
        key: 'delete',
        label: 'Hapus',
        icon: <Trash2 size={16} />,
        onClick: () =>
          void (async () => {
            if (await confirm({ title: 'Hapus pengguna', message: `Hapus ${user.email}?`, confirmLabel: 'Hapus', danger: true }))
              void run(() => api.del(`/api/users/${user.id}`))
          })(),
      })
    return list
  }

  // Hapus massal dari CommandBar — nonaktif sampai ada baris tercentang (pola Azure).
  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!(await confirm({ title: 'Hapus pengguna', message: `Hapus ${ids.length} pengguna terpilih? Tindakan ini tidak dapat dibatalkan.`, confirmLabel: 'Hapus', danger: true }))) return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/users/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const primary: CommandAction | undefined = can('iam.user.create')
    ? {
        key: 'create',
        label: 'Pengguna baru',
        icon: <Plus size={16} />,
        onClick: () => setDraft({ ...EMPTY, roleIds: new Set(), areaIds: new Set() }),
      }
    : undefined

  const actions: CommandAction[] = []
  if (canDelete)
    actions.push({
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void deleteSelected(),
      disabled: selected.size === 0 || deleting,
    })
  actions.push({
    key: 'refresh',
    label: 'Segarkan',
    icon: <RefreshCw size={16} />,
    onClick: () => void reload(),
    dividerBefore: canDelete,
  })

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader title="Pengguna" subtitle="Akun operator, role, dan cakupan area akses." />

      <CommandBar primary={primary} actions={actions} />

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
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={hasRowActions ? rowActions : undefined}
        empty={
          <EmptyState
            title={query ? 'Tidak ada pengguna yang cocok' : 'Belum ada pengguna'}
            hint={query ? 'Coba ubah kata kunci pencarian.' : 'Tambahkan pengguna pertama untuk memberi akses ke sistem.'}
            icon={<IconUsers size={32} />}
          />
        }
      />

      <Blade
        open={draft != null}
        title="Pengguna baru"
        subtitle="Buat akun operator lalu tetapkan role & cakupan area."
        size="sm"
        dirty={
          draft != null &&
          Boolean(draft.name || draft.email || draft.password || draft.roleIds.size || draft.areaIds.size)
        }
        onClose={() => setDraft(null)}
        footer={
          draft && (
            <>
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
            </>
          )
        }
      >
        {draft && (
          <div className="stack">
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
          </div>
        )}
      </Blade>

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

  // Kotor bila komposisi role/area berubah dari nilai awal pengguna.
  const sameSet = (a: Set<string>, b: string[]) => a.size === b.length && b.every((id) => a.has(id))
  const dirty = !sameSet(roleIds, user.roleIds) || !sameSet(areaIds, user.areaIds)

  return (
    <Blade
      open
      title={`Akses — ${user.email}`}
      subtitle="Mengubah akses mencabut refresh token pengguna; izin baru berlaku setelah access-token kedaluwarsa."
      size="sm"
      dirty={dirty}
      onClose={onCancel}
      footer={
        <>
          <button className="primary" onClick={() => onSave([...roleIds], [...areaIds])}>
            Simpan
          </button>
          <button onClick={onCancel}>Batal</button>
        </>
      }
    >
      <RoleAreaPicker
        roles={roles}
        areas={areas}
        roleIds={roleIds}
        areaIds={areaIds}
        onToggleRole={(id) => setRoleIds(toggle(roleIds, id))}
        onToggleArea={(id) => setAreaIds(toggle(areaIds, id))}
      />
    </Blade>
  )
}
