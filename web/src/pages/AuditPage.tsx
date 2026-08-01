import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { AuditEntry, PageResponse } from '../api/types'
import { DataTable, type Column } from '../components/DataTable'
import { Badge, EmptyState, SearchInput, Toolbar, useToast } from '../components/ui'
import { IconAudit } from '../components/icons'

/** Ratakan objek detail jadi `k=v, k=v` untuk sel tabel & pencarian. */
function flattenDetail(detail: Record<string, unknown>): string {
  const keys = Object.keys(detail)
  if (keys.length === 0) return ''
  return Object.entries(detail)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(', ')
}

/**
 * Jejak audit — tabel padat bisa-urut dengan pencarian bebas di atasnya & filter
 * per-jenis aksi. Klien menyaring/mengurutkan sisi-klien dari 50 entri terbaru.
 */
export function AuditPage() {
  const toast = useToast()
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [action, setAction] = useState('')

  useEffect(() => {
    void (async () => {
      try {
        const page = await api.get<PageResponse<AuditEntry>>('/api/audit-logs?size=50')
        setEntries(page.content)
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Gagal memuat jejak audit')
      } finally {
        setLoading(false)
      }
    })()
  }, [toast])

  // Daftar aksi unik untuk dropdown filter — dirakit dari data yang termuat.
  const actions = useMemo(
    () => [...new Set(entries.map((e) => e.action))].sort((a, b) => a.localeCompare(b)),
    [entries],
  )

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return entries.filter((e) => {
      if (action && e.action !== action) return false
      if (!q) return true
      return [e.action, e.actorEmail ?? '', e.entityType ?? '', flattenDetail(e.detail)]
        .join(' ')
        .toLowerCase()
        .includes(q)
    })
  }, [entries, query, action])

  const columns: Column<AuditEntry>[] = [
    {
      key: 'occurredAt',
      header: 'Waktu',
      sortValue: (e) => e.occurredAt,
      cell: (e) => <span className="muted">{new Date(e.occurredAt).toLocaleString('id-ID')}</span>,
    },
    {
      key: 'action',
      header: 'Aksi',
      sortValue: (e) => e.action,
      cell: (e) => <Badge>{e.action}</Badge>,
    },
    {
      key: 'actor',
      header: 'Pelaku',
      sortValue: (e) => e.actorEmail,
      cell: (e) => <span className="muted">{e.actorEmail ?? 'sistem'}</span>,
    },
    {
      key: 'entity',
      header: 'Objek',
      sortValue: (e) => e.entityType,
      cell: (e) => <span className="muted">{e.entityType ?? '–'}</span>,
    },
    {
      key: 'detail',
      header: 'Detail',
      cell: (e) => <span className="muted">{flattenDetail(e.detail) || '–'}</span>,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Jejak Audit</h1>
        <p className="page-sub">50 aktivitas terbaru — siapa melakukan apa, kapan.</p>
      </div>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari aksi, pelaku, objek, atau detail…" />
        <select value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="">Semua aksi</option>
          {actions.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(e) => e.id}
        loading={loading}
        initialSort={{ key: 'occurredAt', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || action ? 'Tidak ada aktivitas yang cocok' : 'Belum ada aktivitas'}
            hint={query || action ? 'Coba ubah kata kunci atau filter.' : undefined}
            icon={<IconAudit size={32} />}
          />
        }
      />
    </div>
  )
}
