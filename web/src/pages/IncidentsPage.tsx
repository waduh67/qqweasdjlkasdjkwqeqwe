import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { IncidentView } from '../api/incident'
import { DataTable, type Column } from '@/components/organisms'
import { EmptyState, SelectField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert } from '@/components/atoms/icons'
import { timeAgo } from '@/utils/timeAgo'
import { IncidentDetailBlade } from './incidents/IncidentDetailBlade'

/** Urutan keparahan untuk pengurutan tabel (turun = paling parah di atas). */
const SEV_RANK: Record<string, number> = { CRITICAL: 5, MAJOR: 4, MINOR: 3, WARNING: 2, INFO: 1 }

const ROOT_LABEL: Record<string, string> = {
  OLT: 'OLT',
  ODC: 'ODC',
  ODP: 'ODP',
  ONU: 'ONU',
  COLLECTOR: 'Collector',
}

/**
 * Dugaan sebab blast-radius dari register ONU — dua gangguan yang di layar tampak
 * sama tapi tindakannya berbeda: mati listrik area (tunggu PLN) vs fiber putus
 * (kirim teknisi).
 */
const CAUSE_LABEL: Record<string, string> = {
  POWER_OUTAGE: 'Dugaan mati listrik area',
  FIBER_CUT: 'Dugaan fiber putus',
  MIXED: 'Sebab campuran',
}

/**
 * Insiden: banjir alarm sejenis digabung menjadi satu insiden ber-akar-masalah.
 * Operator melihat sedikit insiden yang bermakna, bukan ratusan alarm mentah,
 * lalu bisa mengakui atau menutupnya.
 */
export function IncidentsPage() {
  const toast = useToast()
  const [incidents, setIncidents] = useState<IncidentView[]>([])
  const [loading, setLoading] = useState(true)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

  const reload = useCallback(async () => {
    try {
      setIncidents(await api.get<IncidentView[]>('/api/incidents'))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat insiden')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
    // Insiden hidup — segarkan berkala agar korelasi terbaru terlihat.
    const t = window.setInterval(() => void reload(), 20_000)
    return () => window.clearInterval(t)
  }, [reload])

  const openDetail = (id: string) => setDetailId(id)

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return incidents.filter(
      (inc) =>
        (!statusFilter || inc.status === statusFilter) &&
        (!q ||
          inc.title.toLowerCase().includes(q) ||
          inc.rootLabel.toLowerCase().includes(q)),
    )
  }, [incidents, query, statusFilter])

  const columns: Column<IncidentView>[] = [
    {
      key: 'title',
      header: 'Nama',
      sortValue: (i) => i.title,
      cell: (i) => i.title,
      onCellClick: (i) => void openDetail(i.id),
    },
    {
      key: 'severity',
      header: 'Keparahan',
      sortValue: (i) => SEV_RANK[i.severity] ?? 0,
      cell: (i) => i.severity,
    },
    {
      key: 'cause',
      header: 'Dugaan sebab',
      sortValue: (i) => i.suspectedCause ?? '',
      cell: (i) => (i.suspectedCause ? CAUSE_LABEL[i.suspectedCause] : undefined) ?? '—',
    },
    {
      key: 'root',
      header: 'Akar masalah',
      sortValue: (i) => i.rootLabel,
      cell: (i) => `${ROOT_LABEL[i.rootType] ?? i.rootType} ${i.rootLabel}`,
    },
    { key: 'alarms', header: 'Alarm', align: 'right', sortValue: (i) => i.alarmCount, cell: (i) => i.alarmCount },
    {
      key: 'customers',
      header: 'Pelanggan',
      align: 'right',
      sortValue: (i) => i.affectedCustomerCount,
      cell: (i) => i.affectedCustomerCount,
    },
    { key: 'opened', header: 'Dibuka', sortValue: (i) => i.openedAt, cell: (i) => timeAgo(i.openedAt) },
    {
      key: 'status',
      header: 'Status',
      sortValue: (i) => i.status,
      cell: (i) => (i.status === 'ACKNOWLEDGED' ? 'Diakui' : 'Terbuka'),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.2rem' }}>
      <PageHeader title="Insiden" />

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari judul atau akar masalah…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          <option value="">Semua status</option>
          <option value="OPEN">Terbuka</option>
          <option value="ACKNOWLEDGED">Diakui</option>
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(i) => i.id}
        loading={loading}
        initialSort={{ key: 'severity', dir: 'desc' }}
        presentation="resource"
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada insiden yang cocok' : 'Tidak ada insiden aktif'}
            icon={<IconAlert size={34} />}
          />
        }
      />

      {detailId && <IncidentDetailBlade key={detailId} id={detailId} onClose={() => setDetailId(null)} onChanged={() => void reload()} />}
    </div>
  )
}
