import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Database, Pencil, Trash2 } from 'lucide-react'
import { ApiError } from '../api/client'
import {
  createRadiusServer,
  deleteRadiusServer,
  listRadiusServers,
  testRadiusConnection,
  testServerConnection,
  updateRadiusServer,
  type CreateRadiusServerRequest,
  type RadiusServerStatus,
  type RadiusServerView,
  type UpdateRadiusServerRequest,
} from '../api/radiusServer'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { Badge, Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar, type Tone } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconPlus, IconServer } from '@/components/atoms/icons'

function useResource<T>(fetcher: () => Promise<T[]>) {
  const toast = useToast()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      setItems(await fetcher())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data server RADIUS')
    } finally {
      setLoading(false)
    }
  }, [fetcher, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      return false
    }
  }

  return { items, loading, reload, run }
}

type ServerDraft = {
  id: string | null
  name: string
  host: string
  authPort: string
  acctPort: string
  coaPort: string
  sharedSecret: string
  dbUrl: string
  dbUser: string
  dbPassword: string
  maxTenants: string
  status: RadiusServerStatus
}

const EMPTY_SERVER: ServerDraft = {
  id: null,
  name: '',
  host: '',
  authPort: '1812',
  acctPort: '1813',
  coaPort: '3799',
  sharedSecret: '',
  dbUrl: 'jdbc:postgresql://',
  dbUser: 'radius',
  dbPassword: '',
  maxTenants: '2',
  status: 'ACTIVE',
}

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Aktif',
  DRAINING: 'Draining',
  DISABLED: 'Nonaktif',
}
const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export function RadiusServersPage() {
  const { can } = useCan()
  const canManage = can('radius.server.manage')
  const confirm = useConfirm()
  const toast = useToast()
  const { items: servers, loading, run } = useResource(listRadiusServers)

  const [draft, setDraft] = useState<ServerDraft | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

  const statuses = useMemo(
    () => Array.from(new Set(servers.map((s) => s.status))).sort(),
    [servers],
  )

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return servers.filter((s) => {
      if (statusFilter && s.status !== statusFilter) return false
      if (!q) return true
      return (
        s.name.toLowerCase().includes(q) ||
        s.host.toLowerCase().includes(q) ||
        s.dbUrl.toLowerCase().includes(q)
      )
    })
  }, [servers, query, statusFilter])

  const edit = (server: RadiusServerView) => {
    setDraft({
      id: server.id,
      name: server.name,
      host: server.host,
      authPort: String(server.authPort),
      acctPort: String(server.acctPort),
      coaPort: String(server.coaPort),
      sharedSecret: server.sharedSecret,
      dbUrl: server.dbUrl,
      dbUser: server.dbUser,
      dbPassword: '',
      maxTenants: String(server.maxTenants),
      status: server.status,
    })
  }

  const save = async () => {
    if (!draft) return
    const authPort = Number.parseInt(draft.authPort, 10)
    const acctPort = Number.parseInt(draft.acctPort, 10)
    const coaPort = Number.parseInt(draft.coaPort, 10)
    const maxTenants = Number.parseInt(draft.maxTenants, 10)

    if (
      Number.isNaN(authPort) ||
      Number.isNaN(acctPort) ||
      Number.isNaN(coaPort) ||
      Number.isNaN(maxTenants)
    ) {
      toast.error('Port dan batas kapasitas harus berupa angka')
      return
    }

    if (!draft.id && !draft.dbPassword) {
      toast.error('Password database wajib diisi saat pendaftaran node baru')
      return
    }

    await run(async () => {
      if (draft.id) {
        const payload: UpdateRadiusServerRequest = {
          name: draft.name,
          host: draft.host,
          authPort,
          acctPort,
          coaPort,
          sharedSecret: draft.sharedSecret,
          dbUrl: draft.dbUrl,
          dbUser: draft.dbUser,
          dbPassword: draft.dbPassword.trim() || undefined,
          maxTenants,
          status: draft.status,
        }
        await updateRadiusServer(draft.id, payload)
      } else {
        const payload: CreateRadiusServerRequest = {
          name: draft.name,
          host: draft.host,
          authPort,
          acctPort,
          coaPort,
          sharedSecret: draft.sharedSecret,
          dbUrl: draft.dbUrl,
          dbUser: draft.dbUser,
          dbPassword: draft.dbPassword,
          maxTenants,
          status: draft.status,
        }
        await createRadiusServer(payload)
      }
      setDraft(null)
    }, draft.id ? 'Konfigurasi node RADIUS diperbarui' : 'Node RADIUS baru berhasil didaftarkan')
  }

  const testConnection = (server: RadiusServerView) => {
    void (async () => {
      toast.info(`Menguji koneksi ke database ${server.name}...`)
      try {
        const res = await testServerConnection(server.id)
        if (res.success) {
          toast.success(res.message)
        } else {
          toast.error(res.message)
        }
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Uji koneksi database gagal')
      }
    })()
  }

  const remove = (server: RadiusServerView) => {
    void (async () => {
      if (
        !(await confirm({
          title: `Hapus node RADIUS “${server.name}”`,
          message: `Hapus node RADIUS “${server.name}”? Node tidak dapat dihapus bila masih menaungi tenant.`,
          confirmLabel: 'Hapus node',
          danger: true,
        }))
      )
        return
      void run(() => deleteRadiusServer(server.id), 'Node RADIUS dihapus')
    })()
  }

  const columns: Column<RadiusServerView>[] = [
    {
      key: 'name',
      header: 'Node / Server',
      sortValue: (s) => s.name,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{s.name}</strong>
          <Text as="span" className="muted" size={200}>
            Secret: {s.sharedSecret}
          </Text>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (s) => s.status,
      cell: (s) => <StatusBadge status={s.status} label={statusLabel(s.status)} />,
    },
    {
      key: 'endpoint',
      header: 'Host & Port RADIUS',
      sortValue: (s) => s.host,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span>{s.host}</span>
          <Text as="span" className="muted" size={200}>
            Auth :{s.authPort} · Acct :{s.acctPort} · CoA :{s.coaPort}
          </Text>
        </div>
      ),
    },
    {
      key: 'capacity',
      header: 'Kapasitas Tenant',
      sortValue: (s) => s.tenantCount / s.maxTenants,
      cell: (s) => {
        const isFull = s.tenantCount >= s.maxTenants
        const tone: Tone = isFull ? 'critical' : s.tenantCount > 0 ? 'warning' : 'good'
        return (
          <div className="stack" style={{ gap: '0.2rem' }}>
            <Badge tone={tone}>
              {s.tenantCount} / {s.maxTenants} Tenant {isFull ? '(Penuh)' : '(Tersedia)'}
            </Badge>
          </div>
        )
      },
    },
    {
      key: 'db',
      header: 'Database RADIUS',
      sortValue: (s) => s.dbUrl,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <Text as="span" size={300} style={{ fontFamily: 'monospace' }}>
            {s.dbUser}@{s.dbUrl.replace(/^jdbc:postgresql:\/\//, '').split('/')[0]}
          </Text>
        </div>
      ),
    },
  ]

  const rowActions = (s: RadiusServerView): RowAction[] => [
    { key: 'test', label: 'Uji Koneksi DB', icon: <Database size={16} />, onClick: () => testConnection(s) },
    { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => edit(s) },
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => remove(s) },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Server RADIUS"
        subtitle="Kelola node FreeRADIUS multi-server platform dengan pembatasan kapasitas tenant (auto-distribute)."
      />

      <div className="spread">
        <span className="muted">{servers.length} node RADIUS terdaftar</span>
        {canManage && (
          <Button variant="primary" onClick={() => setDraft({ ...EMPTY_SERVER })}>
            <IconPlus size={15} /> Tambah Node RADIUS
          </Button>
        )}
      </div>

      {draft && (
        <ServerForm
          draft={draft}
          setDraft={setDraft}
          onSave={save}
          onCancel={() => setDraft(null)}
        />
      )}

      <Toolbar>
        <SearchInput
          placeholder="Cari nama, host, atau database..."
          value={query}
          onChange={setQuery}
        />
        <SelectField
          value={statusFilter}
          onChange={(_, data) => setStatusFilter(data.value)}
        >
          <option value="">Semua status</option>
          {statuses.map((s) => (
            <option key={s} value={s}>
              {statusLabel(s)}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={filtered}
        rowKey={(s) => s.id}
        loading={loading}
        rowActions={canManage ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada node RADIUS yang cocok' : 'Belum ada node RADIUS'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Jika belum ada node terdaftar, aplikasi berjalan menggunakan konfigurasi RADIUS bawaan (.env).'
            }
            icon={<IconServer size={32} />}
          />
        }
      />
    </div>
  )
}

