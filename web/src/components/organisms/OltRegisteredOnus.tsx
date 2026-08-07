import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '@/api/client'
import type { OltOnuList, OltOnuRow } from '@/api/network'
import { DataTable, type Column } from './DataTable'
import { Badge, EmptyState, StatusBadge } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { IconCustomers } from '@/components/atoms/icons'

/**
 * Daftar ONU pelanggan yang menggantung di bawah satu OLT — pandangan per-OLT ala
 * kitabill: "ONU siapa saja di OLT ini", lengkap dengan pelanggan, ODP, dan port-nya.
 *
 * Disusun server dari topologi (OLT → PON → ODC → ODP → ONU) + data pelanggan lewat
 * `GET /api/gis/olts/{id}/onus`, jadi tak ada join ONU→OLT di sini. Baris diklik →
 * menuju detail pelanggan (bawa `backTo` agar tombol kembali menunjuk ke OLT ini).
 *
 * @param backTo rute halaman OLT ini, untuk tautan kembali dari detail pelanggan.
 */
export function OltRegisteredOnus({ oltId, backTo }: { oltId: string; backTo: string }) {
  const toast = useToast()
  const navigate = useNavigate()
  const [data, setData] = useState<OltOnuList | null>(null)
  const [query, setQuery] = useState('')

  useEffect(() => {
    let alive = true
    api
      .get<OltOnuList>(`/api/gis/olts/${oltId}/onus`)
      .then((d) => {
        if (alive) setData(d)
      })
      .catch((err) => {
        if (alive) toast.error(err instanceof ApiError ? err.message : 'Gagal memuat daftar ONU')
      })
    return () => {
      alive = false
    }
  }, [oltId, toast])

  const rows = useMemo(() => {
    const all = data?.onus ?? []
    const q = query.trim().toLowerCase()
    if (!q) return all
    return all.filter((o) =>
      [o.serialNumber, o.customerName, o.customerCode, o.odpCode, o.subscriptionPackage ?? '']
        .join(' ')
        .toLowerCase()
        .includes(q),
    )
  }, [data, query])

  const columns: Column<OltOnuRow>[] = [
    {
      key: 'serial',
      header: 'Serial',
      sortValue: (o) => o.serialNumber,
      cell: (o) => <span style={{ fontWeight: 550, whiteSpace: 'nowrap' }}>{o.serialNumber}</span>,
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (o) => o.customerName,
      cell: (o) => (
        <div>
          <div style={{ fontSize: '0.88rem', overflow: 'hidden', textOverflow: 'ellipsis' }}>{o.customerName}</div>
          <div className="muted tnum" style={{ fontSize: '0.78rem' }}>{o.customerCode}</div>
        </div>
      ),
    },
    {
      key: 'odp',
      header: 'ODP / Port',
      sortValue: (o) => o.odpCode,
      cell: (o) => (
        <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
          <strong style={{ fontSize: '0.85rem' }}>{o.odpCode}</strong>
          <span className="badge">Port {o.portNumber}</span>
        </span>
      ),
    },
    {
      key: 'package',
      header: 'Paket',
      sortValue: (o) => o.subscriptionPackage ?? '',
      cell: (o) => <span style={{ fontSize: '0.85rem' }}>{o.subscriptionPackage ?? <span className="muted">—</span>}</span>,
    },
    { key: 'status', header: 'Status', sortValue: (o) => o.onuStatus, cell: (o) => <StatusBadge status={o.onuStatus} /> },
    {
      key: 'rx',
      header: 'Redaman',
      align: 'right',
      sortValue: (o) => o.installRxPowerDbm,
      cell: (o) => <span className="muted">{o.installRxPowerDbm != null ? `${o.installRxPowerDbm} dBm` : '—'}</span>,
    },
  ]

  return (
    <div className="card stack">
      <div className="spread" style={{ gap: '0.75rem', alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <h3 style={{ margin: 0 }}>ONU terpasang</h3>
          {data && <Badge tone="neutral">{data.onuCount}</Badge>}
        </div>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari serial, pelanggan, atau ODP…" />
      </div>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.onuId}
        loading={data == null}
        onRowClick={(o) =>
          navigate(`/customers/${o.customerId}`, { state: { backTo, backLabel: 'OLT' } })
        }
        initialSort={{ key: 'odp', dir: 'asc' }}
        empty={
          <EmptyState
            title={query ? 'Tidak ada yang cocok' : 'Belum ada ONU terpasang'}
            hint={
              query
                ? 'Coba ubah kata kunci.'
                : 'ONU pelanggan yang menggantung di OLT ini akan muncul di sini setelah terpasang.'
            }
            icon={<IconCustomers size={32} />}
          />
        }
      />
    </div>
  )
}
