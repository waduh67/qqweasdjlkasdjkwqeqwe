import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { Area } from '../api/types'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { EmptyState, SearchInput, Toolbar, useToast } from '../components/ui'
import { IconArea } from '../components/icons'

export function AreasPage() {
  const { can } = useCan()
  const toast = useToast()
  const [areas, setAreas] = useState<Area[]>([])
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      setAreas(await api.get<Area[]>('/api/areas'))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat area')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
  }, [reload])

  async function run(action: () => Promise<unknown>, ok: string) {
    try {
      await action()
      await reload()
      toast.success(ok)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  // Resolusi induk: parentId → nama area induk (fallback ke id bila tak ketemu).
  const parentName = useMemo(() => {
    const byId = new Map(areas.map((a) => [a.id, a.name] as const))
    return (a: Area) => (a.parentId ? byId.get(a.parentId) ?? a.parentId : null)
  }, [areas])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return areas
    return areas.filter((a) => a.code.toLowerCase().includes(q) || a.name.toLowerCase().includes(q))
  }, [areas, query])

  const columns: Column<Area>[] = [
    { key: 'code', header: 'Kode', sortValue: (a) => a.code, cell: (a) => <span className="badge">{a.code}</span> },
    { key: 'name', header: 'Nama', sortValue: (a) => a.name, cell: (a) => <strong>{a.name}</strong> },
    {
      key: 'parent',
      header: 'Induk',
      sortValue: (a) => parentName(a),
      cell: (a) => {
        const p = parentName(a)
        return p ? <span className="muted">{p}</span> : <span className="muted">—</span>
      },
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (a) =>
        can('iam.area.delete') ? (
          <button className="danger" onClick={() => void run(() => api.del(`/api/areas/${a.id}`), 'Area dihapus')}>
            Hapus
          </button>
        ) : null,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Area / Wilayah</h1>
        <p className="muted">
          Area adalah dimensi <em>scope</em> pada RBAC: pengguna yang dibatasi ke area tertentu hanya melihat aset dan
          tiket di area itu.
        </p>
      </div>

      {can('iam.area.create') && (
        <div className="card row" style={{ alignItems: 'flex-end' }}>
          <label style={{ flex: 1, marginBottom: 0 }}>
            <span>Kode</span>
            <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="BKS" />
          </label>
          <label style={{ flex: 2, marginBottom: 0 }}>
            <span>Nama</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Bekasi" />
          </label>
          <button
            className="primary"
            disabled={!code || !name}
            onClick={() =>
              void run(async () => {
                await api.post('/api/areas', { code, name, parentId: null })
                setCode('')
                setName('')
              }, 'Area ditambahkan')
            }
          >
            Tambah
          </button>
        </div>
      )}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode atau nama…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(a) => a.id}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={
          <EmptyState
            title={query ? 'Tidak ada area yang cocok' : 'Belum ada area'}
            hint={query ? 'Coba ubah kata kunci.' : 'Tambahkan area pertama untuk mulai membatasi scope RBAC.'}
            icon={<IconArea size={32} />}
          />
        }
      />
    </div>
  )
}
