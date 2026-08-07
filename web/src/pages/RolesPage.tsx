import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PermissionCatalog, Role } from '../api/types'
import { PermissionMatrix } from '../components/PermissionMatrix'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { CommandBar, type CommandAction } from '../components/CommandBar'
import { PageHeader } from '../components/PageHeader'
import { Badge, EmptyState, SearchInput, Toolbar, useConfirm } from '../components/ui'
import { IconPlus, IconShield } from '../components/icons'

type Draft = { id: string | null; name: string; description: string; permissionIds: Set<string> }

const EMPTY_DRAFT: Draft = { id: null, name: '', description: '', permissionIds: new Set() }

export function RolesPage() {
  const { can } = useCan()
  const confirm = useConfirm()
  const [roles, setRoles] = useState<Role[]>([])
  const [catalog, setCatalog] = useState<PermissionCatalog | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')

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
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return roles
    return roles.filter(
      (r) => r.name.toLowerCase().includes(q) || (r.description ?? '').toLowerCase().includes(q),
    )
  }, [roles, query])

  const columns: Column<Role>[] = [
    {
      key: 'name',
      header: 'Nama',
      sortValue: (r) => r.name,
      cell: (r) => (
        <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
          <strong>{r.name}</strong>
          {r.systemRole && <Badge>sistem</Badge>}
        </div>
      ),
    },
    {
      key: 'description',
      header: 'Deskripsi',
      sortValue: (r) => r.description,
      cell: (r) => <span className="muted">{r.description ?? '–'}</span>,
    },
    {
      key: 'permissions',
      header: 'Jumlah izin',
      align: 'right',
      sortValue: (r) => r.permissionIds.length,
      cell: (r) => r.permissionIds.length,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (role) => (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
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
      ),
    },
  ]

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
    if (!(await confirm({ title: 'Hapus role', message: `Hapus role "${role.name}"?`, confirmLabel: 'Hapus', danger: true }))) return
    setError(null)
    try {
      await api.del(`/api/roles/${role.id}`)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal menghapus role')
    }
  }

  // CommandBar ala Azure: primary `+ Role baru` dipatok kiri, seragam dengan Pelanggan.
  const primary: CommandAction | undefined = can('iam.role.create')
    ? {
        key: 'create',
        label: 'Role baru',
        icon: <IconPlus size={16} />,
        onClick: () => setDraft({ ...EMPTY_DRAFT, permissionIds: new Set() }),
      }
    : undefined

  return (
    <div className="stack">
      <PageHeader title="Role & Izin" subtitle="Kelompokkan izin ke dalam peran untuk mengatur akses pengguna." />
      <CommandBar primary={primary} />

      {error && <p className="error">{error}</p>}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau deskripsi role…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query ? 'Tidak ada role yang cocok' : 'Belum ada role'}
            hint={query ? 'Coba ubah kata kunci.' : 'Buat role pertama untuk mengatur izin.'}
            icon={<IconShield size={32} />}
          />
        }
      />

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
