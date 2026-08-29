import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Trash2 } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { Area } from '../api/types'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { Button, EmptyState, TextField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconArea } from '@/components/atoms/icons'

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
    { key: 'code', header: 'Kode', sortValue: (a) => a.code, cell: (a) => <Text as="span" className="badge">{a.code}</Text> },
    { key: 'name', header: 'Nama', sortValue: (a) => a.name, cell: (a) => <Text as="strong" weight="semibold" >{a.name}</Text> },
    {
      key: 'parent',
      header: 'Induk',
      sortValue: (a) => parentName(a),
      cell: (a) => {
        const p = parentName(a)
        return p ? <Text as="span" className="muted">{p}</Text> : <Text as="span" className="muted">—</Text>
      },
    },
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  const canDelete = can('iam.area.delete')
  const rowActions = (a: Area): RowAction[] => [
    {
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void run(() => api.del(`/api/areas/${a.id}`), 'Area dihapus'),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader title="Area / Wilayah" />

      {can('iam.area.create') && (
        <div className="card row" style={{ alignItems: 'flex-end' }}>
          <div style={{ flex: 1, marginBottom: 0 }}>
            <TextField label="Kode" value={code} onChange={(_, data) => setCode(data.value)} placeholder="BKS" />
          </div>
          <div style={{ flex: 2, marginBottom: 0 }}>
            <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} placeholder="Bekasi" />
          </div>
          <Button
            variant="primary"
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
          </Button>
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
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query ? 'Tidak ada area yang cocok' : 'Belum ada area'}
            icon={<IconArea size={32} />}
          />
        }
      />
    </div>
  )
}
