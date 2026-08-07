import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { listMyWorkOrders, type WorkOrderStatus, type WorkOrderView } from '../api/workorder'
import { DataTable, type Column } from '../components/DataTable'
import { Badge, EmptyState, Toolbar, useToast } from '../components/ui'
import { PageHeader } from '../components/PageHeader'
import { IconWorkOrder } from '../components/icons'
import {
  PRIORITY_LABEL,
  STATUSES,
  STATUS_LABEL,
  TYPE_LABEL,
  assigneeLabel,
  fmt,
  priorityTone,
} from '../components/workorder/labels'
import { AssigneeChips, WoStatusBadge } from '../components/workorder/views'

/**
 * "Tugas Saya" — papan tugas milik teknisi yang sedang login. Beda dari papan dispatch
 * operator (`WorkOrdersPage`) yang menampilkan SEMUA work order: di sini hanya WO tempat
 * teknisi ini jadi anggota roster (`GET /api/work-orders/mine`). Teknisi mengerjakannya
 * lewat web (mulai, catat redaman, unggah bukti, selesaikan) sampai aplikasi teknisi
 * mobile tersedia. Penugasan/pembatalan/persetujuan tetap milik operator — section-nya
 * ter-gate izin yang tak dimiliki teknisi, jadi tak muncul di sini.
 */
export function MyWorkOrdersPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const [orders, setOrders] = useState<WorkOrderView[]>([])
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState<WorkOrderStatus | ''>('')

  const reload = useCallback(async () => {
    try {
      const page = await listMyWorkOrders(status || undefined)
      setOrders(page.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat tugas')
    } finally {
      setLoading(false)
    }
  }, [status, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const columns: Column<WorkOrderView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (wo) => wo.code,
      cell: (wo) => <span className="badge accent">{wo.code}</span>,
    },
    {
      key: 'type',
      header: 'Tipe',
      sortValue: (wo) => TYPE_LABEL[wo.type],
      cell: (wo) => <span className="badge">{TYPE_LABEL[wo.type]}</span>,
    },
    {
      key: 'title',
      header: 'Judul',
      sortValue: (wo) => wo.title,
      cell: (wo) => (
        <div className="row" style={{ gap: '0.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <strong>{wo.title}</strong>
          {wo.priority !== 'NORMAL' && <Badge tone={priorityTone(wo.priority)}>{PRIORITY_LABEL[wo.priority]}</Badge>}
        </div>
      ),
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (wo) => wo.customerName,
      cell: (wo) => wo.customerName ?? <span className="muted">—</span>,
    },
    {
      key: 'team',
      header: 'Tim',
      sortValue: (wo) => assigneeLabel(wo),
      cell: (wo) => <AssigneeChips wo={wo} />,
    },
    {
      key: 'scheduledAt',
      header: 'Jadwal',
      sortValue: (wo) => wo.scheduledAt,
      cell: (wo) => (wo.scheduledAt ? fmt(wo.scheduledAt) : <span className="muted">—</span>),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (wo) => wo.status,
      cell: (wo) => <WoStatusBadge status={wo.status} />,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Tugas Saya"
        subtitle="Work order yang ditugaskan ke kamu — kerjakan, catat redaman, unggah bukti, lalu selesaikan."
      />

      <Toolbar>
        <select value={status} onChange={(e) => setStatus(e.target.value as WorkOrderStatus | '')}>
          <option value="">Semua status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={orders}
        rowKey={(wo) => wo.id}
        onRowClick={(wo) => navigate(`/my-work-orders/${wo.id}`)}
        loading={loading}
        initialSort={{ key: 'scheduledAt', dir: 'desc' }}
        empty={
          <EmptyState
            title={status ? 'Tidak ada tugas dengan status itu' : 'Belum ada tugas untukmu'}
            hint={status ? 'Coba ubah filter status.' : 'Tugas yang ditugaskan operator akan muncul di sini.'}
            icon={<IconWorkOrder size={32} />}
          />
        }
      />
    </div>
  )
}
