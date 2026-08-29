import { useCallback, useEffect, useState } from 'react'
import { KeyRound, Plus, Power, RefreshCw, ShieldOff, Trash2 } from 'lucide-react'
import { Checkbox, Text } from '@fluentui/react-components'
import { api, ApiError } from '../api/client'
import { resetTwoFactorFor } from '../api/account'
import type { Area, PageResponse, Role, User } from '../api/types'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { Blade } from '@/components/organisms'
import { Badge, Button, EmptyState, StatusBadge, TextField, Toolbar } from '@/components/atoms'
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
      key: 'twoFactor',
      header: '2FA',
      sortValue: (u) => (u.twoFactorEnabled ? 1 : 0),
      cell: (u) =>
        u.twoFactorEnabled ? <Badge tone="good">Aktif</Badge> : <Text as="span" className="muted">–</Text>,
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
    // Ponsel hilang tanpa kode pemulihan tersisa: satu-satunya jalan masuk kembali.
    // Bukan memindahkan 2FA ke perangkat siapa pun — pagarnya dilepas, dan pemiliknya
    // memasang lagi sendiri. Tercatat di jejak audit.
    if (canUpdate && user.twoFactorEnabled)
      list.push({
        key: 'reset-2fa',
        label: 'Setel ulang 2FA',
        icon: <ShieldOff size={16} />,
        onClick: () =>
          void (async () => {
            const ok = await confirm({
              title: 'Setel ulang 2FA',
              message: `Kosongkan verifikasi dua langkah milik ${user.email}? Setelah ini ia bisa masuk dengan password saja sampai memasang autentikator baru.`,
              confirmLabel: 'Setel ulang',
              danger: true,
            })
            if (ok) void run(() => resetTwoFactorFor(user.id))
          })(),
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

      {error && <Text as="p" className="error">{error}</Text>}

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
              <Button
                variant="primary"
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
              </Button>
              <Button onClick={() => setDraft(null)}>Batal</Button>
            </>
          )
        }
      >
        {draft && (
          <div className="stack">
            <TextField
              label="Nama"
              value={draft.name}
              onChange={(_, data) => setDraft({ ...draft, name: data.value })}
            />
            <TextField
              label="Email"
              type="email"
              value={draft.email}
              onChange={(_, data) => setDraft({ ...draft, email: data.value })}
            />
            <TextField
              label="Password (min. 8 karakter)"
              type="password"
              value={draft.password}
              onChange={(_, data) => setDraft({ ...draft, password: data.value })}
            />
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
        <Text as="strong" weight="semibold" >Role</Text>
        {roles.map((role) => (
          <div key={role.id} style={{ marginTop: '0.2rem' }}>
            <Checkbox
              label={role.name}
              checked={roleIds.has(role.id)}
              onChange={() => onToggleRole(role.id)}
            />
          </div>
        ))}
      </div>
      <div>
        <Text as="strong" weight="semibold" >Area (kosong = semua area)</Text>
        {areas.length === 0 && <Text as="p" className="muted">Belum ada area.</Text>}
        {areas.map((area) => (
          <div key={area.id} style={{ marginTop: '0.2rem' }}>
            <Checkbox
              label={`${area.code} — ${area.name}`}
              checked={areaIds.has(area.id)}
              onChange={() => onToggleArea(area.id)}
            />
          </div>
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
          <Button variant="primary" onClick={() => onSave([...roleIds], [...areaIds])}>
            Simpan
          </Button>
          <Button onClick={onCancel}>Batal</Button>
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