function ServerForm({
  draft,
  setDraft,
  onSave,
  onCancel,
}: {
  draft: ServerDraft
  setDraft: (d: ServerDraft) => void
  onSave: () => void
  onCancel: () => void
}) {
  const toast = useToast()
  const [testing, setTesting] = useState(false)
  const isEdit = Boolean(draft.id)

  const handleTestConnection = async () => {
    if (!draft.dbUrl || !draft.dbUser || (!draft.id && !draft.dbPassword)) {
      toast.error('URL, User, dan Password database wajib diisi untuk tes koneksi')
      return
    }
    setTesting(true)
    try {
      const res = await testRadiusConnection({
        dbUrl: draft.dbUrl,
        dbUser: draft.dbUser,
        dbPassword: draft.dbPassword,
      })
      if (res.success) {
        toast.success(res.message)
      } else {
        toast.error(res.message)
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Uji koneksi gagal')
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="card stack" style={{ gap: '1rem', border: '1px solid var(--border)' }}>
      <Text as="h3" size={400} weight="semibold" style={{ margin: 0 }}>
        {isEdit ? `Ubah Node RADIUS “${draft.name}”` : 'Tambah Node RADIUS Baru'}
      </Text>

      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '0.75rem' }}>
        <TextField
          label="Nama Node Server"
          value={draft.name}
          onChange={(_, data) => setDraft({ ...draft, name: data.value })}
          placeholder="Misal: VPS-Node-SG-1"
        />

        <TextField
          label="IP Publik / FQDN Host"
          value={draft.host}
          onChange={(_, data) => setDraft({ ...draft, host: data.value })}
          placeholder="Misal: 203.0.113.10"
        />

        <TextField
          label="Batas Maksimum Tenant (Kapasitas)"
          type="number"
          value={draft.maxTenants}
          onChange={(_, data) => setDraft({ ...draft, maxTenants: data.value })}
          placeholder="2"
        />

        <SelectField
          label="Status Node"
          value={draft.status}
          onChange={(_, data) => setDraft({ ...draft, status: data.value as RadiusServerStatus })}
        >
          <option value="ACTIVE">Aktif (Menerima Tenant Baru)</option>
          <option value="DRAINING">Draining (Tidak Menerima Tenant Baru)</option>
          <option value="DISABLED">Nonaktif</option>
        </SelectField>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '0.75rem' }}>
        <TextField
          label="Port Auth (RFC 2865)"
          type="number"
          value={draft.authPort}
          onChange={(_, data) => setDraft({ ...draft, authPort: data.value })}
        />

        <TextField
          label="Port Acct (RFC 2866)"
          type="number"
          value={draft.acctPort}
          onChange={(_, data) => setDraft({ ...draft, acctPort: data.value })}
        />

        <TextField
          label="Port CoA / DAE (RFC 5176)"
          type="number"
          value={draft.coaPort}
          onChange={(_, data) => setDraft({ ...draft, coaPort: data.value })}
        />

        <TextField
          label="Default Shared Secret"
          value={draft.sharedSecret}
          onChange={(_, data) => setDraft({ ...draft, sharedSecret: data.value })}
          placeholder="Secret koneksi Mikrotik"
        />
      </div>

      <div className="stack" style={{ gap: '0.5rem', background: 'var(--surface-sunken)', padding: '0.75rem', borderRadius: '4px' }}>
        <Text as="h4" size={300} weight="semibold" style={{ margin: 0 }}>
          Koneksi Database FreeRADIUS (radius-db)
        </Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Koneksi JDBC ini dipakai aplikasi untuk menulis tabel <code>radcheck</code>, <code>nas</code>, dan membaca <code>radacct</code>.
        </Text>

        <TextField
          label="JDBC URL"
          value={draft.dbUrl}
          onChange={(_, data) => setDraft({ ...draft, dbUrl: data.value })}
          placeholder="jdbc:postgresql://203.0.113.10:5432/radius"
        />

        <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
          <TextField
            label="Database User"
            value={draft.dbUser}
            onChange={(_, data) => setDraft({ ...draft, dbUser: data.value })}
            placeholder="radius"
          />

          <TextField
            label={isEdit ? 'Password Database (Kosongkan jika tidak berubah)' : 'Password Database'}
            type="password"
            value={draft.dbPassword}
            onChange={(_, data) => setDraft({ ...draft, dbPassword: data.value })}
            placeholder="Password DB"
          />
        </div>

        <div>
          <Button size="small" onClick={handleTestConnection} disabled={testing}>
            <Database size={14} /> {testing ? 'Menguji Koneksi...' : 'Uji Koneksi Database'}
          </Button>
        </div>
      </div>

      <div className="row" style={{ marginTop: '0.5rem' }}>
        <Button variant="primary" onClick={onSave}>
          Simpan Node
        </Button>
        <Button onClick={onCancel}>Batal</Button>
      </div>
    </div>
  )
}
