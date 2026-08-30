import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { listMyWorkOrders, type WorkOrderStatus, type WorkOrderView } from '../api/workorder'
import { DataTable, type Column } from '@/components/organisms'
import { EmptyState, SelectField, Toolbar } from '@/components/atoms'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconWorkOrder } from '@/components/atoms/icons'
import {
  PRIORITY_LABEL,
  STATUSES,
  STATUS_LABEL,
  TYPE_LABEL,
  assigneeLabel,
  fmt,
} from '@/utils/woLabels'
import { WoStatusBadge } from '@/components/organisms/workorder/views'

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
      cell: (wo) => wo.code,
      onCellClick: (wo) => navigate(`/my-work-orders/${wo.id}`),
    },
    {
      key: 'type',
      header: 'Tipe',
      sortValue: (wo) => TYPE_LABEL[wo.type],
      cell: (wo) => TYPE_LABEL[wo.type],
    },
    {
      key: 'title',
      header: 'Judul',
      sortValue: (wo) => wo.title,
      cell: (wo) => wo.title,
    },
    {
      key: 'priority',
      header: 'Prioritas',
      sortValue: (wo) => wo.priority,
      cell: (wo) => PRIORITY_LABEL[wo.priority],
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
      cell: (wo) => assigneeLabel(wo),
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
      <PageHeader title="Tugas Saya" />

      <Toolbar>
        <SelectField value={status} onChange={(_, data) => setStatus(data.value as WorkOrderStatus | '')}>
          <option value="">Semua status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={orders}
        rowKey={(wo) => wo.id}
        loading={loading}
        initialSort={{ key: 'scheduledAt', dir: 'desc' }}
        presentation="resource"
        empty={
          <EmptyState
            title={status ? 'Tidak ada tugas dengan status itu' : 'Belum ada tugas untukmu'}
            icon={<IconWorkOrder size={32} />}
          />
        }
      />
    </div>
  )
}
